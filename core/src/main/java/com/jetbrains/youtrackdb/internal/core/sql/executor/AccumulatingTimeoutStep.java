package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.TimeoutResultSet;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLTimeout;

/**
 * Terminal step that enforces a global query timeout across the entire execution.
 *
 * <p>Always the last step in the chain (when a timeout is configured), so that it
 * monitors the <em>total</em> wall-clock time across all upstream processing. Wraps the
 * upstream stream with a {@link TimeoutResultSet} that checks elapsed wall-clock
 * time between each record.
 *
 * <p>Two failure strategies are supported:
 * <ul>
 *   <li>{@code RETURN} -- silently stops iteration (partial results are returned)</li>
 *   <li>{@code EXCEPTION} -- propagates a {@link TimeoutException} upstream via
 *       {@link #sendTimeout()}</li>
 * </ul>
 *
 * @see SelectExecutionPlanner#createExecutionPlan
 */
public class AccumulatingTimeoutStep extends AbstractExecutionStep {

  /** The timeout configuration (duration + failure strategy). */
  private final SQLTimeout timeout;

  public AccumulatingTimeoutStep(SQLTimeout timeout, CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.timeout = timeout;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    assert prev != null;

    final var internal = prev.start(ctx);
    return new TimeoutResultSet(internal, this.timeout.getVal().longValue(), this::fail);
  }

  private void fail() {
    if (SQLTimeout.RETURN.equals(this.timeout.getFailureStrategy())) {
      // do nothing
    } else {
      sendTimeout();
      throw new TimeoutException("Timeout expired");
    }
  }

  /** Cacheable: the timeout config is copied into the step at construction time. */
  @Override
  public boolean canBeCached() {
    return true;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new AccumulatingTimeoutStep(timeout.copy(), ctx, profilingEnabled);
  }

  @Override
  public void reset() {
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    return ExecutionStepInternal.getIndent(depth, indent)
        + "+ TIMEOUT ("
        + timeout.getVal().toString()
        + "ms)";
  }
}
