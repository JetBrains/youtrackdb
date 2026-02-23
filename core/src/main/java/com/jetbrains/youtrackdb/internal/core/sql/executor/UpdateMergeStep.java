package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLJson;

public class UpdateMergeStep extends AbstractExecutionStep {
  private final SQLJson json;

  public UpdateMergeStep(SQLJson json, CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.json = json;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    assert prev != null;

    var upstream = prev.start(ctx);
    return upstream.map(this::mapResult);
  }

  private Result mapResult(Result result, CommandContext ctx) {
    if (result instanceof ResultInternal resInt) {
      if (result.isEntity()) {
        Identifiable identifiable = result.asEntity();
        var transaction = ctx.getDatabaseSession().getActiveTransaction();
        resInt.setIdentifiable(
            transaction.load(identifiable));
      }
      if (!result.isEntity()) {
        return result;
      }
      handleMerge((EntityImpl) result.asEntity(), ctx);
    }
    return result;
  }

  private void handleMerge(EntityImpl record, CommandContext ctx) {
    record.updateFromJSON(json.toString());
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    return spaces + "+ UPDATE MERGE\n" + spaces + "  " + json;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new UpdateMergeStep(json.copy(), ctx, profilingEnabled);
  }
}
