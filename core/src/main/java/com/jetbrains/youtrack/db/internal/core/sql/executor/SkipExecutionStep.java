package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.internal.common.concur.TimeoutException;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLSkip;

/**
 *
 */
public class SkipExecutionStep extends AbstractExecutionStep {

  private final SQLSkip skip;

  public SkipExecutionStep(SQLSkip skip, CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.skip = skip;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    var skipValue = skip.getValue(ctx);
    assert prev != null;
    var rs = prev.start(ctx);
    var skipped = 0;
    while (rs.hasNext(ctx) && skipped < skipValue) {
      rs.next(ctx);
      skipped++;
    }

    return rs;
  }

  @Override
  public void sendTimeout() {
  }

  @Override
  public void close() {
    if (prev != null) {
      prev.close();
    }
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    return ExecutionStepInternal.getIndent(depth, indent) + "+ SKIP (" + skip.toString() + ")";
  }
}
