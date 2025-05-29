package com.jetbrain.youtrack.db.gremlin.internal.executor;

import com.jetbrains.youtrack.db.internal.core.command.CommandManager;
import com.jetbrains.youtrack.db.internal.core.command.ScriptExecutorRegister;
import com.jetbrains.youtrack.db.internal.core.command.script.ScriptManager;
import com.jetbrains.youtrack.db.internal.core.command.script.transformer.ScriptTransformerImpl;
import com.jetbrain.youtrack.db.gremlin.internal.executor.transformer.YTDBGremlinTransformer;

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
