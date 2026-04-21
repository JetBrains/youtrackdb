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

import static com.jetbrains.youtrackdb.internal.core.sql.executor.TimeoutStepTest.buildTimeout;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.TimeoutResultSet;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLTimeout;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

/**
 * Direct-step tests for {@link AccumulatingTimeoutStep}, the terminal step that tracks total
 * wall-clock time (via {@link TimeoutResultSet}) across the entire upstream pipeline and fires a
 * timeout when cumulative time crosses the configured threshold.
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>{@code internalStart} wraps upstream in a {@link TimeoutResultSet}.
 *   <li>{@code fail()} with {@link SQLTimeout#RETURN} is silent (no exception, sendTimeout not
 *       invoked).
 *   <li>{@code fail()} with a non-RETURN strategy invokes {@code sendTimeout} then throws
 *       {@link TimeoutException}.
 *   <li>{@code prettyPrint} renders {@code "+ TIMEOUT (Nms)"} (note: without a space between N
 *       and "ms", unlike {@link TimeoutStep}).
 *   <li>{@code canBeCached} returns {@code true}.
 *   <li>{@code reset} is a no-op.
 *   <li>{@code copy} deep-copies the {@link SQLTimeout} and carries the {@code profilingEnabled}
 *       flag.
 * </ul>
 */
public class AccumulatingTimeoutStepTest extends TestUtilsFixture {

  // =========================================================================
  // internalStart — wrapping
  // =========================================================================

  /**
   * {@code internalStart} wraps the upstream stream in a {@link TimeoutResultSet}. Upstream
   * records flow through unchanged until the cumulative timeout fires.
   */
  @Test
  public void internalStartWrapsUpstreamInTimeoutResultSet() {
    var ctx = newContext();
    var step = new AccumulatingTimeoutStep(buildTimeout(10_000L, null), ctx, false);
    var r1 = new ResultInternal(session);
    r1.setProperty("id", 1);
    step.setPrevious(sourceStep(ctx, List.of(r1)));

    var stream = step.start(ctx);

    assertThat(stream).isInstanceOf(TimeoutResultSet.class);
    var results = drain(stream, ctx);
    assertThat(results).hasSize(1);
    assertThat(results.get(0).<Integer>getProperty("id")).isEqualTo(1);
  }

  // =========================================================================
  // fail() failure-strategy dispatch
  // =========================================================================

  /**
   * With {@code failureStrategy == RETURN}, the cumulative timeout firing does NOT throw and does
   * NOT invoke {@code sendTimeout}. Pins the RETURN branch at line 46. Uses a slow upstream to
   * ensure totalTime crosses the 0ms threshold so {@code fail()} is actually called.
   */
  @Test
  public void timeoutWithReturnStrategyDoesNotSendTimeoutOrThrow() {
    var ctx = newContext();
    var sentTimeout = new AtomicBoolean(false);
    var r1 = new ResultInternal(session);
    r1.setProperty("id", 1);
    var r2 = new ResultInternal(session);
    r2.setProperty("id", 2);
    var step = new AccumulatingTimeoutStep(buildTimeout(0L, SQLTimeout.RETURN), ctx, false);
    step.setPrevious(slowSendTimeoutPredecessor(ctx, sentTimeout, List.of(r1, r2), 5L));

    var stream = step.start(ctx);

    // hasNext sleeps, accruing >1ms totalTime. The subsequent next() hits the timeout check —
    // but because the strategy is RETURN, fail() is silent. The returned Result is an empty
    // ResultInternal (per TimeoutResultSet.next fallback when timedOut=true), not a throw.
    assertThat(stream.hasNext(ctx)).isTrue();
    assertThatCode(() -> stream.next(ctx)).doesNotThrowAnyException();
    stream.close(ctx);

    assertThat(sentTimeout).as("RETURN strategy never invokes sendTimeout").isFalse();
  }

  /**
   * With a non-RETURN strategy (null or EXCEPTION), a fire via {@code fail()} invokes
   * {@code sendTimeout} on the predecessor AND throws {@link TimeoutException}. Drives the fire
   * deterministically by a slow upstream that sleeps inside {@code hasNext} so the accumulated
   * wall-clock crosses the 0ms threshold before the second {@code next} call.
   */
  @Test
  public void timeoutWithExceptionStrategySendsTimeoutAndThrows() {
    var ctx = newContext();
    var sentTimeout = new AtomicBoolean(false);
    var r1 = new ResultInternal(session);
    r1.setProperty("id", 1);
    var r2 = new ResultInternal(session);
    r2.setProperty("id", 2);
    var step = new AccumulatingTimeoutStep(
        buildTimeout(0L, SQLTimeout.EXCEPTION), ctx, false);
    step.setPrevious(slowSendTimeoutPredecessor(ctx, sentTimeout, List.of(r1, r2), 5L));

    var stream = step.start(ctx);

    // hasNext sleeps, accruing >1ms of cumulative time. The subsequent next() call sees
    // totalTime / 1_000_000 > 0 and fires.
    assertThat(stream.hasNext(ctx)).isTrue();

    // First next() triggers the fire because hasNext already accumulated > 1ms.
    assertThatThrownBy(() -> stream.next(ctx))
        .isInstanceOf(TimeoutException.class)
        .hasMessageContaining("Timeout expired");
    assertThat(sentTimeout).as("EXCEPTION strategy must invoke sendTimeout before throw").isTrue();
  }

