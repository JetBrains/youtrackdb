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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.query.BasicResult;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * Direct-step tests for {@link LetQueryStep}, the per-record LET step that executes a subquery
 * for each incoming record and stores the result list as metadata.
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>{@code internalStart} throws {@link CommandExecutionException} when no predecessor is
 *       attached.
 *   <li>{@code mapResult / calculate} executes the subquery for each upstream record and stores
 *       the materialized result list as metadata under the variable name.
 *   <li>{@code prettyPrint} renders {@code "+ LET (for each record)\n  varname ="} plus the
 *       boxed preview sub-plan; the preview plan is built lazily on first call.
 *   <li>{@code getSubExecutionPlans} returns a singleton list.
 *   <li>{@code canBeCached} returns {@code true}.
 *   <li>{@code copy} produces an independent step with deep-copied varname + query.
 * </ul>
 */
public class LetQueryStepTest extends TestUtilsFixture {

  // =========================================================================
  // internalStart — prev-null guard
  // =========================================================================

  /**
   * Without a predecessor {@code internalStart} throws {@link CommandExecutionException}. Pins
   * the {@code prev == null} guard at line 138.
   */
  @Test
  public void internalStartWithoutPrevThrowsCommandExecutionException() {
    var ctx = newContext();
    var step = new LetQueryStep(
        new SQLIdentifier("x"), stubStatement(List.of()), ctx, false);

    assertThatThrownBy(() -> step.start(ctx))
        .isInstanceOf(CommandExecutionException.class)
        .hasMessageContaining("LET");
  }

  /**
   * {@code mapResult} executes the subquery for each upstream record and stores the materialized
   * result list as metadata on the record. Pins the calculate path (lines 93-125) including
   * toList conversion.
   */
  @Test
  public void mapResultExecutesSubqueryAndStoresList() {
    var ctx = newContext();
    var subResult = new ResultInternal(session);
    subResult.setProperty("name", "sub");
    var r1 = new ResultInternal(session);
    r1.setProperty("id", 1);
    var r2 = new ResultInternal(session);
    r2.setProperty("id", 2);
    var step = new LetQueryStep(
        new SQLIdentifier("orders"),
        stubStatement(List.of(subResult)), ctx, false);
    step.setPrevious(sourceStep(ctx, List.of(r1, r2)));

    var stream = step.start(ctx);
    var results = drain(stream, ctx);

    assertThat(results).hasSize(2);
    var r1Orders = ((ResultInternal) results.get(0)).getMetadata("orders");
    assertThat(r1Orders).isInstanceOf(List.class);
    @SuppressWarnings("unchecked")
    var r1List = (List<Result>) r1Orders;
    assertThat(r1List).hasSize(1);
    assertThat(r1List.get(0).<String>getProperty("name")).isEqualTo("sub");
  }

  /**
   * Each incoming record triggers a FRESH createExecutionPlan call on the subquery — the subquery
   * is invoked once per outer record. Counting invocations via the stub statement pins the
   * per-record execution contract.
   */
  @Test
  public void mapResultRunsSubqueryOncePerOuterRecord() {
    var ctx = newContext();
    var counter = new AtomicInteger();
    var query = new CountingStubStatement(counter);
    var r1 = new ResultInternal(session);
    r1.setProperty("id", 1);
    var r2 = new ResultInternal(session);
    r2.setProperty("id", 2);
    var r3 = new ResultInternal(session);
    r3.setProperty("id", 3);
    var step = new LetQueryStep(new SQLIdentifier("v"), query, ctx, false);
    step.setPrevious(sourceStep(ctx, List.of(r1, r2, r3)));

    drain(step.start(ctx), ctx);

    assertThat(counter.get())
        .as("subquery plan must be created once per outer record")
        .isEqualTo(3);
  }

  // =========================================================================
  // prettyPrint
  // =========================================================================

  /**
   * {@code prettyPrint} renders the "for each record" header and boxes the preview sub-plan.
   * First call triggers lazy construction of the preview plan.
   */
  @Test
  public void prettyPrintRendersLetHeaderAndBoxedSubPlan() {
    var ctx = newContext();
    var step = new LetQueryStep(
        new SQLIdentifier("orders"), stubStatement(List.of()), ctx, false);

    var out = step.prettyPrint(0, 2);

    assertThat(out).contains("+ LET (for each record)");
    assertThat(out).contains("orders");
    assertThat(out).contains("+-------------------------");
    assertThat(out).contains("STUB_PLAN");
  }

  /** A non-zero depth applies leading indent. */
  @Test
  public void prettyPrintAppliesIndentation() {
    var ctx = newContext();
    var step = new LetQueryStep(
        new SQLIdentifier("v"), stubStatement(List.of()), ctx, false);

    var out = step.prettyPrint(1, 4);

    assertThat(out).startsWith("    +").doesNotStartWith("     +");
  }

