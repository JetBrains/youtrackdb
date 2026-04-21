/*
 * Copyright 2018 YouTrackDB
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Direct-step tests for {@link ParallelExecStep}, the source step that executes multiple sub-plans
 * and concatenates their result streams (sequentially, despite the "Parallel" name).
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>{@code internalStart} drains the predecessor for side effects when {@code prev != null}
 *       (line 57 true branch) and is a no-op when prev is null (false branch).
 *   <li>Sub-plans are executed <b>in declared order</b> and their results are concatenated —
 *       this pins the A10 adversarial concern: a refactor that parallelized execution or
 *       reordered sub-plans would fail this test.
 *   <li>{@code canBeCached} returns {@code true} iff every sub-plan is cacheable (line 198 loop);
 *       a single non-cacheable sub-plan forces {@code false}; an empty list is vacuously true.
 *   <li>{@code prettyPrint} renders the "+ PARALLEL" header, "+---" block separators, "| "
 *       pipes, the "V" foot markers, and ASCII arrow/junction rendering (exercises {@code
 *       addArrows} plus {@code isHorizontalRow} / {@code isPlus} / {@code isVerticalRow} for
 *       sub-plan counts 1, 2, 3, and 5).
 *   <li>{@code getSubExecutionPlans} exposes the sub-plan list (covariance cast).
 *   <li>{@code copy} produces a distinct step whose sub-plan list is a copied list (not the same
 *       instance), with the same size.
 * </ul>
 */
public class ParallelExecStepTest {

  // =========================================================================
  // internalStart — predecessor drain
  // =========================================================================

  /**
   * With {@code prev == null} (the {@code ParallelExecStep} is a source step), {@code
   * internalStart} skips the drain and goes straight to sub-plan iteration. Pins the false
   * branch at line 57.
   */
  @Test
  public void internalStartWithoutPrevEmitsSubPlanResults() {
    var ctx = new BasicCommandContext();
    var sub1 = planYielding(ctx, "a1", "a2");
    var sub2 = planYielding(ctx, "b1");

    var step = new ParallelExecStep(new ArrayList<>(List.of(sub1, sub2)), ctx, false);

    var results = drain(step.start(ctx), ctx);

    assertThat(results.stream().map(r -> (String) r.getProperty("v")).toList())
        .containsExactly("a1", "a2", "b1");
  }

  /**
   * With a non-null predecessor, {@code internalStart} calls {@code prev.start(ctx).close(ctx)}
   * before iterating sub-plans. Pins the true branch at line 57 and the drain-then-close
   * contract.
   */
  @Test
  public void internalStartWithPrevDrainsPredecessor() {
    var ctx = new BasicCommandContext();
    var sub1 = planYielding(ctx, "only");
    var step = new ParallelExecStep(new ArrayList<>(List.of(sub1)), ctx, false);

    var prevStarted = new AtomicBoolean(false);
    var prevClosed = new AtomicBoolean(false);
    step.setPrevious(new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStep copy(CommandContext c) {
        throw new UnsupportedOperationException();
      }

      @Override
      public ExecutionStream internalStart(CommandContext c) {
        prevStarted.set(true);
        return new ExecutionStream() {
          @Override
          public boolean hasNext(CommandContext c2) {
            return false;
          }

          @Override
          public Result next(CommandContext c2) {
            throw new UnsupportedOperationException();
          }

          @Override
          public void close(CommandContext c2) {
            prevClosed.set(true);
          }
        };
      }
    });

    drain(step.start(ctx), ctx);

