package com.jetbrains.youtrackdb.internal.core.sql.executor.resultset;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.Result;

public interface MapExecutionStream {

  ExecutionStream flatMap(Result next, CommandContext ctx);
}
