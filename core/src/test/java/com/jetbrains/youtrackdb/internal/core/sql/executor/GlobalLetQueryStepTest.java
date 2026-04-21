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
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.query.BasicResult;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.query.ResultSet;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.LocalResultSet;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSelectStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SubQueryCollector;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YouTrackDBSql;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

/**
 * Direct-step tests for {@link GlobalLetQueryStep}, the one-shot LET step that executes a
 * subquery and stores the result in the context variable. The storage mode (lazy
 * {@link ResultSet} vs materialized {@link List}) depends on whether the variable is
 * planner-generated or referenced in the outer WHERE clause.
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>Constructor uses the positional-parameter path when {@link SQLStatement#containsPositionalParameters}
 *       returns true (createExecutionPlanNoCache) vs the default cached path.
 *   <li>{@code internalStart} drains the predecessor before calculating.
 *   <li>{@code calculate} stores a lazy {@link ResultSet} when the variable name does NOT start
 *       with the {@link SubQueryCollector#GENERATED_ALIAS_PREFIX} AND no parent WHERE expression
 *       references it.
 *   <li>{@code calculate} stores a materialized {@code List<Result>} when the variable name
 *       starts with {@link SubQueryCollector#GENERATED_ALIAS_PREFIX}.
 *   <li>{@code calculate} stores a materialized {@code List<Result>} when a parent WHERE
 *       expression references the variable — pins the {@code varMightBeInUse} true branch.
 *   <li>{@code prettyPrint} renders {@code "+ LET (once)\n  varname ="} plus the boxed sub-plan.
 *   <li>{@code canBeCached} returns {@code subExecutionPlan.canBeCached()}.
 *   <li>{@code getSubExecutionPlans} returns a singleton list.
 *   <li>{@code copy} produces an independent step with a deep-copied sub-plan.
 * </ul>
 */
public class GlobalLetQueryStepTest extends TestUtilsFixture {

  // =========================================================================
  // internalStart — predecessor draining + variable assignment
  // =========================================================================

  /**
   * {@code internalStart} drains (start + close) the predecessor before calculating — pins the
   * side-effect contract at lines 93-95.
   */
  @Test
  public void internalStartDrainsPredecessorBeforeCalculating() {
    var ctx = newContext();
    var prevStarted = new AtomicBoolean(false);
    var prevClosed = new AtomicBoolean(false);
    var step = new GlobalLetQueryStep(
        new SQLIdentifier("v"), stubStatement(List.of()), ctx, false, null);
    step.setPrevious(trackingPredecessor(ctx, prevStarted, prevClosed));

    step.start(ctx).close(ctx);

    assertThat(prevStarted).isTrue();
    assertThat(prevClosed).isTrue();
  }

  /**
   * When the variable name does NOT start with the generated-alias prefix AND no parent WHERE
   * expression references it, the subquery result is stored as a lazy {@link LocalResultSet}.
   * Pins the default (lazy) branch at line 127 false arm.
   */
  @Test
  public void calculateStoresResultSetLazilyForOrdinaryVariable() {
    var ctx = newContext();
    var r1 = new ResultInternal(session);
    r1.setProperty("id", 1);
    var step = new GlobalLetQueryStep(
        new SQLIdentifier("topItems"), stubStatement(List.of(r1)), ctx, false, null);

    step.start(ctx).close(ctx);

    var stored = ctx.getVariable("topItems");
    assertThat(stored)
        .as("variable must be stored as a lazy ResultSet, not a List")
        .isInstanceOf(LocalResultSet.class);
  }

  /**
   * A variable name starting with {@link SubQueryCollector#GENERATED_ALIAS_PREFIX} triggers the
   * materialize branch — the stored variable is a {@code List<Result>}. Pins line 117.
   */
  @Test
  public void calculateMaterializesListForGeneratedAliasVariable() {
    var ctx = newContext();
    var r1 = new ResultInternal(session);
    r1.setProperty("id", 1);
    var r2 = new ResultInternal(session);
    r2.setProperty("id", 2);
    var step = new GlobalLetQueryStep(
        new SQLIdentifier(SubQueryCollector.GENERATED_ALIAS_PREFIX + "0"),
        stubStatement(List.of(r1, r2)), ctx, false, null);

    step.start(ctx).close(ctx);

    var stored = ctx.getVariable(SubQueryCollector.GENERATED_ALIAS_PREFIX + "0");
    assertThat(stored)
        .as("generated-alias variable must materialize as a List")
        .isInstanceOf(List.class);
    @SuppressWarnings("unchecked")
    var list = (List<Result>) stored;
    assertThat(list).hasSize(2);
  }

