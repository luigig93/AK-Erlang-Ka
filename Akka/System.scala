package basic
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, ActorSystem, Behavior}
import akka.actor.typed.DispatcherSelector

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
        // gli utenti contengono delle Thread.sleep(), hanno bisogno di thread dedicati
        // fondamentale usare un dispatcher dedicato, altrimenti si bloccano tutti gli altri attori
        // gli altri attori usano il dispatcher di default e vengono quindi eseguiti con un pool di thread
        (1 to numOfUsers).foreach(index => ctx.spawn(Utente(), name=s"user$index",DispatcherSelector.blocking()))
        loop()

      case (ctx, Stop) =>
        ctx.children.foreach(child => ctx.stop(child))
        Behaviors.stopped
    }

  // entry point
  def apply(): Behavior[Command] =
    loop()
}
