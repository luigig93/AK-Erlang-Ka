%% @author luigi
%% @doc @todo Add description to benchmark.


-module(benchmark).

%% ====================================================================
%% API functions
%% ====================================================================
-export([start/3]).



%% ====================================================================
%% Internal functions
%% ====================================================================
% INPUT:
%
% M  = dewe, dawa, dewa, dawe.
% C  = Number of multiple requests to perform at a time.
% N  = Number of requests to perform for the benchmarking session.
% Tr = Max number of sec to wait for each request (?).
% Tb = Maximum number of seconds to spend for benchmarking (?).

% OUTPUT:
%
% Time taken for the benchmark.
% The number of successful responses received.
% The number of requests that were considered a failure.
% Requests per second (number of requests/ total time taken).
% The average time spent per request.

start(M, N, C) ->
	Server = global:whereis_name(server),  % il server è il dispatcher
	Aggregator = self(),
	[erlang:spawn(fun () -> ping(M, Aggregator, Server, N/C, 0) end) || _ <- lists:seq(1, C)],
	{Time, _} = timer:tc(fun aggregator/2, [C, 0]), % Time  = microseconds
	TimeInSec = Time/1000000,
	io:format("Benchmark completed:~n"),
	io:format("Time elapsed:        ~p [sec]~n",[TimeInSec]),
	io:format("Requests per second: ~p [#/sec]~n",[N/TimeInSec]),
	io:format("Time per request:    ~p [ms] (no concurrency)~n",[C*TimeInSec*1000/N]),
    io:format("Time per request:    ~p [ms] (with concurrency)~n",[TimeInSec*1000/N]).


% Server = dispatcher_erlang + worker_erlang = dewe
ping(M, Aggregator, Server, N, Acc) when (Acc < N) and (M == dewe) ->
	Server ! {ping, self()},
	receive
		pong -> ping(M, Aggregator, Server, N, Acc+1)
	end;

% Server = dispatcher_akka + worker_akka = dawa
ping(M, Aggregator, Server, N, Acc) when (Acc < N) and (M == dawa) ->
	Server ! {'benchmark.Dispatcher$Ping', self()},
	receive
		'benchmark.Client$Pong$' -> ping(M, Aggregator, Server, N, Acc+1)
	end;

% Server = dispatcher_akka + worker_erlang = dawe
ping(M, Aggregator, Server, N, Acc) when (Acc < N) and (M == dawe) ->
	Server ! {'benchmark.Dispatcher$Ping', self()},
	receive
		pong -> ping(M, Aggregator, Server, N, Acc+1)
	end;

% Server = dispatcher_erlang + worker_akka = dewa
ping(M, Aggregator, Server, N, Acc) when (Acc < N) and (M == dewa) ->
	Server ! {ping, self()},
	receive
		'benchmark.Client$Pong$' -> ping(M, Aggregator, Server, N, Acc+1)
	end;

% Server = dispatcher_erlang + 2worker_erlang + worker_akka = mix
ping(M, Aggregator, Server, N, Acc) when (Acc < N) and (M == mix) ->
	Server ! {ping, self()},
	receive
		pong -> ping(M, Aggregator, Server, N, Acc+1);
		'benchmark.Client$Pong$' -> ping(M, Aggregator, Server, N, Acc+1)
	end;


ping(_, Aggregator,  _, _, _) ->
	 Aggregator ! done.


aggregator(C, Acc) when Acc < C ->
 	receive
		done -> aggregator(C, Acc + 1)
	end;


aggregator(_, Acc) -> 
	Acc.





