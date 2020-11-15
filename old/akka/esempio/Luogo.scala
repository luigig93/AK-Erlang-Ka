package basic
import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior, Terminated}
import scala.util.Random


object Luogo {
  // messages
  sealed trait Command
  final case class BeginVisit(visitor: ActorRef[Utente.Command]) extends Command
  final case class EndVisit(visitor: ActorRef[Utente.Command]) extends Command
  private case class ListingResponse(listing: Receptionist.Listing) extends Command

  // utils
  val randomGen: Random.type = scala.util.Random
  def findContacts(
                    newVisitor: ActorRef[Utente.Command],
                    currentVisitors: List[ActorRef[Utente.Command]]
                  ): Unit = {

    val contact = randomGen.nextInt(4) + 1
    contact match {
      case 1 =>
        currentVisitors.foreach { visitor =>
          newVisitor ! Utente.Contact(visitor)
          visitor ! Utente.Contact(newVisitor)
        }
      case _ =>
        ()  // no contact
    }
  }

  def placeClosing(): Boolean = {
    val closing = randomGen.nextInt(10) + 1
    closing match {
      case 10 => true  // closing
      case _ => false  // keep open
    }
  }

  // behaviours
  def luogo(): Behavior[Command] =
    Behaviors.setup { ctx =>
      //ctx.log.info(s"luogo online: ${ctx.self.path}")
      // whereis: part0
      val listingResponseAdapter = ctx.messageAdapter[Receptionist.Listing](ListingResponse)
      ctx.system.receptionist ! Receptionist.Find(Server.serverServiceKey, listingResponseAdapter)

      Behaviors.receiveMessage {
        case ListingResponse(Server.serverServiceKey.Listing(listing)) =>
          // whereis: part1
          val server = listing.head
          ctx.watch(server)
          server ! Server.NewPlace(ctx.self)
          updateVisitors(List.empty[ActorRef[Utente.Command]])
      }
    }

  def updateVisitors(currentVisitors: List[ActorRef[Utente.Command]]): Behavior[Command] =
    Behaviors
      // quando si concatenano metodi è fondamentale il parametro di tipo!
      .receive[Command] {
          case (ctx, BeginVisit(newVisitor)) =>
            //ctx.log.info(s"begin visit: ${newVisitor.path}")
            println(s"PLACE:${ctx.self.path} >> USER:${newVisitor.path} >> BEGINVISIT")
            findContacts(newVisitor, currentVisitors)
            if(placeClosing()) {
              //ctx.log.info(s"place closing: ${ctx.self.path}")
              println(s"PLACE:${ctx.self.path} >> TERMINATED")
              Behaviors.stopped
            } else {
              updateVisitors(newVisitor::currentVisitors)
            }

          case (ctx, EndVisit(visitor)) =>
            //ctx.log.info(s"end visit: ${visitor.path}")
            println(s"PLACE:${ctx.self.path} >> USER:${visitor.path} >> ENDVISIT")
            updateVisitors(currentVisitors.filter(_ != visitor))
    }
    .receiveSignal {
        case (ctx, Terminated(server)) =>
          //ctx.log.info("server offline...")
          Behaviors.stopped
      }

  // entry point
  def apply(): Behavior[Command] = {
    //Behaviors.logMessages(luogo())
    luogo()
  }
}
