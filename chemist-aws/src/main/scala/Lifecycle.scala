package funnel
package chemist
package aws

import scalaz.{\/,-\/,\/-}
import scalaz.syntax.traverse._
import scalaz.concurrent.Task
import scalaz.stream.{Process,Sink}
import com.amazonaws.services.sqs.model.Message
import com.amazonaws.services.sqs.AmazonSQS
import com.amazonaws.services.ec2.AmazonEC2
import com.amazonaws.services.autoscaling.AmazonAutoScaling

/**
 * The purpose of this object is to manage all the "lifecycle" events
 * associated with subordinate Flask instances. Whenever they start,
 * stop, etc the SQS event will come in and be presented on the stream.
 *
 * The design here is that incoming events are translated into a lifecycle
 * algebra which is then acted upon. This is essentially interpreter pattern.
 */
object Lifecycle {
  import JSON._
  import argonaut._, Argonaut._
  import journal.Logger
  import scala.collection.JavaConverters._
  import metrics._

  private implicit val log = Logger[Lifecycle.type]

  case class MessageParseException(override val getMessage: String) extends RuntimeException

  /**
   * Attempt to parse incomming SQS messages into `AutoScalingEvent`. Yield a
   * `MessageParseException` in the event the message is not decipherable.
   */
  def parseMessage(msg: Message): Throwable \/ AutoScalingEvent =
    Parse.decodeEither[AutoScalingEvent](msg.getBody).leftMap(MessageParseException(_))


  /**
   * This function essentially converts the polling of an SQS queue into a scalaz-stream
   * process. Said process contains Strings being delivered on the SQS queue, which we
   * then attempt to parse into an `AutoScalingEvent`. Assuming the message parses correctly
   * we then pass the `AutoScalingEvent` to the `interpreter` function for further processing.
   * In the event the message does not parse to an `AutoScalingEvent`,
   */
  def stream(queueName: String, resources: Seq[String]
    )(r: Repository, sqs: AmazonSQS, asg: AmazonAutoScaling, ec2: AmazonEC2, dsc: Discovery
    ): Process[Task, Throwable \/ Action] = {
    for {
      a <- SQS.subscribe(queueName)(sqs)(Chemist.defaultPool, Chemist.schedulingPool)
      _ <- Process.eval(Task(log.debug(s"stream, number messages recieved: ${a.length}")))

      b <- Process.emitAll(a)
      _ <- Process.eval(Task(log.debug(s"stream, raw message recieved: $b")))

      c <- Process.eval(parseMessage(b).traverseU(interpreter(_, resources)(r, asg, ec2, dsc)))
      _ <- Process.eval(Task(log.debug(s"stream, computed action: $c")))

      _ <- SQS.deleteMessages(queueName, a)(sqs)
    } yield c
  }

  //////////////////////////// I/O Actions ////////////////////////////

