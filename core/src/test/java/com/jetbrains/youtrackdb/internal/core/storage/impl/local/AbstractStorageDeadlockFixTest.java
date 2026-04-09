package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.config.StorageConfiguration;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.storage.Storage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Tests for the deadlock fix in {@link AbstractStorage#periodicRecordsGc()} and the
 * tautology fix in {@link AbstractStorage#makeFuzzyCheckpoint()}.
 *
 * <p>The deadlock fix wraps {@code periodicRecordsGc()} in a {@code stateLock.readLock()}
 * to prevent a 3-way deadlock between storage deletion, periodic GC, and cache flush.
 * The tautology fix changes {@code ||} to {@code &&} in the status check so the guard
 * actually evaluates correctly.
 *
 * <p>Tests are in the same package as {@code AbstractStorage} to access {@code protected}
 * fields ({@code status}, {@code stateLock}, {@code configuration}) for white-box testing
 * of lock contention and error handling paths.
 */
@Category(SequentialTest.class)
public class AbstractStorageDeadlockFixTest {

  private YouTrackDBImpl youTrackDB;

  @Before
  public void setUp() {
    youTrackDB = DbTestBase.createYTDBManagerAndDb(
        "test", DatabaseType.MEMORY, getClass());
  }

  @After
  public void tearDown() {
    youTrackDB.close();
  }

  // ---------------------------------------------------------------------------
  // makeFuzzyCheckpoint: tautology fix coverage
  // ---------------------------------------------------------------------------

  // When the storage is closed, makeFuzzyCheckpoint should return immediately after
  // acquiring the read lock. The status guard after the lock evaluates to true because
  // status is CLOSED (neither OPEN nor MIGRATION), triggering an early return.
  @Test
  public void makeFuzzyCheckpointReturnsOnClosedStorage() {
    var session = openSession();
    var storage = storage(session);

    session.close();
    youTrackDB.close();

    try {
      // Status is CLOSED. tryLock(1ms) succeeds (no write lock held after close),
      // then the status guard after the lock triggers the early return.
      storage.makeFuzzyCheckpoint();

      // Verify the method returned without altering storage state
      assertThat(storage.status)
          .as("storage status should remain CLOSED after early return")
          .isEqualTo(Storage.STATUS.CLOSED);
    } finally {
      // Recreate for tearDown — must always happen even if the test fails
      youTrackDB = DbTestBase.createYTDBManagerAndDb(
          "test", DatabaseType.MEMORY, getClass());
    }
  }

  // When the write lock is held and status is non-OPEN, makeFuzzyCheckpoint should
  // bail out via the status guard inside the while loop (reached when tryLock fails).
  @Test
  public void makeFuzzyCheckpointBailsOutDuringLockContention() throws Exception {
    var session = openSession();
    var storage = storage(session);

    var lockAcquired = new CountDownLatch(1);
    var canRelease = new CountDownLatch(1);

    // Helper thread: holds the write lock and sets status to CLOSING.
    // This simulates the storage deletion path holding the write lock.
    var thread = new Thread(() -> {
      storage.stateLock.writeLock().lock();
      try {
        storage.status = Storage.STATUS.CLOSING;
        lockAcquired.countDown();
        try {
          canRelease.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      } finally {
        // Restore status before releasing so session close works normally
        storage.status = Storage.STATUS.OPEN;
        storage.stateLock.writeLock().unlock();
      }
    });
    thread.start();
    assertThat(lockAcquired.await(10, TimeUnit.SECONDS))
        .as("helper thread should acquire write lock in time")
        .isTrue();

    try {
      // Main thread: makeFuzzyCheckpoint enters while loop, tryLock(1ms) fails
      // (write lock held), reaches the in-loop status guard, status is CLOSING,
      // condition is true, returns immediately.
      storage.makeFuzzyCheckpoint();
    } finally {
      canRelease.countDown();
      thread.join(10_000);
      assertThat(thread.isAlive())
          .as("helper thread should have terminated")
          .isFalse();
      session.close();
    }
  }

  // ---------------------------------------------------------------------------
  // periodicRecordsGc: readLock contention bail-out
  // ---------------------------------------------------------------------------

  // When the write lock is held, periodicRecordsGc should bail out at the
  // tryLock() guard because it returns false. The method must not block — it
  // uses a non-blocking tryLock to avoid holding up scheduled executor threads.
  @Test
  public void periodicGcBailsOutWhenWriteLockHeld() throws Exception {
    var session = openSession();
    var storage = storage(session);

    var lockAcquired = new CountDownLatch(1);
    var canRelease = new CountDownLatch(1);

    // Helper thread: holds the write lock, simulating a concurrent storage close.
    var thread = new Thread(() -> {
      storage.stateLock.writeLock().lock();
      try {
        lockAcquired.countDown();
        try {
          canRelease.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      } finally {
        storage.stateLock.writeLock().unlock();
      }
    });
    thread.start();
    assertThat(lockAcquired.await(10, TimeUnit.SECONDS))
        .as("helper thread should acquire write lock in time")
        .isTrue();

    try {
      // Main thread: status is OPEN (passes pre-lock check), tryLock fails
      // (write lock held), returns immediately.
      storage.periodicRecordsGc();
    } finally {
      canRelease.countDown();
      thread.join(10_000);
      assertThat(thread.isAlive())
          .as("helper thread should have terminated")
          .isFalse();
      session.close();
    }
  }

  // ---------------------------------------------------------------------------
  // periodicRecordsGc: snapshot cleanup exception handling
  // ---------------------------------------------------------------------------

  // When cleanupSnapshotIndex throws, the exception should be caught and logged
  // by the catch block, and the method should continue to the collection GC loop.
  // We simulate the exception by replacing the configuration with a mock that
  // throws on the first getContextConfiguration() call (which occurs inside
  // cleanupSnapshotIndex) and returns the real config on subsequent calls.
  @Test
  public void periodicGcCatchesSnapshotCleanupException() {
    var session = openSession();
    var storage = storage(session);

    var realConfig = storage.configuration;
    var mockConfig = mock(StorageConfiguration.class);

    // First call: from cleanupSnapshotIndex -> throws (simulates cleanup failure)
    // Second call: from the collection GC threshold retrieval -> returns real config
    when(mockConfig.getContextConfiguration())
        .thenThrow(new RuntimeException("simulated snapshot cleanup failure"))
        .thenReturn(realConfig.getContextConfiguration());

    try {
      storage.configuration = mockConfig;
      // Should not throw — the exception is caught and logged internally
      storage.periodicRecordsGc();
    } finally {
      storage.configuration = realConfig;
      session.close();
    }

    // Verify both getContextConfiguration() calls were made — the second call
    // proves that execution continued past the exception into the GC loop phase.
    verify(mockConfig, times(2)).getContextConfiguration();
  }

  // ---------------------------------------------------------------------------
  // periodicRecordsGc: loop exits when status changes
  // ---------------------------------------------------------------------------

  // When the storage status changes from OPEN during the collection iteration
  // loop, the loop should exit immediately via the in-loop status guard.
  // We simulate this by using a mock configuration that changes status as a
  // side effect of the second getContextConfiguration() call (which occurs
  // just before the loop begins).
  @Test
  public void periodicGcExitsLoopWhenStatusChanges() {
    var session = openSession();
    var storage = storage(session);

    // Create data so there is at least one collection to iterate
    session.command("CREATE CLASS GcStatusChangeTest");
    session.begin();
    session.command("INSERT INTO GcStatusChangeTest SET x = 1");
    session.commit();

    var realConfig = storage.configuration;
    var mockConfig = mock(StorageConfiguration.class);

    // First call: from cleanupSnapshotIndex -> returns real config (cleanup runs normally)
    // Second call: from GC threshold retrieval -> side effect: sets status to CLOSING
    when(mockConfig.getContextConfiguration())
        .thenReturn(realConfig.getContextConfiguration())
        .thenAnswer(inv -> {
          storage.status = Storage.STATUS.CLOSING;
          return realConfig.getContextConfiguration();
        });

    // Set GC thresholds to 0 so all collections are eligible for GC,
    // ensuring the loop body is reached (where the status check triggers).
    storage.getContextConfiguration()
        .setValue(GlobalConfiguration.STORAGE_COLLECTION_GC_MIN_THRESHOLD, 0);
    storage.getContextConfiguration()
        .setValue(GlobalConfiguration.STORAGE_COLLECTION_GC_SCALE_FACTOR, 0.0f);

    try {
      storage.configuration = mockConfig;
      storage.periodicRecordsGc();
    } finally {
      storage.status = Storage.STATUS.OPEN;
      storage.configuration = realConfig;
      session.close();
    }

    // Verify the method reached the collection GC phase (second getContextConfiguration
    // call) — the status side effect was triggered, confirming the loop-exit path.
    verify(mockConfig, times(2)).getContextConfiguration();
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private DatabaseSessionEmbedded openSession() {
    return youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
  }

  private static AbstractStorage storage(DatabaseSessionEmbedded session) {
    return (AbstractStorage) session.getStorage();
  }
}
