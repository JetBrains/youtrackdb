package com.jetbrains.youtrackdb.internal.core.sql.executor.resultset;

import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import java.util.Iterator;

public class ResultIteratorExecutionStream implements ExecutionStream {

  private final Iterator<? extends Result> iterator;

  public ResultIteratorExecutionStream(Iterator<? extends Result> iterator) {
    this.iterator = iterator;
  }

  @Override
  public boolean hasNext(CommandContext ctx) {
    return iterator.hasNext();
  }

  @Override
  public Result next(CommandContext ctx) {
    return iterator.next();
  }

  @Override
  public void close(CommandContext ctx) {
  }
}
