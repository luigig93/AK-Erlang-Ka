package basic
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}


object Main {
  def main(args: Array[String]): Unit = {
    val system = ActorSystem(System(), name="system")
    system ! System.Start
  }
}
