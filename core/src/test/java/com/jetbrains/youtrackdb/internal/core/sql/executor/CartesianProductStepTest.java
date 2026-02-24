package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

/**
 * Tests the Cartesian product (cross join) logic of {@link CartesianProductStep},
 * covering the stream composition, predecessor draining, pretty-print rendering,
 * cacheability delegation, and the deep-copy contract.
 */
public class CartesianProductStepTest extends DbTestBase {

  // --- Cartesian product core logic ---

  /**
   * Two sub-plans should produce all pairwise combinations (cross join).
   *
   * <pre>
   *  SubPlan[0]: [{a:1}, {a:2}]
   *  SubPlan[1]: [{b:X}, {b:Y}]
   *
   *  Expected: [{a:1,b:X}, {a:1,b:Y}, {a:2,b:X}, {a:2,b:Y}]
   * </pre>
   */
  @Test
  public void twoSubPlansCrossJoinProducesAllCombinations() {
    var ctx = newContext();
    var step = new CartesianProductStep(ctx, false);

    step.addSubPlan(planProducing(ctx, List.of(
        result(ctx, "a", 1),
        result(ctx, "a", 2))));
    step.addSubPlan(planProducing(ctx, List.of(
        result(ctx, "b", "X"),
        result(ctx, "b", "Y"))));

    var results = drain(step.start(ctx), ctx);

    assertThat(results).hasSize(4);
    assertThat(results.get(0).<Integer>getProperty("a")).isEqualTo(1);
    assertThat(results.get(0).<String>getProperty("b")).isEqualTo("X");
    assertThat(results.get(1).<Integer>getProperty("a")).isEqualTo(1);
    assertThat(results.get(1).<String>getProperty("b")).isEqualTo("Y");
    assertThat(results.get(2).<Integer>getProperty("a")).isEqualTo(2);
    assertThat(results.get(2).<String>getProperty("b")).isEqualTo("X");
    assertThat(results.get(3).<Integer>getProperty("a")).isEqualTo(2);
    assertThat(results.get(3).<String>getProperty("b")).isEqualTo("Y");
  }

  /**
   * Three sub-plans produce the full 3-way Cartesian product via nested flatMap.
   *
   * <pre>
   *  SubPlan[0]: [{a:1}]
   *  SubPlan[1]: [{b:X}, {b:Y}]
   *  SubPlan[2]: [{c:P}]
   *
   *  Expected: [{a:1,b:X,c:P}, {a:1,b:Y,c:P}]
   * </pre>
   */
  @Test
  public void threeSubPlansProduceFullCrossProduct() {
    var ctx = newContext();
    var step = new CartesianProductStep(ctx, false);

    step.addSubPlan(planProducing(ctx, List.of(result(ctx, "a", 1))));
    step.addSubPlan(planProducing(ctx, List.of(
        result(ctx, "b", "X"),
        result(ctx, "b", "Y"))));
    step.addSubPlan(planProducing(ctx, List.of(result(ctx, "c", "P"))));

    var results = drain(step.start(ctx), ctx);

    assertThat(results).hasSize(2);
    assertThat(results.get(0).<Integer>getProperty("a")).isEqualTo(1);
    assertThat(results.get(0).<String>getProperty("b")).isEqualTo("X");
    assertThat(results.get(0).<String>getProperty("c")).isEqualTo("P");
    assertThat(results.get(1).<Integer>getProperty("a")).isEqualTo(1);
    assertThat(results.get(1).<String>getProperty("b")).isEqualTo("Y");
    assertThat(results.get(1).<String>getProperty("c")).isEqualTo("P");
  }

  /**
   * A single sub-plan with no cross partner should pass through its results
   * unchanged (the stream is created with map, not flatMap).
   */
  @Test
  public void singleSubPlanPassesThroughResults() {
    var ctx = newContext();
    var step = new CartesianProductStep(ctx, false);

    step.addSubPlan(planProducing(ctx, List.of(
        result(ctx, "x", 10),
        result(ctx, "x", 20))));

    var results = drain(step.start(ctx), ctx);

    assertThat(results).hasSize(2);
    assertThat(results.get(0).<Integer>getProperty("x")).isEqualTo(10);
    assertThat(results.get(1).<Integer>getProperty("x")).isEqualTo(20);
  }

