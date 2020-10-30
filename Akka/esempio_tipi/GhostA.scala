import akka.actor.typed.scaladsl.{Behaviors, ActorContext}
import akka.actor.typed.{ActorRef, Behavior, Terminated}

object GhostA {

  def apply(): Behavior[Any] =
    Behaviors.setup { ctx =>

      val ref = ctx.spawnAnonymous(B()) // ref: ActorRef[B.Command]
      val myRef = ctx.self  // myRef: ActorRef[Any]
      ref ! B.Ciao(myRef)   // ref: ActorRef[B.Command]

      Behaviors.receiveMessage {
        msg: Any =>
          println("Ok...")
          Behaviors.stopped
      }
    }

}