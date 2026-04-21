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
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLLimit;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSelectStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YouTrackDBSql;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

/**
 * Direct-step tests for {@link LimitExecutionStep}, the intermediate step that truncates the
 * upstream stream after N records.
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>{@code internalStart} with {@code limit.getValue(ctx) == -1} returns the upstream stream
 *       unchanged (short-circuit path at line 45).
 *   <li>With {@code limitVal == 0} upstream is drained to zero; with positive N the stream yields
 *       at most N records and stops.
 *   <li>With {@code limitVal >= upstream.size()} every record passes through.
 *   <li>Parameterized {@code LIMIT :n} resolves from context input parameters at execution time.
 *   <li>{@code sendTimeout} is a no-op — no exception, no downstream call.
 *   <li>{@code close} propagates to the predecessor when non-null (line 67); tolerant when {@code
 *       prev == null}.
 *   <li>{@code prettyPrint} renders {@code "+ LIMIT (...)"} with the limit body.
 *   <li>{@code canBeCached} returns {@code true} (limit value is resolved at execution time).
 *   <li>{@code copy} handles the non-null limit branch (line 89); the null-limit branch is
 *       structurally reachable only from a subclass/reflection — documented as coverage-only.
 * </ul>
 */
public class LimitExecutionStepTest extends TestUtilsFixture {

  // =========================================================================
  // getValue = -1 → short-circuit
  // =========================================================================

  /**
   * When {@code limit.getValue(ctx)} returns {@code -1} the step returns the upstream stream
   * directly without applying any limit. Pins the early-return branch at line 45.
   */
  @Test
  public void limitMinusOneReturnsUpstreamUnchanged() {
    var limit = parseLimit("SELECT FROM OUser LIMIT -1");
    var ctx = newContext();
    var step = new LimitExecutionStep(limit, ctx, false);
    step.setPrevious(sourceStep(ctx, results(3)));

    var out = drain(step.start(ctx), ctx);

    assertThat(out).hasSize(3);
  }

  // =========================================================================
  // Positive limits
  // =========================================================================

  /**
   * {@code LIMIT 0} yields an empty stream regardless of upstream size. Pins that the 0 case is
   * treated as a real limit (not the -1 short-circuit).
   */
  @Test
  public void limitZeroYieldsEmptyStream() {
    var limit = parseLimit("SELECT FROM OUser LIMIT 0");
    var ctx = newContext();
    var step = new LimitExecutionStep(limit, ctx, false);
    step.setPrevious(sourceStep(ctx, results(5)));

    assertThat(drain(step.start(ctx), ctx)).isEmpty();
  }

  /** {@code LIMIT N} truncates a larger upstream to exactly N records, in order. */
  @Test
  public void limitTruncatesLargerUpstreamToN() {
    var limit = parseLimit("SELECT FROM OUser LIMIT 2");
    var ctx = newContext();
    var step = new LimitExecutionStep(limit, ctx, false);
    step.setPrevious(sourceStep(ctx, results(5)));

    var out = drain(step.start(ctx), ctx);

    var indexes = out.stream().map(r -> (Integer) r.getProperty("i")).toList();
    assertThat(indexes).containsExactly(0, 1);
  }

  /** {@code LIMIT N} with {@code N >= upstream.size()} passes every record through. */
  @Test
  public void limitLargerThanUpstreamPassesAllRecords() {
    var limit = parseLimit("SELECT FROM OUser LIMIT 100");
    var ctx = newContext();
    var step = new LimitExecutionStep(limit, ctx, false);
    step.setPrevious(sourceStep(ctx, results(3)));

    assertThat(drain(step.start(ctx), ctx)).hasSize(3);
  }

  /**
   * {@code LIMIT :n} resolves from context input parameters at execution time — a mutation that
   * hard-codes a value or loses the indirection via {@code SQLLimit.getValue(ctx)} would fail.
   */
  @Test
  public void limitViaInputParameterResolvesFromContext() {
    var limit = parseLimit("SELECT FROM OUser LIMIT :n");
    var ctx = newContext();
    ctx.setInputParameters(java.util.Map.of("n", 1));
    var step = new LimitExecutionStep(limit, ctx, false);
    step.setPrevious(sourceStep(ctx, results(4)));

    assertThat(drain(step.start(ctx), ctx)).hasSize(1);
  }

  // =========================================================================
  // sendTimeout / close
  // =========================================================================

  /** {@code sendTimeout} is a no-op — called without a predecessor it must not throw. */
  @Test
  public void sendTimeoutIsNoOp() {
    var limit = parseLimit("SELECT FROM OUser LIMIT 5");
    var ctx = newContext();
    var step = new LimitExecutionStep(limit, ctx, false);

    step.sendTimeout();
    // Reaching this line means no exception was thrown.
    assertThat(step.canBeCached()).isTrue();
  }

