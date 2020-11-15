package erlang
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.ActorContext
import com.ericsson.otp.erlang._
import scala.reflect.runtime.{universe => u}


object Encoder {
  val mirror: u.Mirror = u.runtimeMirror(getClass.getClassLoader)


  def encode(
              scalaRef: Any,
              tab: TranslationTable,
              ctx: ActorContext[Receptionist.Command]
            ): (OtpErlangObject, TranslationTable) = {
    scalaRef match {

      case i: Int =>
        (new OtpErlangInt(i), tab)

      case d: Double =>
        (new OtpErlangDouble(d), tab)

      case b: Boolean =>
        (new OtpErlangBoolean(b), tab)

      case s: String =>
        (new OtpErlangString(s), tab)

      case l: List[Any] =>
        var currentTab = tab
        val erlangList = new OtpErlangList(
          l.map{e =>
            val (encElem, updatedTab) = encode(e, currentTab, ctx)
            currentTab = updatedTab
            encElem
          }.toArray
        )

        (erlangList, currentTab)

      case m: Map[Any, Any] =>
        var currentTab = tab

        val keys = m.keys.map{k =>
          val (encKey, updatedTab) = encode(k, currentTab, ctx)
          currentTab = updatedTab
          encKey
        }.toArray

        val values = m.keys.map{k =>
          val (encValue, updatedTab) =  encode(m(k), currentTab, ctx)
          currentTab = updatedTab
          encValue
        }.toArray

        val erlangMap = new OtpErlangMap(keys, values)
        (erlangMap, currentTab)

      case t2: (Any, Any) =>
        var currentTab = tab

        val erlangTuple2 = new OtpErlangTuple(
          List(t2._1, t2._2).map{e =>
            val (encElem, updatedTab) = encode(e, currentTab, ctx)
            currentTab = updatedTab
            encElem
          }.toArray
        )

        (erlangTuple2, currentTab)

      case t3: (Any, Any, Any) =>
        var currentTab = tab

        val erlangTuple3 = new OtpErlangTuple(
          List(t3._1, t3._2, t3._3).map{e =>
            val (encElem, updatedTab) = encode(e, currentTab, ctx)
            currentTab = updatedTab
            encElem
          }.toArray
        )

        (erlangTuple3, currentTab)

      case t4: (Any, Any, Any, Any) =>
        var currentTab = tab

        val erlangTuple4 = new OtpErlangTuple(
          List(t4._1, t4._2, t4._3, t4._4).map{e =>
            val (encElem, updatedTab) = encode(e, currentTab, ctx)
            currentTab = updatedTab
            encElem
          }.toArray
        )

        (erlangTuple4, currentTab)

      // ... in Scala è possibile arrivare fino a t22: le codifichiamo tutte?

      case akkaRef: ActorRef[Any] =>

        if(tab.contains(akkaRef)) {
          (tab.getErlangPid(akkaRef), tab)

        }else {
          // bisogna creare un receiver
          val mbox = Receptionist.node.createMbox()
          val receiver = ctx.spawnAnonymous(Receiver(mbox, akkaRef, ctx.self))
          // ATTENZIONE: bisogna fare il watch dell'attore e non del receiver
          // perchè il receiver è figlio del receptionist e non dell'attore!
          ctx.watchWith(akkaRef, Receptionist.ActorRefExit(akkaRef, receiver))
          val updateTable = TranslationTable.update(tab, akkaRef, mbox.self())
          (mbox.self(), updateTable)
        }

      case r: AnyRef =>
        /* attenzione alla posizione di questo case, deve essere posto alla fine,
           altrimenti liste, mappe, e tuple matchano con questo case!

           NOTA: qui riusciamo anche a gestire una buona parte delle immutable collections
           non funzionano Vector e Tree. E' possibile creare dei case ad hoc,
           ma ai fini dell'esperimento di interoperabilità Akka-Erlang ne vale davvero la pena?
        */

        // reflection
        val className = scalaRef.getClass.getName
        val classSymbol = mirror.staticClass(className)
        val classType = classSymbol.toType
        val instanceMirror = mirror.reflect(r)

        // costruttore
        val cons = classType.decl(u.termNames.CONSTRUCTOR).asMethod
        // f()()... una funzione può avere più liste di parametri
        // prendiamo solo la prima (assumiamo no currying e no implicit)
        val consParams = cons.paramLists.head

        if(consParams.nonEmpty){
          // abbiamo una classe (case class o class con soli val... ragionare su questa cosa)
          // in teoria dovrebbero viaggiare all'interno dei messaggi solamente dati immutabili
          // quindi per ora non filtriammo, ma assumiamo che vengano inviati solamente dati immutabili.
          var currentTab = tab
          val erlObjList = consParams.map{parSym =>
            val par = instanceMirror.reflectField(parSym.asTerm).get
            val (encPar, updatedTab) = encode(par, currentTab, ctx)
            currentTab = updatedTab
            encPar
          }

          val tag = new OtpErlangAtom(className)
          val erlangTuple = new OtpErlangTuple((tag::erlObjList).toArray)
          (erlangTuple, currentTab)

        }else {
          // abbiamo un object
          (new OtpErlangAtom(className), tab)
        }
    }
  }
}
