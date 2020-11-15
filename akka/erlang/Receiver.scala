package erlang
import com.ericsson.otp.erlang._
import akka.actor.typed.{ActorRef, Behavior, PostStop}
import akka.actor.typed.scaladsl.Behaviors
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Success}


object Receiver {
  sealed trait Command
  final case class NewErlangMsg(msg: OtpErlangObject) extends Command
  case object FutureFail extends Command
  final case class Decoded(akkaMsg: Any) extends Command


  // entry point
  def apply(
              mbox: OtpMbox,
              akkaActor: ActorRef[Any],
              erlangReceptionist: ActorRef[Receptionist.Command]
           ): Behavior[Command] =
      startErlangReceiver(mbox, akkaActor, erlangReceptionist)


  def startErlangReceiver (
                            mbox: OtpMbox,
                            akkaActor: ActorRef[Any],
                            erlReceptionist: ActorRef[Receptionist.Command]
                          ): Behavior[Command] =
    Behaviors.setup { ctx =>
      val futureErlangMsg = futureReceive(mbox)
      ctx.pipeToSelf(futureErlangMsg) {
        case Success(erlangMsg) =>
          // è arrivato un messaggio (future completato), recapitarlo a se stesso
          NewErlangMsg(erlangMsg)

        case Failure(_) =>
          // c'è stato un problema, volendo si può catturare la reason _
          FutureFail
      }

      loopErlangReceiver(mbox, akkaActor, erlReceptionist)
    }


  def loopErlangReceiver(
                           mbox: OtpMbox,
                           akkaActor: ActorRef[Any],
                           erlReceptionist: ActorRef[Receptionist.Command]
                        ): Behavior[Command] =
    Behaviors.receive[Command] {
        case (ctx, NewErlangMsg(erlMsg)) =>
          // è arrivato un nuovo messaggio, richiedere decodifica
          erlReceptionist ! Receptionist.Decode(erlMsg, ctx.self)
          loopErlangReceiver(mbox, akkaActor, erlReceptionist)

        case (_, FutureFail) =>
          // c'è stato qualche problema con la receive, fa niente, ricominciamo da capo
          startErlangReceiver(mbox, akkaActor, erlReceptionist)

        case (_, Decoded(msg)) =>
          // inoltrare messaggio
          akkaActor ! msg
          startErlangReceiver(mbox, akkaActor, erlReceptionist)

    }.receiveSignal{
      case (_, PostStop) =>
        // l'attore akka associato a questo receiver è terminato
        // quindi termina anche il receiver
        mbox.close()  // equivale a mbox.exit("normal")
        Behaviors.same
    }


  def futureReceive(mbox: OtpMbox): Future[OtpErlangObject] =
    Future {
      mbox.receive()
    }
}
