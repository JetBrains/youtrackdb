package com.jetbrains.youtrackdb.internal.core.sql.executor.resultset;

import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import java.util.Iterator;

public class IteratorExecutionStream implements ExecutionStream {

  private final Iterator<?> iterator;
  private final String alias;

  public IteratorExecutionStream(Iterator<?> iter, String alias) {
    this.iterator = iter;
    this.alias = alias;
  }

  @Override
  public boolean hasNext(CommandContext ctx) {
    return iterator.hasNext();
  }

  @Override
  public Result next(CommandContext ctx) {
    var val = iterator.next();
    return ResultInternal.toResult(val, ctx.getDatabaseSession(), alias);
  }

  @Override
  public void close(CommandContext ctx) {
  }
}
