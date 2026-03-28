package com.jetbrains.youtrackdb.internal.core.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.common.concur.lock.ThreadInterruptedException;
import com.jetbrains.youtrackdb.internal.core.config.YouTrackDBConfig;
import com.jetbrains.youtrackdb.internal.core.exception.AcquireTimeoutException;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.commons.configuration2.BaseConfiguration;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * Tests for {@link DatabasePoolImpl} covering lifecycle, error paths,
 * and concurrent access after the synchronized-to-AtomicReference migration.
 */
public class DatabasePoolImplTest {

  private static final String PASSWORD = "adminpwd";

  private YouTrackDBImpl createYouTrackDB() {
    var config = new BaseConfiguration();
    config.setProperty(GlobalConfiguration.CREATE_DEFAULT_USERS.getKey(), false);

    return (YouTrackDBImpl) YourTracks.instance(
        DbTestBase.getBaseDirectoryPathStr(getClass()), config);
  }

  private YouTrackDBImpl createYouTrackDB(BaseConfiguration config) {
    return (YouTrackDBImpl) YourTracks.instance(
        DbTestBase.getBaseDirectoryPathStr(getClass()), config);
  }

  private void createDatabase(YouTrackDBImpl youTrackDb, String dbName) {
    youTrackDb.createIfNotExists(dbName, DatabaseType.MEMORY,
        new LocalUserCredential("admin", PASSWORD, PredefinedLocalRole.ADMIN));
  }

  private DatabasePoolImpl createPool(YouTrackDBImpl youTrackDb, String dbName) {
    return new DatabasePoolImpl(
        youTrackDb.internal, dbName, "admin", PASSWORD,
        (YouTrackDBConfigImpl) YouTrackDBConfig.defaultConfig());
  }

  private DatabasePoolImpl createPool(
      YouTrackDBImpl youTrackDb, String dbName, BaseConfiguration config) {
    var poolConfig = YouTrackDBConfig.builder()
        .fromApacheConfiguration(config).build();
    return new DatabasePoolImpl(
        youTrackDb.internal, dbName, "admin", PASSWORD,
        (YouTrackDBConfigImpl) poolConfig);
  }

  // Verifies that acquire() on a closed pool throws DatabaseException, not NPE.
  @Test
  public void acquireOnClosedPoolThrowsDatabaseException() {
    var youTrackDb = createYouTrackDB();
    try {
      createDatabase(youTrackDb, "testAcquireClosed");
      var pool = createPool(youTrackDb, "testAcquireClosed");
      pool.close();

      try {
        pool.acquire();
        fail("Expected DatabaseException");
      } catch (DatabaseException e) {
        assertEquals("The pool is closed", e.getMessage());
      }
    } finally {
      youTrackDb.close();
    }
  }

  // Verifies that release() on a closed pool throws DatabaseException.
  // Regression test for the bug where release() used the field `pool` instead
  // of the local snapshot `p`, which could NPE under concurrent close().
  @Test
  public void releaseOnClosedPoolThrowsDatabaseException() {
    var youTrackDb = createYouTrackDB();
    try {
      createDatabase(youTrackDb, "testReleaseClosed");
      var pool = createPool(youTrackDb, "testReleaseClosed");
      var session = pool.acquire();
      pool.close();

      try {
        pool.release(session);
        fail("Expected DatabaseException");
      } catch (DatabaseException e) {
        // release() uses the two-arg constructor which appends DB Name
        assertTrue("Expected 'The pool is closed' prefix, got: "
            + e.getMessage(),
            e.getMessage().startsWith("The pool is closed"));
      }
    } finally {
      youTrackDb.close();
    }
  }

  // Verifies that lastCloseTime is NOT updated when release() throws on a
  // closed pool. If lastCloseTime were updated on failed releases, eviction
  // logic would believe the pool is recently active when it is actually closed.
  @Test
  public void releaseOnClosedPoolDoesNotUpdateLastCloseTime() {
    var youTrackDb = createYouTrackDB();
    try {
      createDatabase(youTrackDb, "testReleaseClosedTime");
      var pool = createPool(youTrackDb, "testReleaseClosedTime");
      var session = pool.acquire();
      pool.close();

      long timeBeforeRelease = pool.getLastCloseTime();
      try {
        pool.release(session);
        fail("Expected DatabaseException");
      } catch (DatabaseException e) {
        assertTrue("Expected 'The pool is closed' prefix, got: "
            + e.getMessage(),
            e.getMessage().startsWith("The pool is closed"));
      }
      assertEquals("lastCloseTime must not update when pool is closed",
          timeBeforeRelease, pool.getLastCloseTime());
    } finally {
      youTrackDb.close();
    }
  }

