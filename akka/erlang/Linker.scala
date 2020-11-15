package erlang
import com.ericsson.otp.erlang._
import akka.actor.typed.scaladsl.ActorContext
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.Success


object Linker {
  val linkerMbox: OtpMbox = Receptionist.node.createMbox()


  def apply(ctx: ActorContext[Receptionist.Command]): Unit =
    startExitReceiver(ctx)


  def startExitReceiver(ctx: ActorContext[Receptionist.Command]): Unit = {
    val futureExitPid = futureReceive(linkerMbox)
    ctx.pipeToSelf(futureExitPid) {
      case Success(exitPid: OtpErlangPid) =>
        Receptionist.ErlangPidExit(Some(exitPid))

      case _ =>
        // qui catturiamo il fail, e l'eventuale ricezione di un messaggio nella mailbox
        // che usiamo solamente per ricevere le exit. Evento ipotetico. Non si verificherà mai.
        Receptionist.ErlangPidExit(None)
    }
  }


  def futureReceive(mbox: OtpMbox): Future[OtpErlangObject] =
    Future {
      try{
        mbox.receive()

      } catch {
        case exit: OtpErlangExit =>
          exit.pid()
          // purtroppo non c'è modo di gestire anche la reason
          // questa è ovviamente una limitazione di  questo sistema
          // ma in akka non esiste il concetto di exit(reason)
          // akka basa tutte le interazioni tra attori su scambio di messaggi
      }
    }


  // exit handler
  def onPidExit(
                 tab: TranslationTable,
                 pidExit: Receptionist.ErlangPidExit,
                 ctx: ActorContext[Receptionist.Command]): TranslationTable = {
    // attivare nuovamente il linker
    Linker(ctx)

    // controllo per eventuale fail del future
    // a questo punto il ghost deve esister per forza, assumiamo l'esistenza!
    if(pidExit.pid.nonEmpty){
      // bisogna uccidere il ghost associato al pid
      val ghost = tab.getAkkaRef(pidExit.pid.get)

      // se qualche attore akka sta facendo watch su questo ghost, verrà notificato di questa uccisione
      // che si fa con la reason? Eh... purtroppo non c'è modo di gestirle in Akka
      ctx.stop(ghost)

      // aggiornare tabella
      TranslationTable.remove(tab, ghost)
    } else {
      // c'è stato un fail del future, fa niente
      tab
    }
  }
}
