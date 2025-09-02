package com.jetbrains.youtrackdb.internal.core.sql.executor.resultset;

import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.sql.executor.MatchEdgeTraverser;

public final class ResultSetEdgeTraverser implements ExecutionStream {
  private final MatchEdgeTraverser trav;
  private Result nextResult;

  public ResultSetEdgeTraverser(MatchEdgeTraverser trav) {
    this.trav = trav;
  }

  @Override
  public boolean hasNext(CommandContext ctx) {
    fetchNext(ctx);
    return nextResult != null;
  }

  @Override
  public Result next(CommandContext ctx) {
    if (!hasNext(ctx)) {
      throw new IllegalStateException();
    }
    var result = nextResult;
    ctx.setVariable("$matched", result);
    nextResult = null;
    return result;
  }

  @Override
  public void close(CommandContext ctx) {
  }

  private void fetchNext(CommandContext ctx) {
    if (nextResult == null) {
      while (trav.hasNext(ctx)) {
        nextResult = trav.next(ctx);
        if (nextResult != null) {
          break;
        }
      }
    }
  }
}
