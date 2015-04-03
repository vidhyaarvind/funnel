package funnel
package messages

import scodec._
import scodec.bits._
import shapeless.Sized
import java.util.concurrent.TimeUnit
import zeromq._
import sockets._
import TimeUnit._
import scalaz.stream._
import scalaz.stream.async.mutable.{Signal,Queue}
import scalaz.stream.async.{signalOf,unboundedQueue}
import scalaz.concurrent.Task
import scalaz.{-\/,\/,\/-}
import java.net.URI

sealed trait Telemetry

final case class Error(names: Names) extends Telemetry {
  override def toString = s"${names.mine} gave up on ${names.kind} server ${names.theirs}"
}

final case class NewKey(key: Key[_]) extends Telemetry

object Telemetry extends TelemetryCodecs {

  implicit val transportedTelemetry = Transportable[Telemetry] { t =>
    t match {
      case e @ Error(_) => Transported(Schemes.telemetry, Versions.v1, None, Some(Topic("error")), errorCodec.encodeValid(e).toByteArray)
      case NewKey(key) =>
        val bytes = keyCodec.encodeValid(key).toByteArray
        Transported(Schemes.telemetry, Versions.v1, None, Some(Topic("key")), bytes)
    }
  }

  def telemetryPublishEndpoint(uri: URI): Endpoint = Endpoint.unsafeApply(publish &&& bind, uri)

  def telemetrySubscribeEndpoint(uri: URI): Endpoint = Endpoint.unsafeApply(subscribe &&& (connect ~ topics.all), uri)

  def telemetryPublishSocket(uri: URI, signal: Signal[Boolean], telemetry: Process[Task,Telemetry]): Task[Unit] = {
    val e = telemetryPublishEndpoint(uri)
    Ø.link(e)(signal) { socket =>
      telemetry  through Ø.write(socket)
    }.run
  }

  def telemetrySubscribeSocket(uri: URI, signal: Signal[Boolean]): (Signal[Set[Key[Any]]], Process[Task,Error], Process[Task,Unit]) = {
    val keys = signalOf(Set.empty[Key[Any]])
    val errors = unboundedQueue[Error]
    val endpoint = telemetrySubscribeEndpoint(uri)
    val p = Ø.link(endpoint)(signal) { socket =>
      Ø.receive(socket) to fromTransported(keys, errors)
    }

    (keys, errors.dequeue, p)
  }

  def fromTransported(keys: Signal[Set[Key[Any]]], errors: Queue[Error]): Sink[Task, Transported] = {
    val currentKeys = collection.mutable.Set.empty[Key[Any]]
    Process.constant { x =>
      x match {
        case Transported(_, Versions.v1, _, Some(Topic("error")), bytes) =>
          errors.enqueueOne(errorCodec.decodeValidValue(BitVector(bytes)))
        case Transported(_, Versions.v1, _, Some(Topic("key")), bytes) =>
          Task(currentKeys += keyCodec.decodeValidValue(BitVector(bytes))).flatMap { k =>
            keys.set(k.toSet)
          }
      }
    }
  }

  val keyChanges: Process1[Set[Key[Any]], NewKey] = {
    import Process._
    def go(old: Option[Set[Key[Any]]]): Process1[Set[Key[Any]], NewKey] = receive1 { current =>
      val toEmit = old match {
        case None => current
        case Some(oldKeys) => (current -- oldKeys)
      }
      if(toEmit.isEmpty) {
        go(Some(current))
      } else {
        emitAll(toEmit.toSeq.map(NewKey.apply)) ++ go(Some(current))
      }
    }
    go(None)
  }
}


trait TelemetryCodecs extends Codecs {
  implicit lazy val errorCodec = Codec.derive[Names].xmap[Error](Error(_), _.names)

  implicit val reportableCodec: Codec[Reportable[Any]] = (codecs.provide(Reportable.B) :+: codecs.provide(Reportable.D) :+: codecs.provide(Reportable.S) :+: codecs.provide(Reportable.Stats)).discriminatedByIndex(uint8).as[Reportable[Any]]

  lazy implicit val baseCodec: Codec[Units.Base] = {
    import Units.Base._
      (codecs.provide(Zero) :+: codecs.provide(Kilo) :+: codecs.provide(Mega) :+: codecs.provide(Giga)).discriminatedByIndex(uint8).as[Units.Base]
  }

  val tuToInt: TimeUnit => Int = _ match {
      case DAYS => 0
      case HOURS => 1
      case MICROSECONDS => 2
      case MILLISECONDS => 3
      case MINUTES => 4
      case NANOSECONDS => 5
      case SECONDS => 6
  }
  val intToTU: Int => TimeUnit = _ match {
      case 0 => DAYS
      case 1 => HOURS
      case 2 => MICROSECONDS
      case 3 => MILLISECONDS
      case 4 => MINUTES
      case 5 => NANOSECONDS
      case 6 => SECONDS
  }

  lazy implicit val timeUnitCodec: Codec[TimeUnit] = codecs.uint8.xmap(intToTU, tuToInt)

  lazy implicit val unitsCodec: Codec[Units[Any]] = {
    import Units._
    (Codec.derive[Duration] :+:
       Codec.derive[Bytes] :+:
       codecs.provide(Count) :+:
       codecs.provide(Ratio) :+:
       codecs.provide(TrafficLight) :+:
       codecs.provide(Healthy) :+:
       codecs.provide(Load) :+:
       codecs.provide(None)).discriminatedByIndex(uint8).as[Units[Any]]
  }

  lazy implicit val keyCodec: Codec[Key[Any]] = (utf8 :: reportableCodec :: unitsCodec :: utf8 :: map[String,String]).as[Key[Any]]

}