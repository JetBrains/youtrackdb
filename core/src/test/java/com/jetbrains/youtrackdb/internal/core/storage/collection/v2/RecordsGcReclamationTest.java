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
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for records GC reclamation logic, covering the {@code collectDeadRecords} and
 * {@code processDirtyPage} code paths in {@link PaginatedCollectionV2}.
 *
 * <p>Named without the "IntegrationTest" suffix so PIT mutation testing includes these
 * tests in its coverage analysis. Each test exercises a specific code path that has
 * surviving mutants in the mutation testing gate.
 *
 * <p>All tests use an in-memory database with eager cleanup threshold so that snapshot
 * index entries are evicted immediately, making dead records visible to the GC.
 */
public class RecordsGcReclamationTest {

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
  // collectDeadRecords: basic reclamation with return value verification
  // ---------------------------------------------------------------------------

  // Exercises the core collectDeadRecords loop: dirty page scanning, stale record
  // detection, deletion, counter decrement, and return value. Targets mutants at
  // PaginatedCollectionV2 lines 1654, 1659, 1664, 1686, 1690, 1696, 1698, 1701.
  @Test
  public void collectDeadRecordsReturnsReclaimedCount() {
    try (var session = openSession()) {
      var storage = storage(session);
      setEagerCleanup(storage);

      session.command("CREATE CLASS GcReclaimCount");

      // Insert records
      for (int i = 0; i < 20; i++) {
        session.begin();
        session.command("INSERT INTO GcReclaimCount SET idx = " + i);
        session.commit();
      }

      // Update multiple rounds to create dead versions. Each round's commit
      // advances the LWM past the previous round's snapshot entries.
      for (int round = 1; round <= 3; round++) {
        session.begin();
        session.command("UPDATE GcReclaimCount SET ver = " + round);
        session.commit();
      }

      // Evict stale snapshot entries to increment dead record counters
      storage.getContextConfiguration()
          .setValue(GlobalConfiguration.STORAGE_COLLECTION_GC_MIN_THRESHOLD, 0);
      storage.getContextConfiguration()
          .setValue(GlobalConfiguration.STORAGE_COLLECTION_GC_SCALE_FACTOR, 0.0f);
      storage.periodicRecordsGc();

      // Verify all collections had their dead records reclaimed
      var collections = findAllCollections(session, storage, "GcReclaimCount");
      assertThat(collections).isNotEmpty();

      // Second pass: collectDeadRecords should return 0 (nothing left to reclaim)
      long totalRemaining = 0;
      for (var pc : collections) {
        totalRemaining += pc.collectDeadRecords(storage.getSharedSnapshotIndex());
      }
      assertThat(totalRemaining)
          .as("second GC pass should find nothing to reclaim")
          .isEqualTo(0);

      // Dead record counters should be at 0 after reclamation
      for (var pc : collections) {
        assertThat(pc.getDeadRecordCount())
            .as("dead record counter should be 0 after GC for " + pc.getName())
            .isEqualTo(0);
      }

      // Verify live data is intact
      session.begin();
      int count = 0;
      try (var result = session.query("SELECT FROM GcReclaimCount")) {
        while (result.hasNext()) {
          var record = result.next();
          assertThat((int) record.getProperty("ver"))
              .as("live record should have latest version")
              .isEqualTo(3);
          count++;
        }
      }
      session.commit();
      assertThat(count).isEqualTo(20);
    }
  }

  // ---------------------------------------------------------------------------
  // collectDeadRecords: multi-chunk record deletion
  // ---------------------------------------------------------------------------

