package com.jetbrains.youtrackdb.internal.core.command;

import com.jetbrains.youtrackdb.internal.core.command.script.ScriptManager;

/**
 * Registry interface for script executors that can be registered with the script manager.
 */
public interface ScriptExecutorRegister {

  void registerExecutor(ScriptManager scriptManager, CommandManager commandManager);
}
