package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSkip;

/**
 * Intermediate step that discards the first N records from the upstream stream
 * (implements the SKIP clause).
 *
 * <pre>
 *  SQL:   SELECT FROM Person SKIP 10 LIMIT 5
 *
 *  This step consumes (and discards) 10 records, then passes the remaining
 *  stream to the downstream LimitExecutionStep.
 * </pre>
 *
 * <p>The skip value may be parameterized (e.g. {@code SKIP :offset} or {@code SKIP ?}).
 * It is resolved at execution time via {@link SQLSkip#getValue(CommandContext)},
 * making this step safe to cache even when the actual skip value changes per execution.
 *
 * @see SelectExecutionPlanner#handleProjectionsBlock
 * @see LimitExecutionStep
 */
public class SkipExecutionStep extends AbstractExecutionStep {

  /** The SKIP clause (may contain a parameter reference, resolved at execution time). */
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
    // Eagerly consume and discard the first N records from upstream.
    while (rs.hasNext(ctx) && skipped < skipValue) {
      rs.next(ctx);
      skipped++;
    }

    return rs;
  }

  /**
   * No-op: SKIP does not propagate timeout signals. The terminal
   * {@link AccumulatingTimeoutStep} handles timeout enforcement.
   */
  @Override
  public void sendTimeout() {
  }

  /**
   * Propagates close directly without the {@link AbstractExecutionStep#alreadyClosed}
   * guard. SKIP holds no resources of its own.
   */
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

  /**
   * Cacheable: the skip value is resolved at execution time via
   * {@link SQLSkip#getValue(CommandContext)}, so the AST structure is reusable.
   */
  @Override
  public boolean canBeCached() {
    return true;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new SkipExecutionStep(skip.copy(), ctx, profilingEnabled);
  }
}