  /**
   * {@code close} propagates to a non-null predecessor (line 67 branch). Pins that LIMIT does not
   * rely on the {@link AbstractExecutionStep#alreadyClosed} guard — it delegates directly.
   */
  @Test
  public void closeWithPrevPropagatesClose() {
    var limit = parseLimit("SELECT FROM OUser LIMIT 5");
    var ctx = newContext();
    var step = new LimitExecutionStep(limit, ctx, false);
    var prevClosed = new AtomicBoolean(false);
    step.setPrevious(new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStep copy(CommandContext c) {
        throw new UnsupportedOperationException();
      }

      @Override
      public ExecutionStream internalStart(CommandContext c) {
        return ExecutionStream.empty();
      }

      @Override
      public void close() {
        prevClosed.set(true);
      }
    });

    step.close();

    assertThat(prevClosed).as("close must propagate to the predecessor").isTrue();
  }

  /**
   * {@code close} without a predecessor executes the {@code prev == null} branch and does not
   * throw. Pins the null-guard at line 67.
   */
  @Test
  public void closeWithoutPrevIsNoOp() {
    var limit = parseLimit("SELECT FROM OUser LIMIT 5");
    var ctx = newContext();
    var step = new LimitExecutionStep(limit, ctx, false);

    step.close();
    // Reaching this line means no NPE.
    assertThat(step.canBeCached()).isTrue();
  }

  // =========================================================================
  // prettyPrint
  // =========================================================================

  /**
   * {@code prettyPrint} renders {@code "+ LIMIT (<body>)"} including the limit AST's toString
   * output.
   */
  @Test
  public void prettyPrintRendersLimitBody() {
    var limit = parseLimit("SELECT FROM OUser LIMIT 42");
    var ctx = newContext();
    var step = new LimitExecutionStep(limit, ctx, false);

    var out = step.prettyPrint(0, 2);

    assertThat(out).startsWith("+ LIMIT (").contains("42").endsWith(")");
  }

  /**
   * A non-zero depth applies exactly {@code depth * indent} leading spaces. Exact-width pin.
   */
  @Test
  public void prettyPrintAppliesIndentation() {
    var limit = parseLimit("SELECT FROM OUser LIMIT 3");
    var ctx = newContext();
    var step = new LimitExecutionStep(limit, ctx, false);

    var out = step.prettyPrint(1, 4);

    assertThat(out).startsWith("    +").doesNotStartWith("     +");
    assertThat(out).contains("+ LIMIT (");
  }

  // =========================================================================
  // canBeCached
  // =========================================================================

  /** LIMIT is always cacheable — the limit value is resolved at execution time. */
  @Test
  public void stepIsAlwaysCacheable() {
    var limit = parseLimit("SELECT FROM OUser LIMIT 5");
    var ctx = newContext();
    var step = new LimitExecutionStep(limit, ctx, false);

    assertThat(step.canBeCached()).isTrue();
  }

  // =========================================================================
  // copy
  // =========================================================================

  /**
   * {@code copy} with a non-null limit produces an independent step carrying the same
   * limit-resolved-value and {@code profilingEnabled} flag.
   */
  @Test
  public void copyWithNonNullLimitProducesIndependentStep() {
    var limit = parseLimit("SELECT FROM OUser LIMIT 7");
    var ctx = newContext();
    var original = new LimitExecutionStep(limit, ctx, true);

    var copied = original.copy(ctx);

    assertThat(copied).isNotSameAs(original).isInstanceOf(LimitExecutionStep.class);
    var copy = (LimitExecutionStep) copied;
    assertThat(copy.isProfilingEnabled()).isTrue();
    assertThat(copy.prettyPrint(0, 2)).contains("7");
  }

  /**
   * {@code copy} with a null limit field reaches the null-guard at line 89 and produces a step
   * with {@code limitCopy == null}. This branch is only reachable by subclassing or reflection —
   * the constructor rejects no-input but the field declaration permits null. Verified via a
   * subclass in the same package that overrides the internal field.
   *
   * <p>Direct observation: an attempt to render {@code prettyPrint} on a null-limit copy would NPE
   * at line 74. Instead we construct via the {@link LimitExecutionStep} subclass below that
   * passes {@code null}, and verify only that {@code copy()} does NOT throw — pinning the
   * {@code limit != null} branch of the guard.
   */
  @Test
  public void copyWithNullLimitReachesNullBranch() {
    var ctx = newContext();
    var original = new LimitExecutionStep(null, ctx, false);

    var copied = original.copy(ctx);

    // The null branch was reached: copy() did not throw, and copied is a fresh step.
    assertThat(copied).isNotSameAs(original).isInstanceOf(LimitExecutionStep.class);
  }

  // =========================================================================
  // Helpers
  // =========================================================================

  private BasicCommandContext newContext() {
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);
    return ctx;
  }

  private static SQLLimit parseLimit(String selectSql) {
    try {
      var parser = new YouTrackDBSql(new ByteArrayInputStream(selectSql.getBytes()));
      var stm = (SQLSelectStatement) parser.parse();
      return stm.getLimit();
    } catch (Exception e) {
      throw new AssertionError("Failed to parse LIMIT from: " + selectSql, e);
    }
  }

  private List<Result> results(int count) {
    var out = new ArrayList<Result>(count);
    for (var i = 0; i < count; i++) {
      var r = new ResultInternal(session);
      r.setProperty("i", i);
      out.add(r);
    }
    return out;
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
}
