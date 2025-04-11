package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.common.concur.TimeoutException;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLBatch;

/**
 *
 */
public class BatchStep extends AbstractExecutionStep {

  private final Integer batchSize;

  public BatchStep(SQLBatch batch, CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    batchSize = batch.evaluate(ctx);
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    assert prev != null;

    var prevResult = prev.start(ctx);
    return prevResult.map(this::mapResult);
  }

  private Result mapResult(Result result, CommandContext ctx) {
    var db = ctx.getDatabaseSession();
    if (db.getTransactionInternal().isActive()) {
      if (db.getTransactionInternal().getEntryCount() % batchSize == 0) {
        db.commit();
        db.begin();
      }
    }
    return result;
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    return spaces + "+ BATCH COMMIT EVERY " + batchSize;
  }
}