  /**
   * When a predecessor step is chained before this source step, the predecessor's
   * stream must be fully drained (for its side effects) before the Cartesian
   * product computation begins.
   */
  @Test
  public void predecessorIsDrainedBeforeCartesianProduct() {
    var ctx = newContext();
    var step = new CartesianProductStep(ctx, false);
    step.addSubPlan(planProducing(ctx, List.of(result(ctx, "a", 1))));

    var prevDrained = new AtomicBoolean(false);
    var prev = new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStep copy(CommandContext c) {
        throw new UnsupportedOperationException();
      }

      @Override
      public ExecutionStream internalStart(CommandContext c) {
        prevDrained.set(true);
        return ExecutionStream.empty();
      }
    };
    step.setPrevious(prev);

    var results = drain(step.start(ctx), ctx);
    assertThat(prevDrained).isTrue();
    assertThat(results).hasSize(1);
  }

  /**
   * If any sub-plan produces zero results, the Cartesian product is empty
   * because 0 * N = 0 for any N.
   */
  @Test
  public void emptySubPlanProducesEmptyResult() {
    var ctx = newContext();
    var step = new CartesianProductStep(ctx, false);

    step.addSubPlan(planProducing(ctx, List.of(result(ctx, "a", 1))));
    step.addSubPlan(planProducing(ctx, List.of()));

    var results = drain(step.start(ctx), ctx);

    assertThat(results).isEmpty();
  }

  /**
   * When the first sub-plan is empty, no downstream sub-plans are evaluated
   * and the result is empty.
   */
  @Test
  public void emptyFirstSubPlanProducesEmptyResult() {
    var ctx = newContext();
    var step = new CartesianProductStep(ctx, false);

    step.addSubPlan(planProducing(ctx, List.of()));
    step.addSubPlan(planProducing(ctx, List.of(result(ctx, "b", 1))));

    var results = drain(step.start(ctx), ctx);

    assertThat(results).isEmpty();
  }

  /**
   * Each output result merges properties from all sub-plan rows in the
   * current combination. If two sub-plans produce properties with the same
   * name, the later sub-plan's value overwrites the earlier one (last-write-wins).
   */
  @Test
  public void overlappingPropertiesAreOverwrittenByLaterSubPlan() {
    var ctx = newContext();
    var step = new CartesianProductStep(ctx, false);

    step.addSubPlan(planProducing(ctx, List.of(result(ctx, "name", "Alice"))));
    step.addSubPlan(planProducing(ctx, List.of(result(ctx, "name", "Bob"))));

    var results = drain(step.start(ctx), ctx);

    assertThat(results).hasSize(1);
    // The second sub-plan's "name" overwrites the first's
    assertThat(results.get(0).<String>getProperty("name")).isEqualTo("Bob");
  }

  /**
   * Closing the outer ExecutionStream should propagate close to all sub-plan
   * streams, ensuring resources (iterators, cursors) are released properly.
   */
  @Test
  public void closingResultStreamClosesSubPlanStreams() {
    var ctx = newContext();
    var step = new CartesianProductStep(ctx, false);

    var plan0Closed = new AtomicBoolean(false);
    var plan1Closed = new AtomicBoolean(false);

    step.addSubPlan(planWithCloseTracking(ctx,
        List.of(result(ctx, "a", 1)), plan0Closed));
    step.addSubPlan(planWithCloseTracking(ctx,
        List.of(result(ctx, "b", 2)), plan1Closed));

    var stream = step.start(ctx);
    // Consume one result to trigger stream evaluation
    assertThat(stream.hasNext(ctx)).isTrue();
    stream.next(ctx);
    // Close the stream
    stream.close(ctx);

    assertThat(plan0Closed).isTrue();
    assertThat(plan1Closed).isTrue();
  }

  // --- prettyPrint ---

  /**
   * prettyPrint with a single sub-plan (without profiling) renders the
   * "CARTESIAN PRODUCT" header and the sub-plan's content with ASCII connectors.
   */
  @Test
  public void prettyPrintWithSingleSubPlan() {
    var ctx = newContext();
    var step = new CartesianProductStep(ctx, false);
    step.addSubPlan(planProducing(ctx, List.of(result(ctx, "a", 1))));

    var output = step.prettyPrint(0, 2);

    assertThat(output).contains("CARTESIAN PRODUCT");
    assertThat(output).contains("+-------------------------");
    assertThat(output).doesNotContain("μs");
  }

  /**
   * prettyPrint with profiling enabled should include the cost string in
   * the header line.
   */
  @Test
  public void prettyPrintWithProfilingIncludesCost() {
    var ctx = newContext();
    var step = new CartesianProductStep(ctx, true);
    step.addSubPlan(planProducing(ctx, List.of(result(ctx, "a", 1))));

    var output = step.prettyPrint(0, 2);

    assertThat(output).contains("CARTESIAN PRODUCT");
    assertThat(output).contains("μs");
  }

  /**
   * prettyPrint with two sub-plans renders connector arrows and vertical bars
   * linking each sub-plan block to the main pipeline.
   */
  @Test
  public void prettyPrintWithTwoSubPlansRendersConnectors() {
    var ctx = newContext();
    var step = new CartesianProductStep(ctx, false);
    step.addSubPlan(planProducing(ctx, List.of(result(ctx, "a", 1))));
    step.addSubPlan(planProducing(ctx, List.of(result(ctx, "b", 2))));

    var output = step.prettyPrint(0, 2);

    assertThat(output).contains("CARTESIAN PRODUCT");
    // Two blocks means two sets of "+---" separators
    assertThat(output).contains("+-------------------------");
    // The foot shows one "V" per sub-plan
    assertThat(output).contains(" V ");
  }

  /**
   * prettyPrint with three sub-plans exercises the full ASCII rendering logic
   * including horizontal arrows, vertical bars, and "+" junctions across
   * all column positions.
   */
  @Test
  public void prettyPrintWithThreeSubPlansRendersFullASCII() {
    var ctx = newContext();
    var step = new CartesianProductStep(ctx, false);
    step.addSubPlan(planProducing(ctx, List.of(result(ctx, "a", 1))));
    step.addSubPlan(planProducing(ctx, List.of(result(ctx, "b", 2))));
    step.addSubPlan(planProducing(ctx, List.of(result(ctx, "c", 3))));

    var output = step.prettyPrint(0, 2);

    assertThat(output).contains("CARTESIAN PRODUCT");
    // Three sub-plan blocks
    assertThat(output).contains("+-------------------------");
    // Foot renders " V " three times
    assertThat(output).contains(" V  V  V ");
  }

  /**
   * prettyPrint with non-zero depth applies indentation to each line of
   * the output.
   */
  @Test
  public void prettyPrintWithDepthAppliesIndentation() {
    var ctx = newContext();
    var step = new CartesianProductStep(ctx, false);
    step.addSubPlan(planProducing(ctx, List.of(result(ctx, "a", 1))));

    var output = step.prettyPrint(1, 4);

    // depth=1, indent=4 → 4 leading spaces
    assertThat(output).startsWith("    ");
  }

  // --- canBeCached ---

  /**
   * When all sub-plans report canBeCached() == true, the step itself is cacheable.
   */
  @Test
  public void canBeCachedWhenAllSubPlansAreCacheable() {
    var ctx = newContext();
    var step = new CartesianProductStep(ctx, false);

    step.addSubPlan(cacheablePlan(ctx));
    step.addSubPlan(cacheablePlan(ctx));

    assertThat(step.canBeCached()).isTrue();
  }

  /**
   * When any sub-plan reports canBeCached() == false, the step is not cacheable
   * because the entire plan must be re-computed.
   */
  @Test
  public void canNotBeCachedWhenAnySubPlanIsNotCacheable() {
    var ctx = newContext();
    var step = new CartesianProductStep(ctx, false);

    step.addSubPlan(cacheablePlan(ctx));
    step.addSubPlan(nonCacheablePlan(ctx));

    assertThat(step.canBeCached()).isFalse();
  }

  /**
   * With no sub-plans at all, canBeCached() returns true (vacuously true:
   * the loop body never executes).
   */
  @Test
  public void canBeCachedWithNoSubPlans() {
    var ctx = newContext();
    var step = new CartesianProductStep(ctx, false);

    assertThat(step.canBeCached()).isTrue();
  }

  // --- copy ---

  /**
   * copy() produces a new CartesianProductStep instance that is structurally
   * equivalent but shares no mutable state with the original. Sub-plans are
   * deep-copied, and the copy can execute independently.
   */
  @Test
  public void copyProducesIndependentStep() {
    var ctx = newContext();
    var step = new CartesianProductStep(ctx, false);
    step.addSubPlan(planProducing(ctx, List.of(
        result(ctx, "a", 1),
        result(ctx, "a", 2))));
    step.addSubPlan(planProducing(ctx, List.of(
        result(ctx, "b", "X"))));

    var copied = (CartesianProductStep) step.copy(ctx);

    assertThat(copied).isNotSameAs(step);
    // The copy should produce the same Cartesian product
    var results = drain(copied.start(ctx), ctx);
    assertThat(results).hasSize(2);
    assertThat(results.get(0).<Integer>getProperty("a")).isEqualTo(1);
    assertThat(results.get(0).<String>getProperty("b")).isEqualTo("X");
    assertThat(results.get(1).<Integer>getProperty("a")).isEqualTo(2);
    assertThat(results.get(1).<String>getProperty("b")).isEqualTo("X");
  }

  /**
   * copy() with no sub-plans produces a valid (but empty) step.
   */
  @Test
  public void copyWithNoSubPlansProducesEmptyStep() {
    var ctx = newContext();
    var step = new CartesianProductStep(ctx, false);

    var copied = (CartesianProductStep) step.copy(ctx);

    assertThat(copied).isNotSameAs(step);
    assertThat(copied.canBeCached()).isTrue();
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private BasicCommandContext newContext() {
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);
    return ctx;
  }

  /**
   * Creates a {@link ResultInternal} with a single property.
   */
  private ResultInternal result(CommandContext ctx, String key, Object value) {
    var r = new ResultInternal(ctx.getDatabaseSession());
    r.setProperty(key, value);
    return r;
  }

  /**
   * Creates a {@link SelectExecutionPlan} that produces the given results.
   * The plan is re-startable: each call to start() produces the full set of rows,
   * which is required because Cartesian product calls start() on sub-plans
   * multiple times (once per element of the preceding sub-plan's stream).
   */
  private InternalExecutionPlan planProducing(CommandContext ctx, List<ResultInternal> rows) {
    var plan = new SelectExecutionPlan(ctx);
    plan.chain(sourceStep(ctx, rows));
    return plan;
  }

  /**
   * Creates a re-startable source step that produces copies of the given rows
   * on every call to start().
   */
  private ExecutionStepInternal sourceStep(CommandContext ctx, List<ResultInternal> rows) {
    return new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStep copy(CommandContext c) {
        return sourceStep(c, rows);
      }

      @Override
      public ExecutionStream internalStart(CommandContext c) {
        return ExecutionStream.resultIterator(copyRows(c, rows).iterator());
      }
    };
  }

  /**
   * Creates fresh copies of the given rows so that each start() call produces
   * independent ResultInternal objects.
   */
  private List<Result> copyRows(CommandContext ctx, List<ResultInternal> rows) {
    List<Result> results = new ArrayList<>();
    for (var row : rows) {
      var copy = new ResultInternal(ctx.getDatabaseSession());
      for (var prop : row.getPropertyNames()) {
        copy.setProperty(prop, row.getProperty(prop));
      }
      results.add(copy);
    }
    return results;
  }

  /**
   * Creates a plan that reports canBeCached() == true.
   */
  private InternalExecutionPlan cacheablePlan(CommandContext ctx) {
    return planWithCacheability(ctx, true);
  }

  /**
   * Creates a plan that reports canBeCached() == false.
   */
  private InternalExecutionPlan nonCacheablePlan(CommandContext ctx) {
    return planWithCacheability(ctx, false);
  }

  /**
   * Creates a plan whose canBeCached() returns the given value.
   */
  private InternalExecutionPlan planWithCacheability(CommandContext ctx, boolean cacheable) {
    var plan = new SelectExecutionPlan(ctx);
    plan.chain(new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStep copy(CommandContext c) {
        throw new UnsupportedOperationException();
      }

      @Override
      public ExecutionStream internalStart(CommandContext c) {
        return ExecutionStream.empty();
      }

      @Override
      public boolean canBeCached() {
        return cacheable;
      }
    });
    return plan;
  }

  /**
   * Creates a plan whose start() tracks when its stream is closed.
   */
  private InternalExecutionPlan planWithCloseTracking(
      CommandContext ctx, List<ResultInternal> rows, AtomicBoolean closeFlag) {
    var plan = new SelectExecutionPlan(ctx);
    plan.chain(new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStep copy(CommandContext c) {
        throw new UnsupportedOperationException();
      }

      @Override
      public ExecutionStream internalStart(CommandContext c) {
        return ExecutionStream.resultIterator(copyRows(c, rows).iterator())
            .onClose((context) -> closeFlag.set(true));
      }
    });
    return plan;
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
}
