package erlang
import akka.actor.typed.{Behavior, ActorRef}
import akka.actor.typed.scaladsl.Behaviors
import com.ericsson.otp.erlang._

object Ghost {
  def apply(erlangPid: OtpErlangPid, erlReceptionist: ActorRef[Receptionist.Command]): Behavior[Any] =
    Behaviors.setup{ _ =>
      // ogni volta che viene creato un ghost, bisogna subito linkare la mailbox del receptionist al pid del ghost
      // ATTENZIONE: fondamentale eseguire il link con la stessa mailbox su cui si stanno aspettando le exit
      Linker.linkerMbox.link(erlangPid)
      ghostLoop(erlangPid, erlReceptionist)
    }

  def ghostLoop(erlangPid: OtpErlangPid, erlReceptionist: ActorRef[Receptionist.Command]): Behavior[Any] =
    Behaviors.receiveMessage { akkaMsg =>
        erlReceptionist ! Receptionist.Encode(akkaMsg, erlangPid)
        ghostLoop(erlangPid, erlReceptionist)
    }
}