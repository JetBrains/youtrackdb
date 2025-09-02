package com.jetbrains.youtrackdb.internal.core.command;

import com.jetbrains.youtrackdb.internal.core.command.script.ScriptManager;

/**
 *
 */
public interface ScriptExecutorRegister {

  void registerExecutor(ScriptManager scriptManager, CommandManager commandManager);
}
