-module(luoghi).
-export([start/0]).

start() ->
  [ spawn(fun luogo/0) || _ <- lists:seq(1,10) ].

%%%-------------------------------------------------------------------------
%%% @doc PROTOCOLLO DI INIZIALIZZAZIONE:
%%%  1) si linka al server: nel caso in cui il server muoia tutti gli attori
%%%  in causa devono terminare.
%%%  2) comunica al server la propria esistenza.
%%% @end
%%%-------------------------------------------------------------------------

luogo() ->
  io:format("Io sono il luogo ~p~n",[self()]),
  Server = global:whereis_name(server),
  link(Server),
  Server ! {new_place, self()},
  update_visitors([]).

%%%-------------------------------------------------------------------------
%%% @doc PROTOCOLLO DI VISITA DEI LUOGHI:
%%% mantiene una lista dei visitatori. I visitatori entrano/escono dalla
%%% lista quando vengono ricevuti i messaggi.
%%% @end
%%%-------------------------------------------------------------------------

update_visitors(VisitorsList) ->
  receive
    {begin_visit, PidVisitor, Ref} ->
      self() ! {debug, {begin_visit, PidVisitor, VisitorsList}},
      find_contacts(PidVisitor, VisitorsList),
      place_closing(),
      update_visitors([{Ref, PidVisitor} | VisitorsList]);

    {end_visit, PidVisitor, Ref} ->
      self() ! {debug, {end_visit, PidVisitor, VisitorsList}},
      update_visitors(VisitorsList -- [{Ref, PidVisitor}]);

    {debug, Message} ->
      debug(Message),
      update_visitors(VisitorsList);

    Other ->
      io:format("Messaggio inatteso: ~p~n", [Other]),
      update_visitors(VisitorsList)
  end.

%%%-----------------------------------------------------------------------
%%% @doc PROTOCOLLO DI RILEVAMENTO DEI CONTATTI:
%%% quando un visitatore entra in lista, a lui e a tutti quelli presenti 
%%% nella lista con probabilità 25% viene inviato un messaggio di contatto
%%% per sapere con chi sono entrati in contatto. 
%%% Uno dei due deve essere il nuovo arrivato.
%%% @end
%%%-----------------------------------------------------------------------

find_contacts(NewVisitor, Visitors) ->
  [     case rand:uniform(4) of
          1 ->
            NewVisitor ! {contact, PidVisitor},
            PidVisitor ! {contact, NewVisitor};
          _ -> no_contact
        end|| {_,PidVisitor} <- Visitors].

%%%-------------------------------------------------------------------
%%% @doc CICLO DI VITA:
%%% ogni volta che un luogo viene visitato ha il 10% di probabilità 
%%% di chiudere, ovvero l'attore termina con successo. 
%%% @end
%%%-------------------------------------------------------------------

place_closing() ->
  case rand:uniform(10) of
    10 ->
      exit(normal);
    _ ->
      ok
  end.

%%%-------------------------------------------------------------------
%%% @doc Debug e Stampe
%%% @end
%%%-------------------------------------------------------------------

debug({contact, Pid1, Pid2}) ->
  io:format("~p: Contatto tra ~p e ~p~n", [self(), Pid1, Pid2]);
debug({end_visit, Pid, Visitors}) ->
  VisitorsList = [ L || {_, L} <- Visitors],
  io:format("~p: visitatore ~p ha terminato la visita ~n", [self(), Pid]),
  io:format("~p: Lista di visitatori nel luogo: ~p~n", [self(),VisitorsList -- [Pid]]);
debug({begin_visit, Pid, Visitors}) ->
  VisitorsList = [ L || {_, L} <- Visitors],
  io:format("~p: Lista di visitatori nel luogo: ~p~n", [self(),VisitorsList ++ [Pid]]),
  io:format("~p: visitatore ~p ha iniziato la visita~n", [self(), Pid]).
