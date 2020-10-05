-module(hidden).
-export([start/0]).


register_name(Name, Pid) ->
	io:format("register_name~n"),
	Res = global:register_name(Name, Pid),
	
	case Res of
		yes -> Pid ! ok;
		no ->  Pid ! ko
	end.


unregister_name(Name) -> 
	io:format("unregister_name~n"),
	global:unregister_name(Name).


registered_names(Pid) ->
	io:format("registered_names~n"),
	Names = global:registered_names(),
	Pid ! {names, Names}.


whereis_name(Name, Pid) ->
	io:format("whereis_name~n"),
	Res = global:whereis_name(Name),
	case Res of
		undefined -> Pid ! {where, undefined};
		RegPid -> Pid ! {where, RegPid}
	end.


send(Name, Msg) ->
	io:format("send~n"),
	global:send(Name, Msg).
		

loop() ->
	receive
		{register_name, Name, Pid} -> erlang:spawn(fun () -> register_name(Name, Pid)  end);
		{unregister_name, Name} -> erlang:spawn(fun () -> unregister_name(Name) end);
		{registered_names, Pid} -> erlang:spawn(fun () -> registered_names(Pid) end);
		{whereis_name, Name, Pid} -> erlang:spawn(fun () -> whereis_name(Name, Pid) end);
		{send, Name, Msg} -> erlang:spawn(fun () -> send(Name, Msg) end)
	end,
	loop().
	
	
start() ->
	erlang:register(global_server, self()),
	loop().
	