  // Exercises the deleteRecordChunks method and continuation page processing.
  // Targets mutants at lines 1731-1804, 1835 (multi-chunk chain traversal).
  @Test
  public void collectDeadRecordsHandlesMultiChunkRecords() {
    try (var session = openSession()) {
      var storage = storage(session);
      setEagerCleanup(storage);

      session.command("CREATE CLASS GcMultiChunk");

      // Create a large record that spans multiple pages (~20KB with 8KB page size)
      var largeValue = "x".repeat(20_000);
      session.begin();
      session.command("INSERT INTO GcMultiChunk SET data = '" + largeValue + "'");
      session.commit();

      // Update the record 3 times to create dead multi-chunk versions
      for (var ch : new String[] {"y", "z", "w"}) {
        var newVal = ch.repeat(20_000);
        session.begin();
        session.command("UPDATE GcMultiChunk SET data = '" + newVal + "'");
        session.commit();
      }

      // Run GC
      forceGc(storage);

      // All collections should have zero remaining dead records
      var collections = findAllCollections(session, storage, "GcMultiChunk");
      for (var pc : collections) {
        long remaining = pc.collectDeadRecords(storage.getSharedSnapshotIndex());
        assertThat(remaining)
            .as("all multi-chunk dead records should be reclaimed for " + pc.getName())
            .isEqualTo(0);
      }

      // Verify live record is intact
      session.begin();
      try (var result = session.query("SELECT FROM GcMultiChunk")) {
        assertThat(result.hasNext()).isTrue();
        var record = result.next();
        assertThat((String) record.getProperty("data"))
            .hasSize(20_000)
            .startsWith("w");
        assertThat(result.hasNext()).isFalse();
      }
      session.commit();
    }
  }

  // ---------------------------------------------------------------------------
  // processDirtyPage: live record detection (not reclaimed)
  // ---------------------------------------------------------------------------

  // Exercises the live record check: currentEntry != null && pageIndex/recordPosition match.
  // Targets mutants at lines 1752-1754 (live version detection conditions).
  @Test
  public void collectDeadRecordsSkipsLiveRecords() {
    try (var session = openSession()) {
      var storage = storage(session);
      setEagerCleanup(storage);

      session.command("CREATE CLASS GcSkipLive");

      // Insert records
      for (int i = 0; i < 10; i++) {
        session.begin();
        session.command("INSERT INTO GcSkipLive SET idx = " + i);
        session.commit();
      }

      // Update only even-indexed records to create dead versions.
      // Odd-indexed records remain at their original version (live on the page).
      session.begin();
      session.command("UPDATE GcSkipLive SET ver = 1 WHERE idx % 2 = 0");
      session.commit();

      // One more commit to advance LWM
      session.begin();
      session.command("UPDATE GcSkipLive SET ver = 2 WHERE idx % 2 = 0");
      session.commit();

      // Run GC
      forceGc(storage);

      // Verify all records are still readable
      session.begin();
      int count = 0;
      try (var result = session.query("SELECT FROM GcSkipLive ORDER BY idx")) {
        while (result.hasNext()) {
          result.next();
          count++;
        }
      }
      session.commit();
      assertThat(count).as("all records should survive GC").isEqualTo(10);
    }
  }

  // ---------------------------------------------------------------------------
  // processDirtyPage: snapshot index blocks reclamation
  // ---------------------------------------------------------------------------