    assertThat(prevStarted).as("predecessor must be started").isTrue();
    assertThat(prevClosed).as("predecessor stream must be closed").isTrue();
  }

  // =========================================================================
  // Declared-order concatenation (A10 pin)
  // =========================================================================

  /**
   * Sub-plan results are concatenated in declared order: plan[0] first, then plan[1], etc. A10
   * pin — if a refactor parallelized execution or reordered sub-plans, this test fails because
   * the interleaved output would not match the strict ordering.
   */
  @Test
  public void subPlansExecuteInDeclaredOrder() {
    var ctx = new BasicCommandContext();
    var plan0 = planYielding(ctx, "alpha1", "alpha2", "alpha3");
    var plan1 = planYielding(ctx, "beta1", "beta2");
    var plan2 = planYielding(ctx, "gamma1");

    var step = new ParallelExecStep(List.of(plan0, plan1, plan2), ctx, false);

    var names = drain(step.start(ctx), ctx).stream()
        .map(r -> (String) r.getProperty("v"))
        .toList();

    assertThat(names).containsExactly("alpha1", "alpha2", "alpha3", "beta1", "beta2", "gamma1");
  }

  // =========================================================================
  // canBeCached
  // =========================================================================

  /** With all sub-plans cacheable, the aggregate step is cacheable. */
  @Test
  public void canBeCachedTrueWhenEverySubPlanIsCacheable() {
    var ctx = new BasicCommandContext();
    var plans = List.of(planYielding(ctx, "x"), planYielding(ctx, "y"));

    var step = new ParallelExecStep(plans, ctx, false);

    assertThat(step.canBeCached()).isTrue();
  }

  /**
   * A single non-cacheable sub-plan is enough to force the aggregate to non-cacheable — short-
   * circuits the loop on line 199.
   */
  @Test
  public void canBeCachedFalseWhenAnySubPlanIsNotCacheable() {
    var ctx = new BasicCommandContext();
    var cacheablePlan = planYielding(ctx, "x");
    var nonCacheablePlan = nonCacheablePlan(ctx);

    var step = new ParallelExecStep(List.of(cacheablePlan, nonCacheablePlan), ctx, false);

    assertThat(step.canBeCached()).isFalse();
  }

  /** An empty sub-plan list is vacuously cacheable — the loop body never runs. */
  @Test
  public void canBeCachedTrueForEmptySubPlanList() {
    var ctx = new BasicCommandContext();

    var step = new ParallelExecStep(List.of(), ctx, false);

    assertThat(step.canBeCached()).isTrue();
  }

  /**
   * With an empty sub-plan list, {@code internalStart} returns a {@link
   * com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.MultipleExecutionStream} whose
   * producer has no elements — the resulting stream has {@code hasNext=false} and emits no
   * records. Pins that the empty-list case does not throw.
   */
  @Test
  public void internalStartWithEmptySubPlanListProducesEmptyStream() {
    var ctx = new BasicCommandContext();
    var step = new ParallelExecStep(List.of(), ctx, false);

    assertThat(drain(step.start(ctx), ctx)).isEmpty();
  }

  /**
   * {@code prettyPrint} with an empty sub-plan list renders only the {@code "+ PARALLEL"}
   * header, with no block separators. Exercises the empty-for-loop branch at line 94.
   */
  @Test
  public void prettyPrintWithEmptySubPlanListRendersOnlyHeader() {
    var ctx = new BasicCommandContext();
    var out = new ParallelExecStep(List.of(), ctx, false).prettyPrint(0, 2);

    assertThat(out).contains("+ PARALLEL");
    assertThat(out).doesNotContain("+-------------------------");
  }

  // =========================================================================
  // prettyPrint
  // =========================================================================

  /**
   * With a single sub-plan, {@code prettyPrint} renders the "+ PARALLEL" header, a pair of
   * "+---" separators around the sub-plan, "|" pipes, and one "V" at the foot.
   */
  @Test
  public void prettyPrintWithOneSubPlanRendersHeaderAndFootMarkers() {
    var ctx = new BasicCommandContext();
    var plan = planYielding(ctx, "a");
    var step = new ParallelExecStep(List.of(plan), ctx, false);

    var out = step.prettyPrint(0, 2);

    assertThat(out).contains("+ PARALLEL");
    assertThat(out).contains("+-------------------------");
    assertThat(out).contains("|");
    // Exactly one "V" for one sub-plan.
    assertThat(countOccurrences(out, " V ")).isEqualTo(1);
  }

  /** With two sub-plans, the foot shows two "V" markers. */
  @Test
  public void prettyPrintWithTwoSubPlansRendersTwoFootMarkers() {
    var ctx = new BasicCommandContext();
    var plans = List.of(planYielding(ctx, "a"), planYielding(ctx, "b"));

    var out = new ParallelExecStep(plans, ctx, false).prettyPrint(0, 2);

    assertThat(out).contains("+ PARALLEL");
    assertThat(countOccurrences(out, " V ")).isEqualTo(2);
  }

  /** With three sub-plans, the foot shows three "V" markers. */
  @Test
  public void prettyPrintWithThreeSubPlansRendersThreeFootMarkers() {
    var ctx = new BasicCommandContext();
    var plans = List.of(planYielding(ctx, "a"), planYielding(ctx, "b"), planYielding(ctx, "c"));

    var out = new ParallelExecStep(plans, ctx, false).prettyPrint(0, 2);

    assertThat(countOccurrences(out, " V ")).isEqualTo(3);
  }

  /**
   * With five sub-plans, the foot shows five "V" markers. Pins the {@code addArrows} rendering
   * pipeline: a weaker "contains - + |" check passes even if {@code addArrows} returned its
   * input unchanged (the block separator {@code +-------------------------} and pipe prefix
   * {@code "| "} already contain those chars). A genuine {@code addArrows}-produced junction is
   * a "+" preceded by a space at a non-zero column — that pattern only appears when the arrow
   * pipeline actually ran. A mutation to {@code addArrows} that returned its input unmodified
   * would fail this assertion.
   */
  @Test
  public void prettyPrintWithFiveSubPlansRendersFiveFootMarkersAndArrowJunctions() {
    var ctx = new BasicCommandContext();
    var plans = List.of(
        planYielding(ctx, "a"),
        planYielding(ctx, "b"),
        planYielding(ctx, "c"),
        planYielding(ctx, "d"),
        planYielding(ctx, "e"));

    var out = new ParallelExecStep(plans, ctx, false).prettyPrint(0, 2);

    assertThat(countOccurrences(out, " V ")).isEqualTo(5);

    var sawJunctionAtNonZeroColumn = out.lines().anyMatch(row -> {
      for (var col = 1; col < row.length(); col++) {
        if (row.charAt(col) == '+' && row.charAt(col - 1) == ' ') {
          return true;
        }
      }
      return false;
    });
    assertThat(sawJunctionAtNonZeroColumn)
        .as("addArrows must render at least one junction '+' at a non-zero column")
        .isTrue();
  }

  /** A non-zero depth prepends indentation to every rendered line. */
  @Test
  public void prettyPrintAppliesIndentation() {
    var ctx = new BasicCommandContext();
    var plan = planYielding(ctx, "a");

    var out = new ParallelExecStep(List.of(plan), ctx, false).prettyPrint(1, 4);

    assertThat(out).startsWith("    +").doesNotStartWith("     +");
  }

  // =========================================================================
  // getSubExecutionPlans / copy
  // =========================================================================

  /**
   * {@code getSubExecutionPlans} returns the raw sub-plan list (covariant cast). The returned
   * list has the same size as the configured list.
   */
  @Test
  public void getSubExecutionPlansReturnsConfiguredList() {
    var ctx = new BasicCommandContext();
    var plans = List.of(planYielding(ctx, "a"), planYielding(ctx, "b"));

    var step = new ParallelExecStep(plans, ctx, false);

    assertThat(step.getSubExecutionPlans()).hasSize(2);
  }

  /**
   * {@code copy} produces a distinct step whose sub-plan list is a fresh instance (not an alias)
   * AND whose sub-plans are each independently copied (via {@code plan.copy(ctx)}). Without the
   * two {@code isNotSameAs} pins below, a mutation that returned {@code new ParallelExecStep
   * (this.subExecutionPlans, ctx, profilingEnabled)} (aliasing the list) or that mapped without
   * calling {@code x.copy(ctx)} (aliasing each element) would silently pass a size-only check.
   */
  @Test
  public void copyProducesIndependentStepWithCopiedSubPlans() {
    var ctx = new BasicCommandContext();
    var plans = List.of(planYielding(ctx, "a"), planYielding(ctx, "b"));
    var original = new ParallelExecStep(plans, ctx, true);

    var copied = original.copy(ctx);

    assertThat(copied).isNotSameAs(original).isInstanceOf(ParallelExecStep.class);
    var copy = (ParallelExecStep) copied;
    assertThat(copy.isProfilingEnabled()).isTrue();
    assertThat(copy.getSubExecutionPlans()).hasSize(2);

    // List-level independence: the copy's sub-plan list must be a fresh instance.
    assertThat(copy.getSubExecutionPlans()).isNotSameAs(original.getSubExecutionPlans());
    // Element-level independence: each sub-plan must have been individually copied.
    assertThat(copy.getSubExecutionPlans().get(0)).isNotSameAs(plans.get(0));
    assertThat(copy.getSubExecutionPlans().get(1)).isNotSameAs(plans.get(1));
  }

  // =========================================================================
  // Helpers
  // =========================================================================

  /** Creates a SelectExecutionPlan whose single step emits one Result per supplied value. */
  private InternalExecutionPlan planYielding(CommandContext ctx, String... values) {
    var plan = new SelectExecutionPlan(ctx);
    plan.chain(new YieldingStubStep(ctx, values));
    return plan;
  }

  /**
   * Creates a plan whose single step reports {@code canBeCached=false}, forcing the plan itself
   * to be non-cacheable.
   */
  private InternalExecutionPlan nonCacheablePlan(CommandContext ctx) {
    var plan = new SelectExecutionPlan(ctx);
    plan.chain(new NonCacheableStubStep(ctx));
    return plan;
  }

  /**
   * Stub step that emits one Result per configured value. Reports {@code canBeCached=true} so
   * plans containing it are themselves cacheable, and implements {@code copy} so {@code
   * ParallelExecStep.copy} (which recursively copies each sub-plan's steps) succeeds.
   */
  private static class YieldingStubStep extends AbstractExecutionStep {
    private final String[] values;

    YieldingStubStep(CommandContext ctx, String[] values) {
      super(ctx, false);
      this.values = values;
    }

    @Override
    public ExecutionStep copy(CommandContext c) {
      return new YieldingStubStep(c, values);
    }

    @Override
    public boolean canBeCached() {
      return true;
    }

    @Override
    public ExecutionStream internalStart(CommandContext c) {
      var rows = new ArrayList<Result>(values.length);
      for (var v : values) {
        var r = new ResultInternal(
            (com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded) null);
        r.setProperty("v", v);
        rows.add(r);
      }
      return ExecutionStream.resultIterator(rows.iterator());
    }
  }

  /** Stub step whose {@code canBeCached} returns {@code false}. */
  private static class NonCacheableStubStep extends AbstractExecutionStep {
    NonCacheableStubStep(CommandContext ctx) {
      super(ctx, false);
    }

    @Override
    public ExecutionStep copy(CommandContext c) {
      return new NonCacheableStubStep(c);
    }

    @Override
    public boolean canBeCached() {
      return false;
    }

    @Override
    public ExecutionStream internalStart(CommandContext c) {
      return ExecutionStream.empty();
    }
  }

  private static List<Result> drain(ExecutionStream stream, CommandContext ctx) {
    var out = new ArrayList<Result>();
    while (stream.hasNext(ctx)) {
      out.add(stream.next(ctx));
    }
    stream.close(ctx);
    return out;
  }

  private static int countOccurrences(String haystack, String needle) {
    var count = 0;
    var idx = 0;
    while ((idx = haystack.indexOf(needle, idx)) != -1) {
      count++;
      idx += needle.length();
    }
    return count;
  }
}
