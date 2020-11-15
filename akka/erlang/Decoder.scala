package erlang
import akka.actor.typed.scaladsl.ActorContext
import com.ericsson.otp.erlang._
import scala.reflect.runtime.{universe => u}

object Decoder {
  val mirror: u.Mirror = u.runtimeMirror(getClass.getClassLoader)

  /*
     NOTA: perchè fare typechecking qui dentro? Lasciamolo fare implicitamente all'attore
     quando riceve il messaggio... se non riesce a catturare in un case della receive il messaggio
     allora il pattern matching salta. Questo è una forma di controllo del tipo a runtime.
     ATTENZIONE! Potrebbero esserci messaggi definiti come Object con metodo apply! (Oppure con classi "normali")
     NOTA: non è obbligatorio (ma consigliato) usare case class/object come messaggi.
     NOTA: Questo sistema è stato testato assumendo di avere solamente messaggi codificati come case class/object.
   */

  def decode  (
                 erlangObj: OtpErlangObject,
                 tab: TranslationTable,
                 ctx: ActorContext[Receptionist.Command]
               ): (Any, TranslationTable) = {
    erlangObj match {

      case i: OtpErlangInt =>
        (i.intValue(), tab)

      case d: OtpErlangDouble =>
        (d.doubleValue(), tab)

      case s: OtpErlangString =>
        (s.stringValue(), tab)

      case l: OtpErlangList =>
        var currrentTab = tab
        val scalaList = List(l.elements().map{e =>
          val (decElem, updatedTab) = decode(e, currrentTab, ctx)
          currrentTab = updatedTab
          decElem
        }: _*)
        (scalaList, currrentTab)

      case m: OtpErlangMap =>
        var currentTab = tab
        val keys = m.keys().map{k =>
          val (decKey, updatedTab) = decode(k, currentTab, ctx)
          currentTab = updatedTab
          decKey
        }

        val values = m.keys().map{k =>
          val (decValue, updatedTab) = decode(m.get(k), currentTab, ctx)
          currentTab = updatedTab
          decValue
        }

        val scalaMap = Map(keys.zip(values): _*)
        (scalaMap, currentTab)

      case p: OtpErlangPid =>
        // bisogna controllare se è già presente un'associazione pid/actorRef
        if (tab.contains(p)) {
          (tab.getAkkaRef(p), tab)

        } else {
          // bisogna creare un nuovo ghost
          val ghostRef = ctx.spawnAnonymous(Ghost(p, ctx.self))
          val updatedTab = TranslationTable.update(tab, ghostRef, p)
          (ghostRef, updatedTab)
        }

      case a: OtpErlangAtom =>
        // un atomo può corrispondere, ad un messaggio codificato con un case object, oppure ad una stringa (o Symbol)
        try {
          // il nome degli object ha questo formato: package.OuterClass$Object$
          val objectName = a.atomValue()
          val objectSymbol = mirror.staticModule(objectName)
          val objectMirror = mirror.reflectModule(objectSymbol)
          (objectMirror.instance, tab)

        } catch {
          case _: scala.ScalaReflectionException =>
            // l'oggetto non è stato trovato, quindi si decodifica come una stringa
            // In sviluppi futuri si potrebbe prendere in considerazione anche la decodifica di atomi come Symbol
            (a.atomValue(), tab)
        }

      case t: OtpErlangTuple =>
        t.elementAt(0) match {
          case atomTag: OtpErlangAtom =>
            // una tupla taggata (cioè che in prima posizione ha un atomo) diventa una case class Msg
            // per convenzione scriviamo anche lato Erlang i tag delle tuple
            // nel formato che rispecchia i path delle classi lato Akka
            // quindi package.OuterClass$Object$ , package.OuterClass$Class
            val className = atomTag.atomValue()
            val classSymbol = mirror.staticClass(className)
            val classMirror = mirror.reflectClass(classSymbol)
            val clsType = classSymbol.toType
            val cons = clsType.decl(u.termNames.CONSTRUCTOR).asMethod
            val consMirror = classMirror.reflectConstructor(cons)

            // ricorsione sugli elementi della tupla
            val tupElems = t.elements().drop(1).toList // il tag lo abbiamo già preso
            var currentTab = tab
            val consArgs = tupElems.map{e =>
              val (decElem, updatedTab) = decode(e, currentTab, ctx)
              currentTab = updatedTab
              decElem
            }

            // invocazione costruttore
            (consMirror(consArgs: _*), currentTab)

          case _ =>
            // abbiamo una tupla normale
            var currentTab = tab
            val tupElems = t.elements().map{e =>
              val (decElem, updatedTab) = decode(e, currentTab, ctx)
              currentTab = updatedTab
              decElem
            }

            tupElems.length match {
              case 1 => (Tuple1(tupElems(0)), currentTab)
              case 2 => (Tuple2(tupElems(0), tupElems(1)), currentTab)
              case 3 => (Tuple3(tupElems(0), tupElems(1), tupElems(2)), currentTab)
              case 4 => (Tuple4(tupElems(0), tupElems(1), tupElems(2), tupElems(3)), currentTab)
              // ... andare avanti fino a case 22 ... oppure trovare un modo più furbo
            }
        }

        // case ...  TODO: mancano gli altri tipi di dato Erlang: gestire fun (e ref)
    }
  }
}
