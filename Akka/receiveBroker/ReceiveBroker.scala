import akka.actor.typed.scaladsl.{Behaviors, ActorContext}
import akka.actor.typed.{ActorRef, Behavior, Terminated}
import com.ericsson.otp.erlang._  // Jinterface
import scala.reflect.runtime.{universe => u}
import actors.ErlangGhost


// future
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}


object ReceiveBroker {
  sealed trait Command
  final case class NewErlangMsg(msg: OtpErlangObject) extends Command
  case object FutureFail extends Command
  // final case class GlobalSend(toName: String, msg: AnyRef) extends Command
  // final case class WhereIs(name:String) extends Command
  // final case class GlobalRegister(name: String) extends Command
  // final case class GlobalUnregister(name: String) extends Command

  // il broker è a disposizione di tutti
  // un brooker per nodo
  val node = new OtpNode("akka_broker") // nome nodo da configurare tramite config
  val hnmAddress = Map("name" -> "hnm", "node" -> "node@host")

  // reflection (dove va posizionato? Va bene qui? Nell'Apply?)
  val mirror: u.Mirror = u.runtimeMirror(getClass.getClassLoader)

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


  def apply[M](name: String, akkaActor: ActorRef[M]): Behavior[Command] =
    Behaviors.setup { _ =>
      // creiamo una mailbox, e registriamola locamente
      val mbox = node.createMbox(name)
      // da questo momento possiamo ricevere messaggi da Erlang

      // ora dobbiamo passare ad un behaviour che aspetti i messaggi
      erlangReceiver(mbox, akkaActor)
    }


  def decode[M](msg: OtpErlangObject, expType: u.Type, ctx: ActorContext[M]): Any = {
    msg match {
      /*
         TODO: uniformare tutti gli altri case con la nuova signature del metodo decode
         TODO: fare type checking all'interno di ogni case
         TODO: scegliere strategia per la gestione di eventuali errori di tipaggio
       */
      case p: OtpErlangPid =>
        // TODO: implementare tabella traduzione pid/ActoRef
        // bisogna controllare se è già presente un'associazione pid/actorRef
        // se è già presente (cioè esiste un ghost) si sostituisce direttamente
        // con quell'ActorRef
        // altrimenti, se è la prima volta che si vede, bisogna creare un ghost!

        // quando si hanno problemi di univocità del nome dell'attore, usare la spawnAnonymus!
        val ghostRef = ctx.spawnAnonymous(ErlangGhost[M](node, p))
        ghostRef // approfondire discorso type erasure, a runtime potrebbe non esserci alcuna differenza tra ActorRef[Any] e ActorRef[M]

      case i: OtpErlangInt =>
        println("this is an int!")
        i.intValue()

      case d: OtpErlangDouble =>
        println("this is a double!")
        d.doubleValue()

      case s: OtpErlangString =>
        println("this is a string")
        s.stringValue()

      case a: OtpErlangAtom =>
        println("this is an atom!")
        // un atomo corrisponde ad un messaggio di tipo case object (oppure ad una stringa, o symbol?)
        // il nome degli object ha questo formato: OuterClass$Object$
        try {
          val objectName = a.atomValue()
          val objectSymbol = mirror.staticModule(objectName)
          val objectMirror = mirror.reflectModule(objectSymbol)
          objectMirror.instance

        } catch {
          case _: scala.ScalaReflectionException =>
            // l'oggetto non è stato trovato, quindi codifichiamo come una stringa
            a.atomValue()
        }

      case l: OtpErlangList =>
        println("this is a list!")
        List(l.elements().map(decode):_*)

      case m: OtpErlangMap =>
        println("this is a map!")
        val keys = m.keys().map(decode)
        val values = m.keys().map{k => decode(m.get(k))}
        Map(keys.zip(values):_*)

      case t: OtpErlangTuple =>
        println("this is a tuple!")
        // una tupla taggata (cioè che in prima posizione ha un atomo) deve diventare una case class Msg
        // assumiamo che il protocollo sia già stato scritto e concordato
        t.elementAt(0) match {
          case atomTag: OtpErlangAtom =>
            // per convenzione scriviamo anche lato Erlang i tag delle tuple
            // nel formato che rispecchia i path delle classi lato Akka
            // quindi OuterClass$Object$ , OuterClass$Class
            val className = atomTag.atomValue()
            val classSymbol = mirror.staticClass(className)
            val classMirror = mirror.reflectClass(classSymbol)
            val clsType = classSymbol.toType
            val cons = clsType.decl(u.termNames.CONSTRUCTOR).asMethod
            println(cons.paramLists)
            val consMirror = classMirror.reflectConstructor(cons)

            // bisogna fare type checking dei parametri che verranno passati al costruttore
            //TODO: implementare type checking parametri costruttore
            // prendiamo il tipo di ogni parametro
            // il typecheck lo fai così: p.info <:< u.typeOf[AnyRef]
            val paramTypes = cons.paramLists.head.map(_.info)

            // invocazione: prima bisogna andare in ricorsione sugli elementi della tupla
            val tupElems = t.elements().drop(1).toList // il tag lo abbiamo già preso
            val consArgs = tupElems.zip(paramTypes).map{t => decode(t._1, t._2)}

            // invocarlo in questo modo
            // consMirror(pars:_*)
            consMirror(consArgs:_*)

          case _ =>
            // abbiamo una normalissima tupla
            val tupElems = t.elements().map(decode)
            tupElems.length match {
              case 1 => tupElems(0)
              case 2 => (tupElems(0), tupElems(1))
              case 3 => (tupElems(0), tupElems(1), tupElems(2))
              case 4 => (tupElems(0), tupElems(1), tupElems(2), tupElems(3))
              // ... andare avanti fino a case 22 ... oppure trovare un modo più furbo
            }
        }

      // case ...  TODO: mancano gli altri tipi di dato Erlang: gestire fun (e ref)
    }
  }
}