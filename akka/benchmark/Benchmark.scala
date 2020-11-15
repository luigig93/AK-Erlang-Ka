package benchmark
import akka.actor.typed.ActorSystem

object Benchmark {

  sealed trait Config
  case object FullServer extends Config
  case object OnlyDispatcher extends Config
  case object OnlyWorkers extends Config

  val numOfWorkers = 3

  def main(args: Array[String]): Unit = {
    // jinterface tracing
    sys.props.addOne(("OtpConnection.trace","0"))  // max 4
    ActorSystem(Server(FullServer, numOfWorkers), "server")
  }
}
