package basic
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior, Terminated}
import akka.actor.typed.receptionist.Receptionist
import scala.util.Random



object Utente {
  // record
  private case class StatusRecord(
                                   visiting: Boolean = false,
                                   visitor: Option[ActorRef[Command]] = None,
                                   place: Option[ActorRef[Luogo.Command]] = None,
                                   places: List[ActorRef[Luogo.Command]] = List.empty[ActorRef[Luogo.Command]]
                                 )
  // messages
  sealed trait Command
  final case class Places(places: List[ActorRef[Luogo.Command]]) extends Command
  final case class Contact(user: ActorRef[Command]) extends Command
  final case class StartMonitor(newPlace: ActorRef[Luogo.Command]) extends Command
  case object Positive extends Command
  case object Negative extends Command
  case object DoneVisit extends Command
  private case class ListingResponse(listing: Receptionist.Listing) extends Command
  private case class UpdateStatus(replyTo: ActorRef[Command]) extends Command
  private case class AskStatus(replyTo: ActorRef[Command]) extends Command
  private case class Status(status: StatusRecord) extends Command
  private case class StatusUpdated(newStatus: StatusRecord) extends Command

  // utils
  val randomGen: Random.type = scala.util.Random

  // behaviours
  def utente(): Behavior[Command] =
    Behaviors.setup { ctx =>
      ctx.log.info("user online...")
      // whereis: part0
      val listingResponseAdapter = ctx.messageAdapter[Receptionist.Listing](ListingResponse)
      ctx.system.receptionist ! Receptionist.Find(Server.serverServiceKey, listingResponseAdapter)

      Behaviors.receiveMessage {
        case ListingResponse(Server.serverServiceKey.Listing(listing)) =>
          ctx.log.info("whereis server ok")
          // whereis: part1
          val server = listing.head
          ctx.watch(server)
          ctx.spawn(testManager(parent=ctx.self), name="test_manager")
          val placeObs = ctx.spawn(
            placeObserver(ctx.self, List.empty[ActorRef[Luogo.Command]]),
            name="place_observer"
          )
          ctx.spawn(placeManager(parent=ctx.self, server= server, obs=placeObs), name="place_manager")
          ctx.spawn(performVisit(parent=ctx.self), name="visit_manager")
          loop(StatusRecord(), server)
      }
    }

  def loop(status: StatusRecord, server: ActorRef[Server.Command]): Behavior[Command] =
    Behaviors.receive { (ctx, msg) =>
      msg match {
        case UpdateStatus(replyTo) =>
          replyTo ! Status(status)
          Behaviors.receiveMessage {
            case StatusUpdated(newStatus) => loop(newStatus, server)
          }

        case AskStatus(replyTo) =>
          replyTo ! Status(status)
          loop(status, server)
      }
    }

  def testManager(parent: ActorRef[Command]): Behavior[Command] =
    Behaviors.setup { ctx =>
      // whereis: part0
      val listingResponseAdapter = ctx.messageAdapter[Receptionist.Listing](ListingResponse)
      ctx.system.receptionist ! Receptionist.Find(Ospedale.hospitalServiceKey, listingResponseAdapter)

      Behaviors.receiveMessage {
        case ListingResponse(Ospedale.hospitalServiceKey.Listing(listing)) =>
          // whereis: part1
          doTest(parent, hospital = listing.head)
      }
    }

  def doTest(parent: ActorRef[Command], hospital: ActorRef[Ospedale.Command]): Behavior[Command] =
    Behaviors.setup { ctx =>
      Thread.sleep(3000)
      val testing = randomGen.nextInt(4) + 1
      testing match{
        case 1 =>
          hospital ! Ospedale.TestMe(ctx.self)
          Behaviors.receiveMessage {
            case Positive => Behaviors.stopped
            case Negative => doTest(parent, hospital)
          }

        case _ => doTest(parent, hospital)
      }
    }

