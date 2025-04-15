package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 *
 */
public class UpdateExecutionPlan extends SelectExecutionPlan {

  private final List<Result> result = new ArrayList<>();
  private int next = 0;

  public UpdateExecutionPlan(CommandContext ctx) {
    super(ctx);
  }

  @Override
  public ExecutionStream start() {
    return ExecutionStream.resultIterator(result.iterator());
  }

  @Override
  public void reset(CommandContext ctx) {
    result.clear();
    next = 0;
    super.reset(ctx);
    executeInternal();
  }

  public void executeInternal() throws CommandExecutionException {
    var nextBlock = super.start();
    while (nextBlock.hasNext(ctx)) {
      result.add(nextBlock.next(ctx));
    }
    nextBlock.close(ctx);
  }

  @Override
  public @Nonnull Result toResult(@Nullable DatabaseSession db) {
    var res = (ResultInternal) super.toResult(db);
    res.setProperty("type", "UpdateExecutionPlan");
    return res;
  }

  @Override
  public boolean canBeCached() {
    for (var step : steps) {
      if (!step.canBeCached()) {
        return false;
      }
    }
    return true;
  }

  @Override
  public InternalExecutionPlan copy(CommandContext ctx) {
    var copy = new UpdateExecutionPlan(ctx);
    super.copyOn(copy, ctx);
    return copy;
  }
}
