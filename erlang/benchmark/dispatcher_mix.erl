-module(dispatcher_mix).

%% ====================================================================
%% API functions
%% ====================================================================
-export([start/1]).



%% ====================================================================
%% Internal functions
%% ====================================================================
start(N) ->
	Res = global:register_name(server, self()),
	case Res of 
	    yes -> init([], N);
	    no -> io:format("server offline...~n")
	end.
	

init(Workers, N) -> 
	receive	
		{new_worker, Worker} when length(Workers) < (N-1) ->		
			init([{erl, Worker}|Workers], N);
	
		{'benchmark.Dispatcher$NewWorker', Worker} when length(Workers) < (N-1) ->
			init([{akka, Worker}|Workers], N);
		
		{new_worker, Worker}  ->
			io:format("server online...~n"),
			loop([{erl, Worker}|Workers], 1, N);
			
		{'benchmark.Dispatcher$NewWorker', Worker}  ->
			io:format("server online...~n"),
			loop([{akka, Worker}|Workers], 1, N)
	end.


loop(Workers, Index, N) -> 
	receive
		{ping, ReplyTo} -> 
			%io:format("new ping from ~p~n", [ReplyTo]),
			case lists:nth(Index, Workers) of
				{erl, Worker} -> Worker ! {ping, ReplyTo};
				{akka, Worker} -> Worker ! {'benchmark.Worker$Ping', ReplyTo}
			end,
			loop(Workers, (Index rem N) + 1, N)
	end.

