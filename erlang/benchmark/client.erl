-module(client).

%% ====================================================================
%% API functions
%% ====================================================================
-export([start/3]).



%% ====================================================================
%% Internal functions
%% ====================================================================
start(Mode, N, Max) ->
	Server = global:whereis_name(server),  % il server è il dispatcher
	Aggregator = self(),
	[erlang:spawn(fun () -> client(Mode, Aggregator, Server, Max) end) || _ <- lists:seq(1, N)],
	aggregator(N, 0, 0).

client(Mode, Aggregator, Server, Max) ->
	{ Time, _} = timer:tc(fun ping/4, [Mode, Server, Max, 0]),
	Aggregator ! {time, Time/Max/1000000}.


% Server = dispatcher_erlang + worker_erlang = dewe
ping(Mode, Server, Max, Acc) when (Acc < Max) and (Mode == dewe) ->
	Server ! {ping, self()},
	receive
		pong -> ping(Mode, Server, Max, Acc+1)
	end;

% Server = dispatcher_akka + worker_akka = dawa
ping(Mode, Server, Max, Acc) when (Acc < Max) and (Mode == dawa) ->
	Server ! {'benchmark.Dispatcher$Ping', self()},
	receive
		'benchmark.Client$Pong$' -> ping(Mode, Server, Max, Acc+1)
	end;

% Server = dispatcher_akka + worker_erlang = dawe
ping(Mode, Server, Max, Acc) when (Acc < Max) and (Mode == dawe) ->
	Server ! {'benchmark.Dispatcher$Ping', self()},
	receive
		pong -> ping(Mode, Server, Max, Acc+1)
	end;

% Server = dispatcher_erlang + worker_akka = dewa
ping(Mode, Server, Max, Acc) when (Acc < Max) and (Mode == dewa) ->
	Server ! {ping, self()},
	receive
		'benchmark.Client$Pong$' -> ping(Mode, Server, Max, Acc+1)
	end;


ping(_, _, _, Acc) ->
	 Acc.


aggregator(N, Acc, Res) when Acc < N ->
 	receive
		{time, Time} -> 
			io:format("partial mean time: ~p~n",[Time]),
			aggregator(N, Acc + 1, Res + Time)
	end;


aggregator(N, _, Res) -> 
	Mean = Res / N, 
	io:format("total mean time: ~p~n",[Mean]).
	
