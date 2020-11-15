package benchmark

object Client {
  sealed trait Command
  case object Pong extends Command
}
