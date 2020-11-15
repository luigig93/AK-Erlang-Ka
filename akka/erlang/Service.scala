package erlang
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.ActorContext
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Success}
import com.ericsson.otp.erlang._


object Service {

  val hnmAddress = Map("name" -> "hnm", "node" -> "hnm@airone")   //hnm@host

  type BufferMap = Map[String, ServiceRequestBuffer]

  sealed trait ServiceRequestBuffer

  final case class RegRequestBuffer(
                               globalName: String,
                               akkaRef: ActorRef[Any],
                               pidToRegister: OtpErlangPid,
                               receiver: ActorRef[Receiver.Command],
                               replyTo: ActorRef[Receptionist.ServiceResponse]) extends ServiceRequestBuffer

  final case class WhereRequestBuffer(
                                  globalName: String,
                                  replyTo: ActorRef[Receptionist.ServiceResponse]) extends ServiceRequestBuffer


  // request handler
  def onServiceRequest(
                        requestMsg: Receptionist.ServiceRequest,
                        requestMap: BufferMap,
                        ctx: ActorContext[Receptionist.Command]): BufferMap = {
    // gen request key
    val requestKey = java.util.UUID.randomUUID.toString
    // create mailbox usa e getta
    val tmpMbox = Receptionist.node.createMbox()

    requestMsg match {
      case request:Receptionist.GlobalRegisterRequest[Any] =>
        onRegRequest(requestKey, request, tmpMbox, requestMap, ctx)

      case request:Receptionist.GlobalWhereIsRequest =>
        onWhereRequest(requestKey, request, tmpMbox, requestMap, ctx)

      case request:Receptionist.GlobalUnregisterRequest =>
        onUnregRequest(request, tmpMbox)
        requestMap
    }
  }


  def onRegRequest(
                    requestKey: String,
                    request: Receptionist.GlobalRegisterRequest[Any],
                    tmpMbox: OtpMbox,
                    requestMap: BufferMap,
                    ctx: ActorContext[Receptionist.Command]
                  ): BufferMap = {
    // service msg
    def encodeErlangMsg(globalName: String, pidToRegister: OtpErlangPid, replyTo: OtpErlangPid): OtpErlangTuple = {
      // {register_name, Name, Pid, ReplyToPid}
      val obj = new Array[OtpErlangObject](4)
      obj(0) = new OtpErlangAtom("register_name")
      obj(1) = new OtpErlangAtom(globalName)
      obj(2) = pidToRegister
      obj(3) = replyTo
      new OtpErlangTuple(obj)
    }

    // creare receiver!
    val mbox = Receptionist.node.createMbox()
    val pidToRegister = mbox.self()
    val receiver = ctx.spawnAnonymous(Receiver(mbox, request.actorToRegister, ctx.self))

    //NOTA: la tabella non va ancora aggiornata! Verrà aggiornata quando si riceverà la risposta
    // invia messaggio
    tmpMbox.send(
      hnmAddress("name"),
      hnmAddress("node"),
      encodeErlangMsg(request.globalName, pidToRegister, replyTo=tmpMbox.self())
    )

    // gestione risposta (futura)
    createFutureReceiver(requestKey, ctx, tmpMbox)

    // update request buffer
    requestMap + ( requestKey ->
      Service.RegRequestBuffer(
        request.globalName,
        request.actorToRegister,
        pidToRegister,
        receiver,
        request.replyTo)
      )
  }


  def onWhereRequest(
                      requestKey: String,
                      request: Receptionist.GlobalWhereIsRequest,
                      tmpMbox: OtpMbox,
                      requestMap: BufferMap,
                      ctx: ActorContext[Receptionist.Command]
                    ): BufferMap = {
    // service msg
    def encodeErlangMsg(globalName: String, replyTo: OtpErlangPid): OtpErlangTuple = {
      // {whereis_name, Name, Pid}
      val obj = new Array[OtpErlangObject](3)
      obj(0) = new OtpErlangAtom("whereis_name")
      obj(1) = new OtpErlangAtom(globalName)
      obj(2) = replyTo
      new OtpErlangTuple(obj)
    }

    // invio messaggio
    tmpMbox.send(
      hnmAddress("name"),
      hnmAddress("node"),
      encodeErlangMsg(request.globalName, replyTo=tmpMbox.self())
    )

    // gestione risposta (futura)
    createFutureReceiver(requestKey, ctx, tmpMbox)

    // update request buffer
    requestMap + (requestKey ->
      Service.WhereRequestBuffer(
        request.globalName,
        request.replyTo)
      )
  }


  def onUnregRequest(
                      request: Receptionist.GlobalUnregisterRequest,
                      tmpMbox: OtpMbox): Unit = {
    // service msg
    def encodeErlangMsg(globalName: String): OtpErlangTuple = {
      // {unregister_name, Name}
      val obj = new Array[OtpErlangObject](2)
      obj(0) = new OtpErlangAtom("unregister_name")
      obj(1) = new OtpErlangAtom(globalName)
      new OtpErlangTuple(obj)
    }

    // invio messaggio
    tmpMbox.send(
      hnmAddress("name"),
      hnmAddress("node"),
      encodeErlangMsg(request.globalName)
    )

    // chiusura mbox usa e getta
    Receptionist.node.closeMbox(tmpMbox)
  }