  /**
   * Null failure strategy behaves as non-RETURN: it throws. Pins the {@code RETURN.equals(null)}
   * false result at line 46. Uses the slow-upstream trick to accumulate wall-clock time.
   */
  @Test
  public void timeoutWithNullStrategyThrows() {
    var ctx = newContext();
    var sentTimeout = new AtomicBoolean(false);
    var r1 = new ResultInternal(session);
    r1.setProperty("id", 1);
    var r2 = new ResultInternal(session);
    r2.setProperty("id", 2);
    var step = new AccumulatingTimeoutStep(buildTimeout(0L, null), ctx, false);
    step.setPrevious(slowSendTimeoutPredecessor(ctx, sentTimeout, List.of(r1, r2), 5L));

    var stream = step.start(ctx);
    assertThat(stream.hasNext(ctx)).isTrue();

    assertThatThrownBy(() -> stream.next(ctx)).isInstanceOf(TimeoutException.class);
  }

  // =========================================================================
  // prettyPrint
  // =========================================================================

  /**
   * {@code prettyPrint} renders {@code "+ TIMEOUT (Nms)"} — note the "ms" suffix WITHOUT a space
   * before it, distinguishing this step from {@link TimeoutStep} (which uses " millis").
   */
  @Test
  public void prettyPrintRendersTimeoutHeader() {
    var ctx = newContext();
    var step = new AccumulatingTimeoutStep(buildTimeout(5_000L, SQLTimeout.RETURN), ctx, false);

    var out = step.prettyPrint(0, 2);

    assertThat(out).contains("+ TIMEOUT").contains("5000ms").doesNotContain("millis");
  }

  /** A non-zero depth applies the leading indent; exact-width pin. */
  @Test
  public void prettyPrintAppliesIndentation() {
    var ctx = newContext();
    var step = new AccumulatingTimeoutStep(buildTimeout(100L, null), ctx, false);

    var out = step.prettyPrint(1, 4);

    assertThat(out).startsWith("    +").doesNotStartWith("     +");
  }

  // =========================================================================
  // canBeCached / reset
  // =========================================================================

  /** {@code canBeCached} always returns true. */
  @Test
  public void stepIsAlwaysCacheable() {
    var ctx = newContext();
    var step = new AccumulatingTimeoutStep(buildTimeout(10L, null), ctx, false);

    assertThat(step.canBeCached()).isTrue();
  }

  /** {@code reset} is a no-op and completes without throwing (pins line 66). */
  @Test
  public void resetIsNoOp() {
    var ctx = newContext();
    var step = new AccumulatingTimeoutStep(buildTimeout(10L, null), ctx, false);

    assertThatCode(step::reset).doesNotThrowAnyException();
  }

  // =========================================================================
  // copy
  // =========================================================================

  /**
   * {@code copy} produces an independent step carrying the same timeout configuration and the
   * {@code profilingEnabled} flag.
   */
  @Test
  public void copyProducesIndependentStep() {
    var ctx = newContext();
    var timeout = buildTimeout(42L, SQLTimeout.RETURN);
    var original = new AccumulatingTimeoutStep(timeout, ctx, true);

    ExecutionStep copied = original.copy(ctx);

    assertThat(copied).isNotSameAs(original).isInstanceOf(AccumulatingTimeoutStep.class);
    var copy = (AccumulatingTimeoutStep) copied;
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

  private ExecutionStepInternal trackingSendTimeoutPredecessor(
      CommandContext ctx, AtomicBoolean sentTimeout, List<? extends Result> rows) {
    return new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStep copy(CommandContext c) {
        throw new UnsupportedOperationException();
      }

      @Override
      public ExecutionStream internalStart(CommandContext c) {
        return ExecutionStream.resultIterator(new ArrayList<Result>(rows).iterator());
      }

      @Override
      public void sendTimeout() {
        sentTimeout.set(true);
        super.sendTimeout();
      }
    };
  }

  /**
   * Predecessor that tracks {@code sendTimeout} and sleeps on each {@code hasNext} call so the
   * enclosing {@link TimeoutResultSet} accumulates enough wall-clock time to cross a 0ms
   * threshold deterministically.
   */
  private ExecutionStepInternal slowSendTimeoutPredecessor(
      CommandContext ctx, AtomicBoolean sentTimeout, List<? extends Result> rows,
      long sleepMillis) {
    return new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStep copy(CommandContext c) {
        throw new UnsupportedOperationException();
      }

      @Override
      public ExecutionStream internalStart(CommandContext c) {
        var inner = ExecutionStream.resultIterator(new ArrayList<Result>(rows).iterator());
        return new ExecutionStream() {
          @Override
          public boolean hasNext(CommandContext c2) {
            try {
              Thread.sleep(sleepMillis);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            return inner.hasNext(c2);
          }

          @Override
          public Result next(CommandContext c2) {
            return inner.next(c2);
          }

          @Override
          public void close(CommandContext c2) {
            inner.close(c2);
          }
        };
      }

      @Override
      public void sendTimeout() {
        sentTimeout.set(true);
        super.sendTimeout();
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
