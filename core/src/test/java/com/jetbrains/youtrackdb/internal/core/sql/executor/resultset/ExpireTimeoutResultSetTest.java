package com.jetbrains.youtrackdb.internal.core.sql.executor.resultset;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

/**
 * Deterministic coverage for the two time-sensitive execution-stream wrappers
 * ({@link ExpireResultSet} and {@link TimeoutResultSet}).
 *
 * <p>Both wrappers need a real {@link com.jetbrains.youtrackdb.internal.core.db
 * .DatabaseSessionEmbedded} because their expired-sentinel path constructs
 * {@code new ResultInternal(ctx.getDatabaseSession())}, and
 * {@link BasicCommandContext#getDatabaseSession()} throws when no session is attached. We
 * therefore extend {@link DbTestBase} and feed the session into the context.
 *
 * <p>To keep the tests reliable, we use {@code timeoutMillis = -1} so the expiry check fires
 * on the very first invocation of the method that inspects it (no sleeps, no flakiness). The
 * complementary "not yet expired" path is driven by a large timeout (1 hour) that the test
 * cannot possibly exceed.
 */
public class ExpireTimeoutResultSetTest extends DbTestBase {

  private BasicCommandContext newContext() {
    return new BasicCommandContext(session);
  }

  // =========================================================================
  // ExpireResultSet — measures wall-clock via System.currentTimeMillis
  // =========================================================================

  /**
   * With a negative timeout the expiry instant lies in the past; the very first
   * {@code hasNext()} detects it, invokes the callback once, flips the internal
   * {@code timedOut} flag, and from then on reports {@code hasNext == false}.
   */
  @Test
  public void expireHasNextTriggersTimeoutWhenExpiryInPast() {
    var ctx = newContext();
    var calls = new int[1];
    var source = streamOfInts(1, 2, 3);
    var stream = new ExpireResultSet(source, -1, () -> calls[0]++);

    assertThat(stream.hasNext(ctx)).isFalse();
    assertThat(calls[0]).isEqualTo(1);
  }

  /**
   * A distant-future expiry leaves hasNext passing through to the source; the callback
   * is never invoked and the stream drains normally.
   */
  @Test
  public void expireWithFutureExpiryDelegatesToSource() {
    var ctx = newContext();
    var calls = new int[1];
    var source = streamOfInts(1, 2);
    var stream = new ExpireResultSet(source, 3_600_000, () -> calls[0]++);

    var drained = new ArrayList<Integer>();
    while (stream.hasNext(ctx)) {
      drained.add(stream.next(ctx).<Integer>getProperty("value"));
    }
    stream.close(ctx);

    assertThat(drained).containsExactly(1, 2);
    assertThat(calls[0]).isZero();
  }

  /**
   * {@code next()} with an expired timeout invokes the callback AND returns an empty
   * session-bound {@link ResultInternal} (as a sentinel) rather than throwing. This is
   * the behavior the planner relies on to short-circuit query execution at the boundary.
   */
  @Test
  public void expireNextOnExpiredReturnsEmptyResultAndFiresCallback() {
    var ctx = newContext();
    var calls = new int[1];
    var source = streamOfInts(1, 2);
    var stream = new ExpireResultSet(source, -1, () -> calls[0]++);

    var result = stream.next(ctx);
    assertThat(result).isNotNull();
    assertThat(result.getPropertyNames()).isEmpty();
    assertThat(calls[0]).isEqualTo(1);
  }

  /**
   * WHEN-FIXED: Track 22 — {@link ExpireResultSet#fail} is reachable repeatedly while the
   * timeout remains in the past, so the callback fires multiple times (once per hasNext /
   * next call). The sticky {@code timedOut} flag only guards the return path, not the
   * callback. Tests should observe idempotent timeout reporting (callback fires at most
   * once). Pinned as a falsifiable regression so the fix flips this assertion.
   */
  @Test
  public void expireRepeatedHasNextCallsFireCallbackEachTime() {
    var ctx = newContext();
    var calls = new int[1];
    var source = streamOfInts(1, 2, 3);
    var stream = new ExpireResultSet(source, -1, () -> calls[0]++);

    assertThat(stream.hasNext(ctx)).isFalse();
    assertThat(stream.hasNext(ctx)).isFalse();
    assertThat(stream.hasNext(ctx)).isFalse();
    // Falsifiable: when Track 22 fixes the double-fire by guarding with
    // if (!timedOut) fail(); this will drop to 1.
    assertThat(calls[0]).isEqualTo(3);
  }