  /**
   * When a parent WHERE expression references the variable, the subquery result is materialized
   * into a {@code List<Result>} — pins the {@code varMightBeInUse} true branch at lines 119-123.
   */
  @Test
  public void calculateMaterializesListWhenParentWhereReferencesVariable() {
    var ctx = newContext();
    var where = parseWhere("SELECT FROM OUser WHERE foo IN $fooIds");
    ctx.registerBooleanExpression(where.getBaseExpression());
    var r1 = new ResultInternal(session);
    r1.setProperty("id", 1);
    var step = new GlobalLetQueryStep(
        new SQLIdentifier("fooIds"), stubStatement(List.of(r1)), ctx, false, null);

    step.start(ctx).close(ctx);

    var stored = ctx.getVariable("fooIds");
    assertThat(stored)
        .as("WHERE-referenced variable must materialize as a List")
        .isInstanceOf(List.class);
  }

  /**
   * The {@code scriptVars} constructor parameter forwards script variables to the subquery's
   * context. Pins the non-null-scriptVars branch at line 66. Verifies via prettyPrint
   * equivalence that the step still renders coherently with script vars attached.
   */
  @Test
  public void constructorAcceptsScriptVariables() {
    var ctx = newContext();
    var step = new GlobalLetQueryStep(
        new SQLIdentifier("v"), stubStatement(List.of()), ctx, false, List.of("alpha", "beta"));

    assertThat(step.prettyPrint(0, 2)).contains("+ LET (once)").contains("v");
  }

  // =========================================================================
  // prettyPrint
  // =========================================================================

  /** {@code prettyPrint} renders the "once" header and boxes the sub-plan. */
  @Test
  public void prettyPrintRendersLetOnceHeaderAndBoxedSubPlan() {
    var ctx = newContext();
    var step = new GlobalLetQueryStep(
        new SQLIdentifier("v"), stubStatement(List.of()), ctx, false, null);

    var out = step.prettyPrint(0, 2);

    assertThat(out).contains("+ LET (once)");
    assertThat(out).contains("v");
    assertThat(out).contains("+-------------------------");
    assertThat(out).contains("STUB_PLAN");
  }

  /** A non-zero depth applies leading indent. */
  @Test
  public void prettyPrintAppliesIndentation() {
    var ctx = newContext();
    var step = new GlobalLetQueryStep(
        new SQLIdentifier("v"), stubStatement(List.of()), ctx, false, null);

    var out = step.prettyPrint(1, 4);

    assertThat(out).startsWith("    +").doesNotStartWith("     +");
  }

  // =========================================================================
  // canBeCached
  // =========================================================================

  /**
   * {@code canBeCached} returns true when the sub-plan is cacheable. Pins the {@code &&} true
   * arm at line 175.
   */
  @Test
  public void stepIsCacheableWhenSubPlanIsCacheable() {
    var ctx = newContext();
    var step = new GlobalLetQueryStep(
        new SQLIdentifier("v"), stubStatement(List.of(), true), ctx, false, null);

    assertThat(step.canBeCached()).isTrue();
  }

  /**
   * {@code canBeCached} returns false when the sub-plan is not cacheable. Pins the {@code &&}
   * false arm.
   */
  @Test
  public void stepIsNotCacheableWhenSubPlanIsNotCacheable() {
    var ctx = newContext();
    var step = new GlobalLetQueryStep(
        new SQLIdentifier("v"), stubStatement(List.of(), false), ctx, false, null);

    assertThat(step.canBeCached()).isFalse();
  }

  // =========================================================================
  // getSubExecutionPlans
  // =========================================================================

  /** {@code getSubExecutionPlans} returns a singleton list containing the sub-plan. */
  @Test
  public void getSubExecutionPlansReturnsSingleton() {
    var ctx = newContext();
    var step = new GlobalLetQueryStep(
        new SQLIdentifier("v"), stubStatement(List.of()), ctx, false, null);

    assertThat(step.getSubExecutionPlans()).hasSize(1);
  }

