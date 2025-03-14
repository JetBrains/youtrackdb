package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.common.concur.TimeoutException;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLJson;

/**
 *
 */
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
    if (result instanceof ResultInternal) {
      if (result.isEntity()) {
        ((ResultInternal) result).setIdentifiable(
            result.asEntity().getRecord(ctx.getDatabaseSession()));
      }
      if (!result.isEntity()) {
        return result;
      }
      handleMerge((EntityImpl) result.asEntity(), ctx);
    }
    return result;
  }

  private void handleMerge(EntityImpl record, CommandContext ctx) {
    record.merge(json.toEntity(record, ctx), true, false);
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    return spaces + "+ UPDATE MERGE\n" + spaces + "  " + json;
  }
}
