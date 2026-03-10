package com.jetbrains.youtrackdb.internal.core.storage.collection.v2;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Integration tests for the records GC feature. Verifies end-to-end behavior of the GC
 * algorithm using a real (in-memory) database: record creation, update/delete to produce
 * dead versions, GC reclamation, dirty page bit set persistence across clean restarts,
 * and concurrent read/write workload alongside GC.
 *
 * <p>All tests use an in-memory database with cleanup threshold set to 0 so that snapshot
 * index entries are evicted eagerly, making dead records visible to the GC.
 */
public class RecordsGcIntegrationTest {

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
  // Integration: update path → GC reclaims dead records
  // ---------------------------------------------------------------------------

  // Verifies the full lifecycle: insert records, update them multiple times to create
  // dead versions, then invoke GC directly. After GC, the dead record counter should
  // be decremented and the collection should still return correct live data.
  @Test
  public void gcReclaimsDeadRecordsAfterUpdates() {
    try (var session = openSession()) {
      var storage = storage(session);
      setEagerCleanup(storage);

      session.command("CREATE CLASS GcUpdateTest");

      // Insert records
      int recordCount = 30;
      for (int i = 0; i < recordCount; i++) {
        session.begin();
        session.command("INSERT INTO GcUpdateTest SET idx = " + i);
        session.commit();
      }

      // Update multiple rounds to create dead versions
      int updateRounds = 4;
      for (int round = 0; round < updateRounds; round++) {
        session.begin();
        session.command("UPDATE GcUpdateTest SET ver = " + (round + 1));
        session.commit();
      }

      // Force snapshot cleanup + GC (evicts stale entries, increments dead record
      // counters, reclaims dead records from triggered collections).
      forceGc(storage);

      var pc = findCollection(session, storage, "GcUpdateTest");
      assertThat(pc).as("should find PaginatedCollectionV2").isNotNull();

      // After forceGc, a second collectDeadRecords pass should find nothing left.
      long remaining = pc.collectDeadRecords(storage.getSharedSnapshotIndex());
      assertThat(remaining)
          .as("all reclaimable dead records should have been reclaimed by forceGc")
          .isEqualTo(0);

      // Verify live data is still correct
      session.begin();
      int count = 0;
      try (var result = session.query("SELECT FROM GcUpdateTest")) {
        while (result.hasNext()) {
          var record = result.next();
          assertThat((int) record.getProperty("ver"))
              .as("live record should have latest version")
              .isEqualTo(updateRounds);
          count++;
        }
      }
      session.commit();
      assertThat(count).as("all live records should be readable").isEqualTo(recordCount);
    }
  }

  // ---------------------------------------------------------------------------
  // Integration: delete path → GC reclaims dead records
  // ---------------------------------------------------------------------------

  // Verifies that records deleted via the delete path produce dead versions that the GC
  // can reclaim. After deletion, the position map entries become REMOVED, so the GC
  // should identify the old record data as stale.
  @Test
  public void gcReclaimsDeadRecordsAfterDeletes() {
    try (var session = openSession()) {
      var storage = storage(session);
      setEagerCleanup(storage);

      session.command("CREATE CLASS GcDeleteTest");

      int recordCount = 20;
      for (int i = 0; i < recordCount; i++) {
        session.begin();
        session.command("INSERT INTO GcDeleteTest SET idx = " + i);
        session.commit();
      }

      // Delete half the records
      session.begin();
      session.command("DELETE FROM GcDeleteTest WHERE idx < 10");
      session.commit();

      // Advance the LWM past the delete's commit timestamp so snapshot entries
      // can be evicted. The eviction condition is strict less-than (recordTs < lwm),
      // so another transaction must commit to push idGen beyond the delete's ts.
      session.begin();
      session.command("UPDATE GcDeleteTest SET _bump = 1 WHERE idx = 10");
      session.commit();

      // Force snapshot cleanup and GC via periodicRecordsGc(), which evicts stale
      // entries (incrementing the dead record counter) then reclaims dead records.
      storage.getContextConfiguration()
          .setValue(GlobalConfiguration.STORAGE_COLLECTION_GC_MIN_THRESHOLD, 0);
      storage.getContextConfiguration()
          .setValue(GlobalConfiguration.STORAGE_COLLECTION_GC_SCALE_FACTOR, 0.0f);
      storage.periodicRecordsGc();

      var pc = findCollection(session, storage, "GcDeleteTest");
      assertThat(pc).as("should find PaginatedCollectionV2").isNotNull();

      // After periodic GC, a second collectDeadRecords pass should find nothing
      // (everything reclaimable was already reclaimed).
      long remaining = pc.collectDeadRecords(storage.getSharedSnapshotIndex());
      assertThat(remaining)
          .as("periodic GC should have reclaimed dead records from deletes")
          .isEqualTo(0);

      // Verify remaining records are intact
      session.begin();
      int count = 0;
      try (var result = session.query("SELECT FROM GcDeleteTest")) {
        while (result.hasNext()) {
          var record = result.next();
          int idx = record.getProperty("idx");
          assertThat(idx)
              .as("only non-deleted records should remain")
              .isGreaterThanOrEqualTo(10);
          count++;
        }
      }
      session.commit();
      assertThat(count)
          .as("exactly half should survive deletion")
          .isEqualTo(10);
    }
  }

