package ghost

/*
   esempio di dichiarazione (versione minima) attore Akka
   serve anche un meccasimo per associare un nome (in caso di whereis(name)) ad un tipo di attore
   infatti durante una whereis ci viene restituito un pid, ma non abbiamo alcuna informazione
   per capire di quale attore potrebbe trattarsi. Quindi serve mappa nome -> tipo di attore
 */

object FakeActor {
  // dichiarare solamente i messaggi che serviranno per type checking a runtime
  sealed trait Command
  case object Hi extends Command
}
