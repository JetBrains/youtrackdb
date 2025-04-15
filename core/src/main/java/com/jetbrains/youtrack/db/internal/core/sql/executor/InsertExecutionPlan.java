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
  public @Nonnull Result toResult(@Nullable DatabaseSession db) {
    var res = (ResultInternal) super.toResult(db);
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