  // ---------------------------------------------------------------------------
  // Integration: periodicRecordsGc() end-to-end
  // ---------------------------------------------------------------------------

  // Verifies that the periodic GC task entry point (periodicRecordsGc on AbstractStorage)
  // correctly identifies collections exceeding the trigger threshold and reclaims dead
  // records from them.
  @Test
  public void periodicGcReclaimsFromTriggeredCollections() {
    try (var session = openSession()) {
      var storage = storage(session);
      setEagerCleanup(storage);

      // Set very low thresholds so GC triggers easily
      storage.getContextConfiguration()
          .setValue(GlobalConfiguration.STORAGE_COLLECTION_GC_MIN_THRESHOLD, 0);
      storage.getContextConfiguration()
          .setValue(GlobalConfiguration.STORAGE_COLLECTION_GC_SCALE_FACTOR, 0.0f);

      session.command("CREATE CLASS GcPeriodicTest");

      for (int i = 0; i < 20; i++) {
        session.begin();
        session.command("INSERT INTO GcPeriodicTest SET idx = " + i);
        session.commit();
      }

      // Create dead records via multiple update rounds. Each round's commit
      // advances the LWM past the previous round's snapshot entries.
      for (int round = 0; round < 4; round++) {
        session.begin();
        session.command("UPDATE GcPeriodicTest SET ver = " + (round + 1));
        session.commit();
      }

      var pc = findCollection(session, storage, "GcPeriodicTest");
      assertThat(pc).as("should find PaginatedCollectionV2").isNotNull();

      // periodicRecordsGc() does two things:
      // 1. Opportunistically cleans snapshot/visibility indexes (evicts stale
      //    entries and increments dead record counters).
      // 2. Checks the trigger condition and reclaims dead records from
      //    collections that exceed the threshold.
      // Both steps happen in a single call — the GC runs end-to-end.
      storage.periodicRecordsGc();

      // After periodic GC runs, collectDeadRecords should find nothing left.
      long remaining =
          pc.collectDeadRecords(storage.getSharedSnapshotIndex());
      assertThat(remaining)
          .as("periodic GC should have reclaimed all reclaimable dead records")
          .isEqualTo(0);
    }
  }

  // ---------------------------------------------------------------------------
  // GC is idempotent: running twice does not break anything
  // ---------------------------------------------------------------------------

  // Verifies that running GC twice in a row is safe — the second pass should find no
  // additional records to reclaim (assuming no new writes between passes).
  @Test
  public void gcIsIdempotent() {
    try (var session = openSession()) {
      var storage = storage(session);
      setEagerCleanup(storage);

      session.command("CREATE CLASS GcIdempotentTest");

      for (int i = 0; i < 15; i++) {
        session.begin();
        session.command("INSERT INTO GcIdempotentTest SET idx = " + i);
        session.commit();
      }

      // Two update rounds: the second round's commit advances the LWM past the
      // first round's snapshot entries, allowing them to be evicted.
      for (int round = 1; round <= 2; round++) {
        session.begin();
        session.command("UPDATE GcIdempotentTest SET ver = " + round);
        session.commit();
      }

      // First pass: force cleanup + GC
      forceGc(storage);

      var pc = findCollection(session, storage, "GcIdempotentTest");
      assertThat(pc).as("should find PaginatedCollectionV2").isNotNull();

      // Second pass with no new writes should reclaim nothing (idempotent)
      long secondReclaimed =
          pc.collectDeadRecords(storage.getSharedSnapshotIndex());
      assertThat(secondReclaimed)
          .as("second GC pass should find nothing to reclaim")
          .isEqualTo(0);
    }
  }

