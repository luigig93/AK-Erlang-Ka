import com.ericsson.otp.erlang._
import scala.reflect.runtime.{universe => u}

object Decoder {

  val mirror: u.Mirror = u.runtimeMirror(getClass.getClassLoader)

  def decode(msg: OtpErlangObject): Any = {
    msg match {
    /* TODO: implementare traduzione PID -> ActorRef
      case p: OtpErlangPid =>
        println("this is a pid!")
        // elaborare il pid e tradurlo in un ActorRef
     */

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
          val objectName = this.getClass.getName + a.atomValue() + '$'
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
        Map(m.keys().map{k => (k, m.get(k))}:_*)

      case t: OtpErlangTuple =>
        println("this is a tuple!")
        // una tupla taggata (cioè che in prima posizione ha un atomo) deve diventare una case class Msg
        // assumiamo che il protocollo sia già stato scritto e concordato
        t.elementAt(0) match {
          case atomTag: OtpErlangAtom =>
            // attenzione al nome: OuterClass$ClassName
            // dipende tutto da dove sono posizionate le dichiarazioni delle case class
            val className = this.getClass.getName + atomTag.atomValue()
            val classSymbol = mirror.staticClass(className)
            val classMirror = mirror.reflectClass(classSymbol)
            val clsType = classSymbol.toType
            val cons = clsType.decl(u.termNames.CONSTRUCTOR).asMethod
            val consMirror = classMirror.reflectConstructor(cons)

            // invocazione: prima bisogna andare in ricorsione sugli elementi della tupla
            val tupElems = t.elements().drop(1).toList // il tag lo abbiamo già preso
            val consPars = tupElems.map(decode)

            // bisogna fare type checking dei parametri che verranno passati al costruttore
            //TODO: implementare type checking parametri costruttore

            // invocarlo in questo modo
            // consMirror(pars:_*)
            consMirror(consPars:_*)

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
  case class Msg(temps: Map[String, Int])
  case object Ciao

  def main(args: Array[String]): Unit = {

    // esempio
    val keys = new Array[OtpErlangObject](3)
    keys(0) = new OtpErlangString("Roma")
    keys(1) = new OtpErlangString("Milano")
    keys(2) = new OtpErlangString("Bologna")

    val values = new Array[OtpErlangObject](3)
    values(0) = new OtpErlangInt(23)
    values(1) = new OtpErlangInt(32)
    values(2) = new OtpErlangInt(51)

    val objs = new Array[OtpErlangObject](2)
    objs(0) = new OtpErlangAtom("Msg")
    objs(1) = new OtpErlangMap(keys, values)
    val tuple = new OtpErlangTuple(objs)

    val atom = new OtpErlangAtom("Ciao")

    println(decode(atom))

  }
}