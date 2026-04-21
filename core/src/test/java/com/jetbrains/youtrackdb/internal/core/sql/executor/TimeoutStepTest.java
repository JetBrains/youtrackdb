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

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExpireResultSet;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLTimeout;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

/**
 * Direct-step tests for {@link TimeoutStep}, the per-record timeout wrapper that guards upstream
 * iteration with an {@link ExpireResultSet}.
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>{@code internalStart} wraps the upstream stream in an {@link ExpireResultSet}.
 *   <li>Upstream records flow through unchanged until the expiry fires.
 *   <li>{@code fail()} with {@link SQLTimeout#RETURN} silently stops iteration (no exception).
 *   <li>{@code fail()} with any other strategy (including null and {@link SQLTimeout#EXCEPTION})
 *       throws {@link TimeoutException} via {@code sendTimeout} → parent chain.
 *   <li>{@code sendTimeout} propagates through {@link AbstractExecutionStep#sendTimeout} to any
 *       attached predecessor.
 *   <li>{@code prettyPrint} renders {@code "+ TIMEOUT (N millis)"}.
 *   <li>{@code canBeCached} returns {@code true}.
 *   <li>{@code copy} deep-copies the {@link SQLTimeout} and carries the {@code profilingEnabled}
 *       flag.
 * </ul>
 */
public class TimeoutStepTest extends TestUtilsFixture {

  // =========================================================================
  // internalStart — wrapping + upstream pass-through
  // =========================================================================

  /**
   * With a non-expired timeout, {@code internalStart} wraps the upstream stream in an
   * {@link ExpireResultSet}. Upstream records flow through untouched.
   */
  @Test
  public void internalStartWrapsUpstreamInExpireResultSet() {
    var ctx = newContext();
    var step = new TimeoutStep(buildTimeout(10_000L, null), ctx, false);
    var r1 = new ResultInternal(session);
    r1.setProperty("id", 1);
    step.setPrevious(sourceStep(ctx, List.of(r1)));

    var stream = step.start(ctx);

    assertThat(stream).isInstanceOf(ExpireResultSet.class);
    var results = drain(stream, ctx);
    assertThat(results).hasSize(1);
    assertThat(results.get(0).<Integer>getProperty("id")).isEqualTo(1);
  }

  // =========================================================================
  // fail() failure-strategy dispatch
  // =========================================================================

  /**
   * With {@code failureStrategy == RETURN}, the timeout firing does NOT throw — it simply stops
   * iteration so partial results are returned. Pins the empty-arm of the if-else branch (line 29).
   */
  @Test
  public void timeoutWithReturnStrategySwallowsExpiry() {
    var ctx = newContext();
    var step = new TimeoutStep(buildTimeout(-1L, SQLTimeout.RETURN), ctx, false);
    var r1 = new ResultInternal(session);
    r1.setProperty("id", 1);
    step.setPrevious(sourceStep(ctx, List.of(r1)));

    var stream = step.start(ctx);

    // The wrapped ExpireResultSet fires on the first hasNext() because expiry is in the past
    // (timeoutMillis=-1). RETURN swallows the expiry → stream reports no more records.
    assertThat(stream.hasNext(ctx)).isFalse();
    stream.close(ctx);
  }

  /**
   * With a non-RETURN failure strategy (EXCEPTION is the explicit non-null alternative), the
   * timeout throws {@link TimeoutException} on the first {@code hasNext()} because the expiry
   * has already passed. Pins the throw branch (line 30).
   */
  @Test
  public void timeoutWithExceptionStrategyThrowsTimeoutException() {
    var ctx = newContext();
    var step = new TimeoutStep(buildTimeout(-1L, SQLTimeout.EXCEPTION), ctx, false);
    step.setPrevious(sourceStep(ctx, List.of()));

    var stream = step.start(ctx);

    assertThatThrownBy(() -> stream.hasNext(ctx))
        .isInstanceOf(TimeoutException.class)
        .hasMessageContaining("Timeout expired");
  }