  // response handler
  def onServiceCompleted(
                          serviceResponse: Receptionist.ServiceCompleted,
                          tab: TranslationTable,
                          requestMap: BufferMap,
                          ctx: ActorContext[Receptionist.Command]): (TranslationTable, BufferMap) = {
    // chiusura mailbox usa e getta
    Receptionist.node.closeMbox(serviceResponse.tmpMbox)

    // estrazione buffer
    val buffer = requestMap(serviceResponse.serviceKey)
    val updatedRequestBuffer = requestMap - serviceResponse.serviceKey

    // gestione risposta
    buffer match {
      case regBuffer: RegRequestBuffer =>
        onRegResponse(serviceResponse.msg, regBuffer, tab, updatedRequestBuffer, ctx)

      case whereBuffer: WhereRequestBuffer =>
        onWhereResponse(serviceResponse.msg, whereBuffer, tab, updatedRequestBuffer, ctx)
    }
  }


  def onRegResponse(
                     erlangMsg: Option[OtpErlangObject],
                     buffer: RegRequestBuffer,
                     tab: TranslationTable,
                     updatedRequestBuffer: BufferMap,
                     ctx: ActorContext[Receptionist.Command]
                   ): (TranslationTable, BufferMap) = {

    def decodeErlangMsg(): Boolean = {
      // erlang response: ok | ko
      erlangMsg match {
        case Some(a: OtpErlangAtom) =>
          a.atomValue match {
            case "ok" => true
            case "ko" => false
          }
        case _ => false  // questo è un future fail (oppure non è un atomo)
      }
    }

    // gestione risposta
    if (erlangMsg.nonEmpty && decodeErlangMsg) {
      // registrazione completata
      val updatedTab = TranslationTable.update(tab, buffer.akkaRef, buffer.pidToRegister)
      // ATTENZIONE: bisogna fare il watch dell'attore e non del receiver
      // perchè il receiver è figlio del receptionist e non dell'attore!
      ctx.watchWith(buffer.akkaRef, Receptionist.ActorRefExit(buffer.akkaRef, buffer.receiver))
      buffer.replyTo ! Receptionist.GlobalRegisterResponse(true)
      (updatedTab, updatedRequestBuffer)

    } else {
      // registrazione fallita (nome già presente nel sistema Erlang, oppure future service fail)
      // uccidere il receiver
      ctx.stop(buffer.receiver)
      buffer.replyTo ! Receptionist.GlobalRegisterResponse(false)
      (tab, updatedRequestBuffer)
    }
  }


  def onWhereResponse(
                       erlangMsg: Option[OtpErlangObject],
                       buffer: WhereRequestBuffer,
                       tab: TranslationTable,
                       updatedRequestBuffer: BufferMap,
                       ctx: ActorContext[Receptionist.Command]
                     ): (TranslationTable, BufferMap) = {

    def decodedErlangMsg(): Option[OtpErlangPid] = {
      // erlang response: undefined | RegPid  Nota: modificare codice HNM erlang!
      erlangMsg match {
        case Some(obj) =>
          obj match {
            case p: OtpErlangPid => Some(p)
            case _: OtpErlangAtom => None
          }
        case None => None  // questo è un future fail
      }
    }

    // gestione risposta
    if (decodedErlangMsg().nonEmpty && tab.contains(decodedErlangMsg().get)) {
      // nel sistema esiste già un ghost associato al pid
      buffer.replyTo ! Receptionist.GlobalWhereIsResponse(buffer.globalName, Some(tab.getAkkaRef(decodedErlangMsg().get)))
      (tab, updatedRequestBuffer)

    } else if (decodedErlangMsg().nonEmpty) {
      // bisogna creare un ghost
      val ghost = ctx.spawnAnonymous(Ghost(decodedErlangMsg().get, ctx.self))
      val updatedTab = TranslationTable.update(tab, ghost, decodedErlangMsg().get)
      buffer.replyTo ! Receptionist.GlobalWhereIsResponse(buffer.globalName, Some(ghost))
      (updatedTab, updatedRequestBuffer)

    } else {
      // undefined oppure future service fail
      buffer.replyTo ! Receptionist.GlobalWhereIsResponse(buffer.globalName, None)
      (tab, updatedRequestBuffer)
    }
  }


  // future utils
  def futureReceive(mbox: OtpMbox): Future[OtpErlangObject] =
    Future {
      mbox.receive()
    }


  def createFutureReceiver(
                            requestKey: String,
                            ctx: ActorContext[Receptionist.Command],
                            tmpMbox: OtpMbox
                          ): Unit = {
    val futureServiceResponse = futureReceive(tmpMbox)
    ctx.pipeToSelf(futureServiceResponse) {
      case Success(response) =>
        Receptionist.ServiceCompleted(requestKey, tmpMbox, Some(response))
      case Failure(_) =>
        Receptionist.ServiceCompleted(requestKey, tmpMbox, None)
    }
  }
}
