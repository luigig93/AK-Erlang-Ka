package erlang
import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import com.ericsson.otp.erlang._


object Receptionist {
  val node: OtpNode = new OtpNode("akka")
  val receptionistMbox: OtpMbox = node.createMbox()

  // messages
  sealed trait Command
  final case class ErlangPidExit(pid: Option[OtpErlangPid]) extends Command
  final case class ActorRefExit(akkaActor: ActorRef[Any], receiver: ActorRef[Receiver.Command]) extends Command
  final case class ServiceCompleted(
                                     serviceKey: String,
                                     tmpMbox: OtpMbox,
                                     msg: Option[OtpErlangObject]) extends Command


  sealed trait Msg extends Command
  final case class Decode(erlangMsg: OtpErlangObject, replyTo: ActorRef[Receiver.Command]) extends Msg
  final case class Encode(akkaMsg: Any, sendToPid: OtpErlangPid) extends Msg


  // SERVICE REQUEST
  sealed trait ServiceRequest extends Command
  final case class GlobalUnregisterRequest(globalName: String) extends ServiceRequest
  // il parametro di tipo nel messaggio è abbastanza inutile, ci serve solo per non forzare il cast lato mittente
  // ma effettuiamo il cast durante la ricezione.
  final case class GlobalRegisterRequest[M](
                                              globalName: String,
                                              actorToRegister: ActorRef[M],
                                              replyTo: ActorRef[ServiceResponse] // adapter!
                                           ) extends ServiceRequest

  final case class GlobalWhereIsRequest(
                                         globalName: String,
                                         replyTo: ActorRef[ServiceResponse] // adapter!
                                       ) extends ServiceRequest


  // SERVICE RESPONSE (for adapter)
  sealed trait ServiceResponse
  final case class GlobalRegisterResponse(result: Boolean) extends ServiceResponse
  final case class GlobalWhereIsResponse(globalName: String, aRef: Option[ActorRef[_]]) extends ServiceResponse


  // entry point
  def apply(): Behavior[Command] =
    Behaviors.setup{ ctx =>
      Linker(ctx)
      receiveLoop(TranslationTable.empty, Map.empty[String, Service.ServiceRequestBuffer])
    }

  // behaviours
  def receiveLoop(tab: TranslationTable, requestMap: Service.BufferMap): Behavior[Command] =
    Behaviors.receive { (ctx, msg) =>
      msg match {
        case service: ServiceRequest =>
          val updatedRequestBuffer = Service.onServiceRequest(service, requestMap, ctx)
          receiveLoop(tab, updatedRequestBuffer)

        case service: ServiceCompleted =>
          val (updatedTab, updatedRequestBuffer) = Service.onServiceCompleted(service, tab, requestMap, ctx)
          receiveLoop(updatedTab, updatedRequestBuffer)

        case pidExit: ErlangPidExit =>
          val updatedTab = Linker.onPidExit(tab, pidExit, ctx)
          receiveLoop(updatedTab, requestMap)

        case aRefExit: ActorRefExit =>
          val updatedTab = onActorRefExit(aRefExit, tab, ctx)
          receiveLoop(updatedTab, requestMap)

        case msg: Msg =>
          val (updatedTab, updatedRequestBuffer) = onMsg(msg, tab, requestMap, ctx)
          receiveLoop(updatedTab, updatedRequestBuffer)
      }
    }


  // msg handler
  def onMsg(
             msg: Msg,
             tab: TranslationTable,
             requestMap: Service.BufferMap,
             ctx: ActorContext[Command]): (TranslationTable, Service.BufferMap) = {
    msg match {
      case Decode(erlangMsg, replyTo) =>
        val (akkaMsg, updatedTab) = Decoder.decode(erlangMsg, tab, ctx)
        replyTo ! Receiver.Decoded(akkaMsg)
        (updatedTab, requestMap)

      case Encode(akkaMsg, sendToPid) =>
        val (erlangMsg, updatedTab) = Encoder.encode(akkaMsg, tab, ctx)
        receptionistMbox.send(sendToPid, erlangMsg)
        (updatedTab, requestMap)
    }
  }


  // actor exit handler
  def onActorRefExit(aRefExit: ActorRefExit, tab: TranslationTable, ctx: ActorContext[Command]): TranslationTable = {
    // dobbiamo uccidere il receiver corrispondente all'attore terminato
    ctx.stop(aRefExit.receiver)
    TranslationTable.remove(tab, aRefExit.akkaActor)
  }
}
