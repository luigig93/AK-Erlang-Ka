package benchmark
import akka.NotUsed
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{Behavior, Terminated}


object Server {
  def apply(config: Benchmark.Config, numOfWorkers: Int):Behavior[NotUsed] =
    Behaviors.setup { ctx =>
      val receptionist = ctx.spawn(erlang.Receptionist(), name="receptionist_erl")
      ctx.watch(receptionist)

      config match {
        case Benchmark.FullServer =>
          ctx.spawn(Dispatcher(numOfWorkers, receptionist), name="dispatcher")
          (0 until numOfWorkers).foreach(index => ctx.spawn(Worker(receptionist), name=s"worker$index"))

        case Benchmark.OnlyDispatcher =>
          ctx.spawn(Dispatcher(numOfWorkers, receptionist), name="dispatcher")

        case Benchmark.OnlyWorkers =>
          (0 until numOfWorkers).foreach(index => ctx.spawn(Worker(receptionist), name=s"worker$index"))
      }

      Behaviors.receiveSignal {
        case (ctx, Terminated(_)) =>
          ctx.log.error("benchmark failed...")
          Behaviors.stopped
      }
    }
}
