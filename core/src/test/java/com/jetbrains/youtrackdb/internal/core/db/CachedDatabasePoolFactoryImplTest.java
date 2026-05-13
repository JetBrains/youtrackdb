package com.jetbrains.youtrackdb.internal.core.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.config.YouTrackDBConfig;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import org.junit.Test;

/**
 * Pins {@link CachedDatabasePoolFactoryImpl} — the LRU pool cache used by
 * {@link YouTrackDBInternalEmbedded} for {@code cachedPool(...)}: cache
 * hit/miss, parent-config wiring, no-authentication path, reset, idempotent
 * close, the closed-factory rejection, and {@link
 * CachedDatabasePoolFactoryImpl#getMaxPoolSize() max-pool-size} accessors.
 *
 * <p>Each test that creates a factory closes it in a {@code finally} block to
 * cancel the periodic clean-up task on the shared scheduled pool.
 */
public class CachedDatabasePoolFactoryImplTest extends DbTestBase {

  // Long timeout (60 s) so the scheduled clean-up task does not fire during
  // the test body. Functional behaviour does not depend on the cleanup
  // running; the dedicated cleanUpCacheClosesUnusedExpiredPools test below
  // exercises that path with a tight timeout.
  private static final long LONG_TIMEOUT_MS = 60_000L;

  // getOrCreate — first call for (database, user) is a cache miss and
  // produces a freshly created pool. Pin: result is non-null and not closed
  // (so the factory is correctly returning a live pool, not a placeholder).
  @Test
  public void getOrCreateCacheMissCreatesLivePool() {
    var factory = new CachedDatabasePoolFactoryImpl(
        youTrackDB.internal, 100, LONG_TIMEOUT_MS);
    try {
      var pool = factory.getOrCreate(
          databaseName, adminUser, adminPassword, null);
      try {
        assertNotNull("Cache miss must create a non-null pool", pool);
        assertFalse("Newly created pool must not be closed", pool.isClosed());
      } finally {
        pool.close();
      }
    } finally {
      factory.close();
    }
  }

  // getOrCreate — second call with the same (database, user) is a cache hit
  // and returns the same pool instance. Pin via assertSame to catch a
  // regression where the factory accidentally created a new pool per call.
  @Test
  public void getOrCreateCacheHitReturnsSameInstance() {
    var factory = new CachedDatabasePoolFactoryImpl(
        youTrackDB.internal, 100, LONG_TIMEOUT_MS);
    try {
      var first = factory.getOrCreate(
          databaseName, adminUser, adminPassword, null);
      var second = factory.getOrCreate(
          databaseName, adminUser, adminPassword, null);
      try {
        assertSame("Cache hit must return the same pool instance",
            first, second);
      } finally {
        first.close();
        // second is the same reference; double-close is idempotent on
        // DatabasePoolImpl per its existing tests.
      }
    } finally {
      factory.close();
    }
  }

  // getOrCreate — when the cached pool was externally closed, the next call
  // detects pool.isClosed() and creates a fresh pool. Pin via assertNotSame
  // (i.e., the second call must NOT return the closed instance).
  @Test
  public void getOrCreateClosedCachedPoolIsReplacedByFreshOne() {
    var factory = new CachedDatabasePoolFactoryImpl(
        youTrackDB.internal, 100, LONG_TIMEOUT_MS);
    try {
      var first = factory.getOrCreate(
          databaseName, adminUser, adminPassword, null);
      first.close();
      assertTrue("Sanity: first pool should be closed after explicit close",
          first.isClosed());

      var second = factory.getOrCreate(
          databaseName, adminUser, adminPassword, null);
      try {
        assertFalse(
            "Replacement pool must be live, not the closed cached entry",
            second.isClosed());
      } finally {
        second.close();
      }
    } finally {
      factory.close();
    }
  }

  // getOrCreate — when a non-null parent config is supplied, the factory
  // sets it as the parent on the per-pool config so the pool resolves
  // configuration values via the parent chain. Verify reachability of the
  // setParent branch by exercising getOrCreate with a real config.
  @Test
  public void getOrCreateWithParentConfigDoesNotThrow() {
    var factory = new CachedDatabasePoolFactoryImpl(
        youTrackDB.internal, 100, LONG_TIMEOUT_MS);
    try {
      var parent = (YouTrackDBConfigImpl) YouTrackDBConfig.builder()
          .addGlobalConfigurationParameter(
              GlobalConfiguration.DB_POOL_MIN, 1)
          .build();
      var pool = factory.getOrCreate(
          databaseName, adminUser, adminPassword, parent);
      try {
        assertNotNull(pool);
      } finally {
        pool.close();
      }
    } finally {
      factory.close();
    }
  }