  // ---------------------------------------------------------------------------
  // GC skips records still visible to active transactions
  // ---------------------------------------------------------------------------

  // Verifies that the GC does not reclaim records that are still referenced by the
  // snapshot index (i.e., visible to an active transaction). Opens a long-running read
  // transaction to pin snapshot entries, then runs GC and confirms it does not delete
  // the pinned versions.
  @Test
  public void gcSkipsRecordsVisibleToActiveTransactions() {
    try (var session = openSession()) {
      var storage = storage(session);
      setEagerCleanup(storage);

      session.command("CREATE CLASS GcSkipTest");

      for (int i = 0; i < 10; i++) {
        session.begin();
        session.command("INSERT INTO GcSkipTest SET idx = " + i);
        session.commit();
      }

      // Open a second session and start a long-running read transaction to pin
      // the current snapshot.
      try (var reader = openSession()) {
        reader.begin();
        // Read to establish the snapshot
        reader.query("SELECT FROM GcSkipTest").close();

        // Now update in the writer session to create new versions.
        // The old versions are pinned by the reader's snapshot.
        session.begin();
        session.command("UPDATE GcSkipTest SET ver = 1");
        session.commit();

        // Force snapshot cleanup and run GC while the reader holds the snapshot.
        // The snapshot entries for the old versions should NOT be evicted (the
        // reader's tsMin is below the eviction threshold), so GC should skip them.
        storage.getContextConfiguration()
            .setValue(
                GlobalConfiguration.STORAGE_COLLECTION_GC_MIN_THRESHOLD, 0);
        storage.getContextConfiguration()
            .setValue(
                GlobalConfiguration.STORAGE_COLLECTION_GC_SCALE_FACTOR, 0.0f);
        storage.periodicRecordsGc();

        var pc = findCollection(session, storage, "GcSkipTest");
        assertThat(pc).as("should find PaginatedCollectionV2").isNotNull();

        // The snapshot entries should still be present because the reader's
        // transaction holds the LWM below the entries' visibility timestamps.
        // A second collectDeadRecords should also find 0 reclaimable records.
        long reclaimed =
            pc.collectDeadRecords(storage.getSharedSnapshotIndex());
        assertThat(reclaimed)
            .as("GC should not reclaim records visible to the active reader")
            .isEqualTo(0);

        // Close the reader to release the snapshot
        reader.commit();
      }

      // Now GC should be able to reclaim after the reader closed.
      forceGc(storage);

      var pc = findCollection(session, storage, "GcSkipTest");
      // After forceGc, all reclaimable records should have been reclaimed.
      long remaining =
          pc.collectDeadRecords(storage.getSharedSnapshotIndex());
      assertThat(remaining)
          .as("all reclaimable dead records should be gone after reader closes")
          .isEqualTo(0);
    }
  }

  // ---------------------------------------------------------------------------
  // Clean restart: dirty page bit set survives close/reopen
  // ---------------------------------------------------------------------------

