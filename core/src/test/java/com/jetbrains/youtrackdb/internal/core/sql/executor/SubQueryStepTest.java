package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.junit.Test;

/**
 * Tests for {@link SubQueryStep}, covering the subquery-as-FROM-target source step:
 * predecessor draining, result mapping with {@code $current} variable,
 * cacheability rules (same-context × sub-plan cacheability), pretty-print
 * rendering with delegated sub-plan output, and the deep-copy contract.
 */
public class SubQueryStepTest extends DbTestBase {

  // =========================================================================
  // internalStart: subquery execution without predecessor
  // =========================================================================

  /**
   * When there is no predecessor step, SubQueryStep directly executes the
   * sub-plan and produces its results. Each result is also set as $current
   * in the context.
   */
  @Test
  public void startWithNoPredecessorExecutesSubPlan() {
    var ctx = newContext();

    var r1 = new ResultInternal(session);
    r1.setProperty("name", "Alice");
    var r2 = new ResultInternal(session);
    r2.setProperty("name", "Bob");

    var subPlan = new StubExecutionPlan(ctx, List.of(r1, r2));
    var step = new SubQueryStep(subPlan, ctx, ctx, false);

    var results = drain(step.start(ctx), ctx);

    assertThat(results).hasSize(2);
    assertThat(results.get(0).<String>getProperty("name")).isEqualTo("Alice");
    assertThat(results.get(1).<String>getProperty("name")).isEqualTo("Bob");
  }

  /**
   * When the sub-plan produces no results, the step yields an empty stream.
   */
  @Test
  public void startWithEmptySubPlanProducesNothing() {
    var ctx = newContext();
    var subPlan = new StubExecutionPlan(ctx, List.of());
    var step = new SubQueryStep(subPlan, ctx, ctx, false);

    var results = drain(step.start(ctx), ctx);
    assertThat(results).isEmpty();
  }

  // =========================================================================
  // internalStart: predecessor draining
  // =========================================================================

  /**
   * When a predecessor step exists, SubQueryStep drains it (for side effects)
   * before executing the sub-plan. This verifies that the predecessor's
   * results are consumed and closed.
   */
  @Test
  public void startWithPredecessorDrainsPredecessorBeforeExecutingSubPlan() {
    var ctx = newContext();

    var sideEffectResult = new ResultInternal(session);
    sideEffectResult.setProperty("side", "effect");
    var predecessor = new TrackingSourceStep(ctx, List.of(sideEffectResult));

    var subResult = new ResultInternal(session);
    subResult.setProperty("name", "fromSub");
    var subPlan = new StubExecutionPlan(ctx, List.of(subResult));

    var step = new SubQueryStep(subPlan, ctx, ctx, false);
    step.setPrevious(predecessor);

    var results = drain(step.start(ctx), ctx);

    // Predecessor was drained (started and closed)
    assertThat(predecessor.wasStarted()).isTrue();
    assertThat(predecessor.wasStreamClosed()).isTrue();
    // SubQuery results are produced
    assertThat(results).hasSize(1);
    assertThat(results.get(0).<String>getProperty("name")).isEqualTo("fromSub");
  }

  // =========================================================================
  // mapResult: $current context variable
  // =========================================================================

  /**
   * Each result from the sub-plan is set as the $current variable in the
   * command context. After draining, $current should hold the last result.
   */
  @Test
  public void eachResultIsSetAsCurrentVariable() {
    var ctx = newContext();

    var r1 = new ResultInternal(session);
    r1.setProperty("seq", 1);
    var r2 = new ResultInternal(session);
    r2.setProperty("seq", 2);

    var subPlan = new StubExecutionPlan(ctx, List.of(r1, r2));
    var step = new SubQueryStep(subPlan, ctx, ctx, false);

    var stream = step.start(ctx);
    // After consuming first result, $current should be r1
    var first = stream.next(ctx);
    assertThat(ctx.getVariable("$current")).isSameAs(first);

    // After consuming second result, $current should be r2
    var second = stream.next(ctx);
    assertThat(ctx.getVariable("$current")).isSameAs(second);

    stream.close(ctx);
  }

  // =========================================================================
  // prettyPrint
  // =========================================================================

