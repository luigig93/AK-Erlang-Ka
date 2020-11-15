package basic
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, ActorSystem, Behavior, Terminated}
import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.receptionist.ServiceKey

object Server {
  // messages
  sealed trait Command
  final case class NewPlace(place: ActorRef[Luogo.Command]) extends Command
  final case class GetPlaces(replyTo: ActorRef[Utente.Places]) extends Command

  // register: part0
  val serverServiceKey: ServiceKey[Command] = ServiceKey[Command](id="server")

  // behaviours
  def start(): Behavior[Command] =
    Behaviors.setup { ctx =>
      //ctx.log.info("Server online...\n")
      // register: part1
      ctx.system.receptionist ! Receptionist.Register(serverServiceKey, ctx.self)
      updatePlaces(List.empty[ActorRef[Luogo.Command]])
    }

  def updatePlaces(places: List[ActorRef[Luogo.Command]]): Behavior[Command] =
    Behaviors.receive[Command] {
      case (ctx, NewPlace(place)) =>
        //ctx.log.info(s"new place: ${place.path}")
        ctx.watch(place)
        updatePlaces(place::places)

      case (ctx, GetPlaces(replyTo)) =>
        //ctx.log.info(s"get places: ${replyTo.path} places: $places")
        replyTo ! Utente.Places(places)
        updatePlaces(places)

    }.receiveSignal {
      case (ctx, Terminated(place)) =>
        //ctx.log.info(s"${place.path} terminated...")
        updatePlaces(places.filter(_ != place))
    }

  // entry point
  def apply(): Behavior[Command] =
    //Behaviors.logMessages(start())
    start()
}