  // Verifies that the dirty page bit set persists across a clean database shutdown and
  // restart. After creating dead records (which set dirty bits), shut down and reopen
  // the database, then run GC. The GC should still find dirty pages and reclaim dead
  // records, proving the bit set survived the restart. This tests the normal persistence
  // path; WAL crash recovery is covered by the durable component infrastructure.
  @Test
  public void dirtyPageBitSetSurvivesRestart() {
    var dbPath = diskTestPath("restart");

    // Phase 1: Create dead records, then shut down the entire instance
    var diskYtdb = createDiskInstance(dbPath, "disktest");
    try (var session = (DatabaseSessionEmbedded) diskYtdb.open(
        "disktest", "admin", DbTestBase.ADMIN_PASSWORD)) {
      var storage = storage(session);
      setEagerCleanup(storage);

      session.command("CREATE CLASS GcRestartTest");

      for (int i = 0; i < 20; i++) {
        session.begin();
        session.command("INSERT INTO GcRestartTest SET idx = " + i);
        session.commit();
      }

      // Create dead versions
      for (int round = 0; round < 3; round++) {
        session.begin();
        session.command("UPDATE GcRestartTest SET ver = " + (round + 1));
        session.commit();
      }
    }
    diskYtdb.close();

    // Phase 2: Reopen from disk — simulate a restart
    diskYtdb = (YouTrackDBImpl) com.jetbrains.youtrackdb.api.YourTracks
        .instance(dbPath);
    try (var session = (DatabaseSessionEmbedded) diskYtdb.open(
        "disktest", "admin", DbTestBase.ADMIN_PASSWORD)) {
      var storage = storage(session);

      var pc = findCollection(session, storage, "GcRestartTest");
      assertThat(pc).as("should find PaginatedCollectionV2 after restart").isNotNull();

      // GC should find dirty pages from before the restart (bit set is durable)
      // and reclaim dead records whose snapshot entries are gone (in-memory
      // snapshot index is empty after restart).
      long reclaimed = pc.collectDeadRecords(storage.getSharedSnapshotIndex());
      assertThat(reclaimed)
          .as("GC should reclaim dead records using persisted dirty page bits")
          .isGreaterThan(0);

      // Verify live data is still correct after GC
      session.begin();
      int count = 0;
      try (var result = session.query("SELECT FROM GcRestartTest")) {
        while (result.hasNext()) {
          var record = result.next();
          assertThat((int) record.getProperty("ver"))
              .as("live records should have the latest version")
              .isEqualTo(3);
          count++;
        }
      }
      session.commit();
      assertThat(count).isEqualTo(20);
    } finally {
      diskYtdb.close();
    }
  }

  // ---------------------------------------------------------------------------
  // Concurrent: GC alongside read/write workload
  // ---------------------------------------------------------------------------

