-module(worker_erlang).

%% ====================================================================
%% API functions
%% ====================================================================
-export([start/1]).



%% ====================================================================
%% Internal functions
%% ====================================================================

start(N) -> [erlang:spawn(fun init/0) || _ <- lists:seq(1, N)].
	

init() ->
	case global:whereis_name(server) of 
		undefined -> 
			%io:format("server not found...~n"),
			init();
		
		Server -> 
			%io:format("server found...~n"),
			Server ! {new_worker, self()},
			loop()
	end.


loop() -> 
	receive
		{ping, ReplyTo} ->
			%io:format("new ping from ~p~n", [ReplyTo]),
			ReplyTo ! pong,
			loop()
	end.