  // Verifies that getAvailableResources() on a closed pool throws
  // DatabaseException instead of NPE.
  @Test
  public void getAvailableResourcesOnClosedPoolThrowsDatabaseException() {
    var youTrackDb = createYouTrackDB();
    try {
      createDatabase(youTrackDb, "testAvailClosed");
      var pool = createPool(youTrackDb, "testAvailClosed");
      pool.close();

      try {
        pool.getAvailableResources();
        fail("Expected DatabaseException");
      } catch (DatabaseException e) {
        assertEquals("The pool is closed", e.getMessage());
      }
    } finally {
      youTrackDb.close();
    }
  }

  // Verifies that close() is idempotent — calling it twice does not throw.
  @Test
  public void doubleCloseIsIdempotent() {
    var youTrackDb = createYouTrackDB();
    try {
      createDatabase(youTrackDb, "testDoubleClose");
      var pool = createPool(youTrackDb, "testDoubleClose");

      pool.close();
      assertTrue("Pool should be closed after first close", pool.isClosed());

      pool.close();
      assertTrue("Pool should remain closed after second close",
          pool.isClosed());
    } finally {
      youTrackDb.close();
    }
  }

  // Verifies isClosed(), isUnused(), and getAvailableResources() state
  // transitions through the pool lifecycle.
  @Test
  public void poolStateTransitions() {
    var config = new BaseConfiguration();
    config.setProperty(GlobalConfiguration.CREATE_DEFAULT_USERS.getKey(), false);
    config.setProperty(GlobalConfiguration.DB_POOL_MAX.getKey(), 2);

    var youTrackDb = createYouTrackDB(config);
    try {
      createDatabase(youTrackDb, "testStateTransitions");
      var pool = createPool(youTrackDb, "testStateTransitions", config);

      assertFalse(pool.isClosed());
      assertTrue(pool.isUnused());
      int initialAvailable = pool.getAvailableResources();
      assertEquals(2, initialAvailable);

      var session = pool.acquire();
      assertFalse(pool.isUnused());
      assertEquals(initialAvailable - 1, pool.getAvailableResources());

      session.close();
      assertTrue(pool.isUnused());
      assertEquals(initialAvailable, pool.getAvailableResources());

      pool.close();
      assertTrue(pool.isClosed());
      // A closed pool reports as unused so callers checking isUnused()
      // for eviction don't need a separate isClosed() guard.
      assertTrue(pool.isUnused());
    } finally {
      youTrackDb.close();
    }
  }

  // Verifies that the no-password constructor with a non-embedded factory
  // throws UnsupportedOperationException. The ResourcePool pre-allocates min
  // resources during construction (DB_POOL_MIN defaults to 1), so the
  // exception is thrown at construction time when createNewResource triggers
  // the session factory lambda.
  @Test
  public void noPasswordConstructorWithNonEmbeddedFactoryThrows() {
    // Mock is not an instance of YouTrackDBInternalEmbedded, triggering the
    // UnsupportedOperationException path in the session factory lambda.
    var nonEmbeddedFactory = Mockito.mock(YouTrackDBInternal.class);

    try {
      new DatabasePoolImpl(
          nonEmbeddedFactory, "testNoPassNonEmbedded", "admin",
          (YouTrackDBConfigImpl) YouTrackDBConfig.defaultConfig());
      fail("Expected UnsupportedOperationException");
    } catch (UnsupportedOperationException e) {
      assertEquals("Opening database without password is not supported",
          e.getMessage());
    }
  }

