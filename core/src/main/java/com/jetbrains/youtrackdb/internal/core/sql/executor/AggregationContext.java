package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;

/**
 *
 */
public interface AggregationContext {

  Object getFinalValue();

  void apply(Result next, CommandContext ctx);
}
