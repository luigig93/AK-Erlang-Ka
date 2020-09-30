package basic
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.receptionist.ServiceKey
import scala.util.Random


object Ospedale {
  // messages
  sealed trait Command
  final case class TestMe(user: ActorRef[Utente.Command]) extends Command

  // register: part0
  val hospitalServiceKey: ServiceKey[Command] = ServiceKey[Command](id="hospital")

  // utils
  val randomGen: Random.type = scala.util.Random
  def testUser(user: ActorRef[Utente.Command]): Unit = {
    val res = randomGen.nextInt(4) + 1
    res match {
      case 4 => user ! Utente.Positive
      case _ => user ! Utente.Negative
    }
  }

  // behaviours
  def start(): Behavior[Command] =
    Behaviors.setup { ctx =>
      // register: part1
      ctx.system.receptionist ! Receptionist.Register(hospitalServiceKey, ctx.self)
      loop()
    }

  def loop(): Behavior[Command] =
    Behaviors.receive {
      case (ctx, TestMe(user)) =>
        testUser(user)
        loop()
    }

  // entry point
  def apply(): Behavior[Command] =
    start()
}