  // Verifies the no-password constructor works with an embedded factory,
  // exercising the poolOpenNoAuthenticate path. Confirms the session is
  // usable and that closing it returns the resource to the pool.
  @Test
  public void noPasswordConstructorWithEmbeddedFactory() {
    var youTrackDb = createYouTrackDB();
    try {
      createDatabase(youTrackDb, "testNoPassEmbedded");
      var pool = new DatabasePoolImpl(
          youTrackDb.internal, "testNoPassEmbedded", "admin",
          (YouTrackDBConfigImpl) YouTrackDBConfig.defaultConfig());

      try {
        assertFalse(pool.isClosed());
        int availableBefore = pool.getAvailableResources();

        var session = pool.acquire();
        assertFalse(session.isClosed());
        assertEquals("testNoPassEmbedded", session.getDatabaseName());
        assertEquals(availableBefore - 1, pool.getAvailableResources());

        session.close();
        assertEquals("Session close should return resource to pool",
            availableBefore, pool.getAvailableResources());
        assertTrue(pool.isUnused());
      } finally {
        pool.close();
      }
    } finally {
      youTrackDb.close();
    }
  }

  // Verifies that acquiring from an exhausted pool throws
  // AcquireTimeoutException after the configured timeout, and that the
  // timeout does not corrupt pool state.
  @Test
  public void acquireOnExhaustedPoolThrowsAcquireTimeoutException() {
    var config = new BaseConfiguration();
    config.setProperty(GlobalConfiguration.CREATE_DEFAULT_USERS.getKey(), false);
    config.setProperty(GlobalConfiguration.DB_POOL_MAX.getKey(), 1);
    // 500 ms: short enough for a fast test, long enough to avoid
    // flakiness on loaded CI agents
    config.setProperty(
        GlobalConfiguration.DB_POOL_ACQUIRE_TIMEOUT.getKey(), 500);

    var youTrackDb = createYouTrackDB(config);
    try {
      createDatabase(youTrackDb, "testExhaustedPool");
      var pool = createPool(youTrackDb, "testExhaustedPool", config);

      var session = pool.acquire();
      try {
        pool.acquire();
        fail("Expected AcquireTimeoutException");
      } catch (AcquireTimeoutException e) {
        // Timeout must not close the pool or corrupt state
        assertFalse("Pool must remain open after acquire timeout",
            pool.isClosed());
        assertEquals("All permits still held by the first session",
            0, pool.getAvailableResources());
      } finally {
        session.close();
        pool.close();
      }
    } finally {
      youTrackDb.close();
    }
  }

  // Verifies that with DB_POOL_MIN=0, no resources are pre-allocated at
  // construction time and the first acquire() lazily creates a session.
  // This exercises a different code path in ResourcePool than min>=1.
  @Test
  public void poolWithMinZeroLazilyCreatesResources() {
    var config = new BaseConfiguration();
    config.setProperty(GlobalConfiguration.CREATE_DEFAULT_USERS.getKey(), false);
    config.setProperty(GlobalConfiguration.DB_POOL_MIN.getKey(), 0);
    config.setProperty(GlobalConfiguration.DB_POOL_MAX.getKey(), 2);

    var youTrackDb = createYouTrackDB(config);
    try {
      createDatabase(youTrackDb, "testMinZero");
      var pool = createPool(youTrackDb, "testMinZero", config);

      try {
        // With min=0, no resources are created upfront; all max permits
        // available
        assertEquals(2, pool.getAvailableResources());

        var session = pool.acquire();
        assertFalse(session.isClosed());
        assertEquals(1, pool.getAvailableResources());

        session.close();
        assertEquals(2, pool.getAvailableResources());
        assertTrue(pool.isUnused());
      } finally {
        pool.close();
      }
    } finally {
      youTrackDb.close();
    }
  }

