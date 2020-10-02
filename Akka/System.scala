package basic
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, ActorSystem, Behavior}

object System {
  // messages
  sealed trait Command
  case object Start extends Command
  case object Stop extends Command

  val numOfPlaces = 10
  val numOfUsers = 30

  // behaviours
  def loop(): Behavior[Command] =
    Behaviors.receive {
      case (ctx, Start) =>
        ctx.spawn(Server(), name="server")
        ctx.spawn(Ospedale(), name="hospital")
        (1 to numOfPlaces).foreach(index => ctx.spawn(Luogo(), name=s"place$index"))
        (1 to numOfUsers).foreach(index => ctx.spawn(Utente(), name=s"user$index"))
        loop()

      case (ctx, Stop) =>
        ctx.children.foreach(child => ctx.stop(child))
        Behaviors.stopped
    }

  // entry point
  def apply(): Behavior[Command] =
    loop()
}
