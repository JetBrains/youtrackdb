package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.common.concur.lock.ThreadInterruptedException;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.WriteCacheException;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import org.junit.After;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * Tests the pause / resume background-flush primitive added to
 * {@link WOWCache#pauseBackgroundFlush()} and {@link WOWCache#resumeBackgroundFlush()}.
 *
 * <p>These tests pin the contract advertised by {@link WriteCache#pauseBackgroundFlush()}
 * and {@link WriteCache#resumeBackgroundFlush()}: the periodic-flush entry guard at the
 * top of {@link WOWCache#executePeriodicFlush} exits early when the pause flag is set,
 * the resume early-return contract holds when no prior pause was issued, and resume
 * respects the same scheduling guards the constructor uses ({@code pagesFlushInterval > 0
 * && !stopFlush}).
 *
 * <p>The end-to-end IT {@code LocalPaginatedStorageRestoreFromWALIT.testSimpleRestore}
 * exercises the full barrier-submit + IOResult.await drain, but it runs only in the
 * nightly {@code -P ci-integration-tests} profile and, on the platforms where it runs in
 * PR pipelines, the workload settles before pause is called. A regression that breaks one
 * of the entry-guard / resume-scheduling invariants would not necessarily fail that IT,
 * so these focused unit tests pin the smaller invariants on every PR run.
 *
 * <p>Uses Mockito's {@code CALLS_REAL_METHODS} pattern with reflection-set private fields,
 * mirroring {@link WOWCacheFlushErrorTest}; the indirection is unavoidable because
 * {@code WOWCache} has no test-friendly constructor.
 */
public class WOWCachePauseResumeTest {

  /**
   * Clears the per-thread interrupt flag after every test. Surefire is configured with
   * {@code parallel=classes}, so all @Test methods in this class run sequentially on a
   * single reused thread. If a test left the interrupt flag set (e.g., a regression in
   * {@link WOWCache#pauseBackgroundFlush()} that throws something other than
   * {@link BaseException} on the interrupt path), the leak would pollute whichever test
   * runs next on that thread. Calling {@link Thread#interrupted()} both reads and clears.
   */
  @After
  public void clearInterruptFlag() {
    Thread.interrupted();
  }

  /**
   * Verifies that {@link WOWCache#executePeriodicFlush} returns immediately when the
   * pause flag is set. This is the entry-guard half of the pause contract: a periodic
   * task that fires after pause must observe the flag at its first line and exit without
   * touching the write cache or scheduling further work.
   */
  @Test
  public void testExecutePeriodicFlushReturnsWhenBackgroundFlushPaused() throws Exception {
    var cache = Mockito.mock(WOWCache.class, Mockito.CALLS_REAL_METHODS);
    setField(cache, "backgroundFlushPaused", true);

    // Passing null PeriodicFlushTask would NPE if the method body executes;
    // the entry guard must return before reaching the re-arm in the finally.
    cache.executePeriodicFlush(null);
  }

  /**
   * Verifies that {@link WOWCache#resumeBackgroundFlush()} is a no-op when called
   * without a prior pause. The {@code flushFuture} reference must stay untouched —
   * scheduling a duplicate periodic task on every {@code finally} block would leak
   * scheduled work into the shared {@code commitExecutor()} queue.
   *
   * <p>This is the contract that makes the {@code WalTestUtils.withWalProtection}
   * {@code finally} block safe even if {@code pauseBackgroundFlush()} threw before
   * setting the flag.
   */
  @Test
  public void testResumeWithoutPauseIsNoOp() throws Exception {
    var cache = Mockito.mock(WOWCache.class, Mockito.CALLS_REAL_METHODS);
    setField(cache, "backgroundFlushPaused", false);

    // sentinelFuture stands in for whatever the constructor would have scheduled
    // (or null if pagesFlushInterval == 0); resume must not replace it.
    var sentinelFuture = new CompletableFuture<Void>();
    setField(cache, "flushFuture", sentinelFuture);

    cache.resumeBackgroundFlush();

    assertFalse(
        "resume without pause must leave backgroundFlushPaused at its prior value",
        getBackgroundFlushPaused(cache));
    assertSame(
        "resume without pause must not schedule a new periodic task",
        sentinelFuture, getFlushFuture(cache));
    Mockito.verify(cache, Mockito.never()).scheduleResumeFlush();
  }

  /**
   * Verifies that {@link WOWCache#resumeBackgroundFlush()} clears the pause flag but
   * does NOT schedule a new periodic task when {@code pagesFlushInterval == 0}. The
   * constructor's gating at the equivalent code site uses the same condition, so resume
   * must mirror it — otherwise a cache built with periodic flush disabled would suddenly
   * start firing periodic flushes after the first pause / resume cycle.
   */
  @Test
  public void testResumeWhenPagesFlushIntervalZeroDoesNotSchedule() throws Exception {
    var cache = Mockito.mock(WOWCache.class, Mockito.CALLS_REAL_METHODS);
    setField(cache, "backgroundFlushPaused", true);
    setField(cache, "pagesFlushInterval", 0L);
    setField(cache, "stopFlush", false);
    setField(cache, "storageName", "test");

    var sentinelFuture = new CompletableFuture<Void>();
    setField(cache, "flushFuture", sentinelFuture);

    cache.resumeBackgroundFlush();

    assertFalse(
        "resume must clear the pause flag",
        getBackgroundFlushPaused(cache));
    assertSame(
        "resume with pagesFlushInterval == 0 must not schedule a new periodic task",
        sentinelFuture, getFlushFuture(cache));
    Mockito.verify(cache, Mockito.never()).scheduleResumeFlush();
  }

  /**
   * Verifies that {@link WOWCache#resumeBackgroundFlush()} clears the pause flag but
   * does NOT schedule a new periodic task when {@code stopFlush == true} (the cache
   * is being shut down). A re-scheduled task after close would either run uselessly
   * or, worse, race with shutdown bookkeeping in {@code stopFlush()}.
   */
  @Test
  public void testResumeWhenStopFlushTrueDoesNotSchedule() throws Exception {
    var cache = Mockito.mock(WOWCache.class, Mockito.CALLS_REAL_METHODS);
    setField(cache, "backgroundFlushPaused", true);
    setField(cache, "pagesFlushInterval", 25L);
    setField(cache, "stopFlush", true);
    setField(cache, "storageName", "test");

    var sentinelFuture = new CompletableFuture<Void>();
    setField(cache, "flushFuture", sentinelFuture);

    cache.resumeBackgroundFlush();

    assertFalse(
        "resume must clear the pause flag",
        getBackgroundFlushPaused(cache));
    assertSame(
        "resume with stopFlush == true must not schedule a new periodic task",
        sentinelFuture, getFlushFuture(cache));
    Mockito.verify(cache, Mockito.never()).scheduleResumeFlush();
  }

  /**
   * Verifies that double-resume is idempotent: the second call's early-return branch
   * is reached when the flag is already false from the first call. Without the guard,
   * resume would schedule a duplicate periodic task on every spurious finally call
   * (e.g., nested {@code WalTestUtils.withWalProtection} usage in future tests).
   */
  @Test
  public void testDoubleResumeDoesNotDoubleSchedule() throws Exception {
    var cache = Mockito.mock(WOWCache.class, Mockito.CALLS_REAL_METHODS);
    setField(cache, "backgroundFlushPaused", false);
    setField(cache, "pagesFlushInterval", 25L);
    setField(cache, "stopFlush", false);
    setField(cache, "storageName", "test");

    var sentinelFuture = new CompletableFuture<Void>();
    setField(cache, "flushFuture", sentinelFuture);

    // First resume hits the early-return because the flag was never set.
    cache.resumeBackgroundFlush();
    // Second resume hits the same early-return.
    cache.resumeBackgroundFlush();

    assertSame(
        "double resume without pause must leave flushFuture untouched",
        sentinelFuture, getFlushFuture(cache));
    Mockito.verify(cache, Mockito.never()).scheduleResumeFlush();
  }

  /**
   * Verifies that the default no-op {@link WriteCache#pauseBackgroundFlush()} /
   * {@link WriteCache#resumeBackgroundFlush()} methods on the interface do not throw
   * for implementations that do not override them (e.g.,
   * {@code DirectMemoryOnlyDiskCache} and the test mocks in {@code chm/*}). This pins
   * the "safe to call unconditionally" contract that
   * {@code WalTestUtils.withWalProtection} relies on.
   */
  @Test
  public void testInterfaceDefaultsAreNoOps() {
    // CALLS_REAL_METHODS routes calls through the default interface methods so
    // we exercise the actual default no-op bodies, not Mockito's auto-stubs.
    WriteCache stub = Mockito.mock(WriteCache.class, Mockito.CALLS_REAL_METHODS);

    stub.pauseBackgroundFlush();
    stub.resumeBackgroundFlush();
    stub.resumeBackgroundFlush(); // idempotent on default path too
    assertTrue("default pause / resume completed without throwing", true);
  }

  /**
   * Verifies the happy path of {@link WOWCache#pauseBackgroundFlush()}: the pause flag is
   * set, the barrier task is awaited, and the currently-scheduled {@code flushFuture} is
   * cancelled with {@code cancel(false)} (NOT {@code cancel(true)} — interrupting a
   * running flusher mid-write is exactly the hazard pause is meant to avoid).
   *
   * <p>The barrier executor seam ({@code submitPauseBarrier()}) is stubbed so the test
   * does not depend on the real global commit executor; what we are pinning here is the
   * method's own flow control — set flag, wait for barrier, cancel pending without
   * interrupt. The full barrier semantics (drains every in-flight
   * {@code AsynchronousFileChannel.write} via {@code IOResult.await()}) are covered by
   * the integration test {@code LocalPaginatedStorageRestoreFromWALIT.testSimpleRestore},
   * which runs in the nightly {@code -P ci-integration-tests} profile.
   */
  @SuppressWarnings("unchecked") // unchecked: generic Future<Object> mock
  @Test
  public void testPauseSetsFlagAndCancelsPendingFuture() throws Exception {
    var cache = Mockito.mock(WOWCache.class, Mockito.CALLS_REAL_METHODS);
    setField(cache, "backgroundFlushPaused", false);

    // Use a Mockito-mocked Future so we can verify the EXACT cancel argument.
    // A real CompletableFuture would not let us distinguish cancel(false) from
    // cancel(true) because CompletableFuture#cancel ignores mayInterruptIfRunning
    // per the JDK contract — making the cancel(true) regression invisible.
    var pending = Mockito.mock(Future.class);
    setField(cache, "flushFuture", pending);

    // Stub the barrier so we don't touch the singleton executor. doReturn form
    // is required because submitPauseBarrier() is dispatched via CALLS_REAL_METHODS.
    var completedBarrier = CompletableFuture.<Object>completedFuture(null);
    Mockito.doReturn(completedBarrier).when(cache).submitPauseBarrier();

    cache.pauseBackgroundFlush();

    assertTrue(
        "pause must set the backgroundFlushPaused flag",
        getBackgroundFlushPaused(cache));
    Mockito.verify(cache).submitPauseBarrier();
    Mockito.verify(pending).cancel(false);
    Mockito.verify(pending, Mockito.never()).cancel(true);
  }

  /**
   * Verifies that {@link WOWCache#pauseBackgroundFlush()} skips the cancel call when
   * {@code flushFuture} is null. The null check is what makes pause safe to call before
   * any periodic task has been scheduled (e.g., when the constructor was given
   * {@code pagesFlushInterval == 0}).
   */
  @Test
  public void testPauseSkipsCancelWhenFlushFutureNull() throws Exception {
    var cache = Mockito.mock(WOWCache.class, Mockito.CALLS_REAL_METHODS);
    setField(cache, "backgroundFlushPaused", false);
    setField(cache, "flushFuture", null);

    var completedBarrier = CompletableFuture.<Object>completedFuture(null);
    Mockito.doReturn(completedBarrier).when(cache).submitPauseBarrier();

    // Must not NPE despite flushFuture being null.
    cache.pauseBackgroundFlush();

    assertTrue(
        "pause must set the backgroundFlushPaused flag even when flushFuture is null",
        getBackgroundFlushPaused(cache));
    assertNull(
        "pause must not write a new future into flushFuture when it started as null",
        getFlushFuture(cache));
    Mockito.verify(cache).submitPauseBarrier();
  }

  /**
   * Verifies that {@link WOWCache#pauseBackgroundFlush()} restores the interrupt flag and
   * throws a {@link ThreadInterruptedException} wrapping the original
   * {@link InterruptedException} when the barrier {@code get()} is interrupted. Pins the
   * exact wrapper subtype and cause chain so a regression that swaps the wrapper for a
   * different {@link BaseException} subclass, or drops the {@code initCause} call, fails
   * loudly. Callers must be able to detect the interruption via {@link Thread#interrupted()}
   * for downstream shutdown handling.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testPauseInterruptedRestoresInterruptFlagAndThrowsBaseException()
      throws Exception {
    var cache = Mockito.mock(WOWCache.class, Mockito.CALLS_REAL_METHODS);
    setField(cache, "backgroundFlushPaused", false);
    setField(cache, "flushFuture", null);
    setField(cache, "storageName", "test");

    // Mock Future whose get() throws InterruptedException. We can't use a real
    // CompletableFuture for this because there is no way to make its get()
    // throw InterruptedException without interrupting the calling thread first
    // (which would race against the actual barrier path under test).
    var barrier = Mockito.mock(Future.class);
    Mockito.when(barrier.get()).thenThrow(new InterruptedException("simulated"));
    Mockito.doReturn(barrier).when(cache).submitPauseBarrier();

    // Clear any stale interrupt flag from prior tests so we can assert on the
    // restoration below.
    Thread.interrupted();

    try {
      cache.pauseBackgroundFlush();
      fail("expected ThreadInterruptedException wrapping the InterruptedException");
    } catch (ThreadInterruptedException e) {
      // Thread.interrupted() both reads and clears; the @After also clears, but the
      // assertion here pins that the catch block restored the flag at the moment of throw.
      assertTrue(
          "interrupt flag must be restored by the catch block before throwing",
          Thread.interrupted());
      assertTrue(
          "cause must be the original InterruptedException so operators can diagnose",
          e.getCause() instanceof InterruptedException);
      assertEquals(
          "original interrupt message must be preserved in the cause chain",
          "simulated", e.getCause().getMessage());
      assertTrue(
          "flag must be set before the barrier wait, so the pause flag remains true "
              + "even on a failed pause",
          getBackgroundFlushPaused(cache));
    }
  }

  /**
   * Verifies that {@link WOWCache#pauseBackgroundFlush()} does NOT cancel the
   * pending {@code flushFuture} when the barrier {@code get()} throws — the re-throw
   * exits the method before reaching the cancel block. Pins the semantic so a future
   * refactor that hoists the cancel into the catch handler is caught: hoisting would
   * interrupt a flusher that the failed pause did not actually drain, breaking the
   * very invariant pause was added to protect.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testPauseInterruptedDoesNotCancelPendingFlushFuture() throws Exception {
    var cache = Mockito.mock(WOWCache.class, Mockito.CALLS_REAL_METHODS);
    setField(cache, "backgroundFlushPaused", false);
    setField(cache, "storageName", "test");

    // Non-null pending future; the catch path must NOT cancel it. Mockito-mocked
    // so we can verify cancel was never invoked on either argument value.
    var pending = Mockito.mock(Future.class);
    setField(cache, "flushFuture", pending);

    var barrier = Mockito.mock(Future.class);
    Mockito.when(barrier.get()).thenThrow(new InterruptedException("simulated"));
    Mockito.doReturn(barrier).when(cache).submitPauseBarrier();

    Thread.interrupted();

    try {
      cache.pauseBackgroundFlush();
      fail("expected ThreadInterruptedException");
    } catch (ThreadInterruptedException e) {
      Mockito.verify(pending, Mockito.never()).cancel(Mockito.anyBoolean());
    }
  }

  /**
   * Verifies that {@link WOWCache#pauseBackgroundFlush()} wraps a barrier
   * {@link ExecutionException} as a {@link WriteCacheException} and preserves the full
   * cause chain ({@code WriteCacheException → ExecutionException → original cause}) so
   * operators can diagnose the underlying executor failure. Also pins that this path
   * does NOT touch the thread interrupt flag (only the InterruptedException path does)
   * and that the pause flag remains set after the throw (mirroring the interrupted-path
   * invariant — both failure paths leave the caller responsible for clearing state).
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testPauseExecutionExceptionWrapsAsBaseException() throws Exception {
    var cache = Mockito.mock(WOWCache.class, Mockito.CALLS_REAL_METHODS);
    setField(cache, "backgroundFlushPaused", false);
    setField(cache, "flushFuture", null);
    setField(cache, "storageName", "test");

    var barrier = Mockito.mock(Future.class);
    Mockito.when(barrier.get())
        .thenThrow(new ExecutionException(new RuntimeException("executor down")));
    Mockito.doReturn(barrier).when(cache).submitPauseBarrier();

    // Pre-clear the interrupt flag so the post-condition assertion is meaningful.
    Thread.interrupted();

    try {
      cache.pauseBackgroundFlush();
      fail("expected WriteCacheException wrapping the ExecutionException");
    } catch (WriteCacheException e) {
      assertTrue(
          "wrapped cause must be the ExecutionException raised by the barrier",
          e.getCause() instanceof ExecutionException);
      assertTrue(
          "ExecutionException must preserve the underlying root cause for operator diagnosis",
          e.getCause().getCause() instanceof RuntimeException);
      assertEquals(
          "root-cause message must survive the wrap",
          "executor down", e.getCause().getCause().getMessage());
      assertFalse(
          "ExecutionException path must NOT set the interrupt flag (only the "
              + "InterruptedException path restores it)",
          Thread.interrupted());
      assertTrue(
          "flag must be set before the barrier wait, so the pause flag remains true "
              + "even on a failed pause",
          getBackgroundFlushPaused(cache));
    }
  }

  /**
   * Verifies the happy path of {@link WOWCache#resumeBackgroundFlush()}: when the pause
   * flag is set, {@code pagesFlushInterval > 0}, and {@code stopFlush == false}, resume
   * clears the flag and writes the newly-scheduled future into {@code flushFuture}. The
   * schedule seam ({@code scheduleResumeFlush()}) is stubbed so we verify the wiring
   * without depending on the real executor or instantiating a PeriodicFlushTask against
   * a partly-built mock.
   */
  @Test
  public void testResumeSchedulesNewPeriodicTaskWhenIntervalPositive() throws Exception {
    var cache = Mockito.mock(WOWCache.class, Mockito.CALLS_REAL_METHODS);
    setField(cache, "backgroundFlushPaused", true);
    setField(cache, "pagesFlushInterval", 25L);
    setField(cache, "stopFlush", false);

    // Pre-existing flushFuture (e.g., a cancelled one left by pause) must be
    // replaced by the freshly-scheduled future.
    var previousFuture = new CompletableFuture<Void>();
    setField(cache, "flushFuture", previousFuture);

    var newScheduledFuture = new CompletableFuture<Void>();
    Mockito.doReturn(newScheduledFuture).when(cache).scheduleResumeFlush();

    cache.resumeBackgroundFlush();

    assertFalse(
        "resume must clear the pause flag",
        getBackgroundFlushPaused(cache));
    assertSame(
        "resume must write the freshly-scheduled future into flushFuture",
        newScheduledFuture, getFlushFuture(cache));
    Mockito.verify(cache).scheduleResumeFlush();
  }

  // ---------------------------------------------------------------------------
  // Helper methods (reflection access to private fields — mirrors the pattern
  // used by WOWCacheFlushErrorTest in this same package).
  // ---------------------------------------------------------------------------

  private static boolean getBackgroundFlushPaused(WOWCache cache) throws Exception {
    Field field = findField(cache.getClass(), "backgroundFlushPaused");
    field.setAccessible(true);
    return (boolean) field.get(cache);
  }

  private static Future<?> getFlushFuture(WOWCache cache) throws Exception {
    Field field = findField(cache.getClass(), "flushFuture");
    field.setAccessible(true);
    return (Future<?>) field.get(cache);
  }

  private static void setField(Object target, String fieldName, Object value)
      throws Exception {
    Field field = findField(target.getClass(), fieldName);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static Field findField(Class<?> clazz, String fieldName)
      throws NoSuchFieldException {
    while (clazz != null) {
      try {
        return clazz.getDeclaredField(fieldName);
      } catch (NoSuchFieldException ignored) {
        clazz = clazz.getSuperclass();
      }
    }
    throw new NoSuchFieldException(fieldName);
  }
}
