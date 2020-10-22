package actors
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import com.ericsson.otp.erlang.{OtpErlangAtom, OtpErlangPid, OtpMbox, OtpNode}

object ErlangGhost {

  def apply[M](node: OtpNode, erlangPid: OtpErlangPid): Behavior[M] =
    Behaviors.setup { _ =>
      // creaiamo la mailbox che ci servirà per la send
      val mbox = node.createMbox() // la usiamo solo per fare send, non serve registrarla localmente
      ghostLoop[M](mbox, erlangPid)
    }

  def ghostLoop[M](mbox: OtpMbox, erlangPid: OtpErlangPid): Behavior[M] =
  // un ghost actor ricopre il ruolo del vecchio Send Broker
  // deve aspettare messaggi provenienti da un altro attore Akka
  // facendo finta di essere un attore Akka, ed inoltrare i messaggi (dopo encoding) ad Erlang

  Behaviors.receiveMessage {
    case msg: M =>
      // fai encoding di msg
      println("new msg...")
      // val erlangMsg = encode(msg)
      println("encode...")
      // invialo a Erlang
      mbox.send(erlangPid, new OtpErlangAtom("msg"))
      ghostLoop[M](mbox, erlangPid)

    case _ =>
      println("errore di tipo: l'attore non è in grado di gestire questo messaggio")
      ghostLoop[M](mbox, erlangPid)
    }
}
