package com.jetbrains.youtrackdb.internal.core.sql.executor.cache;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.AbstractExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ExecutionStepInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import javax.annotation.Nonnull;

/**
 * A transparent observe-then-forward execution step the tx-result cache splices upstream of a query's
 * aggregation step so it can seed an {@link AggregateState} from every contributing record before the
 * aggregation collapses them into a single scalar. The step forwards every row downstream
 * unchanged; its only effect is the side {@link AggregateState#observe} call, so the aggregation result
 * is identical to the un-spliced plan's.
 *
 * <p><b>Splice position.</b> The cache rewires this step's {@code prev} to the aggregation step's
 * original predecessor and rewires the aggregation step's {@code prev} to this step, so the tap sits
 * immediately upstream of the aggregation. When a pre-aggregate {@code ProjectionCalculationStep} is
 * present (every value aggregate such as {@code SUM(price)} carries one, projecting the per-row field),
 * the cache splices ABOVE that projection rather than directly under the aggregation: the pre-aggregate
 * projection strips record identity, and {@link AggregateState#observe} requires a non-null RID, so the
 * tap must observe the raw filtered records (identity and property both present). The projection still
 * sits between the tap and the aggregation, so the collapsed scalar is unchanged.
 *
 * <p><b>Single-drive contract.</b> The cache eager-drives the populating plan exactly once at cache-put
 * (the aggregation is blocking, so it pulls every upstream row before emitting its single output row),
 * so every contributing record is observed exactly once per populate. The step is never re-run.
 *
 * <p><b>Not copyable.</b> The step is constructed and spliced into an already-built per-execution plan
 * copy; it is never itself deep-copied (the plan is driven once and discarded), so {@link #copy} throws.
 *
 * <p>Single-transaction state observed only by the owning thread; no field is synchronised.
 */
public final class AggregateCacheTapStep extends AbstractExecutionStep {

  private final AggregateState state;

  public AggregateCacheTapStep(@Nonnull AggregateState state, @Nonnull CommandContext ctx) {
    super(ctx, false);
    this.state = state;
  }

  @Override
  protected ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    // The cache splices this step in only when a real upstream exists; a null prev would mean the tap
    // is acting as a source step, which it never is. Assert it (no-op without -ea) so a broken splice
    // fails loudly in tests rather than NPEing on the prev.start below.
    assert prev != null : "AggregateCacheTapStep spliced with no upstream step";
    var upstream = prev.start(ctx);
    return new ObservingStream(upstream, state);
  }

  /**
   * Wraps the upstream stream and feeds every row to {@link AggregateState#observe} before forwarding it
   * downstream unchanged. {@code hasNext} / {@code close} delegate verbatim, so the only observable
   * difference from the un-tapped stream is the side observe call.
   */
  private static final class ObservingStream implements ExecutionStream {

    private final ExecutionStream upstream;
    private final AggregateState state;

    private ObservingStream(@Nonnull ExecutionStream upstream, @Nonnull AggregateState state) {
      this.upstream = upstream;
      this.state = state;
    }

    @Override
    public boolean hasNext(CommandContext ctx) {
      return upstream.hasNext(ctx);
    }

    @Override
    public Result next(CommandContext ctx) {
      var r = upstream.next(ctx);
      state.observe(r);
      return r;
    }

    @Override
    public void close(CommandContext ctx) {
      upstream.close(ctx);
    }
  }

  @Override
  public ExecutionStepInternal copy(CommandContext ctx) {
    // Spliced post-construction into a one-shot plan copy that is driven once and discarded; the step is
    // never deep-copied. A copy request signals the step reached a code path it was never meant to.
    throw new UnsupportedOperationException("AggregateCacheTapStep is not copyable");
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    return ExecutionStepInternal.getIndent(depth, indent) + "+ AGGREGATE CACHE TAP";
  }
}
