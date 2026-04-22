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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.jetbrains.youtrackdb.internal.common.concur.NeedRetryException;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.query.BasicResult;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLReturnStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * Direct-step tests for {@link RetryStep}, covering its retry loop semantics: the success-first
 * path, success-on-Nth-retry path, exhausted-retries with and without an else body, the
 * {@code elseFail} gate (re-throw vs empty stream), predecessor draining, null-body / null-elseBody
 * handling in {@code copy()}, and the {@code db.rollback()} swallowed-exception branch.
 *
 * <p>Stub strategy: each body/elseBody is a single {@link StubStatement} whose
 * {@link SQLStatement#createExecutionPlan createExecutionPlan} returns a tiny
 * {@link StubInternalPlan}. The plan's {@link StubInternalPlan#start()} calls a
 * configurable supplier, so the test dictates whether each invocation throws a
 * {@link TestNeedRetryException} or returns a stream carrying one marker result. An
 * {@link AtomicInteger} counts invocations so assertions can pin the exact number of attempts
 * made by {@code internalStart}.
 *
 * <p>The {@code ExecutionThreadLocal.isInterruptCurrentOperation()} branch at line 45 is only
 * reachable from a {@code SoftThread} runtime; JUnit test threads are plain
 * {@link java.lang.Thread}s so the check always returns {@code false}. The
 * {@code true} arm is integration-test territory and is deliberately left unpinned here — a
 * production {@code SoftThread} scenario is outside the unit-test fixture. Accepted per
 * Track 8 Phase A review R8.
 */
public class RetryStepTest extends TestUtilsFixture {

  // =========================================================================
  // Constructor: elseFail normalization
  // =========================================================================

  /**
   * The constructor normalizes {@code elseFail} via {@code !Boolean.FALSE.equals(elseFail)}: any
   * non-FALSE value (including {@code null}, {@code true}, or {@code Boolean.TRUE}) becomes
   * {@code true}; only {@code Boolean.FALSE} stays false. Pins the null-branch at line 33 (if
   * mutated to always return true or always return false, the reThrow test would drop).
   */
  @Test
  public void constructorTreatsNullElseFailAsTrue() {
    var ctx = newContext();
    var step = new RetryStep(List.of(successStub(1)), 1, null, null, ctx, false);

    assertThat(step.elseFail).isTrue();
  }

  /**
   * {@code Boolean.FALSE} is the only input that keeps {@code elseFail} false. Pins the exact
   * equality check at line 33 — a mutation to {@code ==} instead of {@code Boolean.FALSE.equals}
   * (or dropping the negation) would fail.
   */
  @Test
  public void constructorTreatsBooleanFalseAsFalse() {
    var ctx = newContext();
    var step = new RetryStep(List.of(successStub(1)), 1, null, Boolean.FALSE, ctx, false);

    assertThat(step.elseFail).isFalse();
  }

  // =========================================================================
  // internalStart: predecessor draining
  // =========================================================================

  /**
   * When a predecessor step exists, {@code internalStart} drains it (start + close) before
   * entering the retry loop — pins the side-effect contract at lines 38-40.
   */
  @Test
  public void internalStartDrainsPredecessorBeforeBody() {
    var ctx = newContext();
    var prevStarted = new AtomicBoolean(false);
    var prevClosed = new AtomicBoolean(false);

    var step = new RetryStep(List.of(successStub(1)), 1, null, Boolean.FALSE, ctx, false);
    step.setPrevious(trackingPredecessor(ctx, prevStarted, prevClosed));

    var stream = step.start(ctx);
    drain(stream, ctx);

    assertThat(prevStarted).as("predecessor must be started before body").isTrue();
    assertThat(prevClosed).as("predecessor stream must be closed before body").isTrue();
  }

  // =========================================================================
  // internalStart: retries boundary (0 / negative)
  // =========================================================================

  /**
   * {@code retries == 0} makes the {@code for (i = 0; i < retries; i++)} loop body never execute
   * — the step falls through to the terminal {@code new EmptyStep(ctx).start(ctx)} and returns
   * an empty stream without ever invoking the body. Pins the loop boundary: a mutation replacing
   * {@code i < retries} with {@code i <= retries} would run the body once for retries=0 and be
   * caught here.
   */
  @Test
  public void zeroRetriesYieldsEmptyStreamWithoutInvokingBody() {
    var ctx = newContext();
    var attempts = new AtomicInteger();
    var body = List.of((SQLStatement) new StubStatement(() -> {
      attempts.incrementAndGet();
      return ExecutionStream.empty();
    }));

    var step = new RetryStep(body, 0, null, Boolean.TRUE, ctx, false);
    var stream = step.start(ctx);

    assertThat(stream.hasNext(ctx)).isFalse();
    assertThat(attempts)
        .as("retries=0 must skip the body entirely")
        .hasValue(0);
  }

  /**
   * Negative {@code retries} behaves like 0 — the {@code for} condition fails immediately and the
   * body is not invoked. Pins the negative-boundary so a mutation to {@code Math.abs(retries)}
   * or similar would be caught.
   */
  @Test
  public void negativeRetriesYieldsEmptyStreamWithoutInvokingBody() {
    var ctx = newContext();
    var attempts = new AtomicInteger();
    var body = List.of((SQLStatement) new StubStatement(() -> {
      attempts.incrementAndGet();
      return ExecutionStream.empty();
    }));

    var step = new RetryStep(body, -1, null, Boolean.TRUE, ctx, false);
    var stream = step.start(ctx);

    assertThat(stream.hasNext(ctx)).isFalse();
    assertThat(attempts)
        .as("negative retries must skip the body entirely")
        .hasValue(0);
  }

  // =========================================================================
  // internalStart: success paths
  // =========================================================================

  /**
   * A body whose plan succeeds on the first attempt exits the retry loop after one invocation.
   * Pins the single-attempt path through the {@code try} branch at line 49 and {@code break} at
   * line 54 (via the {@code result == null} ternary which, for our stub, actually returns a
   * non-null stream).
   *
   * <p>Our stub's plan has no RETURN, so {@code executeFull()} returns null and the loop
   * {@code break}s. The retry step then returns a {@link EmptyStep}'s stream (line 79).
   */
  @Test
  public void internalStartSucceedsOnFirstAttempt() {
    var ctx = newContext();
    var attempts = new AtomicInteger();
    var body = List.of((SQLStatement) new StubStatement(() -> {
      attempts.incrementAndGet();
      return ExecutionStream.empty();
    }));

    var step = new RetryStep(body, 3, null, Boolean.TRUE, ctx, false);
    drain(step.start(ctx), ctx);

    assertThat(attempts).hasValue(1);
  }

  /**
   * When the body throws {@link NeedRetryException} twice and then succeeds, the loop retries
   * until success. Pins the {@code catch} branch (lines 55-76) and the {@code i < retries - 1}
   * path where the retry falls through without invoking the else body.
   */
  @Test
  public void internalStartRetriesUntilSuccess() {
    var ctx = newContext();
    var attempts = new AtomicInteger();
    var body = List.of((SQLStatement) new StubStatement(() -> {
      var n = attempts.incrementAndGet();
      if (n < 3) {
        throw new TestNeedRetryException();
      }
      return ExecutionStream.empty();
    }));

    var step = new RetryStep(body, 5, null, Boolean.TRUE, ctx, false);
    drain(step.start(ctx), ctx);

    assertThat(attempts).as("two failures + one success = three invocations").hasValue(3);
  }

  // =========================================================================
  // internalStart: exhausted retries
  // =========================================================================

  /**
   * When all retries fail and {@code elseFail=true} (the default) and {@code elseBody} is null,
   * the final exception is re-thrown. Pins the throw branch at line 71.
   */
  @Test
  public void internalStartRethrowsWhenRetriesExhaustedAndElseFailTrueAndElseBodyNull() {
    var ctx = newContext();
    var attempts = new AtomicInteger();
    var body = List.of((SQLStatement) new StubStatement(() -> {
      attempts.incrementAndGet();
      throw new TestNeedRetryException();
    }));

    var step = new RetryStep(body, 3, null, Boolean.TRUE, ctx, false);

    assertThatThrownBy(() -> step.start(ctx))
        .isInstanceOf(TestNeedRetryException.class);
    assertThat(attempts).as("all 3 retries must be attempted").hasValue(3);
  }

  /**
   * With {@code elseFail=false} and a null {@code elseBody}, an exhausted retry loop returns an
   * empty stream instead of re-throwing. Pins line 73 ({@code ExecutionStream.empty()}).
   */
  @Test
  public void internalStartReturnsEmptyWhenElseFailFalseAndElseBodyNull() {
    var ctx = newContext();
    var attempts = new AtomicInteger();
    var body = List.of((SQLStatement) new StubStatement(() -> {
      attempts.incrementAndGet();
      throw new TestNeedRetryException();
    }));

    var step = new RetryStep(body, 2, null, Boolean.FALSE, ctx, false);
    var stream = step.start(ctx);

    assertThat(stream.hasNext(ctx)).isFalse();
    stream.close(ctx);
    assertThat(attempts).hasValue(2);
  }

  /**
   * Empty-but-non-null {@code elseBody} is treated the same as null: the else plan is skipped
   * (line 63 false branch) and the elseFail gate runs. Pins the {@code !elseBody.isEmpty()} guard.
   */
  @Test
  public void internalStartSkipsElseBodyWhenEmptyList() {
    var ctx = newContext();
    var body = List.of((SQLStatement) new StubStatement(() -> {
      throw new TestNeedRetryException();
    }));
    var elseAttempts = new AtomicInteger();
    var elseBody = new ArrayList<SQLStatement>();

    var step = new RetryStep(body, 2, elseBody, Boolean.FALSE, ctx, false);
    var stream = step.start(ctx);

    assertThat(stream.hasNext(ctx)).isFalse();
    stream.close(ctx);
    assertThat(elseAttempts).as("else body never runs when the list is empty").hasValue(0);
  }

  /**
   * When all retries fail and {@code elseBody} is non-empty, the else body is executed before
   * the {@code elseFail} gate runs. With {@code elseFail=false} the method returns an empty
   * stream after the else body — pins the else branch at lines 63-68 AND the fall-through to
   * line 73. If the else body returned a RETURN-carrying plan, the step would short-circuit at
   * line 67; here the stub's plan has no RETURN so {@code executeFull} returns null and
   * execution continues to {@code elseFail} (false → empty stream).
   */
  @Test
  public void internalStartRunsElseBodyWhenNonEmptyAfterExhaustedRetries() {
    var ctx = newContext();
    var bodyAttempts = new AtomicInteger();
    var elseAttempts = new AtomicInteger();
    var body = List.of((SQLStatement) new StubStatement(() -> {
      bodyAttempts.incrementAndGet();
      throw new TestNeedRetryException();
    }));
    var elseBody = List.of((SQLStatement) new StubStatement(() -> {
      elseAttempts.incrementAndGet();
      return ExecutionStream.empty();
    }));

    var step = new RetryStep(body, 2, elseBody, Boolean.FALSE, ctx, false);
    var stream = step.start(ctx);

    assertThat(stream.hasNext(ctx)).isFalse();
    stream.close(ctx);
    assertThat(bodyAttempts).as("body attempted retries times").hasValue(2);
    assertThat(elseAttempts).as("else body ran exactly once").hasValue(1);
  }

  /**
   * An {@code elseBody} containing a RETURN statement short-circuits at lines 66-68: the else
   * plan's {@code executeFull()} returns a non-null {@link ReturnStep}, and the retry step
   * invokes {@code result.start(ctx)} — the RETURN value becomes the step's output. Pins the
   * {@code if (result != null)} true branch at line 66.
   */
  @Test
  public void internalStartReturnsElseBodyReturnValueWhenExhausted() {
    var ctx = newContext();
    var body = List.of((SQLStatement) new StubStatement(() -> {
      throw new TestNeedRetryException();
    }));
    var elseBody = List.of((SQLStatement) new SQLReturnStatement(-1));

    var step = new RetryStep(body, 2, elseBody, Boolean.TRUE, ctx, false);
    var results = drain(step.start(ctx), ctx);

    // Bare RETURN (no expression) yields one result whose single "value" property is null.
    assertThat(results).hasSize(1);
    assertThat(results.get(0).getPropertyNames()).containsExactly("value");
    assertThat((Object) results.get(0).getProperty("value")).isNull();
  }

  /**
   * The {@code db.rollback()} between retry attempts is wrapped in a try/catch that swallows any
   * {@link Exception} (lines 57-60). The context returns a Mockito-mocked session whose
   * {@code rollback()} throws an unchecked exception; the retry loop must still complete
   * successfully — pinning the swallow branch.
   */
  @Test
  public void internalStartSwallowsDatabaseRollbackException() {
    var mockSession = mock(DatabaseSessionEmbedded.class);
    doThrow(new IllegalStateException("simulated rollback failure"))
        .when(mockSession).rollback();

    var ctx = new BasicCommandContext() {
      @Override
      public DatabaseSessionEmbedded getDatabaseSession() {
        return mockSession;
      }
    };

    var attempts = new AtomicInteger();
    var body = List.of((SQLStatement) new StubStatement(() -> {
      var n = attempts.incrementAndGet();
      if (n < 2) {
        throw new TestNeedRetryException();
      }
      return ExecutionStream.empty();
    }));

    var step = new RetryStep(body, 3, null, Boolean.TRUE, ctx, false);

    // With rollback throwing, the swallow at line 60 lets the loop continue to success.
    assertThatCode(() -> drain(step.start(ctx), ctx)).doesNotThrowAnyException();
    assertThat(attempts).hasValue(2);
  }

  // =========================================================================
  // initPlan
  // =========================================================================

  /**
   * {@code initPlan} builds a {@link ScriptExecutionPlan} and chains one {@code ScriptLineStep}
   * per statement in the body. Pins the for-each loop at lines 86-88.
   */
  @Test
  public void initPlanChainsEveryStatement() {
    var ctx = newContext();
    var stm1 = (SQLStatement) new StubStatement(ExecutionStream::empty);
    var stm2 = (SQLStatement) new StubStatement(ExecutionStream::empty);
    var step = new RetryStep(List.of(stm1, stm2), 1, null, Boolean.TRUE, ctx, false);

    var plan = step.initPlan(List.of(stm1, stm2), ctx);

    assertThat(plan).isNotNull();
    assertThat(plan.getSteps()).hasSize(2);
  }

  /** Empty body list produces an empty plan with no steps. */
  @Test
  public void initPlanWithEmptyBodyProducesEmptyPlan() {
    var ctx = newContext();
    var step = new RetryStep(List.of(), 0, null, Boolean.TRUE, ctx, false);

    var plan = step.initPlan(List.of(), ctx);

    assertThat(plan).isNotNull();
    assertThat(plan.getSteps()).isEmpty();
  }

  // =========================================================================
  // copy
  // =========================================================================

  /**
   * {@code copy} deep-copies both {@code body} and {@code elseBody}, carries the {@code retries}
   * count and {@code elseFail} flag, and honors {@code profilingEnabled}. Pins lines 93-104.
   */
  @Test
  public void copyProducesIndependentStepWithCopiedStatements() {
    var ctx = newContext();
    var bodyStm = new StubStatement(ExecutionStream::empty);
    var elseStm = new StubStatement(ExecutionStream::empty);
    var original = new RetryStep(
        List.of((SQLStatement) bodyStm), 5, List.of((SQLStatement) elseStm),
        Boolean.FALSE, ctx, true);

    ExecutionStep copied = original.copy(ctx);

    assertThat(copied).isNotSameAs(original).isInstanceOf(RetryStep.class);
    var copy = (RetryStep) copied;
    assertThat(copy.isProfilingEnabled()).isTrue();
    assertThat(copy.elseFail).isFalse();
    assertThat(copy.body).isNotSameAs(original.body).hasSize(1);
    assertThat(copy.elseBody).isNotSameAs(original.elseBody).hasSize(1);
    // Elements must be distinct instances produced by SQLStatement.copy().
    assertThat(copy.body.get(0)).isNotSameAs(original.body.get(0));
    assertThat(copy.elseBody.get(0)).isNotSameAs(original.elseBody.get(0));
  }

  /**
   * {@code copy} with a null {@code body} preserves {@code null} — pins the null-guard at
   * line 96. A mutation producing an empty list instead of null would fail.
   */
  @Test
  public void copyWithNullBodyPreservesNull() {
    var ctx = newContext();
    var original = new RetryStep(null, 3, null, Boolean.TRUE, ctx, false);

    var copy = (RetryStep) original.copy(ctx);

    assertThat(copy.body).isNull();
  }

  /**
   * {@code copy} with a null {@code elseBody} preserves {@code null} — pins the null-guard at
   * line 99.
   */
  @Test
  public void copyWithNullElseBodyPreservesNull() {
    var ctx = newContext();
    var original = new RetryStep(
        List.of((SQLStatement) new StubStatement(ExecutionStream::empty)), 3, null,
        Boolean.TRUE, ctx, false);

    var copy = (RetryStep) original.copy(ctx);

    assertThat(copy.body).hasSize(1);
    assertThat(copy.elseBody).isNull();
  }

  // =========================================================================
  // Helpers
  // =========================================================================

  private BasicCommandContext newContext() {
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);
    return ctx;
  }

  private static StubStatement successStub(@SuppressWarnings("unused") int marker) {
    return new StubStatement(ExecutionStream::empty);
  }

  private static List<Result> drain(ExecutionStream stream, CommandContext ctx) {
    var out = new ArrayList<Result>();
    while (stream.hasNext(ctx)) {
      out.add(stream.next(ctx));
    }
    stream.close(ctx);
    return out;
  }

  private static ExecutionStepInternal trackingPredecessor(
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

  /** Minimal {@link NeedRetryException} used only for fault injection in these tests. */
  private static final class TestNeedRetryException extends NeedRetryException {
    TestNeedRetryException() {
      super("test-db", "simulated retry");
    }
  }

  /**
   * Stub {@link SQLStatement} that yields a configurable execution plan via a supplier for
   * each invocation. The supplier is invoked every time {@code start()} is called on the plan —
   * which happens once per retry attempt in {@link RetryStep#internalStart}.
   */
  private static final class StubStatement extends SQLStatement {
    private final StreamSupplier supplier;

    StubStatement(StreamSupplier supplier) {
      super(-1);
      this.supplier = supplier;
    }

    @Override
    public InternalExecutionPlan createExecutionPlan(CommandContext ctx, boolean profile) {
      return new StubInternalPlan(ctx, supplier);
    }

    @Override
    public SQLStatement copy() {
      return new StubStatement(supplier);
    }

    @Override
    public boolean refersToParent() {
      return false;
    }

    @Override
    public boolean isIdempotent() {
      return true;
    }
  }

  /** Supplier of an {@link ExecutionStream}; may throw to simulate failure. */
  @FunctionalInterface
  private interface StreamSupplier {
    ExecutionStream get();
  }

  /**
   * Minimal {@link InternalExecutionPlan} that runs a supplier on {@code start()}. The plan
   * is not a {@code ScriptExecutionPlan}/{@code SingleOpExecutionPlan}/{@code IfExecutionPlan}/
   * {@code ForEachExecutionPlan} so the enclosing {@code ScriptLineStep.containsReturn()}
   * returns false — exactly what the {@link RetryStep#internalStart} non-return path needs.
   */
  private static final class StubInternalPlan implements InternalExecutionPlan {
    private final CommandContext ctx;
    private final StreamSupplier supplier;

    StubInternalPlan(CommandContext ctx, StreamSupplier supplier) {
      this.ctx = ctx;
      this.supplier = supplier;
    }

    @Override
    public void close() {
    }

    @Override
    public ExecutionStream start() {
      return supplier.get();
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
      return false;
    }

    @Override
    public String prettyPrint(int depth, int indent) {
      return "STUB_PLAN";
    }

    @Override
    public List<ExecutionStep> getSteps() {
      return List.of();
    }

    @Override
    public BasicResult toResult(DatabaseSessionEmbedded s) {
      throw new UnsupportedOperationException();
    }
  }
}