  // Verifies that lastCloseTime advances after a session is released,
  // and that the volatile write is visible from a different thread.
  // lastCloseTime drives pool eviction decisions.
  @Test
  public void lastCloseTimeUpdatesAfterRelease() throws Exception {
    var youTrackDb = createYouTrackDB();
    try {
      createDatabase(youTrackDb, "testLastCloseTime");
      var pool = createPool(youTrackDb, "testLastCloseTime");

      // Capture a strict lower bound right before the release.
      // Acquire and release happen on a separate thread (pooled sessions
      // are thread-local, so both must happen on the same thread).
      // The CountDownLatch establishes happens-before between the
      // volatile write in release() (before countDown) and the read below.
      long beforeRelease = System.currentTimeMillis();

      var released = new CountDownLatch(1);
      var releaseThread = new Thread(() -> {
        var session = pool.acquire();
        session.close();
        released.countDown();
      });
      releaseThread.start();

      assertTrue("Release thread timed out",
          released.await(10, TimeUnit.SECONDS));
      releaseThread.join(10_000);

      long after = pool.getLastCloseTime();
      assertTrue("lastCloseTime must be >= the pre-release timestamp;"
          + " beforeRelease=" + beforeRelease + " after=" + after,
          after >= beforeRelease);

      pool.close();
    } finally {
      youTrackDb.close();
    }
  }

  // Verifies that multiple threads can concurrently acquire and release
  // sessions without corruption. Uses pool max=2 with 8 threads to force
  // heavy semaphore contention and exercise the ConcurrentLinkedQueue
  // poll loop under concurrent access.
  @Test
  public void concurrentAcquireAndRelease() throws Exception {
    var config = new BaseConfiguration();
    config.setProperty(GlobalConfiguration.CREATE_DEFAULT_USERS.getKey(), false);
    config.setProperty(GlobalConfiguration.DB_POOL_MAX.getKey(), 2);

    var youTrackDb = createYouTrackDB(config);
    try {
      createDatabase(youTrackDb, "testConcurrentPool");
      var pool = createPool(youTrackDb, "testConcurrentPool", config);

      int poolMax = config.getInt(GlobalConfiguration.DB_POOL_MAX.getKey());
      // 8 threads vs 2 pool slots forces 6 threads to block at any time,
      // maximising semaphore contention
      int threadCount = 8;
      int iterations = 50;
      var barrier = new CyclicBarrier(threadCount);
      var done = new CountDownLatch(threadCount);
      var firstError = new AtomicReference<Throwable>();

      for (int t = 0; t < threadCount; t++) {
        new Thread(() -> {
          try {
            barrier.await(10, TimeUnit.SECONDS);
            for (int i = 0; i < iterations; i++) {
              var session = pool.acquire();
              try {
                session.getMetadata();
              } finally {
                session.close();
              }
            }
          } catch (Exception e) {
            firstError.compareAndSet(null, e);
          } finally {
            done.countDown();
          }
        }).start();
      }

      assertTrue("Timed out waiting for threads",
          done.await(60, TimeUnit.SECONDS));
      if (firstError.get() != null) {
        throw new AssertionError(
            "Worker thread failed", firstError.get());
      }

      // Verify pool state is fully consistent after concurrent workload
      assertTrue("Pool should be unused after all sessions released",
          pool.isUnused());
      assertEquals("All semaphore permits should be restored",
          poolMax, pool.getAvailableResources());

      pool.close();
    } finally {
      youTrackDb.close();
    }
  }

