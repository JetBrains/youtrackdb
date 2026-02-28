package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import java.text.DecimalFormat;
import javax.annotation.Nullable;

/**
 * Base implementation of {@link ExecutionStepInternal} that provides the common
 * infrastructure shared by all concrete execution steps.
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Stores references to the previous (upstream) and next (downstream) steps in the chain</li>
 *   <li>Implements the profiling wrapper: when profiling is enabled, {@link #start} records
 *       timing statistics around the call to {@link #internalStart}</li>
 *   <li>Provides default {@link #close()} that propagates backward through the chain</li>
 *   <li>Provides default {@link #sendTimeout()} that propagates backward</li>
 * </ul>
 *
 * <h2>Chain wiring</h2>
 * Steps form a doubly-linked list, wired by {@link SelectExecutionPlan#chain()}:
 * <pre>
 *  null &lt;-- prev -- [StepA] -- next --&gt; [StepB] -- next --&gt; [StepC] -- next --&gt; null
 *                     ^                    ^                    ^
 *                   source             intermediate          terminal
 *                  (prev=null)                              (next=null)
 * </pre>
 *
 * <h2>Source step convention</h2>
 * Source steps (e.g. {@link FetchFromClassExecutionStep}) normally have {@code prev = null}.
 * However, the planner may chain a setup step (e.g. {@link GlobalLetExpressionStep}) before
 * a source step. In that case, the source step should drain and close the predecessor's
 * stream to trigger its side effects:
 * <pre>
 *  if (prev != null) {
 *    prev.start(ctx).close(ctx);  // execute side effects, discard results
 *  }
 * </pre>
 *
 * <h2>Subclass contract</h2>
 * Concrete steps must implement {@link #internalStart(CommandContext)} to define their
 * specific behavior (filtering, projecting, sorting, etc.). They should call
 * {@code prev.start(ctx)} to obtain the upstream {@link com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream}.
 *
 * <pre>
 *  public class MyStep extends AbstractExecutionStep {
 *    protected ExecutionStream internalStart(CommandContext ctx) {
 *      ExecutionStream upstream = prev.start(ctx);
 *      return upstream.filter(record -&gt; myCondition(record));
 *    }
 *  }
 * </pre>
 *
 * @see ExecutionStepInternal
 * @see SelectExecutionPlan
 */
public abstract class AbstractExecutionStep implements ExecutionStepInternal {

  /** The command context (database session, variables, input params, profiling stats). */
  public final CommandContext ctx;

  /** The upstream step that feeds data into this step (null for source steps). */
  @Nullable
  public ExecutionStepInternal prev = null;

  /** The downstream step that consumes data from this step (null for terminal steps). */
  @Nullable
  ExecutionStepInternal next = null;

  /** When true, the {@link #start} method wraps execution with profiling instrumentation. */
  public boolean profilingEnabled;

  /**
   * @param ctx              the command context (database session, variables, input params)
   * @param profilingEnabled if true, the {@link #start} wrapper records timing statistics
   */
  public AbstractExecutionStep(CommandContext ctx, boolean profilingEnabled) {
    this.ctx = ctx;
    this.profilingEnabled = profilingEnabled;
  }

  @Override
  public void setPrevious(ExecutionStepInternal step) {
    this.prev = step;
  }

  @Override
  public void setNext(@Nullable ExecutionStepInternal step) {
    this.next = step;
  }

  @Override
  public void sendTimeout() {
    if (prev != null) {
      prev.sendTimeout();
    }
  }

  /** Guard flag to prevent double-close (close propagates backward and could cycle). */
  private boolean alreadyClosed = false;

  /**
   * Releases resources and propagates the close signal backward to the predecessor.
   * Idempotent: calling close() multiple times has no additional effect.
   */
  @Override
  public void close() {
    if (alreadyClosed) {
      return;
    }
    alreadyClosed = true;

    if (prev != null) {
      prev.close();
    }
  }

  public boolean isProfilingEnabled() {
    return profilingEnabled;
  }

  public void setProfilingEnabled(boolean profilingEnabled) {
    this.profilingEnabled = profilingEnabled;
  }

  /**
   * Entry point for step execution. When profiling is enabled, wraps the call to
   * {@link #internalStart} with timing instrumentation and returns a profiled stream.
   * Otherwise delegates directly to {@link #internalStart}.
   *
   * <pre>
   *  Profiling enabled:
   *    ctx.startProfiling(this)
   *      stream = internalStart(ctx).profile(this)   // each next() call is timed
   *    ctx.endProfiling(this)
   *    return stream
   *
   *  Profiling disabled:
   *    return internalStart(ctx)
   * </pre>
   */
  @Override
  public ExecutionStream start(CommandContext ctx) throws TimeoutException {
    if (profilingEnabled) {
      ctx.startProfiling(this);
      try {
        return internalStart(ctx).profile(this);
      } finally {
        ctx.endProfiling(this);
      }
    } else {
      return internalStart(ctx);
    }
  }

  /**
   * Core step logic -- must be implemented by every concrete step.
   *
   * <p>Source steps (e.g. {@link FetchFromClassExecutionStep}) create a new stream
   * from a data source. Intermediate steps (e.g. {@link FilterStep}) call
   * {@code prev.start(ctx)} to obtain the upstream stream and transform it.
   *
   * @param ctx the command context
   * @return a lazy stream of results produced by this step
   * @throws TimeoutException if the query timeout is exceeded
   */
  protected abstract ExecutionStream internalStart(CommandContext ctx) throws TimeoutException;

  /** Returns the profiling cost (in nanoseconds) recorded by this step's context stats. */
  @Override
  public long getCost() {
    var stats = this.ctx.getStats(this);
    if (stats != null) {
      return stats.getCost();
    } else {
      return ExecutionStepInternal.super.getCost();
    }
  }

  /** Returns the profiling cost as a human-readable string (e.g. "1,234μs"). */
  protected String getCostFormatted() {
    return new DecimalFormat().format(getCost() / 1000) + "μs";
  }
}
