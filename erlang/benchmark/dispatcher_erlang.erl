-module(dispatcher_erlang).

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
			init([Worker|Workers], N);
		
		{new_worker, Worker}  ->
			io:format("server online...~n"),
			loop([Worker|Workers], 1, N)
	end.


loop(Workers, Index, N) -> 
	receive
		{ping, ReplyTo} -> 
			%io:format("new ping from ~p~n", [ReplyTo]),
			Worker = lists:nth(Index, Workers),
			%io:format("worker: ~p~n", [Worker]),
			Worker ! {ping, ReplyTo},
			loop(Workers, (Index rem N) + 1, N)
	end.