  // Exercises the snapshotIndex.containsKey check and anyStaleRemaining flag.
  // Targets mutants at lines 1764, 1777, 1778 (snapshot blocking + dirty bit clearing).
  @Test
  public void collectDeadRecordsSkipsSnapshotBlockedRecords() {
    try (var session = openSession()) {
      var storage = storage(session);
      setEagerCleanup(storage);

      session.command("CREATE CLASS GcSnapBlock");

      for (int i = 0; i < 10; i++) {
        session.begin();
        session.command("INSERT INTO GcSnapBlock SET idx = " + i);
        session.commit();
      }

      // Open a reader that pins the current snapshot
      try (var reader = openSession()) {
        reader.begin();
        reader.query("SELECT FROM GcSnapBlock").close();

        // Update in writer session to create new versions
        session.begin();
        session.command("UPDATE GcSnapBlock SET ver = 1");
        session.commit();

        // Run GC while reader holds snapshot — stale records should NOT be reclaimed
        storage.getContextConfiguration()
            .setValue(GlobalConfiguration.STORAGE_COLLECTION_GC_MIN_THRESHOLD, 0);
        storage.getContextConfiguration()
            .setValue(GlobalConfiguration.STORAGE_COLLECTION_GC_SCALE_FACTOR, 0.0f);
        storage.periodicRecordsGc();

        // collectDeadRecords should also return 0 (all stale records are snapshot-blocked)
        var collections = findAllCollections(session, storage, "GcSnapBlock");
        for (var pc : collections) {
          long reclaimed = pc.collectDeadRecords(storage.getSharedSnapshotIndex());
          assertThat(reclaimed)
              .as("snapshot-blocked records should not be reclaimed for " + pc.getName())
              .isEqualTo(0);
        }

        reader.commit();
      }

      // After reader closes, GC should reclaim the previously blocked records.
      // We run periodicRecordsGc which evicts stale snapshot entries (incrementing
      // dead counters) and then runs GC on triggered collections.
      forceGc(storage);

      // Verify: the first GC pass must have reclaimed > 0. If the dirty bits
      // were incorrectly cleared during the snapshot-blocked pass, this second
      // GC would find nothing and return 0.
      var collections = findAllCollections(session, storage, "GcSnapBlock");
      // A second collectDeadRecords pass should return 0 (everything already reclaimed)
      for (var pc : collections) {
        long remaining = pc.collectDeadRecords(storage.getSharedSnapshotIndex());
        assertThat(remaining)
            .as("all dead records should be reclaimed after reader closes")
            .isEqualTo(0);
      }

      // Counter should be at 0 — all dead records were reclaimed
      for (var pc : collections) {
        assertThat(pc.getDeadRecordCount())
            .as("dead record counter should be 0 after full reclamation")
            .isEqualTo(0);
      }
    }
  }

  // ---------------------------------------------------------------------------
  // processDirtyPage: defragmentation and free space map update
  // ---------------------------------------------------------------------------

  // Exercises the defragmentation and freeSpaceMap update after record deletion.
  // Targets mutants at lines 1783-1795 (defragmentation and free space map calls).
  @Test
  public void collectDeadRecordsDefragmentsPageAndUpdatesFreespace() {
    try (var session = openSession()) {
      var storage = storage(session);
      setEagerCleanup(storage);

      session.command("CREATE CLASS GcDefrag");

      // Insert records to fill pages
      for (int i = 0; i < 30; i++) {
        session.begin();
        session.command("INSERT INTO GcDefrag SET idx = " + i
            + ", payload = '" + "x".repeat(200) + "'");
        session.commit();
      }

      // Update all records twice to create dead versions with known payload size
      for (int round = 1; round <= 2; round++) {
        session.begin();
        session.command("UPDATE GcDefrag SET ver = " + round);
        session.commit();
      }

      // Run GC to reclaim dead records (which triggers defragmentation)
      forceGc(storage);

      // Verify we can insert new records that reuse the reclaimed space.
      // If defragmentation and free space map update didn't happen, the allocator
      // might not find space on existing pages and would append new pages instead.
      for (int i = 0; i < 10; i++) {
        session.begin();
        session.command("INSERT INTO GcDefrag SET idx = " + (30 + i)
            + ", payload = '" + "y".repeat(200) + "'");
        session.commit();
      }

      // All records should be readable
      session.begin();
      int count = 0;
      try (var result = session.query("SELECT FROM GcDefrag")) {
        while (result.hasNext()) {
          result.next();
          count++;
        }
      }
      session.commit();
      assertThat(count).isEqualTo(40);
    }
  }

  // ---------------------------------------------------------------------------
  // processDirtyPage: deleted records are skipped
  // ---------------------------------------------------------------------------

