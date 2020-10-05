package basic
import akka.actor.typed.scaladsl.{Behaviors, TimerScheduler}
import akka.actor.typed.{ActorRef, Behavior, Terminated}
import akka.actor.typed.receptionist.Receptionist
import scala.util.Random
import scala.concurrent.duration.{FiniteDuration, MILLISECONDS, SECONDS}


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
  private case class ListingResponse(listing: Receptionist.Listing) extends Command
  private case class UpdateStatus(update: String, newStatus: StatusRecord) extends Command
  private case class AskStatus(replyTo: ActorRef[Command]) extends Command
  private case class Status(status: StatusRecord) extends Command
  private case class WatchUser(user: ActorRef[Command]) extends Command
  private case object DoneVisit extends Command
  private case object NewTest extends Command
  private case object WakeUp extends Command

  // utils
  val randomGen: Random.type = scala.util.Random

  // behaviours
  def utente(): Behavior[Command] =
    Behaviors.setup { ctx =>
      //ctx.log.info("user online...")
      // whereis: part0
      val listingResponseAdapter = ctx.messageAdapter[Receptionist.Listing](ListingResponse)
      ctx.system.receptionist ! Receptionist.Find(Server.serverServiceKey, listingResponseAdapter)

      Behaviors.receiveMessage {
        case ListingResponse(Server.serverServiceKey.Listing(listing)) =>
          // whereis: part1
          val server = listing.head
          ctx.watch(server)
          ctx.spawn(testManager(parent=ctx.self), name="test_manager")
          val placeObs = ctx.spawn(
            //Behaviors.logMessages(placeObserver(ctx.self, List.empty[ActorRef[Luogo.Command]])),
            placeObserver(ctx.self, List.empty[ActorRef[Luogo.Command]]),
            name="place_observer"
          )
          ctx.spawn(
            //Behaviors.logMessages(placeManager(parent=ctx.self, server= server, obs=placeObs)),
            Behaviors.withTimers(timer => placeManager(timer, parent=ctx.self, server=server, obs=placeObs)),
            name="place_manager"
          )
          ctx.spawn(
            //Behaviors.logMessages(visitManager(parent=ctx.self)),
            visitManager(parent=ctx.self),
            name="visit_manager"
          )

          //Behaviors.logMessages(loop(StatusRecord(), server))
          loop(StatusRecord(), server)
      }
    }


  def loop(currentStatus: StatusRecord, server: ActorRef[Server.Command]): Behavior[Command] =
    Behaviors.receive[Command] {
        case (ctx, AskStatus(replyTo)) =>
          //ctx.log.info(s"ask status: ${replyTo.path}")
          replyTo ! Status(currentStatus)
          loop(currentStatus, server)

        case (ctx, UpdateStatus(update, updatedStatus)) =>
          val newStatus = update match {
            case "places" =>
              StatusRecord(
                visiting = currentStatus.visiting,
                visitor = currentStatus.visitor,
                place = currentStatus.place,
                places = updatedStatus.places
              )
            case "visit" =>
              StatusRecord(
                visiting = updatedStatus.visiting,
                visitor = updatedStatus.visitor,
                place = updatedStatus.place,
                places = currentStatus.places
              )
          }
          loop(newStatus, server)

        case (ctx, WatchUser(user)) =>
          ctx.watch(user)
          loop(currentStatus, server)

        case (_, Positive) =>
          // l'utente è risultato positivo al test
          Behaviors.stopped

    }.receiveSignal {
      case (ctx, Terminated(ref)) =>
        ref match {
          case user: ActorRef[Command] =>
            // un utente con cui si è entrati in contatto
            // è risultato positivo, oppure è entrato in quarantena
            // quindi bisogna entrare in quarantena (cioè uscire)
            //ctx.log.info(s"enter quarantena: $user -> ${ctx.self}")
            println(s"USER:${user.path} >> TERMINATED >> USER:${ctx.self.path} >> QUARANTENA")
            if(currentStatus.visiting) {
              currentStatus.place.head ! Luogo.EndVisit(currentStatus.visitor.head)
            }
            Behaviors.stopped

          case _ =>
            // server offline
            //ctx.log.info("server offline")
            Behaviors.stopped
        }
    }

  // blocking!
  def placeManager(
                    timer: TimerScheduler[Command],
                    parent: ActorRef[Command],
                    server: ActorRef[Server.Command],
                    obs: ActorRef[Command]
                  ): Behavior[Command] =

    Behaviors.setup { ctx =>
      parent ! AskStatus(ctx.self)
      Behaviors.receive {
        case (ctx, Status(status)) =>
          //ctx.log.info(s"status: ${ctx.self.path} >> places: ${status.places}")
          val currentPlaces = status.places
          if(currentPlaces.size <3 ) {
            server ! Server.GetPlaces(ctx.self)
            Behaviors.receive[Command] {
              case (ctx, Places(allPlaces)) =>
                //ctx.log.info(s"places: ${ctx.self.path} >> places: $allPlaces")
                val newPlaces = allPlaces diff currentPlaces
                val placesUpdated = randomGen.shuffle(newPlaces).take(3 - currentPlaces.size)
                placesUpdated.foreach(place => obs ! StartMonitor(place))
                val statusUpdate = StatusRecord(places=placesUpdated:::currentPlaces)
                parent ! UpdateStatus(update="places", statusUpdate)
                //Thread.sleep(10000)
                timer.startSingleTimer(WakeUp, FiniteDuration(10, SECONDS))
                placeManagerIdle(timer, parent, server, obs)
            }
          }else{
            //Thread.sleep(10000)
            timer.startSingleTimer(WakeUp, FiniteDuration(10, SECONDS))
            placeManagerIdle(timer, parent, server, obs)
          }

        case _ =>
          //Thread.sleep(10000)
          timer.startSingleTimer(WakeUp, FiniteDuration(10, SECONDS))
          placeManagerIdle(timer, parent, server, obs)
      }
    }

  def placeManagerIdle(
                        timer: TimerScheduler[Command],
                        parent: ActorRef[Command],
                        server: ActorRef[Server.Command],
                        obs: ActorRef[Command]
                      ): Behavior[Command] =

    Behaviors.receive {
      case (ctx, WakeUp) =>
        println(s"USER:${ctx.self.path} >> WAKEUP")
        placeManager(timer, parent, server, obs)
    }

  def placeObserver(parent: ActorRef[Command], places: List[ActorRef[Luogo.Command]]): Behavior[Command] =
    Behaviors.receive[Command] {
      case (ctx, StartMonitor(newPlace)) =>
        //ctx.log.info(s"start monitor place: ${newPlace.path}")
        ctx.watch(newPlace)
        placeObserver(parent, newPlace::places)

    }.receiveSignal {
      case (ctx, Terminated(place)) =>
        println(s"USER:${ctx.self.path} >> PLACE:${place.path} >> TERMINATED")
        val updatedPlaces = places.filter(_ != place)
        val statusUpdate = StatusRecord(places=updatedPlaces)
        parent ! UpdateStatus(update="places", statusUpdate)
        placeObserver(parent, updatedPlaces)
    }

  // blocking!
  def testManager(parent: ActorRef[Command]): Behavior[Command] =
    Behaviors.setup { ctx =>
      // whereis: part0
      val listingResponseAdapter = ctx.messageAdapter[Receptionist.Listing](ListingResponse)
      ctx.system.receptionist ! Receptionist.Find(Ospedale.hospitalServiceKey, listingResponseAdapter)

      Behaviors.receiveMessage {
        case ListingResponse(Ospedale.hospitalServiceKey.Listing(listing)) =>
          // whereis: part1
          Behaviors.withTimers(timer => doTest(timer, parent, hospital = listing.head))
      }
    }


  // blocking!
  def doTest(
              timer: TimerScheduler[Command],
              parent: ActorRef[Command],
              hospital: ActorRef[Ospedale.Command]
            ): Behavior[Command] =

    Behaviors.setup { ctx =>
      val testing = randomGen.nextInt(4) + 1
      testing match{
        case 1 =>
          hospital ! Ospedale.TestMe(ctx.self)
          Behaviors.receiveMessage {
            case Positive =>
              //ctx.log.info(s"positive: ${ctx.self.path}")
              println(s"USER:${ctx.self.path} >> POSITIVE")
              parent ! Positive
              Behaviors.stopped
            case Negative =>
              //ctx.log.info(s"negative: ${ctx.self.path}")
              println(s"USER:${ctx.self.path} >> NEGATIVE")
              //Thread.sleep(30000)
              timer.startSingleTimer(NewTest, FiniteDuration(30, SECONDS))
              doTestIdle(timer, parent, hospital)
          }

        case _ =>
          //Thread.sleep(30000)
          timer.startSingleTimer(NewTest, FiniteDuration(30, SECONDS))
          doTestIdle(timer, parent, hospital)
      }
    }

  def doTestIdle(
                  timer: TimerScheduler[Command],
                  parent: ActorRef[Command],
                  hospital: ActorRef[Ospedale.Command]
                ): Behavior[Command] =

    Behaviors.receive {
      case (ctx, NewTest) =>
        println(s"USER:${ctx.self.path} >> NEWTEST")
        doTest(timer, parent, hospital)
    }


  def visitManager(parent: ActorRef[Command]): Behavior[Command] =
    Behaviors.setup { ctx =>
      parent ! AskStatus(ctx.self)
      Behaviors.receiveMessage {
        case Status(status) =>
          val places = status.places
          if(places.nonEmpty) {
            Behaviors.withTimers(timer => beginVisit(timer, parent, places))
          } else {
            visitManager(parent)
          }
      }
    }

  def beginVisit(
                  timer: TimerScheduler[Command],
                  parent: ActorRef[Command],
                  places: List[ActorRef[Luogo.Command]]
                ): Behavior[Command] =
    Behaviors.setup { ctx =>
      val place = randomGen.shuffle(places).take(1).head
      place ! Luogo.BeginVisit(ctx.self)
      println(s"USER:${ctx.self.path} >> BEGINVISIT >> PLACE:${place.path}")
      val statusUpdate = StatusRecord(visiting=true, visitor=Some(ctx.self), place=Some(place))
      parent ! UpdateStatus(update="visit", statusUpdate)
      val visitTime = randomGen.nextInt(5)+5
      println(s"USER:${ctx.self.path} >> VISITTIME >> $visitTime")
      timer.startSingleTimer(DoneVisit, FiniteDuration(visitTime, SECONDS))
      doVisit(parent, place)
    }

  def doVisit(
               parent: ActorRef[Command],
               place: ActorRef[Luogo.Command]
             ): Behavior[Command] =

    Behaviors.receive { (ctx, msg) =>
      msg match {
        case Contact(user) =>
          //ctx.log.info(s"contact user: ${user.path}")
          println(s"USER:${ctx.self.path} >> CONTACT >> USER:${user.path}")
          // attenzione al watch!
          // ctx.watch(user)
          parent ! WatchUser(user)
          doVisit(parent, place)

        case DoneVisit =>
          //ctx.log.info(s"done visit ${ctx.self.path}")
          println(s"USER:${ctx.self.path} >> DONEVISIT >> PLACE:${place.path}")
          parent ! UpdateStatus(update="visit", StatusRecord())
          place ! Luogo.EndVisit(ctx.self)
          visitManager(parent)
      }
    }


  // entry point
  def apply(): Behavior[Command] =
    //Behaviors.logMessages(utente())
    utente()
}
