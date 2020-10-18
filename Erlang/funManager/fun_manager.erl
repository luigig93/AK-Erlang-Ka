-module(fun_manager).
-export([loop/0]).


loop() ->
  receive
    {new, Name, Ariety, OwnerPid} ->
      FunManagerPid = self(),
      Fun = case Ariety of
        1 -> fun
               (A) ->
                 % function call
                 FunManagerPid ! {exec, Name, [A], OwnerPid, self()},
                 % function result
                 receive {res, Res} -> Res end
             end;

        2 -> fun
               (A, B) ->
                 % function call
                 FunManagerPid ! {exec, Name, [A, B], OwnerPid, self()},
                 % function result
                 receive {res, Res} -> Res end
             end;

        3 -> fun
               (A, B, C) ->
                 % function call
                 FunManagerPid ! {exec, Name, [A, B, C], OwnerPid, self()},
                 % function result
                 receive {res, Res} -> Res end
             end;

        4 -> fun
               (A, B, C, D) ->
                 FunManagerPid ! {exex, Name, [A, B, C, D], OwnerPid, self()},
                 % function result
                 receive {res, Res} -> Res end
             end

        % andare avanti fino a? 10? 20?
      end,

      OwnerPid ! Fun,
      loop();

    {exec, Name, ParsList, OwnerPid, CallerPid} ->
      OwnerPid ! {exec, Name, ParsList, CallerPid},
      loop();

    {exec, Fun, ParsList, CallerPid} ->
      CallerPid !  apply(Fun, ParsList)

  end.
  