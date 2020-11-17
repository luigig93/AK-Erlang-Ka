-module(server).

%% ====================================================================
%% API functions
%% ====================================================================
-export([start/2]).


%% ====================================================================
%% Internal functions
%% ====================================================================

start(Mode, N) ->
	case Mode of 
		server ->
			erlang:spawn(fun () -> dispatcher_erlang:start(N) end),
			worker_erlang:start(N);
		
		dispatcher ->
			erlang:spawn(fun () -> dispatcher_akka:start(N) end);
		
		workers ->
			worker_akka:start(N);
	
		mix -> % S = DE + 2WE + WA
			erlang:spawn(fun () -> dispatcher_mix:start(N) end),
			worker_erlang:start(N-1)
	end.
	
	
	


