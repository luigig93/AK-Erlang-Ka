-module(hnm).
-export([start/0]).


register_name(Name, PidToRegister, ReplyTo) ->
	io:format("register_name~n"),
	Res = global:register_name(Name, PidToRegister),
	
	case Res of
		yes -> ReplyTo ! ok;
		no ->  ReplyTo ! ko
	end.


unregister_name(Name) -> 
	io:format("unregister_name~n"),
	global:unregister_name(Name).


whereis_name(Name, ReplyTo) ->
	io:format("whereis_name~n"),
	Res = global:whereis_name(Name),
	case Res of
		undefined -> ReplyTo ! undefined;
		Pid -> ReplyTo ! Pid
	end.
		

loop() ->
	receive
		{register_name, Name, PidToRegister, ReplyTo} -> 
		        erlang:spawn(fun () -> register_name(Name, PidToRegister, ReplyTo)  end),
		        loop();
		        
		{unregister_name, Name} -> 
		        erlang:spawn(fun () -> unregister_name(Name) end), 
		        loop();
		        
		{whereis_name, Name, ReplyTo} ->
		        erlang:spawn(fun () -> whereis_name(Name, ReplyTo) end),
		        loop()
	end.
	
	
start() ->
	register(hnm, self()),
	loop().
	



