package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.storage.collection.CollectionPositionMapBucket.PositionEntry;
import com.jetbrains.youtrackdb.internal.core.storage.collection.SnapshotKey;
import com.jetbrains.youtrackdb.internal.core.storage.collection.VisibilityKey;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the snapshot index cleanup logic in {@link AbstractStorage}, including the static
 * {@link AbstractStorage#evictStaleSnapshotEntries} method and the integration with
 * {@code cleanupSnapshotIndex()} called during commit.
 */
public class SnapshotIndexCleanupTest {

  private ConcurrentSkipListMap<SnapshotKey, PositionEntry> snapshotIndex;
  private ConcurrentSkipListMap<VisibilityKey, SnapshotKey> visibilityIndex;

  @Before
  public void setUp() {
    snapshotIndex = new ConcurrentSkipListMap<>();
    visibilityIndex = new ConcurrentSkipListMap<>();
  }

  @After
  public void tearDown() {
    snapshotIndex = null;
    visibilityIndex = null;
  }

  // --- evictStaleSnapshotEntries: basic eviction ---

  @Test
  public void testEvictRemovesEntriesBelowLwm() {
    var sk1 = new SnapshotKey(1, 100L, 5L);
    var sk2 = new SnapshotKey(1, 200L, 8L);
    snapshotIndex.put(sk1, new PositionEntry(1L, 0, 5L));
    snapshotIndex.put(sk2, new PositionEntry(2L, 0, 8L));

    visibilityIndex.put(new VisibilityKey(10L, 1, 100L), sk1);
    visibilityIndex.put(new VisibilityKey(20L, 1, 200L), sk2);

    // lwm = 15: entries with recordTs < 15 (i.e., recordTs=10) should be evicted
    AbstractStorage.evictStaleSnapshotEntries(15L, snapshotIndex, visibilityIndex);

    assertThat(snapshotIndex).doesNotContainKey(sk1);
    assertThat(snapshotIndex).containsKey(sk2);
    assertThat(visibilityIndex).hasSize(1);
    assertThat(visibilityIndex.firstKey().recordTs()).isEqualTo(20L);
  }

  @Test
  public void testEvictPreservesEntriesAtLwm() {
    var sk = new SnapshotKey(1, 100L, 5L);
    snapshotIndex.put(sk, new PositionEntry(1L, 0, 5L));
    visibilityIndex.put(new VisibilityKey(10L, 1, 100L), sk);

    // lwm = 10: entry with recordTs=10 should be PRESERVED (headMap is exclusive)
    AbstractStorage.evictStaleSnapshotEntries(10L, snapshotIndex, visibilityIndex);

    assertThat(snapshotIndex).containsKey(sk);
    assertThat(visibilityIndex).hasSize(1);
  }

  @Test
  public void testEvictPreservesEntriesAboveLwm() {
    var sk = new SnapshotKey(1, 100L, 5L);
    snapshotIndex.put(sk, new PositionEntry(1L, 0, 5L));
    visibilityIndex.put(new VisibilityKey(20L, 1, 100L), sk);

    AbstractStorage.evictStaleSnapshotEntries(10L, snapshotIndex, visibilityIndex);

    assertThat(snapshotIndex).containsKey(sk);
    assertThat(visibilityIndex).hasSize(1);
  }

  @Test
  public void testEvictRemovesAllEntriesBelowLwm() {
    // Multiple entries below lwm
    for (int i = 1; i <= 10; i++) {
      var sk = new SnapshotKey(1, (long) i, (long) i);
      snapshotIndex.put(sk, new PositionEntry((long) i, 0, (long) i));
      visibilityIndex.put(new VisibilityKey((long) i, 1, (long) i), sk);
    }
    // Entry above lwm
    var skAbove = new SnapshotKey(1, 11L, 11L);
    snapshotIndex.put(skAbove, new PositionEntry(11L, 0, 11L));
    visibilityIndex.put(new VisibilityKey(100L, 1, 11L), skAbove);

    AbstractStorage.evictStaleSnapshotEntries(50L, snapshotIndex, visibilityIndex);

    assertThat(snapshotIndex).hasSize(1);
    assertThat(snapshotIndex).containsKey(skAbove);
    assertThat(visibilityIndex).hasSize(1);
  }

  // --- evictStaleSnapshotEntries: no-op cases ---

  @Test
  public void testEvictNoOpWhenLwmIsMaxValue() {
    var sk = new SnapshotKey(1, 100L, 5L);
    snapshotIndex.put(sk, new PositionEntry(1L, 0, 5L));
    visibilityIndex.put(new VisibilityKey(10L, 1, 100L), sk);

    AbstractStorage.evictStaleSnapshotEntries(Long.MAX_VALUE, snapshotIndex, visibilityIndex);

    assertThat(snapshotIndex).hasSize(1);
    assertThat(visibilityIndex).hasSize(1);
  }

  @Test
  public void testEvictNoOpOnEmptyIndexes() {
    AbstractStorage.evictStaleSnapshotEntries(100L, snapshotIndex, visibilityIndex);

    assertThat(snapshotIndex).isEmpty();
    assertThat(visibilityIndex).isEmpty();
  }

  @Test
  public void testEvictNoOpWhenAllEntriesAboveLwm() {
    var sk = new SnapshotKey(1, 100L, 5L);
    snapshotIndex.put(sk, new PositionEntry(1L, 0, 5L));
    visibilityIndex.put(new VisibilityKey(50L, 1, 100L), sk);

    AbstractStorage.evictStaleSnapshotEntries(10L, snapshotIndex, visibilityIndex);

    assertThat(snapshotIndex).hasSize(1);
    assertThat(visibilityIndex).hasSize(1);
  }

  // --- evictStaleSnapshotEntries: multi-component ---

  @Test
  public void testEvictAcrossMultipleComponents() {
    // Entries from different componentIds, all below lwm
    var sk1 = new SnapshotKey(1, 100L, 5L);
    var sk2 = new SnapshotKey(2, 200L, 8L);
    var sk3 = new SnapshotKey(3, 300L, 12L);
    snapshotIndex.put(sk1, new PositionEntry(1L, 0, 5L));
    snapshotIndex.put(sk2, new PositionEntry(2L, 0, 8L));
    snapshotIndex.put(sk3, new PositionEntry(3L, 0, 12L));

    visibilityIndex.put(new VisibilityKey(10L, 1, 100L), sk1);
    visibilityIndex.put(new VisibilityKey(20L, 2, 200L), sk2);
    visibilityIndex.put(new VisibilityKey(30L, 3, 300L), sk3);

    // lwm = 25: evict entries with recordTs=10 and recordTs=20; keep recordTs=30
    AbstractStorage.evictStaleSnapshotEntries(25L, snapshotIndex, visibilityIndex);

    assertThat(snapshotIndex).hasSize(1);
    assertThat(snapshotIndex).containsKey(sk3);
    assertThat(visibilityIndex).hasSize(1);
    assertThat(visibilityIndex).containsKey(new VisibilityKey(30L, 3, 300L));
  }

  // --- evictStaleSnapshotEntries: consistency ---

  @Test
  public void testEvictRemovesFromBothMapsConsistently() {
    var sk = new SnapshotKey(1, 100L, 5L);
    var vk = new VisibilityKey(10L, 1, 100L);
    snapshotIndex.put(sk, new PositionEntry(1L, 0, 5L));
    visibilityIndex.put(vk, sk);

    AbstractStorage.evictStaleSnapshotEntries(20L, snapshotIndex, visibilityIndex);

    // Both maps must be empty — entry was removed from both
    assertThat(snapshotIndex).isEmpty();
    assertThat(visibilityIndex).isEmpty();
  }

  @Test
  public void testEvictWithOrphanedVisibilityEntry() {
    // Visibility entry points to a SnapshotKey that doesn't exist in snapshotIndex.
    // This can happen if the snapshot entry was already removed by another path.
    // evict should still remove the visibility entry without error.
    var sk = new SnapshotKey(1, 100L, 5L);
    visibilityIndex.put(new VisibilityKey(10L, 1, 100L), sk);
    // snapshotIndex is empty — no corresponding entry

    AbstractStorage.evictStaleSnapshotEntries(20L, snapshotIndex, visibilityIndex);

    assertThat(visibilityIndex).isEmpty();
    assertThat(snapshotIndex).isEmpty();
  }

  // --- Integration: cleanupSnapshotIndex via commit ---

  @Test
  public void testCommitTriggersCleanupWhenThresholdExceeded() {
    YouTrackDBImpl youTrackDB = null;
    try {
      youTrackDB = DbTestBase.createYTDBManagerAndDb(
          "test", DatabaseType.MEMORY, getClass());
      var session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
      var storage = (AbstractStorage) session.getStorage();

      // Set a very low cleanup threshold to trigger cleanup on next commit
      storage.getContextConfiguration()
          .setValue(GlobalConfiguration.STORAGE_SNAPSHOT_INDEX_CLEANUP_THRESHOLD, 1);

      // Record the initial sizes (populated by db init)
      int initialSnapshotSize = storage.getSharedSnapshotIndex().size();
      int initialVisibilitySize = storage.getVisibilityIndex().size();
      assertThat(initialSnapshotSize).isGreaterThan(1);
      assertThat(initialVisibilitySize).isGreaterThan(1);

      // Perform a write to trigger a commit (which calls cleanupSnapshotIndex).
      // Schema changes are non-transactional, so use a record insert instead.
      session.command("CREATE CLASS TestCleanup");
      session.begin();
      session.command("INSERT INTO TestCleanup SET name = 'test'");
      session.commit();

      // After commit with threshold=1, cleanup should have run and evicted stale
      // entries. The committing thread's tsMin is still set during cleanup (resetTsMin
      // runs later in close()), so lwm != MAX_VALUE and eviction occurs. Entries from
      // db init whose recordTs < lwm are removed.
      assertThat(storage.getSharedSnapshotIndex().size())
          .isLessThan(initialSnapshotSize);
      assertThat(storage.getVisibilityIndex().size())
          .isLessThan(initialVisibilitySize);

      session.close();
    } finally {
      if (youTrackDB != null) {
        youTrackDB.close();
      }
    }
  }

  @Test
  public void testCleanupUsesPerStorageThreshold() {
    YouTrackDBImpl youTrackDB = null;
    try {
      youTrackDB = DbTestBase.createYTDBManagerAndDb(
          "test", DatabaseType.MEMORY, getClass());
      var session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
      var storage = (AbstractStorage) session.getStorage();

      // Verify the per-storage ContextConfiguration can override the global value
      int globalDefault = GlobalConfiguration.STORAGE_SNAPSHOT_INDEX_CLEANUP_THRESHOLD
          .getValueAsInteger();
      assertThat(globalDefault).isEqualTo(10_000);

      // Override at storage level
      storage.getContextConfiguration()
          .setValue(GlobalConfiguration.STORAGE_SNAPSHOT_INDEX_CLEANUP_THRESHOLD, 42);
      int storageValue = storage.getContextConfiguration()
          .getValueAsInteger(GlobalConfiguration.STORAGE_SNAPSHOT_INDEX_CLEANUP_THRESHOLD);
      assertThat(storageValue).isEqualTo(42);

      session.close();
    } finally {
      if (youTrackDB != null) {
        youTrackDB.close();
      }
    }
  }

  // --- tryLock: concurrent cleanup guard ---

  @Test
  public void testTryLockPreventsSecondCleanup() throws InterruptedException {
    // Verify that the tryLock pattern works: if one thread holds the cleanup lock,
    // another thread's evict call should be skipped (not blocked).
    // We simulate this by holding a lock and verifying the maps are unchanged.
    var lock = new ReentrantLock();
    var sk = new SnapshotKey(1, 100L, 5L);
    snapshotIndex.put(sk, new PositionEntry(1L, 0, 5L));
    visibilityIndex.put(new VisibilityKey(10L, 1, 100L), sk);

    // Hold the lock from the main thread
    lock.lock();
    try {
      var latch = new CountDownLatch(1);
      var thread = new Thread(() -> {
        // This simulates what cleanupSnapshotIndex does: tryLock then evict
        if (lock.tryLock()) {
          try {
            AbstractStorage.evictStaleSnapshotEntries(
                20L, snapshotIndex, visibilityIndex);
          } finally {
            lock.unlock();
          }
        }
        // If tryLock fails, nothing happens — maps are unchanged
        latch.countDown();
      });
      thread.start();
      assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

      // Maps should be unchanged because the other thread couldn't acquire the lock
      assertThat(snapshotIndex).hasSize(1);
      assertThat(visibilityIndex).hasSize(1);
    } finally {
      lock.unlock();
    }
  }

  @Test
  public void testEvictIsIdempotent() {
    var sk = new SnapshotKey(1, 100L, 5L);
    snapshotIndex.put(sk, new PositionEntry(1L, 0, 5L));
    visibilityIndex.put(new VisibilityKey(10L, 1, 100L), sk);

    // First eviction removes the entry
    AbstractStorage.evictStaleSnapshotEntries(20L, snapshotIndex, visibilityIndex);
    assertThat(snapshotIndex).isEmpty();
    assertThat(visibilityIndex).isEmpty();

    // Second eviction is a no-op (no entries to evict)
    AbstractStorage.evictStaleSnapshotEntries(20L, snapshotIndex, visibilityIndex);
    assertThat(snapshotIndex).isEmpty();
    assertThat(visibilityIndex).isEmpty();
  }

  // --- Boundary: lwm edge cases ---

  @Test
  public void testEvictWithLwmZero() {
    // lwm = 0: nothing should be evicted (no entry has recordTs < 0)
    var sk = new SnapshotKey(1, 100L, 5L);
    snapshotIndex.put(sk, new PositionEntry(1L, 0, 5L));
    visibilityIndex.put(new VisibilityKey(0L, 1, 100L), sk);

    AbstractStorage.evictStaleSnapshotEntries(0L, snapshotIndex, visibilityIndex);

    assertThat(snapshotIndex).hasSize(1);
    assertThat(visibilityIndex).hasSize(1);
  }

  @Test
  public void testEvictWithLwmOne() {
    // lwm = 1: entry with recordTs=0 should be evicted
    var sk = new SnapshotKey(1, 100L, 5L);
    snapshotIndex.put(sk, new PositionEntry(1L, 0, 5L));
    visibilityIndex.put(new VisibilityKey(0L, 1, 100L), sk);

    AbstractStorage.evictStaleSnapshotEntries(1L, snapshotIndex, visibilityIndex);

    assertThat(snapshotIndex).isEmpty();
    assertThat(visibilityIndex).isEmpty();
  }

  @Test
  public void testCommitSkipsCleanupWhenLockHeld() throws InterruptedException {
    // Exercises the tryLock failure branch inside the real cleanupSnapshotIndex():
    // a background thread holds the snapshotCleanupLock while the main thread
    // commits. Since tryLock is not re-entrant across threads, cleanup is skipped.
    YouTrackDBImpl youTrackDB = null;
    try {
      youTrackDB = DbTestBase.createYTDBManagerAndDb(
          "test", DatabaseType.MEMORY, getClass());
      var session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
      var storage = (AbstractStorage) session.getStorage();

      // Set threshold to 1 so cleanup is attempted
      storage.getContextConfiguration()
          .setValue(GlobalConfiguration.STORAGE_SNAPSHOT_INDEX_CLEANUP_THRESHOLD, 1);

      // Hold the cleanup lock from a background thread (tryLock is NOT re-entrant
      // across threads, so the committing main thread's tryLock will fail)
      var lockAcquired = new CountDownLatch(1);
      var releaseSignal = new CountDownLatch(1);
      var bgThread = new Thread(() -> {
        storage.snapshotCleanupLock.lock();
        try {
          lockAcquired.countDown();
          releaseSignal.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } finally {
          storage.snapshotCleanupLock.unlock();
        }
      });
      bgThread.start();
      assertThat(lockAcquired.await(5, TimeUnit.SECONDS)).isTrue();

      try {
        int sizeBefore = storage.getSharedSnapshotIndex().size();

        // Perform a commit — cleanupSnapshotIndex will try tryLock and skip
        session.command("CREATE CLASS TestLock");
        session.begin();
        session.command("INSERT INTO TestLock SET name = 'test'");
        session.commit();

        // Snapshot index should NOT have been cleaned (tryLock failed on a
        // different thread). The new INSERT adds entries, so size >= sizeBefore.
        assertThat(storage.getSharedSnapshotIndex().size())
            .isGreaterThanOrEqualTo(sizeBefore);
      } finally {
        releaseSignal.countDown();
        bgThread.join(5000);
      }

      session.close();
    } finally {
      if (youTrackDB != null) {
        youTrackDB.close();
      }
    }
  }

  @Test
  public void testEvictWithLargeNumberOfEntries() {
    // Stress test: many entries, half below lwm, half above
    int count = 1000;
    long lwm = count / 2;
    for (int i = 0; i < count; i++) {
      var sk = new SnapshotKey(1, (long) i, (long) i);
      snapshotIndex.put(sk, new PositionEntry((long) i, 0, (long) i));
      visibilityIndex.put(new VisibilityKey((long) i, 1, (long) i), sk);
    }

    AbstractStorage.evictStaleSnapshotEntries(lwm, snapshotIndex, visibilityIndex);

    // Entries with recordTs 0..499 evicted, entries 500..999 preserved
    assertThat(snapshotIndex).hasSize(count - (int) lwm);
    assertThat(visibilityIndex).hasSize(count - (int) lwm);
    for (int i = (int) lwm; i < count; i++) {
      assertThat(snapshotIndex)
          .containsKey(new SnapshotKey(1, (long) i, (long) i));
    }
  }
}
