package benchmark
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}

object Dispatcher {
  sealed trait Command
  final case class Ping(client: ActorRef[Client.Command]) extends Command
  final case class NewWorker(worker: ActorRef[Worker.Command]) extends Command
  final case class ServiceResponseWrapper(response: erlang.Receptionist.ServiceResponse) extends Command

  def apply(n: Int, receptionist: ActorRef[erlang.Receptionist.Command]): Behavior[Command] =
    Behaviors.setup { ctx =>
      // global register part1
      val serviceResponseAdapter = ctx.messageAdapter(ServiceResponseWrapper)
      receptionist ! erlang.Receptionist.GlobalRegisterRequest("server", ctx.self, serviceResponseAdapter)
      loop(List.empty[ActorRef[Worker.Command]], n, index=0)
    }

  def loop(workers: List[ActorRef[Worker.Command]], n: Int, index: Int): Behavior[Command] =
    Behaviors.receive {
      case (ctx, ServiceResponseWrapper(response)) =>
        // global register part2
        val globalRegResponse = response.asInstanceOf[erlang.Receptionist.GlobalRegisterResponse]

        if(globalRegResponse.result){
          ctx.log.info("server registered...")
          loop(workers, n, index=0)
        } else {
          ctx.log.info("server offline...")
          Behaviors.stopped
        }

      case (ctx, NewWorker(worker)) =>
        ctx.log.info(s"new worker $worker")
        val updatedWorkers = worker::workers
        if(updatedWorkers.size == n) ctx.log.info("server online...")
        loop(updatedWorkers, n, index)

      case (ctx, Ping(client)) =>
        // ctx.log.info(s"new ping from $client")
        workers(index) ! Worker.Ping(client)
        loop(workers, n, (index + 1) % n)
    }
}