  /**
   * prettyPrint renders "FETCH FROM SUBQUERY" followed by the sub-plan's
   * own prettyPrint output (at depth+1).
   */
  @Test
  public void prettyPrintRendersSubPlanAtIncreasedDepth() {
    var ctx = newContext();
    var subPlan = new StubExecutionPlan(ctx, List.of());
    var step = new SubQueryStep(subPlan, ctx, ctx, false);

    var output = step.prettyPrint(0, 2);
    assertThat(output).startsWith("+ FETCH FROM SUBQUERY");
    // Sub-plan prettyPrint is included (StubExecutionPlan renders "STUB_PLAN")
    assertThat(output).contains("STUB_PLAN");
  }

  /**
   * prettyPrint with non-zero depth prepends the correct indentation.
   */
  @Test
  public void prettyPrintWithDepthAppliesIndentation() {
    var ctx = newContext();
    var subPlan = new StubExecutionPlan(ctx, List.of());
    var step = new SubQueryStep(subPlan, ctx, ctx, false);

    var output = step.prettyPrint(1, 4);
    // depth=1, indent=4 -> 4 spaces before "+"
    assertThat(output).startsWith("    + FETCH FROM SUBQUERY");
  }

  /**
   * The sub-plan's prettyPrint is rendered at depth+1, so its indentation
   * is one level deeper than the SubQueryStep's own label.
   */
  @Test
  public void prettyPrintDelegatesSubPlanAtIncreasedDepth() {
    var ctx = newContext();
    var subPlan = new StubExecutionPlan(ctx, List.of());
    var step = new SubQueryStep(subPlan, ctx, ctx, false);

    // depth=0, indent=4 -> step label at depth 0, sub-plan at depth 1 (4 spaces)
    var output = step.prettyPrint(0, 4);
    // The sub-plan line should be indented by 4 spaces (depth=1, indent=4)
    assertThat(output).contains("    STUB_PLAN");
  }

  // =========================================================================
  // canBeCached: 4 combinations of (sameContext × subPlanCacheable)
  // =========================================================================

  /**
   * When the subquery shares the parent context AND the sub-plan is cacheable,
   * the step is cacheable.
   */
  @Test
  public void canBeCachedReturnsTrueWhenSameContextAndSubPlanCacheable() {
    var ctx = newContext();
    var subPlan = new StubExecutionPlan(ctx, List.of());
    subPlan.setCacheable(true);

    var step = new SubQueryStep(subPlan, ctx, ctx, false);
    assertThat(step.canBeCached()).isTrue();
  }

  /**
   * When the subquery shares the parent context but the sub-plan is NOT
   * cacheable, the step is NOT cacheable.
   */
  @Test
  public void canBeCachedReturnsFalseWhenSameContextButSubPlanNotCacheable() {
    var ctx = newContext();
    var subPlan = new StubExecutionPlan(ctx, List.of());
    subPlan.setCacheable(false);

    var step = new SubQueryStep(subPlan, ctx, ctx, false);
    assertThat(step.canBeCached()).isFalse();
  }

  /**
   * When the subquery has a different context (child context), the step is
   * NOT cacheable even if the sub-plan itself is cacheable.
   */
  @Test
  public void canBeCachedReturnsFalseWhenDifferentContextAndSubPlanCacheable() {
    var ctx = newContext();
    var subCtx = newContext(); // Different context instance
    var subPlan = new StubExecutionPlan(ctx, List.of());
    subPlan.setCacheable(true);

    var step = new SubQueryStep(subPlan, ctx, subCtx, false);
    assertThat(step.canBeCached()).isFalse();
  }

  /**
   * When the subquery has a different context AND the sub-plan is NOT
   * cacheable, the step is NOT cacheable.
   */
  @Test
  public void canBeCachedReturnsFalseWhenDifferentContextAndSubPlanNotCacheable() {
    var ctx = newContext();
    var subCtx = newContext();
    var subPlan = new StubExecutionPlan(ctx, List.of());
    subPlan.setCacheable(false);

    var step = new SubQueryStep(subPlan, ctx, subCtx, false);
    assertThat(step.canBeCached()).isFalse();
  }

  // =========================================================================
  // copy
  // =========================================================================

  /**
   * copy() produces a new SubQueryStep with the same sub-plan (deep-copied)
   * and the given context. The copied step always uses the same context for
   * both parent and sub (sameContextAsParent = true), enabling cacheability
   * if the sub-plan is cacheable.
   */
  @Test
  public void copyProducesIndependentCacheableStep() {
    var ctx = newContext();
    var subPlan = new StubExecutionPlan(ctx, List.of());
    subPlan.setCacheable(true);

    // Original step with different contexts (not cacheable)
    var subCtx = newContext();
    var step = new SubQueryStep(subPlan, ctx, subCtx, false);
    assertThat(step.canBeCached()).isFalse();

    // Copy always uses same context, so it becomes cacheable
    var copied = (SubQueryStep) step.copy(ctx);
    assertThat(copied).isNotSameAs(step);
    assertThat(copied.canBeCached()).isTrue();
  }

