package broker
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior, Terminated}
import akka.actor.typed.receptionist.Receptionist
import com.ericsson.otp.erlang._  // Jinterface

// future
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}


object Broker {
  sealed trait Command
  final case class NewErlangMsg(msg: OtpErlangObject) extends Command
  case object FutureFail extends Command
  final case class GlobalSend(toName: String, msg: AnyRef) extends Command
  final case class WhereIs(name:String) extends Command
  final case class GlobalRegister(name: String) extends Command
  final case class GlobalUnregister(name: String) extends Command
  final case class ErlangMsgWrap[M](msg: M, sendTo: ActorRef[M]) extends Command

  // il broker è a disposizione di tutti
  // un brooker per nodo
  val node = new OtpNode("akka_broker") // nome nodo da configurare tramite config
  val hnmAddress = Map("name" -> "hnm", "node" -> "node@host")

  def futureReceive(mbox: OtpMbox): Future[OtpErlangObject] =
    Future {
      mbox.receive()
    }

  def erlangReceiver[M](mbox: OtpMbox, akkaActor: ActorRef[M]): Behavior[Command] =
    Behaviors.setup { ctx =>
      val futureErlangMsg = futureReceive(mbox)
      ctx.pipeToSelf(futureErlangMsg) {
        case Success(erlangMsg) =>
          // è arrivato un messaggio (future completato), recapitarlo a se stesso
          NewErlangMsg(erlangMsg)

        case Failure(e) =>
          // c'è stato un problema
          FutureFail
      }

      // mettersi in ascolto del messaggio
      Behaviors.receiveMessage {
        case NewErlangMsg(msg) =>
          // è arrivato un nuovo messaggio, decodificarlo ed inviarlo all'attore Akka
          val akkaMsg = decode(msg)
          akkaActor ! akkaMsg // inoltrare messaggio all'attore Akka
          erlangReceiver(mbox, akkaActor)

        case FutureFail =>
          // c'è stato qualche problema con la receive, fa niente, ricominciamo da capo
          erlangReceiver(mbox, akkaActor)
      }
    }

  def loop(mbox: OtpMbox): Behavior[Command] =
    Behaviors.receive {
      case (_, GlobalRegister(name)) =>
        // {register_name, Name, Pid}
        val msg = new Array[OtpErlangObject](3)
        msg(0) = new OtpErlangAtom("register_name")
        msg(1) = new OtpErlangString(name)
        msg(2) = mbox.self()
        val tuple = new OtpErlangTuple(msg)
        mbox.send(hnmAddress("name"), hnmAddress("node"), tuple)
        loop(mbox)

      case (_, GlobalUnregister(name)) =>
        // {unregister_name, Name}
        val msg = new Array[OtpErlangObject](3)
        msg(0) = new OtpErlangAtom("unregister_name")
        msg(1) = new OtpErlangString(name)
        msg(2) = mbox.self()
        val tuple = new OtpErlangTuple(msg)
        mbox.send(hnmAddress("name"), hnmAddress("node"), tuple)
        loop(mbox)

      case (_, WhereIs(name)) =>
        // {whereis_name, Name, Pid}
        val msg = new Array[OtpErlangObject](3)
        msg(0) = new OtpErlangAtom("whereis_name")
        msg(1) = new OtpErlangString(name)
        msg(2) = mbox.self()
        val tuple = new OtpErlangTuple(msg)
        mbox.send(hnmAddress("name"), hnmAddress("node"), tuple)
        loop(mbox)

      case (_, GlobalSend(toName, msg)) =>
        // {send, Name, Msg}
        val msg = new Array[OtpErlangObject](3)
        msg(0) = new OtpErlangAtom("send")
        msg(1) = new OtpErlangString(toName)
        msg(2) = encode(msg) // qui bisogna fare l'encoding del messaggio
        val tuple = new OtpErlangTuple(msg)
        mbox.send(hnmAddress("name"), hnmAddress("node"), tuple)
        loop(mbox)

      case (_, ErlangMsgWrap(msg, sendTo)) =>
        // l'attore Akka vuole inviare un messaggio ad un attore Erlang tramite il suo ActorRef
        val erlangActor = translate(sendTo) // bisogna tradurre l'ActorRef in un Pid Erlang
        val erlangMsg = encode(msg)  // bisogna fare l'encoding del messaggio
        mbox.send(erlangActor, erlangMsg)
        loop(mbox)
    }

  def apply[M](name: String, akkaActor: ActorRef[M]): Behavior[Command] =
    Behaviors.setup { ctx =>
      // creiamo una mailbox, e registriamola locamente
      val mbox = node.createMbox(name)
      // da questo momento potremo ricevere messaggi da Erlang
      // infatti avendo registrato la mailbox localmente
      // potremo ricevere messaggi così: {RegName, Node} ! msg
      // quindi dobbiamo lanciare un receiver
      ctx.spawn(erlangReceiver(mbox, akkaActor), "receiver")

      // ora dobbiamo cambiare behaviour e passare ad un loop che aspetta messaggi
      loop(mbox)
    }
}
