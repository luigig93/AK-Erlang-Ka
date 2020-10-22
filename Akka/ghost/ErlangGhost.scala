package ghost
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import com.ericsson.otp.erlang.{OtpErlangAtom, OtpErlangPid, OtpMbox, OtpNode}

import scala.reflect.runtime.{universe => u}  // reflection

object ErlangGhost {

  def apply(node: OtpNode, erlangPid: OtpErlangPid, expMsgType: u.Type): Behavior[Any] =
    Behaviors.setup { _ =>
      // creaiamo la mailbox che ci servirà per la send
      val mbox = node.createMbox() // la usiamo solo per fare send, non serve registrarle localmente
      ghostLoop(mbox, erlangPid, expMsgType)
    }


  def ghostLoop(mbox: OtpMbox, erlangPid: OtpErlangPid, expMsgType: u.Type): Behavior[Any] =
  // un ghost actor ricopre il ruolo del vecchio Send Broker
  // deve aspettare messaggi provenienti da un altro attore Akka
  // facendo finta di essere un attore Akka, ed inoltrare i messaggi (dopo encoding) ad Erlang

  Behaviors.receiveMessage {
      msg: Any =>
        // reflection
        val mirror = u.runtimeMirror(getClass.getClassLoader)
        val className = msg.getClass.getName
        val classSymbol = mirror.staticClass(className)
        val clsType = classSymbol.toType
        // qui simula il controllo di tipo dei messaggi Akka
        // se si vuole bloccare l'esecuzione, allora usare un assert
        if(clsType <:< expMsgType) {
          // fai encoding di msg
          println("new msg...")
          // val erlangMsg = encode(msg)
          println("encode...")
          // invialo a Erlang
          mbox.send(erlangPid, new OtpErlangAtom("msg"))
        } else {
          // segnala l'errore
          println("msg type error...")
          // e ora, che si fa?
          // - accettare il fallimento e andare avanti
          // - propagare l'eccezione
          // - terminare l'esecuzione
        }

        ghostLoop(mbox, erlangPid, expMsgType)
    }
}