  /**
   * The copied step executes independently and produces results from the
   * copied sub-plan.
   */
  @Test
  public void copiedStepExecutesIndependently() {
    var ctx = newContext();

    var r1 = new ResultInternal(session);
    r1.setProperty("val", "original");
    var subPlan = new StubExecutionPlan(ctx, List.of(r1));

    var step = new SubQueryStep(subPlan, ctx, ctx, false);
    var copied = (SubQueryStep) step.copy(ctx);

    // Execute the copy
    var results = drain(copied.start(ctx), ctx);
    assertThat(results).hasSize(1);
    assertThat(results.get(0).<String>getProperty("val")).isEqualTo("original");
  }

  /**
   * copy() preserves the profilingEnabled flag.
   */
  @Test
  public void copyPreservesProfilingFlag() {
    var ctx = newContext();
    var subPlan = new StubExecutionPlan(ctx, List.of());

    var step = new SubQueryStep(subPlan, ctx, ctx, true);
    var copied = (SubQueryStep) step.copy(ctx);

    assertThat(copied.isProfilingEnabled()).isTrue();
  }

  // =========================================================================
  // Helpers
  // =========================================================================

  private BasicCommandContext newContext() {
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);
    return ctx;
  }

  /**
   * Drains all results from a stream into a list.
   */
  private List<Result> drain(ExecutionStream stream, CommandContext ctx) {
    var results = new ArrayList<Result>();
    while (stream.hasNext(ctx)) {
      results.add(stream.next(ctx));
    }
    stream.close(ctx);
    return results;
  }

  // =========================================================================
  // Test doubles
  // =========================================================================

  /**
   * A tracking source step that records whether it was started and closed,
   * used to verify predecessor draining behavior.
   */
  private static class TrackingSourceStep extends AbstractExecutionStep {
    private final List<? extends Result> rows;
    private boolean started = false;
    // Tracks whether the returned ExecutionStream was closed, not the step itself.
    private boolean streamClosed = false;

    TrackingSourceStep(CommandContext ctx, List<? extends Result> rows) {
      super(ctx, false);
      this.rows = rows;
    }

    @Override
    public ExecutionStream internalStart(CommandContext ctx) {
      started = true;
      return new ExecutionStream() {
        private final Iterator<? extends Result> iterator =
            new ArrayList<>(rows).iterator();

        @Override
        public boolean hasNext(CommandContext ctx) {
          return iterator.hasNext();
        }

        @Override
        public Result next(CommandContext ctx) {
          return iterator.next();
        }

        @Override
        public void close(CommandContext ctx) {
          streamClosed = true;
        }
      };
    }

    boolean wasStarted() {
      return started;
    }

    boolean wasStreamClosed() {
      return streamClosed;
    }

    @Override
    public ExecutionStep copy(CommandContext ctx) {
      throw new UnsupportedOperationException();
    }
  }

  /**
   * A minimal stub execution plan that produces a fixed list of results
   * and supports configurable cacheability. Used to test SubQueryStep's
   * delegation to its sub-plan.
   */
  private static class StubExecutionPlan extends SelectExecutionPlan {
    private final List<? extends Result> results;
    private boolean cacheable = true;

    StubExecutionPlan(CommandContext ctx, List<? extends Result> results) {
      super(ctx);
      this.results = results;
    }

    void setCacheable(boolean cacheable) {
      this.cacheable = cacheable;
    }

    @Override
    public ExecutionStream start() {
      return ExecutionStream.resultIterator(
          new ArrayList<>(results).iterator());
    }

    @Override
    public boolean canBeCached() {
      return cacheable;
    }

    @Override
    public String prettyPrint(int depth, int indent) {
      return ExecutionStepInternal.getIndent(depth, indent) + "STUB_PLAN";
    }

    @Override
    public void close() {
      // No-op: stub has no resources to release
    }

    @Override
    public InternalExecutionPlan copy(CommandContext ctx) {
      var copy = new StubExecutionPlan(ctx, results);
      copy.cacheable = this.cacheable;
      return copy;
    }
  }
}
