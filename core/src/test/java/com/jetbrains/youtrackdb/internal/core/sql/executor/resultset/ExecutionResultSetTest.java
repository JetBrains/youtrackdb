package com.jetbrains.youtrackdb.internal.core.sql.executor.resultset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.BasicResult;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.query.ResultSet;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnull;
import org.junit.Test;

/**
 * Direct coverage for {@link ExecutionResultSet}, the public-API {@link ResultSet} that wraps
 * an {@link ExecutionStream} + {@link CommandContext} + {@link ExecutionPlan}. We test:
 *
 * <ul>
 *   <li>hasNext/next delegate to the underlying stream;</li>
 *   <li>close is idempotent and invokes stream.close exactly once;</li>
 *   <li>hasNext short-circuits after close, and next throws {@link NoSuchElementException}
 *       when the stream is exhausted or closed;</li>
 *   <li>getExecutionPlan / getBoundToSession surface the constructor args;</li>
 *   <li>tryAdvance, trySplit, estimateSize, characteristics, forEachRemaining behave as per
 *       the Spliterator / public ResultSet contract.</li>
 * </ul>
 *
 * <p>Requires {@link DbTestBase} because {@link ExecutionResultSet} pulls the session out of
 * the command context on construction, and {@link CommandContext#getDatabaseSession()} throws
 * when no session is attached.
 */
public class ExecutionResultSetTest extends DbTestBase {

  private BasicCommandContext newContext() {
    return new BasicCommandContext(session);
  }

  /**
   * hasNext/next delegate to the underlying stream. Each call to next returns the same
   * instance the stream yields.
   */
  @Test
  public void hasNextAndNextDelegateToUnderlyingStream() {
    var ctx = newContext();
    var r1 = new ResultInternal(session);
    r1.setProperty("idx", 1);
    var r2 = new ResultInternal(session);
    r2.setProperty("idx", 2);

    var stream = ExecutionStream.resultIterator(List.of((Result) r1, r2).iterator());
    try (ResultSet rs = new ExecutionResultSet(stream, ctx, null)) {
      assertThat(rs.hasNext()).isTrue();
      assertThat(rs.next()).isSameAs(r1);
      assertThat(rs.hasNext()).isTrue();
      assertThat(rs.next()).isSameAs(r2);
      assertThat(rs.hasNext()).isFalse();
    }
  }

  /**
   * {@code next()} on exhaustion throws {@link NoSuchElementException} (public-API contract,
   * not IllegalStateException which would be the ExecutionStream contract).
   */
  @Test
  public void nextOnExhaustedStreamThrowsNoSuchElementException() {
    var ctx = newContext();
    var empty = ExecutionStream.empty();

    try (var rs = new ExecutionResultSet(empty, ctx, null)) {
      assertThatThrownBy(rs::next).isInstanceOf(NoSuchElementException.class);
    }
  }

  /**
   * {@code close()} is idempotent and invokes the underlying stream's close exactly once.
   * After close, hasNext short-circuits to false and next throws NoSuchElementException.
   */
  @Test
  public void closeIsIdempotentAndShortCircuitsHasNext() {
    var ctx = newContext();
    var closes = new AtomicInteger();
    var stream = new ExecutionStream() {
      private boolean advanced;

      @Override
      public boolean hasNext(CommandContext c) {
        return !advanced;
      }

      @Override
      public Result next(CommandContext c) {
        advanced = true;
        return new ResultInternal(session);
      }

      @Override
      public void close(CommandContext c) {
        closes.incrementAndGet();
      }
    };

    var rs = new ExecutionResultSet(stream, ctx, null);
    assertThat(rs.isClosed()).isFalse();
    rs.close();
    assertThat(rs.isClosed()).isTrue();
    assertThat(closes.get()).isEqualTo(1);

    // Second close is a no-op: does not invoke stream.close again and does not throw.
    rs.close();
    assertThat(closes.get()).isEqualTo(1);

    // After close, hasNext returns false and next throws NoSuchElementException.
    assertThat(rs.hasNext()).isFalse();
    assertThatThrownBy(rs::next).isInstanceOf(NoSuchElementException.class);
  }