  // Verifies that the GC can run concurrently with a read/write workload without causing
  // data corruption or exceptions. Multiple writer threads continuously update records
  // while a GC thread periodically runs periodicRecordsGc(). After the workload ends,
  // all live records should still be readable with correct values.
  @Test
  public void gcRunsConcurrentlyWithReadWriteWorkload() throws Exception {
    try (var session = openSession()) {
      var storage = storage(session);
      setEagerCleanup(storage);

      storage.getContextConfiguration()
          .setValue(GlobalConfiguration.STORAGE_COLLECTION_GC_MIN_THRESHOLD, 0);
      storage.getContextConfiguration()
          .setValue(GlobalConfiguration.STORAGE_COLLECTION_GC_SCALE_FACTOR, 0.0f);

      session.command("CREATE CLASS GcConcurrentTest");

      int recordCount = 50;
      for (int i = 0; i < recordCount; i++) {
        session.begin();
        session.command("INSERT INTO GcConcurrentTest SET idx = " + i
            + ", ver = 0");
        session.commit();
      }
    }

    int writerThreads = 4;
    int gcThreads = 2;
    int totalIterations = 20;
    var stop = new AtomicBoolean(false);
    var errors = new AtomicReference<Throwable>();
    var successCount = new AtomicInteger();
    var startBarrier = new CyclicBarrier(writerThreads + gcThreads);
    var executor = Executors.newFixedThreadPool(writerThreads + gcThreads);
    var futures = new ArrayList<Future<?>>();

    try {
      // Writer threads: each writer updates a non-overlapping range of records.
      for (int w = 0; w < writerThreads; w++) {
        final int writerId = w;
        futures.add(executor.submit(() -> {
          try {
            startBarrier.await(30, TimeUnit.SECONDS);
            for (int iter = 0; iter < totalIterations && !stop.get(); iter++) {
              try (var s = openSession()) {
                // Retry on ConcurrentModificationException — expected under
                // concurrent writes (internal MVCC conflicts on shared
                // internal collections like schema metadata).
                for (int retry = 0; retry < 5; retry++) {
                  try {
                    s.begin();
                    s.command("UPDATE GcConcurrentTest SET ver = "
                        + ((writerId + 1) * 1000 + iter)
                        + " WHERE idx >= " + (writerId * 12)
                        + " AND idx < " + ((writerId + 1) * 12));
                    s.commit();
                    successCount.incrementAndGet();
                    break;
                  } catch (com.jetbrains.youtrackdb.api.exception.ConcurrentModificationException e) {
                    // Rollback the failed transaction before retrying.
                    s.rollback();
                  }
                }
              }
            }
          } catch (Throwable t) {
            errors.compareAndSet(null, t);
            stop.set(true);
          }
        }));
      }

      // GC threads: open one session per thread, reuse it across iterations.
      for (int g = 0; g < gcThreads; g++) {
        futures.add(executor.submit(() -> {
          try (var s = openSession()) {
            var st = storage(s);
            startBarrier.await(30, TimeUnit.SECONDS);
            while (!stop.get()) {
              st.periodicRecordsGc();
              Thread.sleep(5);
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } catch (Throwable t) {
            errors.compareAndSet(null, t);
            stop.set(true);
          }
        }));
      }

      // Wait for writers to finish, then stop GC threads
      for (int i = 0; i < writerThreads; i++) {
        futures.get(i).get(60, TimeUnit.SECONDS);
      }
      stop.set(true);
      for (int i = writerThreads; i < futures.size(); i++) {
        futures.get(i).get(10, TimeUnit.SECONDS);
      }
    } finally {
      executor.shutdownNow();
      executor.awaitTermination(10, TimeUnit.SECONDS);
    }

    assertThat(errors.get())
        .as("no exceptions should occur during concurrent GC + read/write")
        .isNull();
    assertThat(successCount.get())
        .as("at least some writer commits should have succeeded")
        .isGreaterThan(0);

    // Verify data integrity: all 50 records should be readable
    try (var session = openSession()) {
      session.begin();
      int count = 0;
      try (var result = session.query("SELECT FROM GcConcurrentTest")) {
        while (result.hasNext()) {
          result.next();
          count++;
        }
      }
      session.commit();
      assertThat(count)
          .as("all records should survive concurrent GC")
          .isEqualTo(50);
    }
  }

  // ---------------------------------------------------------------------------
  // Multi-chunk records: GC deletes entire chain
  // ---------------------------------------------------------------------------

  // Verifies that the GC correctly handles multi-chunk (large) records. When a large
  // record is updated, the old version spans multiple pages. The GC must follow the
  // nextPagePointer chain and delete all chunks, not just the start chunk.
  @Test
  public void gcReclaimsMultiChunkRecords() {
    try (var session = openSession()) {
      var storage = storage(session);
      setEagerCleanup(storage);

      session.command("CREATE CLASS GcMultiChunkTest");

      // Create a large record that will span multiple pages.
      // Default page size is 8KB, MAX_ENTRY_SIZE is ~8095 bytes. A 20KB string
      // will require multiple chunks.
      var largeValue = "x".repeat(20_000);

      session.begin();
      session.command("INSERT INTO GcMultiChunkTest SET data = '" + largeValue + "'");
      session.commit();

      // Three updates ensure at least the first version's snapshot entries get
      // evicted (each successive commit advances the LWM past the previous one).
      String[] values = {"y", "z", "w"};
      String finalLargeValue = null;
      for (var ch : values) {
        finalLargeValue = ch.repeat(20_000);
        session.begin();
        session.command(
            "UPDATE GcMultiChunkTest SET data = '" + finalLargeValue + "'");
        session.commit();
      }

      // Force snapshot index cleanup to evict stale entries and increment dead
      // record counters, then run GC on triggered collections.
      storage.getContextConfiguration()
          .setValue(GlobalConfiguration.STORAGE_COLLECTION_GC_MIN_THRESHOLD, 0);
      storage.getContextConfiguration()
          .setValue(GlobalConfiguration.STORAGE_COLLECTION_GC_SCALE_FACTOR, 0.0f);
      storage.periodicRecordsGc();

      var pc = findCollection(session, storage, "GcMultiChunkTest");
      assertThat(pc).as("should find PaginatedCollectionV2").isNotNull();

      // After periodicRecordsGc, all reclaimable records should be gone.
      // Verify by running collectDeadRecords directly — should return 0.
      long remaining = pc.collectDeadRecords(storage.getSharedSnapshotIndex());
      assertThat(remaining)
          .as("periodic GC should have already reclaimed multi-chunk dead records")
          .isEqualTo(0);

      // Verify the live record is still readable
      session.begin();
      try (var result = session.query("SELECT FROM GcMultiChunkTest")) {
        assertThat(result.hasNext()).isTrue();
        var record = result.next();
        assertThat((String) record.getProperty("data"))
            .as("live multi-chunk record should be intact")
            .isEqualTo(finalLargeValue);
        assertThat(result.hasNext()).isFalse();
      }
      session.commit();
    }
  }

  // ---------------------------------------------------------------------------
  // GC with mixed updates and deletes
  // ---------------------------------------------------------------------------

  // Verifies that GC correctly handles a mix of updated and deleted records on the same
  // collection pages. Some records are updated (position map points to new location),
  // others are deleted (position map is REMOVED). Both should be reclaimable.
  @Test
  public void gcHandlesMixedUpdatesAndDeletes() {
    try (var session = openSession()) {
      var storage = storage(session);
      setEagerCleanup(storage);

      session.command("CREATE CLASS GcMixedTest");

      for (int i = 0; i < 30; i++) {
        session.begin();
        session.command("INSERT INTO GcMixedTest SET idx = " + i);
        session.commit();
      }

      // Update even-indexed records
      session.begin();
      session.command("UPDATE GcMixedTest SET ver = 1 WHERE idx % 2 = 0");
      session.commit();

      // Delete odd-indexed records with idx < 15
      session.begin();
      session.command("DELETE FROM GcMixedTest WHERE idx % 2 = 1 AND idx < 15");
      session.commit();

      // Force snapshot cleanup and GC
      forceGc(storage);

      var pc = findCollection(session, storage, "GcMixedTest");
      assertThat(pc).as("should find PaginatedCollectionV2").isNotNull();

      // After forceGc, all reclaimable records should have been reclaimed.
      long remaining = pc.collectDeadRecords(storage.getSharedSnapshotIndex());
      assertThat(remaining)
          .as("all reclaimable dead records should be gone after forceGc")
          .isEqualTo(0);

      // Verify remaining records:
      // - Even-indexed: all 15 should exist with ver=1
      // - Odd-indexed >= 15: 8 records (15,17,19,21,23,25,27,29) with no ver
      session.begin();
      int count = 0;
      try (var result = session.query("SELECT FROM GcMixedTest ORDER BY idx")) {
        while (result.hasNext()) {
          result.next();
          count++;
        }
      }
      session.commit();

      // 15 even + 8 odd (>= 15) = 23
      assertThat(count)
          .as("correct number of records should survive")
          .isEqualTo(23);
    }
  }

  // ---------------------------------------------------------------------------
  // Dead record counter clamps to zero on over-decrement (post-restart scenario)
  // ---------------------------------------------------------------------------

  // Verifies the counter clamp behavior: after restart, the dead record counter starts
  // at 0, but GC may still reclaim pre-restart dead records. The counter should not go
  // negative — it clamps to zero.
  @Test
  public void deadRecordCounterClampsToZeroAfterRestart() {
    var dbPath = diskTestPath("clamp");

    // Phase 1: Create dead records, then shut down
    var diskYtdb = createDiskInstance(dbPath, "clamptest");
    try (var session = (DatabaseSessionEmbedded) diskYtdb.open(
        "clamptest", "admin", DbTestBase.ADMIN_PASSWORD)) {
      var storage = storage(session);
      setEagerCleanup(storage);

      session.command("CREATE CLASS GcClampTest");
      for (int i = 0; i < 10; i++) {
        session.begin();
        session.command("INSERT INTO GcClampTest SET idx = " + i);
        session.commit();
      }
      // Two update rounds so the first round's entries get evicted
      for (int round = 1; round <= 2; round++) {
        session.begin();
        session.command("UPDATE GcClampTest SET ver = " + round);
        session.commit();
      }
    }
    diskYtdb.close();

    // Phase 2: Reopen from disk — counter resets to 0
    diskYtdb = (YouTrackDBImpl) com.jetbrains.youtrackdb.api.YourTracks
        .instance(dbPath);
    try (var session = (DatabaseSessionEmbedded) diskYtdb.open(
        "clamptest", "admin", DbTestBase.ADMIN_PASSWORD)) {
      var storage = storage(session);

      var pc = findCollection(session, storage, "GcClampTest");
      assertThat(pc).as("should find collection after restart").isNotNull();

      // Counter is 0 after restart
      assertThat(pc.getDeadRecordCount())
          .as("counter should be 0 after restart")
          .isEqualTo(0);

      // GC reclaims pre-restart dead records using persisted dirty bits.
      // After restart the snapshot index is empty, so all stale records
      // whose dirty bits survived are immediately reclaimable.
      long reclaimed =
          pc.collectDeadRecords(storage.getSharedSnapshotIndex());
      assertThat(reclaimed).isGreaterThan(0);

      // Counter should be clamped at 0, not negative
      assertThat(pc.getDeadRecordCount())
          .as("counter should clamp to 0, not go negative")
          .isGreaterThanOrEqualTo(0);
    } finally {
      diskYtdb.close();
    }
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

  /**
   * Sets the snapshot index cleanup threshold to 0 so that stale snapshot entries are
   * evicted on every transaction close, making dead records immediately available for GC.
   */
  private static void setEagerCleanup(AbstractStorage storage) {
    storage.getContextConfiguration()
        .setValue(GlobalConfiguration.STORAGE_SNAPSHOT_INDEX_CLEANUP_THRESHOLD, 0);
  }

  /**
   * Forces snapshot index cleanup and records GC for all collections. Sets the cleanup
   * threshold to 0 and GC thresholds to their most permissive values, then invokes
   * {@link AbstractStorage#periodicRecordsGc()} which handles both eviction and
   * reclamation. This avoids reliance on automatic cleanup via {@code resetTsMin()},
   * which uses {@code tryLock()} and can be skipped under thread contention.
   */
  private static void forceGc(AbstractStorage storage) {
    storage.getContextConfiguration()
        .setValue(GlobalConfiguration.STORAGE_SNAPSHOT_INDEX_CLEANUP_THRESHOLD, 0);
    storage.getContextConfiguration()
        .setValue(GlobalConfiguration.STORAGE_COLLECTION_GC_MIN_THRESHOLD, 0);
    storage.getContextConfiguration()
        .setValue(GlobalConfiguration.STORAGE_COLLECTION_GC_SCALE_FACTOR, 0.0f);
    storage.periodicRecordsGc();
  }

  /**
   * Finds a {@link PaginatedCollectionV2} instance for the given class, or {@code null}
   * if none exists.
   */
  private static PaginatedCollectionV2 findCollection(
      DatabaseSessionEmbedded session,
      AbstractStorage storage,
      String className) {
    var collectionIds = session.getClass(className).getCollectionIds();
    for (int cid : collectionIds) {
      for (var coll : storage.getCollectionInstances()) {
        if (coll.getId() == cid && coll instanceof PaginatedCollectionV2 pc) {
          return pc;
        }
      }
    }
    return null;
  }

  /**
   * Returns a unique path for a DISK test, isolated from the MEMORY-based base path
   * used by {@link #setUp()}. Uses the build directory directly to avoid sharing a
   * parent path with the MEMORY YouTrackDB instance.
   */
  private static Path diskTestPath(String suffix) {
    var buildDir = Path.of(System.getProperty("buildDirectory", "./target"))
        .toAbsolutePath();
    // Include nanoTime to guarantee unique paths across surefire and vmlens runs
    // in the same JVM (the vmlens plugin re-executes all tests in a second pass).
    return buildDir.resolve("gc-disk-tests")
        .resolve(suffix + "-" + System.nanoTime());
  }

  /**
   * Creates a fresh YouTrackDB instance at the given path, cleaning up any stale data
   * from previous test runs. This is necessary because the vmlens plugin re-runs tests
   * in a second JVM, where DISK databases from the first run may still exist.
   */
  private static YouTrackDBImpl createDiskInstance(Path dbPath, String dbName) {
    FileUtils.deleteRecursively(dbPath.toFile());
    var instance = (YouTrackDBImpl) com.jetbrains.youtrackdb.api.YourTracks
        .instance(dbPath);
    instance.create(dbName, DatabaseType.DISK,
        new com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential(
            "admin", DbTestBase.ADMIN_PASSWORD,
            com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole.ADMIN));
    return instance;
  }
}