  /**
   * {@code getSubExecutionPlans} returns a singleton list containing the preview plan. Pins the
   * lazy-init via {@code getPreviewPlan}.
   */
  @Test
  public void getSubExecutionPlansReturnsSingleton() {
    var ctx = newContext();
    var step = new LetQueryStep(
        new SQLIdentifier("v"), stubStatement(List.of()), ctx, false);

    assertThat(step.getSubExecutionPlans()).hasSize(1);
  }

  // =========================================================================
  // canBeCached
  // =========================================================================

  /** {@code canBeCached} always returns true. */
  @Test
  public void stepIsAlwaysCacheable() {
    var ctx = newContext();
    var step = new LetQueryStep(
        new SQLIdentifier("v"), stubStatement(List.of()), ctx, false);

    assertThat(step.canBeCached()).isTrue();
  }

  // =========================================================================
  // copy
  // =========================================================================

  /**
   * {@code copy} deep-copies varname + query (and the previewPlan if it was already built).
   * Renders the same prettyPrint output; is a distinct instance.
   */
  @Test
  public void copyProducesIndependentStep() {
    var ctx = newContext();
    var original = new LetQueryStep(
        new SQLIdentifier("v"), stubStatement(List.of()), ctx, true);
    // Force previewPlan construction so the copy hits the non-null previewPlan branch.
    original.prettyPrint(0, 2);

    ExecutionStep copied = original.copy(ctx);

    assertThat(copied).isNotSameAs(original).isInstanceOf(LetQueryStep.class);
    var copy = (LetQueryStep) copied;
    assertThat(copy.isProfilingEnabled()).isTrue();
    assertThat(copy.prettyPrint(0, 2)).isEqualTo(original.prettyPrint(0, 2));
  }

  /**
   * {@code copy} on a fresh step (with previewPlan still null) still renders a coherent
   * prettyPrint — pins the {@code previewPlan != null} false branch at line 200.
   */
  @Test
  public void copyWithoutPreBuiltPreviewPlanStillRendersCoherently() {
    var ctx = newContext();
    var original = new LetQueryStep(
        new SQLIdentifier("v"), stubStatement(List.of()), ctx, false);

    ExecutionStep copied = original.copy(ctx);

    assertThat(copied).isInstanceOf(LetQueryStep.class);
    assertThat(((LetQueryStep) copied).prettyPrint(0, 2))
        .contains("+ LET (for each record)").contains("v");
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
    return new StubStatement(results);
  }

  private ExecutionStepInternal sourceStep(CommandContext ctx, List<? extends Result> rows) {
    return new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStep copy(CommandContext c) {
        throw new UnsupportedOperationException();
      }

      @Override
      public ExecutionStream internalStart(CommandContext c) {
        return ExecutionStream.resultIterator(new ArrayList<Result>(rows).iterator());
      }
    };
  }

  private static List<Result> drain(ExecutionStream stream, CommandContext ctx) {
    var out = new ArrayList<Result>();
    while (stream.hasNext(ctx)) {
      out.add(stream.next(ctx));
    }
    stream.close(ctx);
    return out;
  }

  /**
   * Stub SQLStatement that produces a StubInternalPlan yielding a fixed list of results on each
   * {@code createExecutionPlan} call.
   */
  private static final class StubStatement extends SQLStatement {
    private final List<? extends Result> results;

    StubStatement(List<? extends Result> results) {
      super(-1);
      this.results = results;
    }

    @Override
    public InternalExecutionPlan createExecutionPlan(CommandContext ctx, boolean profile) {
      return new StubInternalPlan(ctx, results);
    }

    @Override
    public InternalExecutionPlan createExecutionPlanNoCache(CommandContext ctx, boolean profile) {
      return createExecutionPlan(ctx, profile);
    }

    @Override
    public SQLStatement copy() {
      return new StubStatement(results);
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
   * Variant of StubStatement that counts how many times {@code createExecutionPlan} is invoked —
   * once per outer record for a correctly-implemented LetQueryStep.
   */
  private static final class CountingStubStatement extends SQLStatement {
    private final AtomicInteger counter;

    CountingStubStatement(AtomicInteger counter) {
      super(-1);
      this.counter = counter;
    }

    @Override
    public InternalExecutionPlan createExecutionPlan(CommandContext ctx, boolean profile) {
      counter.incrementAndGet();
      return new StubInternalPlan(ctx, List.of());
    }

    @Override
    public InternalExecutionPlan createExecutionPlanNoCache(CommandContext ctx, boolean profile) {
      return createExecutionPlan(ctx, profile);
    }

    @Override
    public SQLStatement copy() {
      return new CountingStubStatement(counter);
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

  private static final class StubInternalPlan implements InternalExecutionPlan {
    private final CommandContext ctx;
    private final List<? extends Result> results;

    StubInternalPlan(CommandContext ctx, List<? extends Result> results) {
      this.ctx = ctx;
      this.results = results;
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
      return true;
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
      return new StubInternalPlan(ctx, results);
    }

    @Override
    public BasicResult toResult(DatabaseSessionEmbedded s) {
      throw new UnsupportedOperationException();
    }
  }
}
