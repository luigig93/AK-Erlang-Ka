import com.ericsson.otp.erlang._
import scala.reflect.runtime.{universe => u}
import akka.actor.typed.scaladsl.ActorContext
import actors.ErlangGhost

object Decoder {

  val mirror: u.Mirror = u.runtimeMirror(getClass.getClassLoader)
  // questo va passato come parametro alla decode, oppure trasformare il decoder in una classe
  // e lasciarlo come campo (possibilmente privato) della classe
  val node = new OtpNode("akka")

  def typeCheck[T: u.TypeTag](value: T, expType: u.Type): Option[Any] = {
    if(u.typeOf[T] <:< expType) Some(value) else None
  }

  def decode(msg: OtpErlangObject, expType: u.Type, ctx: ActorContext[Any]): Any = {
    msg match {
        /*
           TODO: uniformare tutti gli altri case con la nuova signature del metodo decode
           TODO: fare type checking all'interno di ogni case
           TODO: scegliere strategia per la gestione di eventuali errori di tipaggio
         */
      case p: OtpErlangPid =>
        // TODO: scegliere la strategia di creazione dei ghost: multipli su richiesta, oppure unico?
        // TODO: se si sceglie ghost unico, allora implementare tabella traduzione pid/ActoRef
        // bisogna controllare se è già presente un'associazione pid/actorRef
        // se è già presente (cioè esiste un ghost) si sostituisce direttamente
        // con quell'ActorRef
        // altrimenti, se è la prima volta che si vede, bisogna creare un ghost!
        // qui stiamo cercando di ricostruire un messaggio akka
        // quindi tramite il tipo del parametro corrispodente dobbiamo capire
        // come è tipato l'actorRef, cioè prendere il parametro di tipo
        // che corrisponde ai messaggi che quel tipo di attore sa gestire.
        // per questo è importante mandare in ricorsione anche i tipi
        // altrimenti qui non sappiamo che fare...
        // qui ci arriverà un tipo, es: ActorRef[Utente.Command]
        // tramite reflection dobbiamo andare ad estrarre l'argomento del tipo
        // cioè vogliamo estrarre Utente.Command
        // a questo punto dobbiamo andare a spawnare il ghost

        // sappiamo che expType in questo caso è un actorRef (andrebbe controllato... ma ok)
        // risaliamo al parametro di tipo dell'actorRef che ci dice il tipo di messaggi che
        // l'attore sa gestire
        val expMsgType = expType.typeArgs.head
        val ghostRef = ctx.spawn(ErlangGhost(node, p, expMsgType), "ghost")  // qua bisogna rendere il nome univoco
        ghostRef  //:ActorRef[Any]

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

      // case ...  TODO: mancano gli altri tipi di dato Erlang non gestiti nemmeno nel decoding
    }
  }

  // classi esempio
  trait Command
  final case class Msg(text: Int) extends Command
  case object Ciao

  def main(args: Array[String]): Unit = {


    val objs = new Array[OtpErlangObject](2)
    objs(0) = new OtpErlangAtom("Decoder$Msg")
    objs(1) = new OtpErlangInt(23)
    val tuple = new OtpErlangTuple(objs)

    println(decode(tuple))

  }
}