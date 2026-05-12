package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
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