  /**
   * This function is a bit of a monster and forms the crux of the chemist system
   * when running on AWS. It's primary job is to recieve an AutoScalingEvent and
   * then determine if that event pertains to a flask instance, or if it pertains
   * to a normal service instance that needs to be monitored. The following cases
   * are handled by this function:
   *
   * + New flask instance avalible:
   *     Add this extra capacity to the known flask list
   *
   * + Flask instance terminated:
   *     Remove this flask from the known instances and repartiion any work that
   *     was assigned to this flask to another still-avalible flask.
   *
   * + New generic instance avalible:
   *     Instruct one of the online flasks to start monitoring this new instance
   *
   */
  def interpreter(e: AutoScalingEvent, resources: Seq[String]
    )(r: Repository, asg: AmazonAutoScaling, ec2: AmazonEC2, dsc: Discovery
    ): Task[Action] = {
    log.debug(s"interpreting event: $e")

    // terrible side-effect but we want to track the events
    // that chemist actually sees
    r.addEvent(e).run // YIKES!

    // MOAR side-effects!
    LifecycleEvents.increment

    def isFlask: Task[Boolean] =
      ASG.lookupByName(e.asgName)(asg).flatMap { a =>
        log.debug(s"Found ASG from the EC2 lookup: $a")

        a.getTags.asScala.find(t =>
          t.getKey.trim == "type" &&
          t.getValue.trim.startsWith("flask")
        ).fold(Task.now(false))(_ => Task.now(true))
      }

    e match {
      case AutoScalingEvent(_,Launch,_,_,_,_,_,_,_,_,_,_,id) =>
        isFlask.flatMap(bool =>
          if(bool){
            // if its a flask, update our internal list of avalible shards
            log.debug(s"Adding capactiy $id")
            r.increaseCapacity(id).map(_ => NoOp)
          } else {
            // in any other case, its something new to monitor
            for {
              i <- dsc.lookupOne(id)
              _  = log.debug(s"Found instance metadata from remote: $i")

              _ <- r.addInstance(i)
              _  = log.debug(s"Adding service instance '$id' to known entries")

              t  = Sharding.Target.fromInstance(resources)(i)
              m <- Sharding.locateAndAssignDistribution(t, r)
              _  = log.debug("Computed and set a new distribution for the addition of host...")
            } yield Redistributed(m)
          }
        )

      case AutoScalingEvent(_,Terminate,_,_,_,_,_,_,_,_,_,_,id) => {
        r.isFlask(id).flatMap(bool =>
          if(bool){
            log.info(s"Terminating and rebalancing the flask cluster. Downing $id")
            (for {
              t <- r.assignedTargets(id)
              _  = log.debug(s"sink, targets= $t")
              _ <- r.decreaseCapacity(id)
              m <- Sharding.locateAndAssignDistribution(t,r)
            } yield Redistributed(m)) or Task.now(NoOp)
          } else {
            log.info(s"Terminating the monitoring of a non-flask service instance with id = $id")
            Task.now(NoOp)
          }
        )
      }

      case _ => Task.now(NoOp)
    }
  }

  // not sure if this is really needed but
  // looks like i can use this in a more generic way
  // to send previously unknown targets to flasks
  def sink: Sink[Task,Action] =
    Process.emit {
      case Redistributed(seq) =>
        for {
          _ <- Sharding.distribute(seq)
          _ <- Task.now(Reshardings.increment)
        } yield ()

      case _ => Task.now( () )
    }

  /**
   * The purpose of this function is to turn the result from the interpreter
   * into an effect that has some meaning within the system (i.e. resharding or not)
   */
  def event(e: AutoScalingEvent, resources: Seq[String]
    )(r: Repository, asg: AmazonAutoScaling, ec2: AmazonEC2, dsc: Discovery
    ): Task[Unit] = {
    interpreter(e, resources)(r, asg, ec2, dsc).map {
      case Redistributed(seq) =>
        Sharding.distribute(seq).map(_ => ())
      case _ =>
        Task.now( () )
    }
  }

  /**
   * The main method for the lifecycle process. Run the `stream` method and then handle
   * failures that might occour on the process. This function is primarily used in the
   * init method for chemist so that the SQS/SNS lifecycle is started from the edge of
   * the world.
   */
  def run(queueName: String, resources: Seq[String], s: Sink[Task, Action]
    )(r: Repository, sqs: AmazonSQS, asg: AmazonAutoScaling, ec2: AmazonEC2, dsc: Discovery
    ): Task[Unit] = {
    val process: Sink[Task, Action] = stream(queueName, resources)(r,sqs,asg,ec2,dsc).flatMap {
      case -\/(fail) => {
        log.error(s"Problem encountered when trying to processes SQS lifecycle message: $fail")
        fail.printStackTrace
        s
      }
      case \/-(win)  => win match {
        case Redistributed(_) => s
        case _ => s
      }
    }

    process.run
  }
}


