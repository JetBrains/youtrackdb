package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.Result;

/** Context for computing aggregation values during SQL query execution. */
public interface AggregationContext {

  Object getFinalValue();

  void apply(Result next, CommandContext ctx);
}
