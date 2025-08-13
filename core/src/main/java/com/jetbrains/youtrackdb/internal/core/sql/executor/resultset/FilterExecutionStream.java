package com.jetbrains.youtrackdb.internal.core.sql.executor.resultset;

import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;

public class FilterExecutionStream implements ExecutionStream {

  private final ExecutionStream prevResult;
  private final FilterResult filter;
  private Result nextItem = null;

  public FilterExecutionStream(ExecutionStream resultSet, FilterResult filter) {
    super();
    this.prevResult = resultSet;
    this.filter = filter;
  }

  @Override
  public boolean hasNext(CommandContext ctx) {
    if (nextItem == null) {
      fetchNextItem(ctx);
    }

    return nextItem != null;
  }

  @Override
  public Result next(CommandContext ctx) {
    if (nextItem == null) {
      fetchNextItem(ctx);
    }
    if (nextItem == null) {
      throw new IllegalStateException();
    }
    var result = nextItem;
    nextItem = null;
    return result;
  }

  @Override
  public void close(CommandContext ctx) {
    this.prevResult.close(ctx);
  }

  private void fetchNextItem(CommandContext ctx) {
    while (prevResult.hasNext(ctx)) {
      nextItem = prevResult.next(ctx);
      nextItem = filter.filterMap(nextItem, ctx);
      if (nextItem != null) {
        break;
      }
    }
  }
}