  /**
   * {@code close} delegates to the underlying source.
   */
  @Test
  public void expireCloseDelegatesToSource() {
    var ctx = newContext();
    var source = new CloseTracker(streamOfInts(1));
    var stream = new ExpireResultSet(source, 3_600_000, () -> {
    });

    stream.close(ctx);
    assertThat(source.closeCount()).isEqualTo(1);
  }

  // =========================================================================
  // TimeoutResultSet — accumulates nanoseconds via totalTime across calls
  // =========================================================================

  /**
   * With a negative millis threshold the division {@code totalTime.get()/1_000_000} is
   * always greater than the threshold (any non-negative integer beats -1), so the very
   * first {@code next()} call triggers the callback and returns an empty result.
   */
  @Test
  public void timeoutNextFiresImmediatelyWithNegativeMillis() {
    var ctx = newContext();
    var calls = new int[1];
    var source = streamOfInts(1, 2);
    var stream = new TimeoutResultSet(source, -1, () -> calls[0]++);

    // hasNext does not check the timeout; it simply delegates to the source and
    // accumulates nanos. So the first hasNext call returns true.
    assertThat(stream.hasNext(ctx)).isTrue();

    // next sees totalTime/1_000_000 > -1 and fails, returning an empty sentinel.
    var first = stream.next(ctx);
    assertThat(first).isNotNull();
    assertThat(first.getPropertyNames()).isEmpty();
    assertThat(calls[0]).isEqualTo(1);

    // After timedOut, hasNext short-circuits to false.
    assertThat(stream.hasNext(ctx)).isFalse();
  }

  /**
   * A large timeout leaves the wrapper transparent: hasNext/next delegate to the source
   * and the stream drains normally without firing the callback.
   */
  @Test
  public void timeoutWithLargeThresholdIsTransparent() {
    var ctx = newContext();
    var calls = new int[1];
    var source = streamOfInts(1, 2, 3);
    var stream = new TimeoutResultSet(source, 3_600_000, () -> calls[0]++);

    var drained = new ArrayList<Integer>();
    while (stream.hasNext(ctx)) {
      drained.add(stream.next(ctx).<Integer>getProperty("value"));
    }
    stream.close(ctx);

    assertThat(drained).containsExactly(1, 2, 3);
    assertThat(calls[0]).isZero();
  }

  /**
   * {@code close} delegates to the underlying source.
   */
  @Test
  public void timeoutCloseDelegatesToSource() {
    var ctx = newContext();
    var source = new CloseTracker(streamOfInts(1));
    var stream = new TimeoutResultSet(source, 3_600_000, () -> {
    });

    stream.close(ctx);
    assertThat(source.closeCount()).isEqualTo(1);
  }

  /**
   * After timing out once (via a negative timeout), subsequent hasNext calls continue to
   * report false even when the underlying source would still have data — the timedOut
   * flag is sticky.
   */
  @Test
  public void timeoutFlagIsSticky() {
    var ctx = newContext();
    var fired = new AtomicBoolean(false);
    var source = streamOfInts(1, 2, 3);
    var stream = new TimeoutResultSet(source, -1, () -> fired.set(true));

    assertThat(stream.hasNext(ctx)).isTrue();
    stream.next(ctx); // triggers timeout
    assertThat(fired.get()).isTrue();

    // Source still has elements, but the wrapper is stuck in timedOut state.
    assertThat(stream.hasNext(ctx)).isFalse();
  }

  // =========================================================================
  // Helpers
  // =========================================================================

  private ExecutionStream streamOfInts(int... values) {
    var list = new ArrayList<Result>(values.length);
    for (var v : values) {
      var r = new ResultInternal(session);
      r.setProperty("value", v);
      list.add(r);
    }
    return ExecutionStream.resultIterator(list.iterator());
  }

  /** Wraps a stream to count close invocations. */
  private static class CloseTracker implements ExecutionStream {
    private final ExecutionStream inner;
    private int closeCount;

    CloseTracker(ExecutionStream inner) {
      this.inner = inner;
    }

    int closeCount() {
      return closeCount;
    }

    @Override
    public boolean hasNext(CommandContext ctx) {
      return inner.hasNext(ctx);
    }

    @Override
    public Result next(CommandContext ctx) {
      return inner.next(ctx);
    }

    @Override
    public void close(CommandContext ctx) {
      closeCount++;
      inner.close(ctx);
    }
  }
}
