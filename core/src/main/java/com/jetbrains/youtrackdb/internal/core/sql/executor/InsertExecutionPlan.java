package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.DatabaseSession;
import com.jetbrains.youtrackdb.api.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 *
 */
public class InsertExecutionPlan extends SelectExecutionPlan {

  private final List<Result> result = new ArrayList<>();

  public InsertExecutionPlan(CommandContext ctx) {
    super(ctx);
  }

  @Override
  public ExecutionStream start() {
    return ExecutionStream.resultIterator(result.iterator());
  }

  @Override
  public void reset(CommandContext ctx) {
    result.clear();
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
  public @Nonnull Result toResult(@Nullable DatabaseSession session) {
    var res = (ResultInternal) super.toResult(session);
    res.setProperty("type", "InsertExecutionPlan");
    return res;
  }

  @Override
  public InternalExecutionPlan copy(CommandContext ctx) {
    var copy = new InsertExecutionPlan(ctx);
    super.copyOn(copy, ctx);
    return copy;
  }

  @Override
  public boolean canBeCached() {
    return super.canBeCached();
  }
}
