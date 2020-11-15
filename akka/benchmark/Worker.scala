package benchmark
import akka.actor.typed.{Behavior, ActorRef}
import akka.actor.typed.scaladsl.Behaviors

object Worker {
  sealed trait Command
  final case class Ping(replyTo: ActorRef[Client.Command]) extends Command
  final case class ServiceResponseWrapper(response: erlang.Receptionist.ServiceResponse) extends Command

  def apply(receptionist: ActorRef[erlang.Receptionist.Command]): Behavior[Command] =
    Behaviors.setup { ctx =>
      // global whereis: part1
      val serviceResponseAdapter = ctx.messageAdapter(ServiceResponseWrapper)
      receptionist ! erlang.Receptionist.GlobalWhereIsRequest("server", serviceResponseAdapter)
      loop(receptionist)
    }

  def loop(receptionist: ActorRef[erlang.Receptionist.Command]): Behavior[Command] =
      Behaviors.receive[Command] {
        case (ctx, ServiceResponseWrapper(response)) =>
          // global whereis: part2
          val whereResponse = response.asInstanceOf[erlang.Receptionist.GlobalWhereIsResponse]
          whereResponse.aRef match {
            case Some(ref) =>
              val dispatcher = ref.asInstanceOf[ActorRef[Dispatcher.Command]]
              dispatcher ! Dispatcher.NewWorker(ctx.self)
              loop(receptionist)

            case None =>
              // il server non si è ancora registrato (oppure la registrazione è fallita)
              // ctx.log.info("server not found...")
              Worker(receptionist)
          }

        case (ctx, Ping(replyTo)) =>
          // ctx.log.info(s"new ping from $replyTo")
          replyTo ! Client.Pong
          loop(receptionist)
      }
}