  /**
   * A null failure strategy is treated as non-RETURN (the {@code RETURN.equals(null)} check
   * returns false), so the timeout throws. Pins the null-strategy branch at line 29 via the
   * null side of the {@code equals} call.
   */
  @Test
  public void timeoutWithNullStrategyThrowsTimeoutException() {
    var ctx = newContext();
    var step = new TimeoutStep(buildTimeout(-1L, null), ctx, false);
    step.setPrevious(sourceStep(ctx, List.of()));

    var stream = step.start(ctx);

    assertThatThrownBy(() -> stream.hasNext(ctx)).isInstanceOf(TimeoutException.class);
  }

  /**
   * {@code sendTimeout()} propagates backward to any attached predecessor via
   * {@link AbstractExecutionStep#sendTimeout}. Reaching this path requires triggering the failure
   * via {@code fail()} on an EXCEPTION-strategy timeout and catching the throw; the predecessor's
   * {@code sendTimeout} must have been invoked before the throw.
   */
  @Test
  public void timeoutFailurePropagatesSendTimeoutToPredecessor() {
    var ctx = newContext();
    var sentTimeout = new AtomicBoolean(false);
    var step = new TimeoutStep(buildTimeout(-1L, SQLTimeout.EXCEPTION), ctx, false);
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
      public void sendTimeout() {
        sentTimeout.set(true);
        super.sendTimeout();
      }
    });

    var stream = step.start(ctx);
    assertThatThrownBy(() -> stream.hasNext(ctx)).isInstanceOf(TimeoutException.class);

    assertThat(sentTimeout).as("sendTimeout must propagate to predecessor before throwing")
        .isTrue();
  }

  // =========================================================================
  // prettyPrint
  // =========================================================================

  /**
   * {@code prettyPrint} renders {@code "+ TIMEOUT (N millis)"}. Pins the exact format. A mutation
   * to "(N ms)" or dropping the "+" header would fail.
   */
  @Test
  public void prettyPrintRendersTimeoutHeader() {
    var ctx = newContext();
    var step = new TimeoutStep(buildTimeout(5_000L, SQLTimeout.RETURN), ctx, false);

    var out = step.prettyPrint(0, 2);

    assertThat(out).contains("+ TIMEOUT").contains("5000").contains("millis");
  }

  /**
   * With a non-zero depth, {@code prettyPrint} applies the leading indent. Exact-width pin.
   */
  @Test
  public void prettyPrintAppliesIndentation() {
    var ctx = newContext();
    var step = new TimeoutStep(buildTimeout(100L, null), ctx, false);

    var out = step.prettyPrint(1, 4);

    assertThat(out).startsWith("    +").doesNotStartWith("     +");
  }

  // =========================================================================
  // canBeCached
  // =========================================================================

  /** {@code canBeCached} always returns true. */
  @Test
  public void stepIsAlwaysCacheable() {
    var ctx = newContext();
    var step = new TimeoutStep(buildTimeout(10L, null), ctx, false);

    assertThat(step.canBeCached()).isTrue();
  }

  // =========================================================================
  // copy
  // =========================================================================

  /**
   * {@code copy} deep-copies the {@link SQLTimeout} and carries the {@code profilingEnabled} flag.
   * The returned step is a distinct instance; the underlying timeout is a distinct
   * {@link SQLTimeout} instance too (not aliased).
   */
  @Test
  public void copyProducesIndependentStep() {
    var ctx = newContext();
    var timeout = buildTimeout(42L, SQLTimeout.RETURN);
    var original = new TimeoutStep(timeout, ctx, true);

    ExecutionStep copied = original.copy(ctx);

    assertThat(copied).isNotSameAs(original).isInstanceOf(TimeoutStep.class);
    var copy = (TimeoutStep) copied;
    assertThat(copy.isProfilingEnabled()).isTrue();
    // prettyPrint rendering equivalence pins the field-copied timeout
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

  /**
   * Builds a {@link SQLTimeout} with the given value and failure strategy by subclassing so the
   * protected fields are writable from this package (tests live in a different package than the
   * parser AST classes).
   *
   * <p>The parameter names are intentionally suffixed because inside an anonymous subclass
   * initializer, unqualified references to {@code failureStrategy} would resolve to the inherited
   * field (shadowing the local parameter), so we use distinct names to avoid the silent no-op
   * assignment.
   */
  static SQLTimeout buildTimeout(long valMillisArg, String failureStrategyArg) {
    return new SQLTimeout(-1) {
      {
        this.val = valMillisArg;
        this.failureStrategy = failureStrategyArg;
      }
    };
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
