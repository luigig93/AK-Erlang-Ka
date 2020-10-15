package example

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.receptionist.ServiceKey
import scala.util.Random

// sperimentale
import broker.Broker

object EsempioOspedale {
  // messages
  sealed trait Command
  final case class TestMe(user: ActorRef[Utente.Command]) extends Command

  // register: part0
  // val hospitalServiceKey: ServiceKey[Command] = ServiceKey[Command](id="hospital")

  // utils
  val randomGen: Random.type = scala.util.Random
  def testUser(user: ActorRef[Utente.Command], broker: ActorRef[Broker.Command]): Unit = {
    val res = randomGen.nextInt(4) + 1
    res match {
      case 4 => broker ! Broker.ErlangMsgWrap(Utente.Positive, user)
                // user ! Utente.Positive
      case _ => broker ! Broker.ErlangMsgWrap(Utente.Negative, user)
                // user ! Utente.Negative
    }
  }

  // behaviours
  def start(broker: ActorRef[Broker.Command]): Behavior[Command] =
    Behaviors.setup { ctx =>
      //ctx.log.info("Hospital online...")
      // register: part1
      // ctx.system.receptionist ! Receptionist.Register(hospitalServiceKey, ctx.self)
      broker ! Broker.GlobalRegister("hospital")
      loop(broker)
    }

  def loop(broker: ActorRef[Broker.Command]): Behavior[Command] =
    Behaviors.receive {
      case (ctx, TestMe(user)) =>
        //ctx.log.info(s"test me: ${user.path}")
        println(s"HOSPITAL >> USER:${user.path} >> TESTME")
        testUser(user, broker)
        loop(broker)
    }

  // entry point
  def apply(): Behavior[Command] =
    Behaviors.setup { ctx =>
      //Behaviors.logMessages(start())
      // se vogliamo interagire con Erlang, qui dobbiamo istanziare il Broker
      // che aiuterà l'attore Akka a comunicare con il sistema Erlang
      val broker = ctx.spawn(Broker("hospital", ctx.self), "broker")
      start(broker)
    }
}
