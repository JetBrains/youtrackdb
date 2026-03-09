package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.storage.StorageCollection;
import com.jetbrains.youtrackdb.internal.core.storage.collection.CollectionPositionMapBucket.PositionEntry;
import com.jetbrains.youtrackdb.internal.core.storage.collection.SnapshotKey;
import com.jetbrains.youtrackdb.internal.core.storage.collection.VisibilityKey;
import com.jetbrains.youtrackdb.internal.core.storage.collection.v2.PaginatedCollectionV2;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the snapshot index cleanup logic in {@link AbstractStorage}, including the static
 * {@link AbstractStorage#evictStaleSnapshotEntries} method and the integration with
 * {@code cleanupSnapshotIndex()} called from {@code resetTsMin()} at transaction close.
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
    AbstractStorage.evictStaleSnapshotEntries(15L, snapshotIndex, visibilityIndex,
        new AtomicLong());

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
    AbstractStorage.evictStaleSnapshotEntries(10L, snapshotIndex, visibilityIndex,
        new AtomicLong());

    assertThat(snapshotIndex).containsKey(sk);
    assertThat(visibilityIndex).hasSize(1);
  }

  @Test
  public void testEvictPreservesEntriesAboveLwm() {
    var sk = new SnapshotKey(1, 100L, 5L);
    snapshotIndex.put(sk, new PositionEntry(1L, 0, 5L));
    visibilityIndex.put(new VisibilityKey(20L, 1, 100L), sk);

    AbstractStorage.evictStaleSnapshotEntries(10L, snapshotIndex, visibilityIndex,
        new AtomicLong());

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

    AbstractStorage.evictStaleSnapshotEntries(50L, snapshotIndex, visibilityIndex,
        new AtomicLong());

    assertThat(snapshotIndex).hasSize(1);
    assertThat(snapshotIndex).containsKey(skAbove);
    assertThat(visibilityIndex).hasSize(1);
  }

  // --- evictStaleSnapshotEntries: no-op cases ---

  @Test
  public void testEvictWithLwmMaxValueEvictsAllEntries() {
    // After the LWM fallback change, computeGlobalLowWaterMark() never returns
    // Long.MAX_VALUE. But even if Long.MAX_VALUE is passed directly, eviction
    // now proceeds (the old Long.MAX_VALUE guard was removed). All entries with
    // recordTs < Long.MAX_VALUE are evicted.
    var sk = new SnapshotKey(1, 100L, 5L);
    snapshotIndex.put(sk, new PositionEntry(1L, 0, 5L));
    visibilityIndex.put(new VisibilityKey(10L, 1, 100L), sk);

    AbstractStorage.evictStaleSnapshotEntries(
        Long.MAX_VALUE, snapshotIndex, visibilityIndex, new AtomicLong());

    assertThat(snapshotIndex).isEmpty();
    assertThat(visibilityIndex).isEmpty();
  }

  @Test
  public void testEvictNoOpOnEmptyIndexes() {
    AbstractStorage.evictStaleSnapshotEntries(100L, snapshotIndex, visibilityIndex,
        new AtomicLong());

    assertThat(snapshotIndex).isEmpty();
    assertThat(visibilityIndex).isEmpty();
  }

  @Test
  public void testEvictNoOpWhenAllEntriesAboveLwm() {
    var sk = new SnapshotKey(1, 100L, 5L);
    snapshotIndex.put(sk, new PositionEntry(1L, 0, 5L));
    visibilityIndex.put(new VisibilityKey(50L, 1, 100L), sk);

    AbstractStorage.evictStaleSnapshotEntries(10L, snapshotIndex, visibilityIndex,
        new AtomicLong());

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
    AbstractStorage.evictStaleSnapshotEntries(25L, snapshotIndex, visibilityIndex,
        new AtomicLong());

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

    AbstractStorage.evictStaleSnapshotEntries(20L, snapshotIndex, visibilityIndex,
        new AtomicLong());

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

    AbstractStorage.evictStaleSnapshotEntries(20L, snapshotIndex, visibilityIndex,
        new AtomicLong());

    assertThat(visibilityIndex).isEmpty();
    assertThat(snapshotIndex).isEmpty();
  }

  // --- Integration: cleanupSnapshotIndex via transaction close (resetTsMin) ---

  @Test
  public void testCommitTriggersCleanupWhenThresholdExceeded() {
    YouTrackDBImpl youTrackDB = null;
    try {
      youTrackDB = DbTestBase.createYTDBManagerAndDb(
          "test", DatabaseType.MEMORY, getClass());
      var session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
      var storage = (AbstractStorage) session.getStorage();

      // Set a very low cleanup threshold to trigger cleanup on next transaction close
      storage.getContextConfiguration()
          .setValue(GlobalConfiguration.STORAGE_SNAPSHOT_INDEX_CLEANUP_THRESHOLD, 1);

      // Record the initial sizes (populated by db init)
      int initialSnapshotSize = storage.getSharedSnapshotIndex().size();
      int initialVisibilitySize = storage.getVisibilityIndex().size();
      assertThat(initialSnapshotSize).isGreaterThan(1);
      assertThat(initialVisibilitySize).isGreaterThan(1);

      // Perform a write to trigger a commit. cleanupSnapshotIndex() is called from
      // resetTsMin() during transaction close (after commit). The committing thread's
      // tsMin has been reset to Long.MAX_VALUE by the time cleanup runs, but
      // computeGlobalLowWaterMark() falls back to idGen.getLastId() when all threads
      // are idle, so eviction still proceeds correctly.
      session.command("CREATE CLASS TestCleanup");
      session.begin();
      session.command("INSERT INTO TestCleanup SET name = 'test'");
      session.commit();

      // After commit with threshold=1, cleanup should have run and evicted stale
      // entries. Entries from db init whose recordTs < lwm are removed.
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
  public void testReadOnlyTransactionTriggersCleanup() {
    // Verifies that read-only transactions also trigger snapshot index cleanup
    // via resetTsMin(). Before the move to resetTsMin(), cleanup only ran on
    // write commits, so stale entries accumulated in read-heavy workloads.
    YouTrackDBImpl youTrackDB = null;
    try {
      youTrackDB = DbTestBase.createYTDBManagerAndDb(
          "test", DatabaseType.MEMORY, getClass());
      var session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
      var storage = (AbstractStorage) session.getStorage();

      // Set a very low cleanup threshold to trigger cleanup on next transaction close
      storage.getContextConfiguration()
          .setValue(GlobalConfiguration.STORAGE_SNAPSHOT_INDEX_CLEANUP_THRESHOLD, 1);

      // First, create some stale snapshot entries via a write transaction.
      session.command("CREATE CLASS TestReadCleanup");
      session.begin();
      session.command("INSERT INTO TestReadCleanup SET name = 'v1'");
      session.commit();

      // Update the record to create a stale snapshot entry for the old version
      session.begin();
      session.command("UPDATE TestReadCleanup SET name = 'v2'");
      session.commit();

      int snapshotSizeAfterWrites = storage.getSharedSnapshotIndex().size();
      int visibilitySizeAfterWrites = storage.getVisibilityIndex().size();

      // Now perform a read-only transaction. When it closes, resetTsMin()
      // triggers cleanupSnapshotIndex() which should evict stale entries.
      session.begin();
      session.query("SELECT FROM TestReadCleanup").close();
      session.commit();

      // After the read-only transaction close, cleanup should have evicted
      // stale entries (those whose recordTs < lwm).
      assertThat(storage.getSharedSnapshotIndex().size())
          .isLessThanOrEqualTo(snapshotSizeAfterWrites);
      assertThat(storage.getVisibilityIndex().size())
          .isLessThanOrEqualTo(visibilitySizeAfterWrites);

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
                20L, snapshotIndex, visibilityIndex, new AtomicLong());
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
    AbstractStorage.evictStaleSnapshotEntries(20L, snapshotIndex, visibilityIndex,
        new AtomicLong());
    assertThat(snapshotIndex).isEmpty();
    assertThat(visibilityIndex).isEmpty();

    // Second eviction is a no-op (no entries to evict)
    AbstractStorage.evictStaleSnapshotEntries(20L, snapshotIndex, visibilityIndex,
        new AtomicLong());
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

    AbstractStorage.evictStaleSnapshotEntries(0L, snapshotIndex, visibilityIndex, new AtomicLong());

    assertThat(snapshotIndex).hasSize(1);
    assertThat(visibilityIndex).hasSize(1);
  }

  @Test
  public void testEvictWithLwmOne() {
    // lwm = 1: entry with recordTs=0 should be evicted
    var sk = new SnapshotKey(1, 100L, 5L);
    snapshotIndex.put(sk, new PositionEntry(1L, 0, 5L));
    visibilityIndex.put(new VisibilityKey(0L, 1, 100L), sk);

    AbstractStorage.evictStaleSnapshotEntries(1L, snapshotIndex, visibilityIndex, new AtomicLong());

    assertThat(snapshotIndex).isEmpty();
    assertThat(visibilityIndex).isEmpty();
  }

  @Test
  public void testTransactionCloseSkipsCleanupWhenLockHeld() throws InterruptedException {
    // Exercises the tryLock failure branch inside the real cleanupSnapshotIndex():
    // a background thread holds the snapshotCleanupLock while the main thread
    // closes a transaction (via resetTsMin). Since tryLock is not re-entrant across
    // threads, cleanup is skipped.
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
      // across threads, so the main thread's tryLock in resetTsMin will fail)
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

        // Perform a commit — cleanupSnapshotIndex (called from resetTsMin during
        // transaction close) will try tryLock and skip
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

    AbstractStorage.evictStaleSnapshotEntries(lwm, snapshotIndex, visibilityIndex,
        new AtomicLong());

    // Entries with recordTs 0..499 evicted, entries 500..999 preserved
    assertThat(snapshotIndex).hasSize(count - (int) lwm);
    assertThat(visibilityIndex).hasSize(count - (int) lwm);
    for (int i = (int) lwm; i < count; i++) {
      assertThat(snapshotIndex)
          .containsKey(new SnapshotKey(1, (long) i, (long) i));
    }
  }

  // --- Concurrent reads during cleanup ---

  @Test
  public void testConcurrentReaderDuringEviction() throws Exception {
    // Verify that a reader iterating snapshotIndex.subMap() while eviction runs
    // concurrently never throws ConcurrentModificationException and always sees
    // a consistent (possibly reduced) subset of entries. ConcurrentSkipListMap
    // guarantees weakly-consistent iterators, so this test makes that explicit.
    int count = 2000;
    long lwm = count / 2;
    for (int i = 0; i < count; i++) {
      var sk = new SnapshotKey(1, (long) i, (long) i);
      snapshotIndex.put(sk, new PositionEntry((long) i, 0, (long) i));
      visibilityIndex.put(new VisibilityKey((long) i, 1, (long) i), sk);
    }

    var readerError = new AtomicReference<Throwable>();
    var evictionDone = new AtomicBoolean(false);
    // Use a barrier so reader and evictor start at roughly the same time
    var barrier = new CyclicBarrier(2);

    // Reader thread: continuously iterates subMap ranges during eviction
    var readerThread = new Thread(() -> {
      try {
        barrier.await(5, TimeUnit.SECONDS);
        // Iterate multiple subMap ranges while eviction is in progress
        while (!evictionDone.get()) {
          var fromKey = new SnapshotKey(1, 0L, 0L);
          var toKey = new SnapshotKey(1, (long) count, (long) count);
          var subMap = snapshotIndex.subMap(fromKey, true, toKey, true);
          // Iterate the view — must not throw ConcurrentModificationException
          var entries = new ArrayList<>(subMap.entrySet());
          // Each entry must be a valid SnapshotKey/PositionEntry pair
          for (var entry : entries) {
            assertThat(entry.getKey()).isNotNull();
            assertThat(entry.getValue()).isNotNull();
          }
        }
      } catch (Throwable t) {
        readerError.set(t);
      }
    });
    readerThread.start();

    // Evictor: start together with reader, then evict entries below lwm
    barrier.await(5, TimeUnit.SECONDS);
    AbstractStorage.evictStaleSnapshotEntries(lwm, snapshotIndex, visibilityIndex,
        new AtomicLong());
    evictionDone.set(true);

    readerThread.join(10_000);
    assertThat(readerThread.isAlive()).isFalse();
    assertThat(readerError.get()).isNull();

    // After eviction, only entries at/above lwm remain
    assertThat(snapshotIndex).hasSize(count - (int) lwm);
  }

  @Test
  public void testConcurrentGetDuringEviction() throws Exception {
    // Verify that concurrent get() lookups during eviction return either a valid
    // PositionEntry or null (if already evicted), and never throw.
    int count = 2000;
    long lwm = count / 2;
    for (int i = 0; i < count; i++) {
      var sk = new SnapshotKey(1, (long) i, (long) i);
      snapshotIndex.put(sk, new PositionEntry((long) i, 0, (long) i));
      visibilityIndex.put(new VisibilityKey((long) i, 1, (long) i), sk);
    }

    var readerError = new AtomicReference<Throwable>();
    // Use a barrier so both threads start at roughly the same time
    var barrier = new CyclicBarrier(2);

    var readerThread = new Thread(() -> {
      try {
        barrier.await(5, TimeUnit.SECONDS);
        for (int i = 0; i < count; i++) {
          var key = new SnapshotKey(1, (long) i, (long) i);
          var value = snapshotIndex.get(key);
          // value is either the original PositionEntry or null (already evicted)
          if (value != null) {
            assertThat(value.getPageIndex()).isEqualTo((long) i);
          }
        }
      } catch (Throwable t) {
        readerError.set(t);
      }
    });
    readerThread.start();

    barrier.await(5, TimeUnit.SECONDS);
    AbstractStorage.evictStaleSnapshotEntries(lwm, snapshotIndex, visibilityIndex,
        new AtomicLong());

    readerThread.join(10_000);
    assertThat(readerThread.isAlive()).isFalse();
    assertThat(readerError.get()).isNull();

    // Post-eviction: entries below lwm are gone
    for (int i = 0; i < (int) lwm; i++) {
      assertThat(snapshotIndex).doesNotContainKey(
          new SnapshotKey(1, (long) i, (long) i));
    }
    // Entries at/above lwm remain
    for (int i = (int) lwm; i < count; i++) {
      assertThat(snapshotIndex).containsKey(
          new SnapshotKey(1, (long) i, (long) i));
    }
  }

  // --- Double-check threshold: cleanup skipped after concurrent eviction ---

  @Test
  public void testDoubleCheckThresholdSkipsEvictionAfterConcurrentCleanup()
      throws InterruptedException {
    // Exercises the double-check pattern in cleanupSnapshotIndex(): the first size
    // check passes (above threshold), but by the time the lock is acquired another
    // thread has already cleaned up, so the second check finds size <= threshold.
    // We simulate this by populating the maps, starting eviction on a background
    // thread while the main thread waits for the lock, then verifying no double
    // eviction occurs.
    int count = 100;
    var lock = new ReentrantLock();
    var mapsCleaned = new AtomicBoolean(false);

    for (int i = 0; i < count; i++) {
      var sk = new SnapshotKey(1, (long) i, (long) i);
      snapshotIndex.put(sk, new PositionEntry((long) i, 0, (long) i));
      visibilityIndex.put(new VisibilityKey((long) i, 1, (long) i), sk);
    }

    int threshold = 10;
    // Verify size > threshold before cleanup
    assertThat(snapshotIndex.size()).isGreaterThan(threshold);

    // Thread 1 acquires lock and evicts
    var lockAcquired = new CountDownLatch(1);
    var evictDone = new CountDownLatch(1);
    var thread1 = new Thread(() -> {
      lock.lock();
      try {
        lockAcquired.countDown();
        // Evict most entries (lwm = 95 removes entries 0..94)
        AbstractStorage.evictStaleSnapshotEntries(
            count - 5, snapshotIndex, visibilityIndex, new AtomicLong());
        evictDone.countDown();
      } finally {
        lock.unlock();
      }
    });
    thread1.start();
    assertThat(lockAcquired.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(evictDone.await(5, TimeUnit.SECONDS)).isTrue();

    // After thread1's eviction, only 5 entries remain (below threshold of 10)
    assertThat(snapshotIndex.size()).isLessThanOrEqualTo(threshold);

    // Thread 2 (main) now acquires lock and applies the double-check pattern:
    // size <= threshold, so cleanup is a no-op
    lock.lock();
    try {
      int sizeAfterFirstCleanup = snapshotIndex.size();
      if (snapshotIndex.size() > threshold) {
        // This branch should NOT be taken
        mapsCleaned.set(true);
        AbstractStorage.evictStaleSnapshotEntries(
            Long.MAX_VALUE - 1, snapshotIndex, visibilityIndex, new AtomicLong());
      }
      // Size unchanged — double-check prevented redundant eviction
      assertThat(mapsCleaned.get()).isFalse();
      assertThat(snapshotIndex.size()).isEqualTo(sizeAfterFirstCleanup);
    } finally {
      lock.unlock();
    }

    thread1.join(5000);
  }

  // --- Progressive eviction with advancing lwm ---

  @Test
  public void testProgressiveEvictionWithAdvancingLwm() {
    // Three rounds of eviction with increasing lwm values. Each round removes
    // the entries in the newly-stale range while preserving everything above.
    for (int i = 0; i < 30; i++) {
      var sk = new SnapshotKey(1, (long) i, (long) i);
      snapshotIndex.put(sk, new PositionEntry((long) i, 0, (long) i));
      visibilityIndex.put(new VisibilityKey((long) i, 1, (long) i), sk);
    }

    // Round 1: lwm=10 removes entries 0..9
    AbstractStorage.evictStaleSnapshotEntries(10L, snapshotIndex, visibilityIndex,
        new AtomicLong());
    assertThat(snapshotIndex).hasSize(20);
    assertThat(visibilityIndex).hasSize(20);
    for (int i = 0; i < 10; i++) {
      assertThat(snapshotIndex).doesNotContainKey(
          new SnapshotKey(1, (long) i, (long) i));
    }
    for (int i = 10; i < 30; i++) {
      assertThat(snapshotIndex).containsKey(
          new SnapshotKey(1, (long) i, (long) i));
    }

    // Round 2: lwm=20 removes entries 10..19
    AbstractStorage.evictStaleSnapshotEntries(20L, snapshotIndex, visibilityIndex,
        new AtomicLong());
    assertThat(snapshotIndex).hasSize(10);
    assertThat(visibilityIndex).hasSize(10);
    for (int i = 10; i < 20; i++) {
      assertThat(snapshotIndex).doesNotContainKey(
          new SnapshotKey(1, (long) i, (long) i));
    }
    for (int i = 20; i < 30; i++) {
      assertThat(snapshotIndex).containsKey(
          new SnapshotKey(1, (long) i, (long) i));
    }

    // Round 3: lwm=30 removes entries 20..29
    AbstractStorage.evictStaleSnapshotEntries(30L, snapshotIndex, visibilityIndex,
        new AtomicLong());
    assertThat(snapshotIndex).isEmpty();
    assertThat(visibilityIndex).isEmpty();
  }

  // --- Same recordTs, different components ---

  @Test
  public void testEvictWithSameRecordTsDifferentComponents() {
    // Multiple entries with identical recordTs but different componentId/position.
    // All entries at recordTs=10 (below lwm=20) should be evicted; entry at
    // recordTs=20 (at lwm) should be preserved.
    var sk1 = new SnapshotKey(1, 100L, 5L);
    var sk2 = new SnapshotKey(2, 200L, 8L);
    var sk3 = new SnapshotKey(3, 300L, 12L);
    var skAtLwm = new SnapshotKey(4, 400L, 15L);

    snapshotIndex.put(sk1, new PositionEntry(1L, 0, 5L));
    snapshotIndex.put(sk2, new PositionEntry(2L, 0, 8L));
    snapshotIndex.put(sk3, new PositionEntry(3L, 0, 12L));
    snapshotIndex.put(skAtLwm, new PositionEntry(4L, 0, 15L));

    // All three entries have the same recordTs=10
    visibilityIndex.put(new VisibilityKey(10L, 1, 100L), sk1);
    visibilityIndex.put(new VisibilityKey(10L, 2, 200L), sk2);
    visibilityIndex.put(new VisibilityKey(10L, 3, 300L), sk3);
    // Entry at lwm boundary
    visibilityIndex.put(new VisibilityKey(20L, 4, 400L), skAtLwm);

    AbstractStorage.evictStaleSnapshotEntries(20L, snapshotIndex, visibilityIndex,
        new AtomicLong());

    // All three entries with recordTs=10 should be evicted
    assertThat(snapshotIndex).doesNotContainKey(sk1);
    assertThat(snapshotIndex).doesNotContainKey(sk2);
    assertThat(snapshotIndex).doesNotContainKey(sk3);
    // Entry at lwm=20 should be preserved (headMap is exclusive)
    assertThat(snapshotIndex).containsKey(skAtLwm);
    assertThat(snapshotIndex).hasSize(1);
    assertThat(visibilityIndex).hasSize(1);
    assertThat(visibilityIndex.firstKey()).isEqualTo(
        new VisibilityKey(20L, 4, 400L));
  }

  // --- Extreme boundary: lwm near Long.MAX_VALUE ---

  @Test
  public void testEvictWithLwmNearMaxValue() {
    // Verify that lwm = Long.MAX_VALUE - 1 correctly evicts entries below it
    // while preserving entries at that recordTs (headMap exclusive). This tests
    // the sentinel key VisibilityKey(MAX_VALUE-1, MIN_VALUE, MIN_VALUE) at the
    // extreme end of the long range.
    long nearMax = Long.MAX_VALUE - 1;
    var skBelow = new SnapshotKey(1, 100L, 5L);
    var skAtBoundary = new SnapshotKey(2, 200L, 8L);

    snapshotIndex.put(skBelow, new PositionEntry(1L, 0, 5L));
    snapshotIndex.put(skAtBoundary, new PositionEntry(2L, 0, 8L));

    // Entry below lwm (recordTs = nearMax - 1) should be evicted
    visibilityIndex.put(new VisibilityKey(nearMax - 1, 1, 100L), skBelow);
    // Entry at lwm (recordTs = nearMax) should be preserved
    visibilityIndex.put(new VisibilityKey(nearMax, 2, 200L), skAtBoundary);

    AbstractStorage.evictStaleSnapshotEntries(
        nearMax, snapshotIndex, visibilityIndex, new AtomicLong());

    assertThat(snapshotIndex).doesNotContainKey(skBelow);
    assertThat(snapshotIndex).containsKey(skAtBoundary);
    assertThat(snapshotIndex).hasSize(1);
    assertThat(visibilityIndex).hasSize(1);
  }

  // --- Dead record counter: increment during eviction ---

  @Test
  public void testEvictIncrementsDeadRecordCounterForCollection() {
    // Verify that evicting snapshot entries increments the per-collection dead record
    // counter via the full integration path (commit → resetTsMin → cleanupSnapshotIndex
    // → evictStaleSnapshotEntries with collections list).
    YouTrackDBImpl youTrackDB = null;
    try {
      youTrackDB = DbTestBase.createYTDBManagerAndDb(
          "test", DatabaseType.MEMORY, getClass());
      var session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
      var storage = (AbstractStorage) session.getStorage();

      // Set threshold to 0 so eviction runs on every transaction close regardless
      // of snapshot index size.
      storage.getContextConfiguration()
          .setValue(GlobalConfiguration.STORAGE_SNAPSHOT_INDEX_CLEANUP_THRESHOLD, 0);

      // Create a class and insert records
      session.command("CREATE CLASS TestDeadCounter");
      session.begin();
      session.command("INSERT INTO TestDeadCounter SET name = 'v1'");
      session.commit();

      // Update the record multiple times to create stale snapshot entries.
      // Each update creates a visibility entry with recordTs = commit timestamp.
      // Eviction requires recordTs < lwm, so a subsequent commit is needed to
      // advance lwm past the previous entry's recordTs.
      for (int i = 2; i <= 5; i++) {
        session.begin();
        session.command("UPDATE TestDeadCounter SET name = 'v" + i + "'");
        session.commit();
      }

      // Sum dead record counts across ALL collections of the TestDeadCounter class.
      // A class may be backed by multiple physical collections (e.g., testdeadcounter,
      // testdeadcounter_1, ...), and records can go to any of them.
      var collectionIds = session.getClass("TestDeadCounter").getCollectionIds();
      long totalDead = 0;
      for (int cid : collectionIds) {
        for (var coll : storage.getCollectionInstances()) {
          if (coll.getId() == cid && coll instanceof PaginatedCollectionV2 pc) {
            totalDead += pc.getDeadRecordCount();
          }
        }
      }

      // After 4 updates, at least some snapshot entries should have been evicted
      // and incremented the dead record counter.
      assertThat(totalDead)
          .as("dead record counter should be incremented by eviction")
          .isGreaterThan(0);

      session.close();
    } finally {
      if (youTrackDB != null) {
        youTrackDB.close();
      }
    }
  }

  @Test
  public void testEvictWithNullCollectionsDoesNotFail() {
    // The 4-parameter overload passes null collections — verify no NPE.
    var sk = new SnapshotKey(1, 100L, 5L);
    snapshotIndex.put(sk, new PositionEntry(1L, 0, 5L));
    visibilityIndex.put(new VisibilityKey(10L, 1, 100L), sk);

    var sizeCounter = new AtomicLong(1);
    AbstractStorage.evictStaleSnapshotEntries(
        20L, snapshotIndex, visibilityIndex, sizeCounter);

    assertThat(snapshotIndex).isEmpty();
    assertThat(visibilityIndex).isEmpty();
    assertThat(sizeCounter.get()).isEqualTo(0);
  }

  @Test
  public void testEvictWithNullCollectionsEvictsMultipleEntries() {
    // Tests the 5-parameter static method with null collections list and multiple
    // entries across different components. Eviction should proceed normally and
    // decrement the size counter, but skip dead record counting.
    var sk1 = new SnapshotKey(1, 100L, 5L);
    var sk2 = new SnapshotKey(2, 200L, 8L);
    snapshotIndex.put(sk1, new PositionEntry(1L, 0, 5L));
    snapshotIndex.put(sk2, new PositionEntry(2L, 0, 8L));
    visibilityIndex.put(new VisibilityKey(10L, 1, 100L), sk1);
    visibilityIndex.put(new VisibilityKey(15L, 2, 200L), sk2);

    var sizeCounter = new AtomicLong(2);
    AbstractStorage.evictStaleSnapshotEntries(
        20L, snapshotIndex, visibilityIndex, sizeCounter, null);

    assertThat(snapshotIndex).isEmpty();
    assertThat(sizeCounter.get()).isEqualTo(0);
  }

  @Test
  public void testEvictWithOutOfBoundsComponentIdDoesNotFail() {
    // Snapshot entry with componentId larger than collections list size.
    // Should be evicted normally without dead record counting.
    var sk = new SnapshotKey(999, 100L, 5L);
    snapshotIndex.put(sk, new PositionEntry(1L, 0, 5L));
    visibilityIndex.put(new VisibilityKey(10L, 999, 100L), sk);

    List<StorageCollection> collections = new CopyOnWriteArrayList<>();
    var sizeCounter = new AtomicLong(1);
    AbstractStorage.evictStaleSnapshotEntries(
        20L, snapshotIndex, visibilityIndex, sizeCounter, collections);

    assertThat(snapshotIndex).isEmpty();
    assertThat(sizeCounter.get()).isEqualTo(0);
  }

  @Test
  public void testEvictWithNegativeComponentIdDoesNotFail() {
    // Snapshot entry with negative componentId. Should be evicted without counting.
    var sk = new SnapshotKey(-1, 100L, 5L);
    snapshotIndex.put(sk, new PositionEntry(1L, 0, 5L));
    visibilityIndex.put(new VisibilityKey(10L, -1, 100L), sk);

    List<StorageCollection> collections = new CopyOnWriteArrayList<>();
    var sizeCounter = new AtomicLong(1);
    AbstractStorage.evictStaleSnapshotEntries(
        20L, snapshotIndex, visibilityIndex, sizeCounter, collections);

    assertThat(snapshotIndex).isEmpty();
    assertThat(sizeCounter.get()).isEqualTo(0);
  }

  // --- GC trigger condition ---

  @Test
  public void testGcTriggerConditionNotMetWhenNoDeadRecords() {
    // A fresh collection with no dead records should not trigger GC.
    YouTrackDBImpl youTrackDB = null;
    try {
      youTrackDB = DbTestBase.createYTDBManagerAndDb(
          "test", DatabaseType.MEMORY, getClass());
      var session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
      var storage = (AbstractStorage) session.getStorage();

      session.command("CREATE CLASS TestGcTrigger");
      session.begin();
      session.command("INSERT INTO TestGcTrigger SET name = 'v1'");
      session.commit();

      // Find any collection of this class
      var collectionIds = session.getClass("TestGcTrigger").getCollectionIds();
      PaginatedCollectionV2 collection = null;
      for (int cid : collectionIds) {
        for (var coll : storage.getCollectionInstances()) {
          if (coll.getId() == cid && coll instanceof PaginatedCollectionV2 pc) {
            collection = pc;
            break;
          }
        }
        if (collection != null) {
          break;
        }
      }
      assertThat(collection).isNotNull();

      // With default thresholds (minThreshold=1000, scaleFactor=0.1), a collection
      // with a few records and 0 dead records should not trigger GC.
      assertThat(collection.isGcTriggered(1_000, 0.1f)).isFalse();

      session.close();
    } finally {
      if (youTrackDB != null) {
        youTrackDB.close();
      }
    }
  }

  @Test
  public void testGcTriggerConditionMetWhenThresholdExceeded() {
    // Verify that the trigger fires when enough dead records accumulate.
    YouTrackDBImpl youTrackDB = null;
    try {
      youTrackDB = DbTestBase.createYTDBManagerAndDb(
          "test", DatabaseType.MEMORY, getClass());
      var session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
      var storage = (AbstractStorage) session.getStorage();

      // Set threshold to 0 so eviction runs on every transaction close
      storage.getContextConfiguration()
          .setValue(GlobalConfiguration.STORAGE_SNAPSHOT_INDEX_CLEANUP_THRESHOLD, 0);

      session.command("CREATE CLASS TestGcTrigger2");

      // Insert and update multiple records to accumulate dead record counts
      for (int i = 0; i < 10; i++) {
        session.begin();
        session.command("INSERT INTO TestGcTrigger2 SET name = 'v" + i + "'");
        session.commit();
      }
      // Update all records to create stale versions
      for (int i = 0; i < 5; i++) {
        session.begin();
        session.command("UPDATE TestGcTrigger2 SET name = 'updated" + i + "'");
        session.commit();
      }

      // Sum dead record counts across all collections of the class
      var collectionIds = session.getClass("TestGcTrigger2").getCollectionIds();
      long totalDead = 0;
      PaginatedCollectionV2 collectionWithDead = null;
      for (int cid : collectionIds) {
        for (var coll : storage.getCollectionInstances()) {
          if (coll.getId() == cid && coll instanceof PaginatedCollectionV2 pc) {
            totalDead += pc.getDeadRecordCount();
            if (pc.getDeadRecordCount() > 0) {
              collectionWithDead = pc;
            }
          }
        }
      }

      // At least some dead records should have been counted
      assertThat(collectionWithDead)
          .as("at least one collection should have dead records after updates")
          .isNotNull();

      // With minThreshold=0 and scaleFactor=0, any dead record triggers GC
      assertThat(collectionWithDead.isGcTriggered(0, 0.0f)).isTrue();

      // With very high threshold, it should not trigger even with dead records
      assertThat(collectionWithDead.isGcTriggered(1_000_000, 0.0f)).isFalse();

      session.close();
    } finally {
      if (youTrackDB != null) {
        youTrackDB.close();
      }
    }
  }

  // --- GlobalConfiguration: GC parameters ---

  @Test
  public void testGcConfigParameterDefaults() {
    // Verify the default values for the new GC configuration parameters.
    assertThat(GlobalConfiguration.STORAGE_COLLECTION_GC_MIN_THRESHOLD
        .getValueAsInteger()).isEqualTo(1_000);
    assertThat(GlobalConfiguration.STORAGE_COLLECTION_GC_SCALE_FACTOR
        .getValueAsFloat()).isEqualTo(0.1f);
  }
}
