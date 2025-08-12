package com.jetbrains.youtrackdb.internal.core.sql.executor.resultset;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.api.query.Result;

public interface ProduceResult {

  Result produce(CommandContext ctx);
}