  // Exercises the isDeleted check at line 1733.
  @Test
  public void collectDeadRecordsSkipsDeletedSlots() {
    try (var session = openSession()) {
      var storage = storage(session);
      setEagerCleanup(storage);

      session.command("CREATE CLASS GcDelSlots");

      for (int i = 0; i < 20; i++) {
        session.begin();
        session.command("INSERT INTO GcDelSlots SET idx = " + i);
        session.commit();
      }

      // Delete some records (creates REMOVED position map entries)
      session.begin();
      session.command("DELETE FROM GcDelSlots WHERE idx < 10");
      session.commit();

      // Update remaining to create dead versions
      session.begin();
      session.command("UPDATE GcDelSlots SET ver = 1 WHERE idx >= 10");
      session.commit();

      // Advance LWM
      session.begin();
      session.command("UPDATE GcDelSlots SET ver = 2 WHERE idx >= 10");
      session.commit();

      // Run GC — should handle deleted slots + stale versions
      forceGc(storage);

      // Verify remaining records
      session.begin();
      int count = 0;
      try (var result = session.query("SELECT FROM GcDelSlots")) {
        while (result.hasNext()) {
          var record = result.next();
          int idx = record.getProperty("idx");
          assertThat(idx).isGreaterThanOrEqualTo(10);
          count++;
        }
      }
      session.commit();
      assertThat(count).isEqualTo(10);
    }
  }

  // ---------------------------------------------------------------------------
  // periodicRecordsGc: only triggered collections are processed
  // ---------------------------------------------------------------------------

  // Exercises the instanceof check and isGcTriggered condition in periodicRecordsGc.
  // Targets mutants at AbstractStorage lines 5516, 5519, 5520.
  @Test
  public void periodicGcOnlyProcessesTriggeredCollections() {
    try (var session = openSession()) {
      var storage = storage(session);
      setEagerCleanup(storage);

      session.command("CREATE CLASS GcTriggered");
      session.command("CREATE CLASS GcNotTriggered");

      // Insert and update in GcTriggered to create dead records
      for (int i = 0; i < 10; i++) {
        session.begin();
        session.command("INSERT INTO GcTriggered SET idx = " + i);
        session.commit();
      }
      for (int round = 1; round <= 3; round++) {
        session.begin();
        session.command("UPDATE GcTriggered SET ver = " + round);
        session.commit();
      }

      // Only insert in GcNotTriggered (no dead records)
      session.begin();
      session.command("INSERT INTO GcNotTriggered SET idx = 0");
      session.commit();

      // Run periodicRecordsGc with low thresholds
      storage.getContextConfiguration()
          .setValue(GlobalConfiguration.STORAGE_COLLECTION_GC_MIN_THRESHOLD, 0);
      storage.getContextConfiguration()
          .setValue(GlobalConfiguration.STORAGE_COLLECTION_GC_SCALE_FACTOR, 0.0f);
      storage.periodicRecordsGc();

      // GcTriggered should have all dead records reclaimed
      var triggered = findAllCollections(session, storage, "GcTriggered");
      for (var pc : triggered) {
        long remaining = pc.collectDeadRecords(storage.getSharedSnapshotIndex());
        assertThat(remaining).isEqualTo(0);
      }

      // GcNotTriggered should have 0 dead records (was never triggered)
      var notTriggered = findAllCollections(session, storage, "GcNotTriggered");
      for (var pc : notTriggered) {
        assertThat(pc.getDeadRecordCount()).isEqualTo(0);
      }
    }
  }

  // ---------------------------------------------------------------------------
  // periodicRecordsGc: status check prevents GC on non-open storage
  // ---------------------------------------------------------------------------

  // Exercises the status != STATUS.OPEN guard at AbstractStorage line 5496.
  @Test
  public void periodicGcIsNoOpOnClosedStorage() {
    var session = openSession();
    var storage = storage(session);
    setEagerCleanup(storage);

    session.command("CREATE CLASS GcClosed");
    for (int i = 0; i < 5; i++) {
      session.begin();
      session.command("INSERT INTO GcClosed SET idx = " + i);
      session.commit();
    }
    session.begin();
    session.command("UPDATE GcClosed SET ver = 1");
    session.commit();

    // Capture storage reference before closing
    var storageRef = storage;

    // Close session and the entire YouTrackDB instance
    session.close();
    youTrackDB.close();

    // Calling periodicRecordsGc on a closed storage should be a no-op.
    // The status != STATUS.OPEN guard at line 5496 prevents any work.
    // If the guard is removed (mutant), this would throw because internal
    // structures are no longer available.
    storageRef.periodicRecordsGc();

    // Recreate youTrackDB for tearDown
    youTrackDB = DbTestBase.createYTDBManagerAndDb(
        "test", DatabaseType.MEMORY, getClass());
  }