  // getOrCreateNoAuthentication — happy path mirroring getOrCreate, but via
  // YouTrackDBInternalEmbedded.poolOpenNoAuthenticate. Parent config arg is
  // null so the no-parent branch is exercised.
  @Test
  public void getOrCreateNoAuthenticationCreatesLivePool() {
    var factory = new CachedDatabasePoolFactoryImpl(
        youTrackDB.internal, 100, LONG_TIMEOUT_MS);
    try {
      var pool = factory.getOrCreateNoAuthentication(
          databaseName, adminUser, null);
      try {
        assertNotNull(pool);
        assertFalse(pool.isClosed());
      } finally {
        pool.close();
      }
    } finally {
      factory.close();
    }
  }

  // getOrCreateNoAuthentication — second call hits the cache.
  @Test
  public void getOrCreateNoAuthenticationCacheHitReturnsSameInstance() {
    var factory = new CachedDatabasePoolFactoryImpl(
        youTrackDB.internal, 100, LONG_TIMEOUT_MS);
    try {
      var first = factory.getOrCreateNoAuthentication(
          databaseName, adminUser, null);
      var second = factory.getOrCreateNoAuthentication(
          databaseName, adminUser, null);
      try {
        assertSame(first, second);
      } finally {
        first.close();
      }
    } finally {
      factory.close();
    }
  }

  // getOrCreateNoAuthentication — closed cached pool is replaced.
  @Test
  public void getOrCreateNoAuthenticationClosedPoolIsReplaced() {
    var factory = new CachedDatabasePoolFactoryImpl(
        youTrackDB.internal, 100, LONG_TIMEOUT_MS);
    try {
      var first = factory.getOrCreateNoAuthentication(
          databaseName, adminUser, null);
      first.close();

      var second = factory.getOrCreateNoAuthentication(
          databaseName, adminUser, null);
      try {
        assertFalse(second.isClosed());
      } finally {
        second.close();
      }
    } finally {
      factory.close();
    }
  }

  // getOrCreateNoAuthentication — parent config wiring path mirrors
  // getOrCreateWithParentConfigDoesNotThrow.
  @Test
  public void getOrCreateNoAuthenticationWithParentConfigDoesNotThrow() {
    var factory = new CachedDatabasePoolFactoryImpl(
        youTrackDB.internal, 100, LONG_TIMEOUT_MS);
    try {
      var parent = (YouTrackDBConfigImpl) YouTrackDBConfig.builder()
          .addGlobalConfigurationParameter(
              GlobalConfiguration.DB_POOL_MIN, 1)
          .build();
      var pool = factory.getOrCreateNoAuthentication(
          databaseName, adminUser, parent);
      try {
        assertNotNull(pool);
      } finally {
        pool.close();
      }
    } finally {
      factory.close();
    }
  }

  // reset — closes every cached pool and clears the cache. Subsequent
  // getOrCreate must return a fresh pool, not a stale closed reference.
  @Test
  public void resetClosesCachedPoolsAndClearsTheCache() {
    var factory = new CachedDatabasePoolFactoryImpl(
        youTrackDB.internal, 100, LONG_TIMEOUT_MS);
    try {
      var first = factory.getOrCreate(
          databaseName, adminUser, adminPassword, null);

      var resetReturn = factory.reset();
      assertSame("reset must return the factory itself for fluent chaining",
          factory, resetReturn);

      assertTrue("reset must close every cached pool", first.isClosed());

      var second = factory.getOrCreate(
          databaseName, adminUser, adminPassword, null);
      try {
        assertFalse("getOrCreate after reset must produce a live pool",
            second.isClosed());
      } finally {
        second.close();
      }
    } finally {
      factory.close();
    }
  }

  // close — sets the closed flag and closes all cached pools. Subsequent
  // calls to getOrCreate / getOrCreateNoAuthentication must throw.
  @Test
  public void closeRejectsSubsequentGetOrCreate() {
    var factory = new CachedDatabasePoolFactoryImpl(
        youTrackDB.internal, 100, LONG_TIMEOUT_MS);
    factory.close();
    assertTrue("Factory must report closed after close()", factory.isClosed());

    try {
      factory.getOrCreate(databaseName, adminUser, adminPassword, null);
      fail("Expected IllegalStateException after factory close");
    } catch (IllegalStateException e) {
      // pin the exact production message so a documentation refactor must
      // update the test in lockstep.
      assertEquals("Cached pool factory is closed!", e.getMessage());
    }

    try {
      factory.getOrCreateNoAuthentication(databaseName, adminUser, null);
      fail("Expected IllegalStateException after factory close");
    } catch (IllegalStateException e) {
      assertNotNull(e.getMessage());
    }
  }

  // close — calling close twice is idempotent (the closed flag short-
  // circuits the second invocation).
  @Test
  public void closeIsIdempotent() {
    var factory = new CachedDatabasePoolFactoryImpl(
        youTrackDB.internal, 100, LONG_TIMEOUT_MS);
    factory.close();
    factory.close();
    assertTrue(factory.isClosed());
  }

