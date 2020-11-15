package erlang
import com.ericsson.otp.erlang._
import akka.actor.typed.ActorRef
import erlang.TranslationTable.{ErlangPid, AkkaRef, AkkaToErlang, ErlangToAkka}


class TranslationTable(val pidToRef: ErlangToAkka, val refToPid: AkkaToErlang) {

  def getAkkaRef(key: ErlangPid): AkkaRef = pidToRef(key)

  def getErlangPid(key: AkkaRef): ErlangPid = refToPid(key)

  def contains(key: Any): Boolean = {
    key match {
      case p: ErlangPid => pidToRef.contains(p)
      case r: AkkaRef => refToPid.contains(r)
    }
  }
}


object TranslationTable {

  type ErlangPid = OtpErlangPid
  type AkkaRef = ActorRef[_]  // trucchetto visto nel receptionist ufficiale oppure ActorRef[Any]
  type ErlangToAkka = Map[ErlangPid, AkkaRef]
  type AkkaToErlang = Map[AkkaRef, ErlangPid]


  def apply(
             pidToRef: ErlangToAkka = Map.empty[ErlangPid, AkkaRef],
             refToPid: AkkaToErlang = Map.empty[AkkaRef, ErlangPid],
           ): TranslationTable = new TranslationTable(pidToRef, refToPid)

  def empty: TranslationTable = apply()

  def unapply(tab: TranslationTable): (ErlangToAkka, AkkaToErlang) = (tab.pidToRef, tab.refToPid)

  def update(oldTab: TranslationTable, newRef: AkkaRef, newPid: ErlangPid): TranslationTable = {
    val (oldPidToRef, oldRefToPid) = unapply(oldTab)
    TranslationTable(oldPidToRef + (newPid -> newRef), oldRefToPid + (newRef -> newPid))
  }


  def remove(oldTab: TranslationTable, key: Any): TranslationTable = {
    key match {
      case p: ErlangPid =>
        TranslationTable(
          oldTab.pidToRef - p,
          oldTab.refToPid - oldTab.pidToRef(p)
        )

      case r: AkkaRef =>
        TranslationTable(
          oldTab.pidToRef - oldTab.refToPid(r),
          oldTab.refToPid - r
        )
    }
  }
}
