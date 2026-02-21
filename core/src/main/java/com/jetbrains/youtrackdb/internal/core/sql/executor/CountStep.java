package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;

/**
 * Intermediate <b>blocking</b> step that counts all records from the upstream and produces
 * a single result with a {@code "count"} property.
 *
 * <p>Unlike {@link CountFromClassStep} (which uses metadata), this step actually
 * iterates through all upstream records, counting them one by one. It is used as
 * a generic fallback when the optimized count paths are not applicable.
 *
 * @see CountFromClassStep
 * @see CountFromIndexStep
 * @see CountFromIndexWithKeyStep
 */
public class CountStep extends AbstractExecutionStep {

  /**
   * @param ctx              the query context
   * @param profilingEnabled true to enable the profiling of the execution (for SQL PROFILE)
   */
  public CountStep(CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    assert prev != null;

    // Blocking: must consume the entire upstream before producing the single count result.
    var prevResult = prev.start(ctx);
    long count = 0;
    while (prevResult.hasNext(ctx)) {
      count++;
      prevResult.next(ctx);
    }
    prevResult.close(ctx);
    var resultRecord = new ResultInternal(ctx.getDatabaseSession());
    resultRecord.setProperty("count", count);
    return ExecutionStream.singleton(resultRecord);
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    var result = new StringBuilder();
    result.append(spaces);
    result.append("+ COUNT");
    if (profilingEnabled) {
      result.append(" (").append(getCostFormatted()).append(")");
    }
    return result.toString();
  }

  /** Cacheable: this step has no external state -- it simply counts upstream records. */
  @Override
  public boolean canBeCached() {
    return true;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new CountStep(ctx, profilingEnabled);
  }
}
