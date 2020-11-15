import akka.actor.typed.scaladsl.{Behaviors, ActorContext}
import akka.actor.typed.{ActorRef, Behavior, Terminated}

object B {
  sealed trait Command
  final case class Ciao(replyTo: ActorRef[A.Command]) extends Command
  // ActorRef[-T] CONTROVARIANTE!

  def apply(): Behavior[Command] =
    Behaviors.receiveMessage {
      case Ciao(replyTo) =>
        println("Ciao...")
        replyTo ! A.Ok  // replyTo: ActorRef[A.Command]
        Behaviors.same
    }
}