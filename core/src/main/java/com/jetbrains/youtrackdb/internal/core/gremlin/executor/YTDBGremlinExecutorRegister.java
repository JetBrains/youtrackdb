package com.jetbrains.youtrackdb.internal.core.gremlin.executor;

import com.jetbrains.youtrackdb.internal.core.command.CommandManager;
import com.jetbrains.youtrackdb.internal.core.command.ScriptExecutorRegister;
import com.jetbrains.youtrackdb.internal.core.command.script.ScriptManager;
import com.jetbrains.youtrackdb.internal.core.command.script.transformer.ScriptTransformerImpl;
import com.jetbrains.youtrackdb.internal.core.gremlin.executor.transformer.YTDBGremlinTransformer;

public class YTDBGremlinExecutorRegister implements ScriptExecutorRegister {
  @Override
  public void registerExecutor(ScriptManager scriptManager, CommandManager commandManager) {
    commandManager.registerScriptExecutor(
        "gremlin",
        new YTDBCommandGremlinExecutor(
            scriptManager, new YTDBGremlinTransformer(new ScriptTransformerImpl())));
    commandManager.registerScriptExecutor(
        "gremlin-groovy",
        new YTDBCommandGremlinExecutor(
            scriptManager, new YTDBGremlinTransformer(new ScriptTransformerImpl())));
  }
}