  def placeObserver(parent: ActorRef[Command], places: List[ActorRef[Luogo.Command]]): Behavior[Command] =
    Behaviors.receive[Command] {
      case (ctx, StartMonitor(newPlace)) =>
        ctx.watch(newPlace)
        placeObserver(parent, newPlace::places)

    }.receiveSignal {
      case (ctx, Terminated(place)) =>
        val updatedPlaces = places.filter(_ != place)
        val statusUpdate = StatusRecord(places=updatedPlaces)
        updateStatus(parent, update="places", statusUpdate)
        placeObserver(parent, updatedPlaces)
    }

  def placeManager(
                    parent: ActorRef[Command],
                    server: ActorRef[Server.Command],
                    obs: ActorRef[Command]
                  ): Behavior[Command] =
    Behaviors.setup { ctx =>
      parent ! AskStatus(ctx.self)
      Behaviors.receiveMessage {
        case Status(status) =>
          val currentPlaces = status.places
          if(currentPlaces.size <3 ) {
            server ! Server.GetPlaces(ctx.self)
            Behaviors.receiveMessage[Command] {
              case Places(allPlaces) =>
                val newPlaces = allPlaces diff currentPlaces
                val placesUpdated = randomGen.shuffle(newPlaces).take(3 - currentPlaces.size)
                placesUpdated.foreach(place => obs ! StartMonitor(place))
                val statusUpdate = StatusRecord(places=placesUpdated:::currentPlaces)
                updateStatus(parent, update="places", statusUpdate)
                Thread.sleep(10000)
                placeManager(parent, server, obs)
            }
          }
          Thread.sleep(10000)
          placeManager(parent, server, obs)

        case _ =>
          ctx.log.info("case _")
          Thread.sleep(10000)
          placeManager(parent, server, obs)
      }
    }

  def performVisit(parent: ActorRef[Command]): Behavior[Command] =
    Behaviors.setup { ctx =>
      parent ! AskStatus(ctx.self)
      Behaviors.receiveMessage {
        case Status(status) =>
          val places = status.places
          if(places.nonEmpty){
            val place = randomGen.shuffle(places).take(1).head
            place ! Luogo.BeginVisit(ctx.self)
            val statusUpdate = StatusRecord(
              visiting = true,
              visitor = Some(ctx.self),
              place = Some(place)
            )

            updateStatus(parent, update="visit", statusUpdate)
            ctx.spawn(reminder(ctx.self, randomGen.nextInt(5)+1), name="reminder")
            receiveContact()
            updateStatus(parent, update="visit", StatusRecord())
            place ! Luogo.EndVisit(ctx.self)
          }

          Thread.sleep((3 + randomGen.nextInt(2))*1000)
          performVisit(parent)
      }
    }

  def reminder(replyTo: ActorRef[Command], visitTime: Int): Behavior[Command] = {
    Thread.sleep(visitTime*1000)
    replyTo ! DoneVisit
    Behaviors.same
  }

  def performExit(status: Option[StatusRecord], reason: String): Unit = {
    status match {
      case Some(s) if s.visiting =>
        val place = s.place.head
        place ! Luogo.EndVisit(s.visitor.head)
        // exit(reason)
      case None =>
        // exit(reason)
    }
  }

  def updateStatus(
                    parent: ActorRef[Command],
                    update: String,
                    statusUpdate: StatusRecord
                  ): Behavior[Command] =
    Behaviors.setup { ctx =>
      parent ! UpdateStatus(ctx.self)
      Behaviors.receiveMessage {
        case Status(oldStatus) =>
          val newStatus = update match {
            case "places" =>
              StatusRecord(
                visiting = oldStatus.visiting,
                visitor = oldStatus.visitor,
                place = oldStatus.place,
                places = statusUpdate.places
              )
            case "visit" =>
              StatusRecord(
                visiting = statusUpdate.visiting,
                visitor = statusUpdate.visitor,
                place = statusUpdate.place,
                places = oldStatus.places
              )
          }
          parent ! StatusUpdated(newStatus)
          Behaviors.same // tanto verrà sempre ignorato
      }
    }

  def receiveContact(): Behavior[Command] =
    Behaviors.receive { (ctx, msg) =>
      msg match {
        case Contact(user) =>
          ctx.watch(user)
          receiveContact()
        case DoneVisit =>
          // ok
          Behaviors.same
      }
    }

  // entry point
  def apply(): Behavior[Command] =
    utente()

}
