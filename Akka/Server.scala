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
  final case class ExitPlace(place: ActorRef[Luogo.Command]) extends Command
  // final case class ExitPositive() extends Command
  // final case class ExitQuarantena() extends Command

  // register: part0
  val serverServiceKey: ServiceKey[Command] = ServiceKey[Command](id="server")

  // behaviours
  def start(): Behavior[Command] =
    Behaviors.setup { ctx =>
      // register: part1
      ctx.system.receptionist ! Receptionist.Register(serverServiceKey, ctx.self)
      ctx.log.info("Server online...\n")
      updatePlaces(List.empty[ActorRef[Luogo.Command]])
    }

  def updatePlaces(places: List[ActorRef[Luogo.Command]]): Behavior[Command] =
    Behaviors.receive[Command] {
      case (ctx, NewPlace(place)) =>
        ctx.watch(place)
        updatePlaces(place::places)

      case (_, GetPlaces(replyTo)) =>
        replyTo ! Utente.Places(places)
        updatePlaces(places)

    }.receiveSignal {
      case (_, Terminated(place)) =>
        updatePlaces(places.filter(_ != place))
    }

  // entry point
  def apply(): Behavior[Command] =
    start()
}
