import com.ericsson.otp.erlang._
import akka.actor.typed.ActorRef
import scala.reflect.runtime.{universe => u}

object Encoder {

  /************** RUNTIME REFLECTION *******************/
  val mirror: u.Mirror = u.runtimeMirror(getClass.getClassLoader)

  def encode(ref: Any): OtpErlangObject = {
    ref match {

      case i: Int =>
        println("this is an int")
        new OtpErlangInt(i)

      case d: Double =>
        println("this is a double")
        new OtpErlangDouble(d)

      case b: Boolean =>
        println("this is a bool")
        new OtpErlangBoolean(b)

      case s: String =>
        println("this is a String")
        new OtpErlangString(s)

      case l: List[Any] =>
        println("this is a list")
        new OtpErlangList(l.map(encode).toArray)

      case m: Map[Any, Any] =>
        println("this is a map")
        val keys = m.keys.map(encode).toArray
        val values = m.keys.map{k => encode(m(k))}.toArray
        new OtpErlangMap(keys, values)

      case t2: (Any, Any) =>
        println("this is a tuple2")
        new OtpErlangTuple(List(t2._1, t2._2).map(encode).toArray)

      case t3: (Any, Any, Any) =>
        println("this is a tuple3")
        new OtpErlangTuple(List(t3._1, t3._2, t3._3).map(encode).toArray)

      case t4: (Any, Any, Any, Any) =>
        println("this is a tuple4")
        new OtpErlangTuple(List(t4._1, t4._2, t4._3, t4._4).map(encode).toArray)

      // ... in Scala è possibile arrivare fino a t22: le codifichiamo tutte?

      /*
      TODO: implementare un meccanismo di traduzione tra ActorRef (Akka) e Pid (Erlang)

      case actRef: ActorRef[Any] =>
        new OtpErlangPid()
       */

      /*
      TODO: gestire la corrispondenza con gli altri tipi di dato Erlang:
      - Reference: vale la pena approfondire...
      - Fun: vale la pena approfondire...
      - Bit String, Port: basso livello, poco interessanti ai fini dell'esperimento
      - Record: tanto vengono trasformati in tuple, quindi poco interessanti

      per ora si gestiscono solo i principali e più interessanti.
       */

      case r: AnyRef =>
        /* attenzione alla posizione di questo case, deve essere posto alla fine,
           altrimenti liste, mappe, e tuple matchano con questo case!

           NOTA: qui riusciamo anche a gestire una buona parte delle immutable collections
           non funzionano Vector e Tree. E' possibile creare dei case ad hoc,
           ma ai fini dell'esperimento di interoperabilità Akka-Erlang ne vale davvero la pena?
        */

        println("this is an object ref")

        /*************** STATIC REFLECTION ******************/
        val className = ref.getClass.getName
        val classSymbol = mirror.staticClass(className)
        val theType = classSymbol.toType
        // println(theType)

        /************** RUNTIME REFLECTION *******************/
        val instanceMirror =  mirror.reflect(r)

        /************** FILTRAGGIO VAL ***********************/
        val valList = theType.decls.filter{d => d.isTerm && d.asTerm.isVal}.map(_.asTerm).toList
        // println(valList)

        /* il nome del tag va pulito, si presenta in questo formato:
            - OuterClass$Object$
            - OuterClass$Class
            convenzione erlang atomi scritti in minuscolo? .toLowerCase()
            solo che dopo non è immediato tornare indietro durante il decoding
         */
        val tagName = theType.toString.split('$').last

        if(valList.isEmpty){
          // case object, oppure classe che non ha val
          new OtpErlangAtom(tagName)

        }else {
          // (case) class
          /************** RUNTIME REFLECTION *******************/
          val erlObjList = valList.map{valSym => encode(instanceMirror.reflectField(valSym).get)}

          val tag = new OtpErlangAtom(tagName)
          new OtpErlangTuple((tag::erlObjList).toArray)
        }
    }
  }

  // esempi
  sealed trait Command
  case class Text(t: String)
  case class Msg(text: Text) extends Command
  case object Ciao

  def main(args: Array[String]): Unit = {

    // esempio
    val msg = Msg(Text("ciao"))
    val obj = encode(msg)
    println(obj)
  }
}