  // Verifies that closing a pool while threads are actively acquiring
  // sessions does not deadlock. Workers and the main thread start together
  // via a CyclicBarrier to maximize the chance of overlapping acquire()
  // and close() calls. Uses max=8 so that close()'s semaphore drain does
  // not block threads mid-acquire indefinitely.
  //
  // Expected exceptions during the race:
  //   1. DatabaseException("The pool is closed") — acquire()/release()
  //      when the pool AtomicReference is already null.
  //   2. DatabaseException("Database '...' is closed") — session operations
  //      (e.g. getMetadata()) when pool.close() has already called
  //      realClose() on the underlying session.
  //   3. AcquireTimeoutException — threads blocked on the semaphore after
  //      close() drained all permits. A short DB_POOL_ACQUIRE_TIMEOUT
  //      (5 seconds) ensures these threads unblock promptly rather than
  //      waiting the default 60 seconds, which would exceed the test
  //      timeout on slow CI runners (e.g. ARM).
  //   4. ThreadInterruptedException — surefire may interrupt threads when
  //      forkedProcessExitTimeoutInSeconds fires on overloaded runners.
  @Test
  public void closeDuringConcurrentAcquireDoesNotHang() throws Exception {
    var config = new BaseConfiguration();
    config.setProperty(GlobalConfiguration.CREATE_DEFAULT_USERS.getKey(), false);
    config.setProperty(GlobalConfiguration.DB_POOL_MAX.getKey(), 8);
    // Keep acquire timeout short so threads blocked on the semaphore after
    // close() drains permits unblock within a few seconds. The default
    // 60 s exceeds the test's done.await() timeout on slow ARM runners.
    config.setProperty(
        GlobalConfiguration.DB_POOL_ACQUIRE_TIMEOUT.getKey(), 5_000);

    var youTrackDb = createYouTrackDB(config);
    try {
      createDatabase(youTrackDb, "testCloseDuringAcquire");
      var pool = createPool(youTrackDb, "testCloseDuringAcquire", config);

      int threadCount = 4;
      // +1 for the main thread so all start simultaneously
      var barrier = new CyclicBarrier(threadCount + 1);
      var done = new CountDownLatch(threadCount);
      var firstUnexpected = new AtomicReference<Throwable>();

      for (int t = 0; t < threadCount; t++) {
        new Thread(() -> {
          try {
            barrier.await(10, TimeUnit.SECONDS);
            // Enough iterations to likely overlap with close()
            for (int i = 0; i < 200; i++) {
              var session = pool.acquire();
              try {
                session.getMetadata();
              } finally {
                session.close();
              }
            }
          } catch (DatabaseException e) {
            // "The pool is closed" — acquire()/release() after pool nulled.
            // "Database '...' is closed" — session operations after
            // pool.close() called realClose() on the underlying session
            // (race between close() and a worker using an already-acquired
            // session).
            String msg = e.getMessage();
            if (!msg.startsWith("The pool is closed")
                && !msg.startsWith("Database '")) {
              firstUnexpected.compareAndSet(null, e);
            }
          } catch (AcquireTimeoutException e) {
            // Threads blocked on sem.tryAcquire() when close() drained
            // all permits will get this after DB_POOL_ACQUIRE_TIMEOUT.
            // This is expected and not a bug.
          } catch (ThreadInterruptedException e) {
            // Surefire may interrupt threads when
            // forkedProcessExitTimeoutInSeconds fires on slow runners.
            // This is not a pool bug.
          } catch (Exception e) {
            firstUnexpected.compareAndSet(null, e);
          } finally {
            done.countDown();
          }
        }).start();
      }

      // Main thread also waits on barrier, then immediately closes
      barrier.await(10, TimeUnit.SECONDS);
      pool.close();

      // 30 s is well above the 5 s acquire timeout × 4 threads,
      // providing ample margin for slow CI runners.
      assertTrue("Threads did not finish (possible deadlock)",
          done.await(30, TimeUnit.SECONDS));
      assertTrue("Pool must be closed after close() was called",
          pool.isClosed());
      if (firstUnexpected.get() != null) {
        throw new AssertionError(
            "Unexpected exception in worker thread",
            firstUnexpected.get());
      }
    } finally {
      youTrackDb.close();
    }
  }

  // Verifies that concurrent close() calls are safe: exactly one thread
  // performs the actual cleanup via getAndSet(null), others see null and
  // no-op.
  @Test
  public void concurrentCloseIsIdempotent() throws Exception {
    var youTrackDb = createYouTrackDB();
    try {
      createDatabase(youTrackDb, "testConcurrentClose");
      var pool = createPool(youTrackDb, "testConcurrentClose");

      int threadCount = 8;
      var barrier = new CyclicBarrier(threadCount);
      var done = new CountDownLatch(threadCount);
      var firstError = new AtomicReference<Throwable>();

      for (int t = 0; t < threadCount; t++) {
        new Thread(() -> {
          try {
            barrier.await(10, TimeUnit.SECONDS);
            pool.close();
          } catch (Exception e) {
            firstError.compareAndSet(null, e);
          } finally {
            done.countDown();
          }
        }).start();
      }

      assertTrue("Timed out waiting for threads",
          done.await(30, TimeUnit.SECONDS));
      if (firstError.get() != null) {
        throw new AssertionError(
            "Thread failed during concurrent close", firstError.get());
      }
      assertTrue(pool.isClosed());
    } finally {
      youTrackDb.close();
    }
  }
}