  // setMaxPoolSize / getMaxPoolSize — fluent setter returns the factory
  // and the getter reflects the new value. Default is read from
  // GlobalConfiguration.DB_POOL_MAX, which is the same value the factory
  // sees during construction.
  @Test
  public void setMaxPoolSizeIsFluentAndPersists() {
    var factory = new CachedDatabasePoolFactoryImpl(
        youTrackDB.internal, 100, LONG_TIMEOUT_MS);
    try {
      var defaultMax = factory.getMaxPoolSize();
      assertEquals(
          "Default max pool size must equal GlobalConfiguration.DB_POOL_MAX",
          (int) GlobalConfiguration.DB_POOL_MAX.getValueAsInteger(),
          defaultMax);

      var setReturn = factory.setMaxPoolSize(7);
      assertSame("setMaxPoolSize must return the factory for fluent chaining",
          factory, setReturn);
      assertEquals(7, factory.getMaxPoolSize());
    } finally {
      factory.close();
    }
  }

  // cleanUpCache — runs periodically once eviction interval elapses. With a
  // tight timeout (50 ms) the scheduled task fires almost immediately. After
  // closing a pool externally, the next clean-up sweep must remove the closed
  // entry from the private poolCache map. The previous shape of this test
  // observed only that a follow-up getOrCreate produced a live pool, but
  // getOrCreate's own retry path replaces a closed cached entry inline — so
  // a broken sweep would still return a live pool from getOrCreate. Pin the
  // sweep itself by polling the private poolCache field directly: the size
  // must drop to zero from the sweep, before any second getOrCreate is made.
  @Test
  public void cleanUpCacheRemovesClosedPoolsAfterPeriodicSweep()
      throws Exception {
    // 50 ms eviction interval — short enough for the test to observe a sweep
    // within the poll window below.
    var factory = new CachedDatabasePoolFactoryImpl(
        youTrackDB.internal, 100, 50);
    try {
      var first = factory.getOrCreate(
          databaseName, adminUser, adminPassword, null);
      first.close();

      // Read the private poolCache map reflectively; the sweep is the only
      // path that removes a closed-but-not-replaced entry without a parallel
      // getOrCreate(). Falsifier: a future change that drops the periodic
      // schedule, or that no-ops cleanUpCache, leaves the closed entry in
      // the map and this poll times out.
      var poolCache = readPoolCache(factory);
      pollUntilEmpty(poolCache, 5_000L);
      assertEquals("periodic sweep must remove the closed pool from the cache",
          0, poolCache.size());
    } finally {
      factory.close();
    }
  }

  // After close, the periodic clean-up task observes the closed flag and
  // cancels itself via cleanUpFuture.cancel(false). Pin via reflection on the
  // private cleanUpFuture field: it must be cancelled within a small grace
  // window. (The previous shape asserted only factory.isClosed(), which the
  // synchronous close() flag flip satisfies regardless of cancellation.)
  @Test
  public void closeCancelsPeriodicCleanUpTask() throws Exception {
    var factory = new CachedDatabasePoolFactoryImpl(
        youTrackDB.internal, 100, 50);
    factory.close();

    // The cleanUpFuture is volatile and cancelled inside close(); poll for
    // up to 1 s to absorb scheduler thread hop. Falsifier: a future change
    // that drops the cancel call leaves cleanUpFuture in a non-cancelled,
    // potentially still-firing state.
    var future = readCleanUpFuture(factory);
    var deadline = System.nanoTime() + 1_000_000_000L;
    while (!future.isCancelled() && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertTrue("close must cancel the periodic clean-up future",
        future.isCancelled());
    assertTrue(factory.isClosed());
  }

  // ---------------------------------------------------------------------------
  // Reflection helpers — used to pin behaviour the public API does not expose.
  //
  // Both readers target {@code private} fields on
  // {@link CachedDatabasePoolFactoryImpl}: a follow-up that exposes a
  // package-private accessor on the factory would let these helpers go away.
  // ---------------------------------------------------------------------------

  private static Map<?, ?> readPoolCache(CachedDatabasePoolFactoryImpl factory)
      throws Exception {
    Field f = CachedDatabasePoolFactoryImpl.class.getDeclaredField("poolCache");
    f.setAccessible(true);
    return (Map<?, ?>) f.get(factory);
  }

  private static ScheduledFuture<?> readCleanUpFuture(
      CachedDatabasePoolFactoryImpl factory) throws Exception {
    Field f = CachedDatabasePoolFactoryImpl.class.getDeclaredField("cleanUpFuture");
    f.setAccessible(true);
    return (ScheduledFuture<?>) f.get(factory);
  }

  private static void pollUntilEmpty(Map<?, ?> map, long timeoutMs)
      throws InterruptedException {
    var deadline = System.nanoTime() + timeoutMs * 1_000_000L;
    while (!map.isEmpty() && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
  }
}