  /**
   * {@code getExecutionPlan} returns the plan supplied at construction, or null when none was.
   */
  @Test
  public void getExecutionPlanReturnsConstructorArgument() {
    var ctx = newContext();
    try (var rs = new ExecutionResultSet(ExecutionStream.empty(), ctx, null)) {
      assertThat(rs.getExecutionPlan()).isNull();
    }

    var fixedPlan = new StubPlan();
    try (var rs = new ExecutionResultSet(ExecutionStream.empty(), ctx, fixedPlan)) {
      assertThat(rs.getExecutionPlan()).isSameAs(fixedPlan);
    }
  }

  /** {@code getBoundToSession} returns the session derived from the command context. */
  @Test
  public void boundToSessionReturnsContextSession() {
    var ctx = newContext();
    try (var rs = new ExecutionResultSet(ExecutionStream.empty(), ctx, null)) {
      assertThat(rs.getBoundToSession()).isSameAs(session);
    }
  }

  /** {@code getBoundToSession} returns null after close (session reference cleared). */
  @Test
  public void boundToSessionReturnsNullAfterClose() {
    var ctx = newContext();
    var rs = new ExecutionResultSet(ExecutionStream.empty(), ctx, null);
    rs.close();
    assertThat(rs.getBoundToSession()).isNull();
  }

  /** {@code trySplit} is null and {@code estimateSize} is unknown (Long.MAX_VALUE). */
  @Test
  public void spliteratorReportsUnknownSizeAndOrdered() {
    var ctx = newContext();
    try (var rs = new ExecutionResultSet(ExecutionStream.empty(), ctx, null)) {
      assertThat((Object) rs.trySplit()).isNull();
      assertThat(rs.estimateSize()).isEqualTo(Long.MAX_VALUE);
      assertThat(rs.characteristics()).isEqualTo(ResultSet.ORDERED);
    }
  }

  /**
   * {@code tryAdvance} returns true & invokes the consumer when a next result exists,
   * false (without invoking the consumer) when exhausted.
   */
  @Test
  public void tryAdvanceAdvancesAndStopsAtEnd() {
    var ctx = newContext();
    var r1 = new ResultInternal(session);
    r1.setProperty("v", 7);
    var stream = ExecutionStream.resultIterator(List.of((Result) r1).iterator());

    try (var rs = new ExecutionResultSet(stream, ctx, null)) {
      var captured = new AtomicInteger(-1);
      assertThat(rs.tryAdvance(r -> captured.set(r.getProperty("v")))).isTrue();
      assertThat(captured.get()).isEqualTo(7);

      var secondCall = new int[1];
      assertThat(rs.tryAdvance(r -> secondCall[0]++)).isFalse();
      assertThat(secondCall[0]).isZero();
    }
  }

  /** {@code forEachRemaining} drains in order. */
  @Test
  public void forEachRemainingDrainsInOrder() {
    var ctx = newContext();
    var r1 = new ResultInternal(session);
    r1.setProperty("v", 1);
    var r2 = new ResultInternal(session);
    r2.setProperty("v", 2);
    var stream = ExecutionStream.resultIterator(List.of((Result) r1, r2).iterator());

    try (var rs = new ExecutionResultSet(stream, ctx, null)) {
      var drained = new ArrayList<Integer>();
      rs.forEachRemaining(r -> drained.add(r.<Integer>getProperty("v")));
      assertThat(drained).containsExactly(1, 2);
    }
  }

  // =========================================================================
  // Helpers
  // =========================================================================

  /**
   * Minimal {@link ExecutionPlan} stub. Needed for the "plan is surfaced" test where the
   * constructor receives a specific instance and we verify {@code getExecutionPlan} returns
   * that same instance by identity.
   */
  private static class StubPlan implements ExecutionPlan {
    @Override
    @Nonnull
    public String prettyPrint(int depth, int indent) {
      return "STUB";
    }

    @Override
    @Nonnull
    public List<ExecutionStep> getSteps() {
      return List.of();
    }

    @Override
    @Nonnull
    public BasicResult
        toResult(com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded s) {
      return new ResultInternal(s);
    }
  }
}
