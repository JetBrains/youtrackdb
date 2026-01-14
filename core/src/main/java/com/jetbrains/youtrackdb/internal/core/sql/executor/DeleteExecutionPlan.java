package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSession;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 *
 */
public class DeleteExecutionPlan extends UpdateExecutionPlan {

  public DeleteExecutionPlan(CommandContext ctx) {
    super(ctx);
  }

  @Override
  public @Nonnull Result toResult(@Nullable DatabaseSession session) {
    var res = (ResultInternal) super.toResult(session);
    res.setProperty("type", "DeleteExecutionPlan");
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
    var copy = new DeleteExecutionPlan(ctx);
    super.copyOn(copy, ctx);
    return copy;
  }
}
