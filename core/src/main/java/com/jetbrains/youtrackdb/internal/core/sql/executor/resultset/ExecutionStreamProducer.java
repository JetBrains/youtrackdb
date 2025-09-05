package com.jetbrains.youtrackdb.internal.core.sql.executor.resultset;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;

public interface ExecutionStreamProducer {

  boolean hasNext(CommandContext ctx);

  ExecutionStream next(CommandContext ctx);

  void close(CommandContext ctx);
}
