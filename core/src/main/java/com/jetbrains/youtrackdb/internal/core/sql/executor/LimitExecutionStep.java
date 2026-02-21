package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLLimit;

/**
 * Intermediate step that truncates the upstream stream after N records
 * (implements the LIMIT clause).
 *
 * <pre>
 *  SQL:   SELECT FROM Person LIMIT 5
 *
 *  Wraps the upstream ExecutionStream with .limit(5), which stops iteration
 *  after 5 records. This enables early termination -- upstream steps will
 *  not be called once the limit is reached.
 * </pre>
 *
 * <p>A limit value of -1 means "no limit" (the upstream stream is returned as-is).
 *
 * <p>The limit value may be parameterized (e.g. {@code LIMIT :n} or {@code LIMIT ?}).
 * It is resolved at execution time via {@link SQLLimit#getValue(CommandContext)},
 * making this step safe to cache even when the actual limit changes per execution.
 *
 * @see SelectExecutionPlanner#handleProjectionsBlock
 * @see SkipExecutionStep
 */
public class LimitExecutionStep extends AbstractExecutionStep {

  /** The LIMIT clause (may contain a parameter reference, resolved at execution time). */
  private final SQLLimit limit;

  public LimitExecutionStep(SQLLimit limit, CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.limit = limit;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    // LimitExecutionStep is always chained after another step (never a source step).
    assert prev != null;
    var limitVal = limit.getValue(ctx);
    if (limitVal == -1) {
      return prev.start(ctx);
    }
    var result = prev.start(ctx);
    return result.limit(limitVal);
  }

  /**
   * No-op: LIMIT does not propagate timeout signals. When a timeout fires, the
   * terminal {@link AccumulatingTimeoutStep} handles it; LIMIT simply stops pulling
   * once the count is reached.
   */
  @Override
  public void sendTimeout() {
  }

  /**
   * Propagates close directly without the {@link AbstractExecutionStep#alreadyClosed}
   * guard. LIMIT holds no resources of its own.
   */
  @Override
  public void close() {
    if (prev != null) {
      prev.close();
    }
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    return ExecutionStepInternal.getIndent(depth, indent) + "+ LIMIT (" + limit.toString() + ")";
  }

  /**
   * Cacheable: the limit value is resolved at execution time via
   * {@link SQLLimit#getValue(CommandContext)}, so the AST structure is reusable.
   */
  @Override
  public boolean canBeCached() {
    return true;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    SQLLimit limitCopy = null;
    if (limit != null) {
      limitCopy = limit.copy();
    }

    return new LimitExecutionStep(limitCopy, ctx, profilingEnabled);
  }
}