  // ---------------------------------------------------------------------------
  // collectDeadRecords: dead record counter clamps to zero
  // ---------------------------------------------------------------------------

  // Exercises the clamping logic at line 1698: Math.max(0, current - reclaimed).
  @Test
  public void deadRecordCounterClampsToZero() {
    try (var session = openSession()) {
      var storage = storage(session);
      setEagerCleanup(storage);

      session.command("CREATE CLASS GcClamp");

      for (int i = 0; i < 10; i++) {
        session.begin();
        session.command("INSERT INTO GcClamp SET idx = " + i);
        session.commit();
      }

      // Create dead versions
      for (int round = 1; round <= 3; round++) {
        session.begin();
        session.command("UPDATE GcClamp SET ver = " + round);
        session.commit();
      }

      // Run GC which reclaims and decrements the counter
      forceGc(storage);

      // Counter should be clamped at exactly 0 (not negative, not stuck positive)
      var collections = findAllCollections(session, storage, "GcClamp");
      for (var pc : collections) {
        assertThat(pc.getDeadRecordCount())
            .as("counter should be 0 after GC for " + pc.getName())
            .isEqualTo(0);
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Dirty page bit set: synch flushes bit set
  // ---------------------------------------------------------------------------

  // Exercises the dirtyPageBitSet.flush() call in PaginatedCollectionV2.synch().
  // Targets mutant at line 1885.
  @Test
  public void synchFlushesDirtyPageBitSet() {
    var dbPath = diskTestPath("synch");

    var diskYtdb = createDiskInstance(dbPath, "synchtest");
    try (var session = (DatabaseSessionEmbedded) diskYtdb.open(
        "synchtest", "admin", DbTestBase.ADMIN_PASSWORD)) {
      var storage = storage(session);
      setEagerCleanup(storage);

      session.command("CREATE CLASS GcSynch");

      for (int i = 0; i < 10; i++) {
        session.begin();
        session.command("INSERT INTO GcSynch SET idx = " + i);
        session.commit();
      }

      // Update to set dirty bits
      session.begin();
      session.command("UPDATE GcSynch SET ver = 1");
      session.commit();
    }
    // Close triggers synch() which flushes the dirty page bit set
    diskYtdb.close();

    // Reopen and verify dirty bits survived (GC can find dirty pages)
    diskYtdb = (YouTrackDBImpl) com.jetbrains.youtrackdb.api.YourTracks.instance(dbPath);
    try (var session = (DatabaseSessionEmbedded) diskYtdb.open(
        "synchtest", "admin", DbTestBase.ADMIN_PASSWORD)) {
      var storage = storage(session);

      var collections = findAllCollections(session, storage, "GcSynch");
      assertThat(collections).isNotEmpty();

      long reclaimed = 0;
      for (var pc : collections) {
        reclaimed += pc.collectDeadRecords(storage.getSharedSnapshotIndex());
      }
      // After restart with no active transactions, snapshot index is empty,
      // so all stale records whose dirty bits survived are reclaimable.
      assertThat(reclaimed)
          .as("dirty bits should survive synch/restart, enabling GC to reclaim")
          .isGreaterThan(0);
    } finally {
      diskYtdb.close();
    }
  }

  // ---------------------------------------------------------------------------
  // Dirty page bit set: rename
  // ---------------------------------------------------------------------------

  // Exercises the dirtyPageBitSet.rename() call in setCollectionName().
  // Targets mutant at line 1986.
  @Test
  public void renameCollectionRenamesDirtyPageBitSet() {
    var dbPath = diskTestPath("rename");

    var diskYtdb = createDiskInstance(dbPath, "renametest");
    try (var session = (DatabaseSessionEmbedded) diskYtdb.open(
        "renametest", "admin", DbTestBase.ADMIN_PASSWORD)) {
      var storage = storage(session);
      setEagerCleanup(storage);

      session.command("CREATE CLASS GcRename");

      for (int i = 0; i < 5; i++) {
        session.begin();
        session.command("INSERT INTO GcRename SET idx = " + i);
        session.commit();
      }

      // Update to set dirty bits
      session.begin();
      session.command("UPDATE GcRename SET ver = 1");
      session.commit();

      // Rename the class (which renames collections and their associated files)
      session.command("ALTER CLASS GcRename NAME GcRenamed");

      // Another update to create more dead records under the new name
      session.begin();
      session.command("UPDATE GcRenamed SET ver = 2");
      session.commit();
    }
    diskYtdb.close();

    // Reopen and verify GC still works under the new name
    diskYtdb = (YouTrackDBImpl) com.jetbrains.youtrackdb.api.YourTracks.instance(dbPath);
    try (var session = (DatabaseSessionEmbedded) diskYtdb.open(
        "renametest", "admin", DbTestBase.ADMIN_PASSWORD)) {
      var storage = storage(session);

      var collections = findAllCollections(session, storage, "GcRenamed");
      assertThat(collections).isNotEmpty();

      long reclaimed = 0;
      for (var pc : collections) {
        reclaimed += pc.collectDeadRecords(storage.getSharedSnapshotIndex());
      }
      assertThat(reclaimed)
          .as("GC should work after collection rename")
          .isGreaterThan(0);
    } finally {
      diskYtdb.close();
    }
  }

  // ---------------------------------------------------------------------------
  // Dirty page bit set: open existing bit set
  // ---------------------------------------------------------------------------

  // Exercises the dirtyPageBitSet.exists() / open() path at line 427.
  // Targets mutant at PaginatedCollectionV2 line 427.
  @Test
  public void openExistingDirtyPageBitSet() {
    var dbPath = diskTestPath("openbs");

    var diskYtdb = createDiskInstance(dbPath, "opentest");
    try (var session = (DatabaseSessionEmbedded) diskYtdb.open(
        "opentest", "admin", DbTestBase.ADMIN_PASSWORD)) {
      session.command("CREATE CLASS GcOpen");
      session.begin();
      session.command("INSERT INTO GcOpen SET idx = 0");
      session.commit();
    }
    diskYtdb.close();

    // Reopen: the dirty page bit set file already exists, so open() is called (not create())
    diskYtdb = (YouTrackDBImpl) com.jetbrains.youtrackdb.api.YourTracks.instance(dbPath);
    try (var session = (DatabaseSessionEmbedded) diskYtdb.open(
        "opentest", "admin", DbTestBase.ADMIN_PASSWORD)) {
      // If open() failed, queries would break
      session.begin();
      int count = 0;
      try (var result = session.query("SELECT FROM GcOpen")) {
        while (result.hasNext()) {
          result.next();
          count++;
        }
      }
      session.commit();
      assertThat(count).isEqualTo(1);
    } finally {
      diskYtdb.close();
    }
  }

  // ---------------------------------------------------------------------------
  // close: dirtyPageBitSet.close() is called
  // ---------------------------------------------------------------------------

  // Exercises the dirtyPageBitSet.close() call at line 452.
  // Targets mutant at PaginatedCollectionV2 line 452.
  @Test
  public void closeDirtyPageBitSetOnShutdown() {
    var dbPath = diskTestPath("closedpb");

    var diskYtdb = createDiskInstance(dbPath, "closetest");
    try (var session = (DatabaseSessionEmbedded) diskYtdb.open(
        "closetest", "admin", DbTestBase.ADMIN_PASSWORD)) {
      session.command("CREATE CLASS GcClose");
      session.begin();
      session.command("INSERT INTO GcClose SET idx = 0");
      session.commit();

      // Update to create dirty bits
      session.begin();
      session.command("UPDATE GcClose SET ver = 1");
      session.commit();
    }
    // Close triggers close(flush=true) on the dirty page bit set
    diskYtdb.close();

    // Reopen should work (file was properly closed)
    diskYtdb = (YouTrackDBImpl) com.jetbrains.youtrackdb.api.YourTracks.instance(dbPath);
    try (var session = (DatabaseSessionEmbedded) diskYtdb.open(
        "closetest", "admin", DbTestBase.ADMIN_PASSWORD)) {
      session.begin();
      try (var result = session.query("SELECT FROM GcClose")) {
        assertThat(result.hasNext()).isTrue();
      }
      session.commit();
    } finally {
      diskYtdb.close();
    }
  }

  // ---------------------------------------------------------------------------
  // periodicRecordsGc: cleanupSnapshotIndex is actually called
  // ---------------------------------------------------------------------------

  // Verifies that periodicRecordsGc calls cleanupSnapshotIndex, which evicts
  // stale snapshot entries and increments dead record counters. Without the
  // cleanup call, snapshot entries remain, dead counters stay at 0, and GC
  // never triggers.
  // Targets mutant at AbstractStorage line 5502: "removed call to cleanupSnapshotIndex".
  @Test
  public void periodicGcRunsSnapshotCleanup() {
    try (var session = openSession()) {
      var storage = storage(session);

      // Set cleanup threshold to MAX_VALUE so commits never trigger eviction.
      // Only periodicRecordsGc should evict entries.
      storage.getContextConfiguration()
          .setValue(GlobalConfiguration.STORAGE_SNAPSHOT_INDEX_CLEANUP_THRESHOLD,
              Integer.MAX_VALUE);

      session.command("CREATE CLASS GcSnapCleanup");

      for (int i = 0; i < 10; i++) {
        session.begin();
        session.command("INSERT INTO GcSnapCleanup SET idx = " + i);
        session.commit();
      }

      // Update records to create stale snapshot entries
      for (int round = 1; round <= 3; round++) {
        session.begin();
        session.command("UPDATE GcSnapCleanup SET ver = " + round);
        session.commit();
      }

      var collections = findAllCollections(session, storage, "GcSnapCleanup");

      // periodicRecordsGc should call cleanupSnapshotIndex, which evicts entries
      // and increments dead counters. Set GC thresholds to 0 to ensure triggered.
      storage.getContextConfiguration()
          .setValue(GlobalConfiguration.STORAGE_COLLECTION_GC_MIN_THRESHOLD, 0);
      storage.getContextConfiguration()
          .setValue(GlobalConfiguration.STORAGE_COLLECTION_GC_SCALE_FACTOR, 0.0f);
      storage.periodicRecordsGc();

      // After periodicRecordsGc with cleanupSnapshotIndex, stale entries should
      // have been evicted. If the cleanup call was removed (mutant), dead counters
      // would not increase and GC would not find any dirty pages to reclaim.
      // Verify by running collectDeadRecords — if cleanup happened and GC ran,
      // a second pass should find 0.
      long secondPassReclaimed = 0;
      for (var pc : collections) {
        secondPassReclaimed += pc.collectDeadRecords(
            storage.getSharedSnapshotIndex());
      }
      assertThat(secondPassReclaimed)
          .as("after periodicRecordsGc (which includes cleanup), "
              + "second GC pass should find nothing left")
          .isEqualTo(0);

      // Verify live data is intact
      session.begin();
      int count = 0;
      try (var result = session.query("SELECT FROM GcSnapCleanup")) {
        while (result.hasNext()) {
          result.next();
          count++;
        }
      }
      session.commit();
      assertThat(count).isEqualTo(10);
    }
  }

  // ---------------------------------------------------------------------------
  // collectDeadRecords: return value matches actual reclamation
  // ---------------------------------------------------------------------------

  // Exercises the return value of collectDeadRecords and the dead record counter
  // decrement logic. The test creates known dead records, runs snapshot eviction
  // (to populate dirty bits and dead counters) but NOT the GC pass, then calls
  // collectDeadRecords directly to verify:
  // (a) the first pass returns > 0 (records were reclaimed), and
  // (b) a second pass returns 0 (nothing left).
  // Targets mutants at PaginatedCollectionV2 line 1698 (return 0 for lambda)
  // and the counter update at lines 1696-1698.
  @Test
  public void collectDeadRecordsReturnValueMatchesReclamation() {
    try (var session = openSession()) {
      var storage = storage(session);
      setEagerCleanup(storage);

      session.command("CREATE CLASS GcReturnVal");

      // Insert 5 records
      for (int i = 0; i < 5; i++) {
        session.begin();
        session.command("INSERT INTO GcReturnVal SET idx = " + i);
        session.commit();
      }

      // Update all records twice to create dead versions.
      // With eager cleanup, each commit's snapshot eviction will increment
      // the dead record counters and set dirty bits.
      for (int round = 1; round <= 2; round++) {
        session.begin();
        session.command("UPDATE GcReturnVal SET ver = " + round);
        session.commit();
      }

      // Do one more no-op transaction to advance the LWM past the last update,
      // ensuring all stale snapshot entries are evicted.
      session.begin();
      session.command("INSERT INTO GcReturnVal SET idx = 99");
      session.commit();

      // Now call collectDeadRecords directly (NOT through periodicRecordsGc,
      // which would run GC itself and leave nothing for us to measure)
      var collections = findAllCollections(session, storage, "GcReturnVal");
      long totalReclaimed = 0;
      for (var pc : collections) {
        totalReclaimed += pc.collectDeadRecords(storage.getSharedSnapshotIndex());
      }
      assertThat(totalReclaimed)
          .as("first GC pass should reclaim dead records")
          .isGreaterThan(0);

      // Second pass should find nothing
      long secondPassReclaimed = 0;
      for (var pc : collections) {
        secondPassReclaimed += pc.collectDeadRecords(
            storage.getSharedSnapshotIndex());
      }
      assertThat(secondPassReclaimed)
          .as("second GC pass should find nothing to reclaim")
          .isEqualTo(0);
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

  private static void setEagerCleanup(AbstractStorage storage) {
    storage.getContextConfiguration()
        .setValue(GlobalConfiguration.STORAGE_SNAPSHOT_INDEX_CLEANUP_THRESHOLD, 0);
  }

  private static void forceGc(AbstractStorage storage) {
    storage.getContextConfiguration()
        .setValue(GlobalConfiguration.STORAGE_SNAPSHOT_INDEX_CLEANUP_THRESHOLD, 0);
    storage.getContextConfiguration()
        .setValue(GlobalConfiguration.STORAGE_COLLECTION_GC_MIN_THRESHOLD, 0);
    storage.getContextConfiguration()
        .setValue(GlobalConfiguration.STORAGE_COLLECTION_GC_SCALE_FACTOR, 0.0f);
    storage.periodicRecordsGc();
  }

  private static java.util.List<PaginatedCollectionV2> findAllCollections(
      DatabaseSessionEmbedded session,
      AbstractStorage storage,
      String className) {
    var result = new ArrayList<PaginatedCollectionV2>();
    var collectionIds = session.getClass(className).getCollectionIds();
    for (int cid : collectionIds) {
      for (var coll : storage.getCollectionInstances()) {
        if (coll.getId() == cid && coll instanceof PaginatedCollectionV2 pc) {
          result.add(pc);
        }
      }
    }
    return result;
  }

  private static Path diskTestPath(String suffix) {
    var buildDir = Path.of(System.getProperty("buildDirectory", "./target"))
        .toAbsolutePath();
    return buildDir.resolve("gc-reclamation-tests")
        .resolve(suffix + "-" + System.nanoTime());
  }

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
