package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.query.ExecutionStep;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.common.concur.TimeoutException;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLProjectionItem;
import java.util.Collections;

public class GuaranteeEmptyCountStep extends AbstractExecutionStep {

  private final SQLProjectionItem item;

  public GuaranteeEmptyCountStep(
      SQLProjectionItem oProjectionItem, CommandContext ctx, boolean enableProfiling) {
    super(ctx, enableProfiling);
    this.item = oProjectionItem;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    if (prev == null) {
      throw new IllegalStateException("filter step requires a previous step");
    }

    var upstream = prev.start(ctx);
    if (upstream.hasNext(ctx)) {
      return upstream.limit(1);
    } else {
      var result = new ResultInternal(ctx.getDatabaseSession());
      result.setProperty(item.getProjectionAliasAsString(), 0L);
      return ExecutionStream.resultIterator(Collections.singleton((Result) result).iterator());
    }
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new GuaranteeEmptyCountStep(item.copy(), ctx, profilingEnabled);
  }

  public boolean canBeCached() {
    return true;
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    return ExecutionStepInternal.getIndent(depth, indent) + "+ GUARANTEE FOR ZERO COUNT ";
  }
}