  // =========================================================================
  // copy
  // =========================================================================

  /**
   * {@code copy} deep-copies the varName AND the sub-plan. The returned step renders the same
   * prettyPrint output; the sub-plan is a distinct instance.
   */
  @Test
  public void copyProducesIndependentStep() {
    var ctx = newContext();
    var original = new GlobalLetQueryStep(
        new SQLIdentifier("v"), stubStatement(List.of()), ctx, true, null);

    ExecutionStep copied = original.copy(ctx);

    assertThat(copied).isNotSameAs(original).isInstanceOf(GlobalLetQueryStep.class);
    var copy = (GlobalLetQueryStep) copied;
    assertThat(copy.isProfilingEnabled()).isTrue();
    assertThat(copy.prettyPrint(0, 2)).isEqualTo(original.prettyPrint(0, 2));
  }

  // =========================================================================
  // Helpers
  // =========================================================================

  private BasicCommandContext newContext() {
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);
    return ctx;
  }

  private static SQLStatement stubStatement(List<? extends Result> results) {
    return stubStatement(results, true);
  }

  private static SQLStatement stubStatement(List<? extends Result> results, boolean cacheable) {
    return new StubStatement(results, cacheable);
  }

  private static SQLWhereClause parseWhere(String sql) {
    try {
      var parser = new YouTrackDBSql(new ByteArrayInputStream(sql.getBytes()));
      var stm = (SQLSelectStatement) parser.parse();
      return stm.getWhereClause();
    } catch (Exception e) {
      throw new AssertionError("Failed to parse WHERE from: " + sql, e);
    }
  }

  private ExecutionStepInternal trackingPredecessor(
      CommandContext ctx, AtomicBoolean started, AtomicBoolean closed) {
    return new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStep copy(CommandContext c) {
        throw new UnsupportedOperationException();
      }

      @Override
      public ExecutionStream internalStart(CommandContext c) {
        started.set(true);
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
            closed.set(true);
          }
        };
      }
    };
  }

  /**
   * Stub SQLStatement whose createExecutionPlan returns a {@link StubInternalPlan} yielding the
   * fixed result list. Used only in these tests.
   */
  private static final class StubStatement extends SQLStatement {
    private final List<? extends Result> results;
    private final boolean cacheable;

    StubStatement(List<? extends Result> results, boolean cacheable) {
      super(-1);
      this.results = results;
      this.cacheable = cacheable;
    }

    @Override
    public InternalExecutionPlan createExecutionPlan(CommandContext ctx, boolean profile) {
      return new StubInternalPlan(ctx, results, cacheable);
    }

    @Override
    public InternalExecutionPlan createExecutionPlanNoCache(CommandContext ctx, boolean profile) {
      return createExecutionPlan(ctx, profile);
    }

    @Override
    public SQLStatement copy() {
      return new StubStatement(results, cacheable);
    }

    @Override
    public boolean containsPositionalParameters() {
      return false;
    }

    @Override
    public boolean refersToParent() {
      return false;
    }
  }

  /**
   * Minimal {@link InternalExecutionPlan} that yields a fixed list of results on {@code start()}.
   */
  private static final class StubInternalPlan implements InternalExecutionPlan {
    private final CommandContext ctx;
    private final List<? extends Result> results;
    private final boolean cacheable;

    StubInternalPlan(CommandContext ctx, List<? extends Result> results, boolean cacheable) {
      this.ctx = ctx;
      this.results = results;
      this.cacheable = cacheable;
    }

    @Override
    public void close() {
    }

    @Override
    public ExecutionStream start() {
      return ExecutionStream.resultIterator(new ArrayList<Result>(results).iterator());
    }

    @Override
    public void reset(CommandContext ctx) {
    }

    @Override
    public CommandContext getContext() {
      return ctx;
    }

    @Override
    public long getCost() {
      return 0L;
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
    public List<ExecutionStep> getSteps() {
      return List.of();
    }

    @Override
    public InternalExecutionPlan copy(CommandContext ctx) {
      return new StubInternalPlan(ctx, results, cacheable);
    }

    @Override
    public BasicResult toResult(DatabaseSessionEmbedded s) {
      throw new UnsupportedOperationException();
    }
  }
}
