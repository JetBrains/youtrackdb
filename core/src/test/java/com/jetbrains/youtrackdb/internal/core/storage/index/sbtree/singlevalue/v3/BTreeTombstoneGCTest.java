package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.id.SnapshotMarkerRID;
import com.jetbrains.youtrackdb.internal.core.id.TombstoneRID;
import com.jetbrains.youtrackdb.internal.core.index.CompositeKey;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.impl.index.IndexMultiValuKeySerializer;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import java.io.File;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Tests for tombstone GC during leaf page splits in {@link BTree}. Verifies
 * that removable {@link TombstoneRID} entries (below global LWM) are filtered
 * out during bucket overflow, that stale {@link SnapshotMarkerRID} entries are
 * demoted to plain {@link RecordId}, and that live entries remain untouched.
 *
 * <p>The GC is triggered when {@code addLeafEntry()} returns false (bucket
 * full) in the {@code update()} method.
 *
 * <p>Keys use the single-value index format: {@code CompositeKey(userKey, version)}.
 * Tombstones and live entries are interleaved in key space to ensure they share
 * buckets, so that overflow on one triggers GC of the other.
 */
public class BTreeTombstoneGCTest {

  private static final String DB_NAME = "btreeIndexTombstoneGCTest";
  private static final String DIR_NAME = "/btreeIndexTombstoneGCTest";

  // Enough entries to fill multiple buckets and trigger splits. With ~30 bytes
  // per entry (CompositeKey(String, Long) + RID) and ~8KB usable per bucket,
  // ~250 entries per bucket. We use 400 to ensure multiple splits.
  private static final int FILL_COUNT = 400;

  // Stub engine ID used for indexEngineNameMap registration so that
  // AbstractStorage.hasActiveSnapshotEntries() resolves the correct index.
  private static final int STUB_ENGINE_ID = 99;
  private static final String ENGINE_NAME = "tombstoneGCIdx";

  private static YouTrackDBImpl youTrackDB;
  private static AtomicOperationsManager atomicOperationsManager;
  private static AbstractStorage storage;
  private static String buildDirectory;

  private BTree<CompositeKey> bTree;

  @BeforeClass
  public static void beforeClass() {
    buildDirectory = System.getProperty("buildDirectory");
    if (buildDirectory == null) {
      buildDirectory = "./target" + DIR_NAME;
    } else {
      buildDirectory += DIR_NAME;
    }

    FileUtils.deleteRecursively(new File(buildDirectory));

    youTrackDB = (YouTrackDBImpl) YourTracks.instance(buildDirectory);
    if (youTrackDB.exists(DB_NAME)) {
      youTrackDB.drop(DB_NAME);
    }
    youTrackDB.create(DB_NAME, DatabaseType.DISK, "admin", "admin", "admin");

    var databaseSession = youTrackDB.open(DB_NAME, "admin", "admin");
    storage = databaseSession.getStorage();
    atomicOperationsManager = storage.getAtomicOperationsManager();
    databaseSession.close();
  }

  @AfterClass
  public static void afterClass() {
    youTrackDB.drop(DB_NAME);
    youTrackDB.close();
    FileUtils.deleteRecursively(new File(buildDirectory));
  }

  @Before
  public void beforeMethod() throws Exception {
    // Create a BTree with CompositeKey(userKey, version) layout, matching
    // BTreeSingleValueIndexEngine's key structure.
    bTree = new BTree<>(ENGINE_NAME, ".cbt", ".nbt", storage);
    bTree.setEngineId(STUB_ENGINE_ID);
    atomicOperationsManager.executeInsideAtomicOperation(
        atomicOperation -> bTree.create(
            atomicOperation,
            new IndexMultiValuKeySerializer(),
            new PropertyTypeInternal[] {
                PropertyTypeInternal.STRING,
                PropertyTypeInternal.LONG},
            2));

    // Register a stub engine so AbstractStorage snapshot queries resolve it
    BTreeGCTestSupport.registerStubEngine(storage, ENGINE_NAME, STUB_ENGINE_ID);
  }

  @After
  public void afterMethod() throws Exception {
    // Clear any snapshot entries added during the test to prevent leakage
    // between tests. The IndexesSnapshot is scoped to our stub engine's ID,
    // so clear() only removes entries for this test's index.
    var snapshot = storage.getIndexSnapshotByEngineName(ENGINE_NAME);
    if (snapshot != null) {
      snapshot.clear();
    }

    BTreeGCTestSupport.unregisterStubEngine(storage, ENGINE_NAME);
    atomicOperationsManager.executeInsideAtomicOperation(
        atomicOperation -> bTree.delete(atomicOperation));
  }

  // ---- Helpers ----

  /**
   * Counts entries of a specific RID type in the tree by iterating all
   * entries from firstKey to lastKey.
   */
  private long countEntriesOfType(Class<? extends RID> ridType) throws Exception {
    long[] count = {0};
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var firstKey = bTree.firstKey(atomicOperation);
      if (firstKey == null) {
        return;
      }
      var lastKey = bTree.lastKey(atomicOperation);
      try (var stream = bTree.iterateEntriesBetween(
          firstKey, true, lastKey, true, true, atomicOperation)) {
        count[0] = stream.filter(p -> ridType.isInstance(p.second())).count();
      }
    });
    return count[0];
  }

  /**
   * Counts all entries in the tree (regardless of type).
   */
  private long countAllEntries() throws Exception {
    long[] count = {0};
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var firstKey = bTree.firstKey(atomicOperation);
      if (firstKey == null) {
        return;
      }
      var lastKey = bTree.lastKey(atomicOperation);
      try (var stream = bTree.iterateEntriesBetween(
          firstKey, true, lastKey, true, true, atomicOperation)) {
        count[0] = stream.count();
      }
    });
    return count[0];
  }

  // ---- Basic tombstone GC during put() ----

  @Test
  public void testTombstonesBelowLwmAreRemovedDuringPut() throws Exception {
    // Fill the tree with tombstones at even-numbered keys (version=1), then
    // insert live entries at odd-numbered keys (version=100) to trigger
    // bucket overflows. Tombstones and live entries share buckets because
    // they interleave in key space. GC should remove tombstones below LWM.

    // Insert FILL_COUNT tombstones at even positions
    for (int i = 0; i < FILL_COUNT; i++) {
      final var key = new CompositeKey("key" + String.format("%06d", i * 2), 1L);
      final var value = new TombstoneRID(new RecordId(1, i));
      atomicOperationsManager.executeInsideAtomicOperation(
          atomicOperation -> bTree.put(atomicOperation, key, value));
    }

    long tombstonesBefore = countEntriesOfType(TombstoneRID.class);
    // GC during the insertion phase is aggressive: every bucket overflow
    // removes all tombstones in that bucket. Only partially-filled
    // buckets at the end survive. Require enough for the post-condition
    // ratio check (isLessThan(before / 2)) to be meaningful.
    assertThat(tombstonesBefore)
        .as("Enough tombstones should survive insertion-phase GC "
            + "for the ratio check to be meaningful")
        .isGreaterThan(5);

    // Insert FILL_COUNT live entries at odd positions to trigger overflows
    for (int i = 0; i < FILL_COUNT; i++) {
      final var key = new CompositeKey(
          "key" + String.format("%06d", i * 2 + 1), 100L);
      final var value = new RecordId(2, i);
      atomicOperationsManager.executeInsideAtomicOperation(
          atomicOperation -> bTree.put(atomicOperation, key, value));
    }

    long tombstonesAfter = countEntriesOfType(TombstoneRID.class);

    // We assert "substantially fewer tombstones" rather than "zero tombstones"
    // because GC only runs on buckets that actually overflow. Tombstones in
    // buckets that had room for the new entry are never visited. However,
    // overflow events are distributed across buckets, so at least half should
    // be collected.
    assertThat(tombstonesAfter)
        .as("Tombstones below LWM should be GC'd during bucket overflow")
        .isLessThan(tombstonesBefore / 2);

    // All live entries must be present
    assertThat(countEntriesOfType(RecordId.class))
        .as("All live entries must survive GC")
        .isEqualTo(FILL_COUNT);

    // Spot-check that live entries retain their original RID identity
    for (int i = 0; i < 10; i++) {
      final var key = new CompositeKey(
          "key" + String.format("%06d", i * 2 + 1), 100L);
      final RID[] result = {null};
      atomicOperationsManager.executeInsideAtomicOperation(
          atomicOperation -> result[0] = bTree.get(key, atomicOperation));
      assertThat(result[0])
          .as("Live entry at odd position %d must exist", i * 2 + 1)
          .isNotNull()
          .isInstanceOf(RecordId.class)
          .isNotInstanceOf(TombstoneRID.class);
      assertThat(result[0].getCollectionId())
          .as("Live entry at odd position %d must have collectionId=2",
              i * 2 + 1)
          .isEqualTo(2);
      assertThat(result[0].getCollectionPosition())
          .as("Live entry at odd position %d must have "
              + "collectionPosition=%d", i * 2 + 1, i)
          .isEqualTo(i);
    }
  }

  @Test
  public void testTombstonesAboveLwmArePreserved() throws Exception {
    // Pin LWM so that tombstones with ts=100 are ABOVE it
    var holder = BTreeGCTestSupport.pinLwm(storage, 5L);
    try {
      // Insert tombstones at version=100 (above lwm=5)
      for (int i = 0; i < FILL_COUNT; i++) {
        final var key = new CompositeKey(
            "key" + String.format("%06d", i * 2), 100L);
        final var value = new TombstoneRID(new RecordId(1, i));
        atomicOperationsManager.executeInsideAtomicOperation(
            atomicOperation -> bTree.put(atomicOperation, key, value));
      }

      // Insert live entries to trigger overflows
      for (int i = 0; i < FILL_COUNT; i++) {
        final var key = new CompositeKey(
            "key" + String.format("%06d", i * 2 + 1), 200L);
        final var value = new RecordId(2, i);
        atomicOperationsManager.executeInsideAtomicOperation(
            atomicOperation -> bTree.put(atomicOperation, key, value));
      }

      // All tombstones should be preserved (version 100 > lwm 5)
      assertThat(countEntriesOfType(TombstoneRID.class))
          .as("Tombstones above LWM must not be removed")
          .isEqualTo(FILL_COUNT);
    } finally {
      BTreeGCTestSupport.unpinLwm(storage, holder);
    }
  }

  @Test
  public void testTombstonesAtExactLwmArePreserved() throws Exception {
    // The GC condition is `version < LWM` (strictly below). Tombstones whose
    // version equals exactly the LWM must NOT be removed — they may still be
    // needed by a transaction reading at exactly that timestamp.

    final long lwmValue = 50L;
    var holder = BTreeGCTestSupport.pinLwm(storage, lwmValue);
    try {
      // Insert tombstones at version == LWM (exactly 50)
      for (int i = 0; i < FILL_COUNT; i++) {
        final var key = new CompositeKey(
            "key" + String.format("%06d", i * 2), lwmValue);
        final var value = new TombstoneRID(new RecordId(1, i));
        atomicOperationsManager.executeInsideAtomicOperation(
            atomicOperation -> bTree.put(atomicOperation, key, value));
      }

      long tombstonesBefore = countEntriesOfType(TombstoneRID.class);
      // At version == LWM, even bucket overflows during tombstone insertion
      // should not GC any tombstones (condition is version < lwm, not <=).
      assertThat(tombstonesBefore)
          .as("All tombstones at version == LWM should survive insertion-phase overflows")
          .isEqualTo(FILL_COUNT);

      // Insert live entries at odd positions to trigger overflows
      for (int i = 0; i < FILL_COUNT; i++) {
        final var key = new CompositeKey(
            "key" + String.format("%06d", i * 2 + 1), 200L);
        final var value = new RecordId(2, i);
        atomicOperationsManager.executeInsideAtomicOperation(
            atomicOperation -> bTree.put(atomicOperation, key, value));
      }

      // Tombstones at exactly LWM must survive (condition is version < lwm, not <=)
      assertThat(countEntriesOfType(TombstoneRID.class))
          .as("Tombstones at exactly LWM must not be removed (strict < comparison)")
          .isEqualTo(tombstonesBefore);
    } finally {
      BTreeGCTestSupport.unpinLwm(storage, holder);
    }
  }

  @Test
  public void testNoGhostResurrectionAfterGC() throws Exception {
    // After tombstone GC removes entries, looking up the deleted keys via
    // BTree.get() must return null — the deletion must not be "undone" by
    // the removal of the tombstone marker. This validates the "no ghost
    // resurrection" invariant from the design document.

    // Insert tombstones at even positions (version=1, below default LWM)
    for (int i = 0; i < FILL_COUNT; i++) {
      final var key = new CompositeKey(
          "key" + String.format("%06d", i * 2), 1L);
      final var value = new TombstoneRID(new RecordId(1, i));
      atomicOperationsManager.executeInsideAtomicOperation(
          atomicOperation -> bTree.put(atomicOperation, key, value));
    }

    // Insert live entries at odd positions to trigger overflows and GC
    for (int i = 0; i < FILL_COUNT; i++) {
      final var key = new CompositeKey(
          "key" + String.format("%06d", i * 2 + 1), 100L);
      final var value = new RecordId(2, i);
      atomicOperationsManager.executeInsideAtomicOperation(
          atomicOperation -> bTree.put(atomicOperation, key, value));
    }

    // Verify: looking up tombstoned keys must return null (no ghost resurrection)
    for (int i = 0; i < FILL_COUNT; i++) {
      final var key = new CompositeKey(
          "key" + String.format("%06d", i * 2), 1L);
      final RID[] result = {null};
      atomicOperationsManager.executeInsideAtomicOperation(
          atomicOperation -> result[0] = bTree.get(key, atomicOperation));

      // Whether the tombstone was GC'd or not, get() should return either a
      // TombstoneRID (still present) or null (GC'd). It must NEVER return a
      // plain RecordId — that would be ghost resurrection.
      assertThat(result[0])
          .as("Key at even position %d must not resurrect as live entry", i * 2)
          .satisfiesAnyOf(
              rid -> assertThat(rid).isNull(),
              rid -> assertThat(rid).isInstanceOf(TombstoneRID.class));
    }
  }

  @Test
  public void testLiveEntriesAreNeverRemovedByGC() throws Exception {
    // Insert all live entries (no tombstones) and trigger overflows.
    // GC should not remove any entries.
    for (int i = 0; i < FILL_COUNT * 2; i++) {
      final var key = new CompositeKey(
          "key" + String.format("%06d", i), 50L);
      final var value = new RecordId(1, i);
      atomicOperationsManager.executeInsideAtomicOperation(
          atomicOperation -> bTree.put(atomicOperation, key, value));
    }

    assertThat(countAllEntries())
        .as("Live entries must never be removed by GC")
        .isEqualTo(FILL_COUNT * 2);
  }

  // ---- SnapshotMarkerRID demotion ----

  @Test
  public void testSnapshotMarkerDemotedWhenNoActiveSnapshotEntries() throws Exception {
    // Insert SnapshotMarkerRID entries at version=1 (below default LWM).
    // When bucket overflows, markers should be demoted to plain RecordId.
    for (int i = 0; i < FILL_COUNT; i++) {
      final var key = new CompositeKey(
          "key" + String.format("%06d", i * 2), 1L);
      final var value = new SnapshotMarkerRID(new RecordId(1, i));
      atomicOperationsManager.executeInsideAtomicOperation(
          atomicOperation -> bTree.put(atomicOperation, key, value));
    }

    long markersBefore = countEntriesOfType(SnapshotMarkerRID.class);
    // GC during the insertion phase is aggressive: bucket overflows
    // demote all markers in overflowing buckets. Require enough for
    // the ratio check to be meaningful.
    assertThat(markersBefore)
        .as("Enough markers should survive insertion-phase GC "
            + "for the ratio check to be meaningful")
        .isGreaterThan(5);

    // Insert live entries at odd positions to trigger overflows
    for (int i = 0; i < FILL_COUNT; i++) {
      final var key = new CompositeKey(
          "key" + String.format("%06d", i * 2 + 1), 100L);
      final var value = new RecordId(2, i);
      atomicOperationsManager.executeInsideAtomicOperation(
          atomicOperation -> bTree.put(atomicOperation, key, value));
    }

    long markersAfter = countEntriesOfType(SnapshotMarkerRID.class);

    // Markers should have been demoted to RecordId during GC. Overflow events
    // are distributed across buckets, so at least half should be demoted.
    assertThat(markersAfter)
        .as("SnapshotMarkerRID entries below LWM should be demoted")
        .isLessThan(markersBefore / 2);

    // Total entry count should remain the same (demotions don't remove)
    assertThat(countAllEntries())
        .as("Demotions should not change total entry count")
        .isEqualTo(FILL_COUNT * 2);

    // Spot-check that demoted entries retain their original identity.
    // Track demotedCount to ensure at least one identity assertion fires.
    int demotedCount = 0;
    for (int i = 0; i < 10; i++) {
      final var key = new CompositeKey(
          "key" + String.format("%06d", i * 2), 1L);
      final RID[] result = {null};
      atomicOperationsManager.executeInsideAtomicOperation(
          atomicOperation -> result[0] = bTree.get(key, atomicOperation));
      // If demoted, the entry should be a plain RecordId with the original identity
      if (result[0] instanceof RecordId
          && !(result[0] instanceof SnapshotMarkerRID)) {
        demotedCount++;
        assertThat(result[0].getCollectionId())
            .as("Demoted marker at position %d should retain original collection ID", i)
            .isEqualTo(1);
        assertThat(result[0].getCollectionPosition())
            .as("Demoted marker at position %d should retain original position", i)
            .isEqualTo(i);
      }
    }
    assertThat(demotedCount)
        .as("At least one of the first 10 markers should have been demoted")
        .isGreaterThan(0);
  }

  @Test
  public void testSnapshotMarkerPreservedWhenActiveSnapshotEntriesExist()
      throws Exception {
    // Add snapshot entries BEFORE inserting markers so that GC during marker
    // insertion (caused by bucket overflow) correctly preserves markers that
    // have active snapshot entries.
    var snapshot = storage.getIndexSnapshotByEngineName(ENGINE_NAME);
    assertThat(snapshot).isNotNull();

    for (int i = 0; i < FILL_COUNT; i++) {
      var userKeyPrefix = new CompositeKey(
          "key" + String.format("%06d", i * 2));
      snapshot.addSnapshotPair(
          new CompositeKey(userKeyPrefix, 1L),
          new CompositeKey(userKeyPrefix, 50L),
          new RecordId(1, i));
    }

    // Pin LWM at 5 so markers at version=1 are below LWM (eligible for
    // demotion check), but snapshot entries at version 50 >= LWM prevent it.
    var holder = BTreeGCTestSupport.pinLwm(storage, 5L);
    try {
      // Insert markers at version=1 (below LWM=5)
      for (int i = 0; i < FILL_COUNT; i++) {
        final var key = new CompositeKey(
            "key" + String.format("%06d", i * 2), 1L);
        final var value = new SnapshotMarkerRID(new RecordId(1, i));
        atomicOperationsManager.executeInsideAtomicOperation(
            atomicOperation -> bTree.put(atomicOperation, key, value));
      }

      // Insert live entries to trigger overflows
      for (int i = 0; i < FILL_COUNT; i++) {
        final var key = new CompositeKey(
            "key" + String.format("%06d", i * 2 + 1), 100L);
        final var value = new RecordId(2, i);
        atomicOperationsManager.executeInsideAtomicOperation(
            atomicOperation -> bTree.put(atomicOperation, key, value));
      }

      // Markers should be preserved (snapshot entries with version >= LWM exist)
      assertThat(countEntriesOfType(SnapshotMarkerRID.class))
          .as("Markers with active snapshot entries must be preserved")
          .isEqualTo(FILL_COUNT);
    } finally {
      BTreeGCTestSupport.unpinLwm(storage, holder);
    }
  }

  // ---- Tree size consistency ----

  @Test
  public void testTreeSizeConsistentAfterGC() throws Exception {
    // Fill with tombstones, then live entries. After GC, reported tree size
    // must match the actual count of entries.

    for (int i = 0; i < FILL_COUNT; i++) {
      final var key = new CompositeKey(
          "key" + String.format("%06d", i * 2), 1L);
      final var value = new TombstoneRID(new RecordId(1, i));
      atomicOperationsManager.executeInsideAtomicOperation(
          atomicOperation -> bTree.put(atomicOperation, key, value));
    }

    for (int i = 0; i < FILL_COUNT; i++) {
      final var key = new CompositeKey(
          "key" + String.format("%06d", i * 2 + 1), 100L);
      final var value = new RecordId(2, i);
      atomicOperationsManager.executeInsideAtomicOperation(
          atomicOperation -> bTree.put(atomicOperation, key, value));
    }

    long[] reportedSize = {0};
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      reportedSize[0] = bTree.size(atomicOperation);
    });

    long actualCount = countAllEntries();

    // Verify GC actually removed some tombstones (actualCount < total inserted)
    assertThat(actualCount)
        .as("GC should have removed some tombstones, reducing count below 2*FILL_COUNT")
        .isLessThan(FILL_COUNT * 2L);

    assertThat(reportedSize[0])
        .as("Reported tree size must match actual entry count after GC")
        .isEqualTo(actualCount);
  }

  // ---- Splits proceed when GC finds no candidates ----

  @Test
  public void testSplitsProceedNormallyWhenNoTombstonesExist() throws Exception {
    // Fill the tree so buckets are nearly full, then insert entries that
    // trigger overflow. Even if GC doesn't free enough space (e.g., no
    // tombstones to remove), the split should still proceed without error.

    // Fill with only live entries (no tombstones to GC)
    for (int i = 0; i < FILL_COUNT * 2; i++) {
      final var key = new CompositeKey(
          "key" + String.format("%06d", i), 50L);
      final var value = new RecordId(1, i);
      atomicOperationsManager.executeInsideAtomicOperation(
          atomicOperation -> bTree.put(atomicOperation, key, value));
    }

    // Insert more entries to trigger further splits — GC finds nothing
    // to remove but should not loop or error
    for (int i = FILL_COUNT * 2; i < FILL_COUNT * 3; i++) {
      final var key = new CompositeKey(
          "key" + String.format("%06d", i), 50L);
      final var value = new RecordId(1, i);
      atomicOperationsManager.executeInsideAtomicOperation(
          atomicOperation -> bTree.put(atomicOperation, key, value));
    }

    assertThat(countAllEntries())
        .as("All entries must be present after splits with no GC candidates")
        .isEqualTo(FILL_COUNT * 3);
  }

  // ---- AbstractStorage helper edge cases ----

  @Test
  public void testGetIndexSnapshotByEngineNameReturnsNullForUnknownEngine() {
    // Verifies the defensive null-return path in getIndexSnapshotByEngineName
    // when the engine name is not registered in indexEngineNameMap.
    assertThat(storage.getIndexSnapshotByEngineName(ENGINE_NAME))
        .as("Registered engine name must return a non-null snapshot")
        .isNotNull();
    assertThat(storage.getIndexSnapshotByEngineName("nonExistentEngine"))
        .as("Unknown engine name must return null snapshot")
        .isNull();
  }

  @Test
  public void testGetNullIndexSnapshotByEngineNameReturnsNullForUnknownEngine() {
    // Verifies both positive and negative paths in getNullIndexSnapshotByEngineName.
    assertThat(storage.getNullIndexSnapshotByEngineName(ENGINE_NAME))
        .as("Registered engine name must return a non-null null-index snapshot")
        .isNotNull();
    assertThat(storage.getNullIndexSnapshotByEngineName("nonExistentEngine"))
        .as("Unknown engine name must return null for null-index snapshot")
        .isNull();
  }

  @Test
  public void testHasActiveSnapshotEntriesReturnsFalseForUnknownEngine() {
    // Verifies the defensive false-return path in hasActiveIndexSnapshotEntries
    // when the resolved engine name is not found in indexEngineNameMap.
    var result = storage.hasActiveIndexSnapshotEntries(
        "nonExistentEngine", new CompositeKey("key"), 1L);
    assertThat(result)
        .as("Unknown engine name must return false for active snapshot check")
        .isFalse();
  }

  @Test
  public void testHasActiveSnapshotEntriesReturnsFalseForUnknownNullTreeEngine() {
    // The "$null" suffix triggers a different code path that strips the suffix
    // and queries sharedNullIndexesSnapshot. An unregistered base name must
    // still return false.
    var result = storage.hasActiveIndexSnapshotEntries(
        "nonExistentEngine$null", new CompositeKey("key"), 1L);
    assertThat(result)
        .as("Unknown null-tree engine name must return false")
        .isFalse();
  }

  // ---- Mixed entry types ----

  @Test
  public void testMixedTombstonesMarkersAndLiveEntriesInSameBucket() throws Exception {
    // Insert interleaved tombstones (version=1), markers (version=2), and live
    // entries (version=3) — all below default LWM. Then trigger overflow.
    // Tombstones should be removed, markers demoted, live entries preserved.
    // This exercises the case where both removedCount > 0 and demoted == true
    // in filterAndRebuildBucket, plus the partition invariant across all three
    // entry types.
    int count = FILL_COUNT / 3;
    for (int i = 0; i < count; i++) {
      int base = i * 6;
      // Tombstone
      final var tKey = new CompositeKey(
          "key" + String.format("%06d", base), 1L);
      final var tVal = new TombstoneRID(new RecordId(1, i));
      atomicOperationsManager.executeInsideAtomicOperation(
          op -> bTree.put(op, tKey, tVal));
      // SnapshotMarker
      final var mKey = new CompositeKey(
          "key" + String.format("%06d", base + 2), 2L);
      final var mVal = new SnapshotMarkerRID(new RecordId(1, i + count));
      atomicOperationsManager.executeInsideAtomicOperation(
          op -> bTree.put(op, mKey, mVal));
      // Live entry
      final var lKey = new CompositeKey(
          "key" + String.format("%06d", base + 4), 3L);
      final var lVal = new RecordId(1, i + 2 * count);
      atomicOperationsManager.executeInsideAtomicOperation(
          op -> bTree.put(op, lKey, lVal));
    }

    long tombstonesBefore = countEntriesOfType(TombstoneRID.class);
    long markersBefore = countEntriesOfType(SnapshotMarkerRID.class);

    // Trigger overflow with more live entries at higher key values
    for (int i = 0; i < FILL_COUNT; i++) {
      final var key = new CompositeKey(
          "key" + String.format("%06d", count * 6 + i), 100L);
      final var val = new RecordId(2, i);
      atomicOperationsManager.executeInsideAtomicOperation(
          op -> bTree.put(op, key, val));
    }

    assertThat(countEntriesOfType(TombstoneRID.class))
        .as("Tombstones should be GC'd from mixed bucket")
        .isLessThan(tombstonesBefore / 2);
    assertThat(countEntriesOfType(SnapshotMarkerRID.class))
        .as("Markers should be demoted from mixed bucket")
        .isLessThan(markersBefore / 2);

    // Verify tree size consistency after mixed GC
    long[] reportedSize = {0};
    atomicOperationsManager.executeInsideAtomicOperation(
        op -> reportedSize[0] = bTree.size(op));
    assertThat(reportedSize[0])
        .as("Reported tree size must match actual count after mixed GC")
        .isEqualTo(countAllEntries());

    // Spot-check that original live entries survive with correct identity
    for (int i = 0; i < Math.min(5, count); i++) {
      int base = i * 6;
      final var lKey = new CompositeKey(
          "key" + String.format("%06d", base + 4), 3L);
      final RID[] result = {null};
      atomicOperationsManager.executeInsideAtomicOperation(
          op -> result[0] = bTree.get(lKey, op));
      assertThat(result[0])
          .as("Live entry at base+4 position %d must survive", base + 4)
          .isNotNull()
          .isInstanceOf(RecordId.class)
          .isNotInstanceOf(TombstoneRID.class);
      assertThat(result[0].getCollectionId())
          .as("Live entry at base+4 must retain collectionId=1")
          .isEqualTo(1);
      assertThat(result[0].getCollectionPosition())
          .as("Live entry at base+4 must retain position=%d",
              i + 2 * count)
          .isEqualTo(i + 2 * count);
    }
  }

  // ---- Sort order preservation ----

  @Test
  public void testEntriesRemainCorrectlyOrderedAfterGC() throws Exception {
    // After GC removes tombstones and rebuilds the bucket, the insertion index
    // is recalculated via keyBucket.find(). If the recalculated index is wrong,
    // entries are inserted at incorrect positions, corrupting B-tree sort order.
    // This test verifies that all entries remain in strictly ascending key order
    // after GC + insert.

    for (int i = 0; i < FILL_COUNT; i++) {
      final var key = new CompositeKey(
          "key" + String.format("%06d", i * 2), 1L);
      final var value = new TombstoneRID(new RecordId(1, i));
      atomicOperationsManager.executeInsideAtomicOperation(
          op -> bTree.put(op, key, value));
    }
    for (int i = 0; i < FILL_COUNT; i++) {
      final var key = new CompositeKey(
          "key" + String.format("%06d", i * 2 + 1), 100L);
      final var value = new RecordId(2, i);
      atomicOperationsManager.executeInsideAtomicOperation(
          op -> bTree.put(op, key, value));
    }

    // Verify ascending key order across the entire tree
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var firstKey = bTree.firstKey(atomicOperation);
      var lastKey = bTree.lastKey(atomicOperation);
      try (var stream = bTree.iterateEntriesBetween(
          firstKey, true, lastKey, true, true, atomicOperation)) {
        var keys = stream.map(p -> (Comparable<?>) p.first()).toList();
        for (int i = 1; i < keys.size(); i++) {
          @SuppressWarnings("unchecked")
          var prev = (Comparable<Object>) keys.get(i - 1);
          assertThat(prev.compareTo(keys.get(i)))
              .as("Keys must be in ascending order at position %d", i)
              .isLessThan(0);
        }
      }
    });
  }

  // ---- Null-tree GC path ----

  @Test
  public void testNullTreeGCQueriesNullSnapshotMap() throws Exception {
    // BTreeMultiValueIndexEngine creates a nullTree with name ending in
    // "$null". GC must query sharedNullIndexesSnapshot (not the regular
    // sharedIndexesSnapshot) for demotion decisions. This test verifies
    // that markers on a null tree are preserved when the null snapshot
    // map has active entries — proving isNullTree routes correctly.
    var nullEngineName =
        ENGINE_NAME + AbstractStorage.NULL_TREE_SUFFIX;
    var nullTree =
        new BTree<CompositeKey>(nullEngineName, ".cbt", ".nbt", storage);
    nullTree.setEngineId(STUB_ENGINE_ID);
    atomicOperationsManager.executeInsideAtomicOperation(
        op -> nullTree.create(
            op,
            new IndexMultiValuKeySerializer(),
            new PropertyTypeInternal[] {
                PropertyTypeInternal.STRING,
                PropertyTypeInternal.LONG},
            2));
    try {
      // Add snapshot entries to the NULL snapshot map. The stub engine
      // is registered under ENGINE_NAME (without $null suffix).
      var nullSnapshot =
          storage.getNullIndexSnapshotByEngineName(ENGINE_NAME);
      assertThat(nullSnapshot)
          .as("Null snapshot for registered engine must exist")
          .isNotNull();

      var holder = BTreeGCTestSupport.pinLwm(storage, 5L);
      try {
        for (int i = 0; i < FILL_COUNT; i++) {
          var userKeyPrefix = new CompositeKey(
              "key" + String.format("%06d", i * 2));
          nullSnapshot.addSnapshotPair(
              new CompositeKey(userKeyPrefix, 1L),
              new CompositeKey(userKeyPrefix, 50L),
              new RecordId(1, i));
        }

        // Insert markers at version=1 (below LWM=5)
        for (int i = 0; i < FILL_COUNT; i++) {
          final var key = new CompositeKey(
              "key" + String.format("%06d", i * 2), 1L);
          final var value = new SnapshotMarkerRID(new RecordId(1, i));
          atomicOperationsManager.executeInsideAtomicOperation(
              op -> nullTree.put(op, key, value));
        }

        // Insert live entries to trigger overflow + GC
        for (int i = 0; i < FILL_COUNT; i++) {
          final var key = new CompositeKey(
              "key" + String.format("%06d", i * 2 + 1), 100L);
          final var value = new RecordId(2, i);
          atomicOperationsManager.executeInsideAtomicOperation(
              op -> nullTree.put(op, key, value));
        }

        // Markers should be preserved: GC queries the null snapshot map
        // where active entries exist (version 50 >= LWM 5).
        long[] markerCount = {0};
        atomicOperationsManager.executeInsideAtomicOperation(op -> {
          var firstKey = nullTree.firstKey(op);
          var lastKey = nullTree.lastKey(op);
          try (var stream = nullTree.iterateEntriesBetween(
              firstKey, true, lastKey, true, true, op)) {
            markerCount[0] = stream
                .filter(p -> p.second() instanceof SnapshotMarkerRID)
                .count();
          }
        });
        assertThat(markerCount[0])
            .as("Markers on null tree must be preserved when null "
                + "snapshot map has active entries")
            .isEqualTo(FILL_COUNT);
      } finally {
        nullSnapshot.clear();
        BTreeGCTestSupport.unpinLwm(storage, holder);
      }
    } finally {
      atomicOperationsManager.executeInsideAtomicOperation(
          op -> nullTree.delete(op));
    }
  }

  // ---- engineId fallback ----

  @Test
  public void testMarkersNotDemotedWhenEngineIdNotSet() throws Exception {
    // When engineId is not set (-1), hasActiveSnapshotEntries
    // conservatively returns true, preventing marker demotion.
    // Tombstones should still be removed (they bypass snapshot checks).
    var unregisteredTree = new BTree<CompositeKey>(
        "unregisteredTree", ".cbt", ".nbt", storage);
    // Deliberately do NOT call setEngineId — default is -1
    atomicOperationsManager.executeInsideAtomicOperation(
        op -> unregisteredTree.create(
            op,
            new IndexMultiValuKeySerializer(),
            new PropertyTypeInternal[] {
                PropertyTypeInternal.STRING,
                PropertyTypeInternal.LONG},
            2));
    try {
      // Insert interleaved markers and tombstones (same key prefix so
      // they co-locate in the same B-tree buckets). Markers at positions
      // i*3, tombstones at positions i*3+1. Version=1 (below default LWM).
      for (int i = 0; i < FILL_COUNT; i++) {
        final var mKey = new CompositeKey(
            "key" + String.format("%06d", i * 3), 1L);
        final var mVal = new SnapshotMarkerRID(new RecordId(1, i));
        atomicOperationsManager.executeInsideAtomicOperation(
            op -> unregisteredTree.put(op, mKey, mVal));

        final var tKey = new CompositeKey(
            "key" + String.format("%06d", i * 3 + 1), 1L);
        final var tVal = new TombstoneRID(new RecordId(3, i));
        atomicOperationsManager.executeInsideAtomicOperation(
            op -> unregisteredTree.put(op, tKey, tVal));
      }

      long markersBefore = countEntriesOfType(
          unregisteredTree, SnapshotMarkerRID.class);
      assertThat(markersBefore)
          .as("Enough markers should survive insertion-phase GC")
          .isGreaterThan(5);

      long tombstonesBefore = countEntriesOfType(
          unregisteredTree, TombstoneRID.class);
      assertThat(tombstonesBefore)
          .as("Enough tombstones should survive insertion-phase GC")
          .isGreaterThan(5);

      // Insert live entries at interleaved positions (i*3+2) to trigger
      // overflow + GC. These share buckets with markers and tombstones.
      for (int i = 0; i < FILL_COUNT; i++) {
        final var key = new CompositeKey(
            "key" + String.format("%06d", i * 3 + 2), 100L);
        final var value = new RecordId(2, i);
        atomicOperationsManager.executeInsideAtomicOperation(
            op -> unregisteredTree.put(op, key, value));
      }

      // With engineId == -1, ALL markers must be preserved
      // (conservative fallback prevents demotion)
      assertThat(countEntriesOfType(
          unregisteredTree, SnapshotMarkerRID.class))
          .as("All markers must be preserved when engineId is not set")
          .isEqualTo(markersBefore);

      // Tombstones must still be GC'd even without engineId —
      // tombstone removal bypasses the snapshot check entirely
      assertThat(countEntriesOfType(
          unregisteredTree, TombstoneRID.class))
          .as("Tombstones must be removed regardless of engineId")
          .isLessThan(tombstonesBefore / 2);
    } finally {
      atomicOperationsManager.executeInsideAtomicOperation(
          op -> unregisteredTree.delete(op));
    }
  }

  // ---- SnapshotMarkerRID boundary ----

  @Test
  public void testSnapshotMarkersAtExactLwmArePreserved() throws Exception {
    // The GC condition is `version < LWM` (strictly below). Markers whose
    // version equals exactly the LWM must NOT be demoted — they may still
    // be needed by a transaction reading at exactly that timestamp.
    final long lwmValue = 50L;
    var holder = BTreeGCTestSupport.pinLwm(storage, lwmValue);
    try {
      // Insert markers at version == LWM (exactly 50)
      for (int i = 0; i < FILL_COUNT; i++) {
        final var key = new CompositeKey(
            "key" + String.format("%06d", i * 2), lwmValue);
        final var value = new SnapshotMarkerRID(new RecordId(1, i));
        atomicOperationsManager.executeInsideAtomicOperation(
            op -> bTree.put(op, key, value));
      }

      // Insert live entries at odd positions to trigger overflows
      for (int i = 0; i < FILL_COUNT; i++) {
        final var key = new CompositeKey(
            "key" + String.format("%06d", i * 2 + 1), 200L);
        final var value = new RecordId(2, i);
        atomicOperationsManager.executeInsideAtomicOperation(
            op -> bTree.put(op, key, value));
      }

      // Markers at exactly LWM must NOT be demoted
      assertThat(countEntriesOfType(SnapshotMarkerRID.class))
          .as("Markers at exactly LWM must not be demoted "
              + "(strict < comparison)")
          .isEqualTo(FILL_COUNT);
    } finally {
      BTreeGCTestSupport.unpinLwm(storage, holder);
    }
  }

  // ---- Snapshot entry scoping ----

  @Test
  public void testSnapshotMarkerDemotedOnlyForKeysWithoutSnapshotEntries()
      throws Exception {
    // Snapshot entries exist for keys at indices 50+ but NOT for indices
    // 0-49. Markers at indices 0-49 should be demoted (no matching
    // snapshot entries); markers at indices 50+ should be preserved.
    // This verifies that hasActiveSnapshotEntriesInMap correctly scopes
    // the subMap query to the specific user-key prefix.
    var snapshot = storage.getIndexSnapshotByEngineName(ENGINE_NAME);
    assertThat(snapshot).isNotNull();

    var holder = BTreeGCTestSupport.pinLwm(storage, 5L);
    try {
      // Only add snapshot entries for keys at indices 50+
      for (int i = 50; i < FILL_COUNT; i++) {
        var userKeyPrefix = new CompositeKey(
            "key" + String.format("%06d", i * 2));
        snapshot.addSnapshotPair(
            new CompositeKey(userKeyPrefix, 1L),
            new CompositeKey(userKeyPrefix, 50L),
            new RecordId(1, i));
      }

      // Insert markers for ALL keys (version=1, below LWM=5)
      for (int i = 0; i < FILL_COUNT; i++) {
        final var key = new CompositeKey(
            "key" + String.format("%06d", i * 2), 1L);
        final var value = new SnapshotMarkerRID(new RecordId(1, i));
        atomicOperationsManager.executeInsideAtomicOperation(
            op -> bTree.put(op, key, value));
      }

      // Insert live entries to trigger overflow + GC
      for (int i = 0; i < FILL_COUNT; i++) {
        final var key = new CompositeKey(
            "key" + String.format("%06d", i * 2 + 1), 100L);
        final var value = new RecordId(2, i);
        atomicOperationsManager.executeInsideAtomicOperation(
            op -> bTree.put(op, key, value));
      }

      // Markers at indices 50+ should be preserved (snapshot entries
      // exist). Markers at indices 0-49 should be demoted (no snapshot
      // entries) — at least in buckets that overflowed. Overall marker
      // count should be less than FILL_COUNT (some demoted) but at
      // least FILL_COUNT - 50 (those with snapshot entries survive).
      long survivingMarkers =
          countEntriesOfType(SnapshotMarkerRID.class);
      assertThat(survivingMarkers)
          .as("Some markers without snapshot entries should be demoted")
          .isLessThan((long) FILL_COUNT);
      assertThat(survivingMarkers)
          .as("Markers with snapshot entries must be preserved")
          .isGreaterThanOrEqualTo((long) FILL_COUNT - 50);
    } finally {
      BTreeGCTestSupport.unpinLwm(storage, holder);
    }
  }

  // ---- All-tombstone bucket GC ----

  @Test
  public void testAllTombstoneBucketIsFullyClearedByGC() throws Exception {
    // When a leaf bucket contains ONLY tombstones below LWM,
    // filterAndRebuildBucket removes all entries, calls clear(), then
    // addAll(survivors) with an empty list. The subsequent addLeafEntry
    // must succeed on the empty bucket. This exercises the extreme case
    // where the survivor list is empty after GC.
    for (int i = 0; i < FILL_COUNT; i++) {
      final var key = new CompositeKey(
          "key" + String.format("%06d", i), 1L);
      final var value = new TombstoneRID(new RecordId(1, i));
      atomicOperationsManager.executeInsideAtomicOperation(
          op -> bTree.put(op, key, value));
    }

    // Insert a single live entry into the middle of the key range
    // to trigger GC on a bucket full of tombstones
    final var liveKey = new CompositeKey("key000100", 100L);
    final var liveVal = new RecordId(2, 100);
    atomicOperationsManager.executeInsideAtomicOperation(
        op -> bTree.put(op, liveKey, liveVal));

    // Verify the live entry exists
    final RID[] result = {null};
    atomicOperationsManager.executeInsideAtomicOperation(
        op -> result[0] = bTree.get(liveKey, op));
    assertThat(result[0])
        .as("Live entry must be findable after all-tombstone GC")
        .isNotNull()
        .isInstanceOf(RecordId.class)
        .isNotInstanceOf(TombstoneRID.class);

    // Tree size must be consistent
    long[] reportedSize = {0};
    atomicOperationsManager.executeInsideAtomicOperation(
        op -> reportedSize[0] = bTree.size(op));
    assertThat(reportedSize[0])
        .as("Reported tree size must match actual count after "
            + "all-tombstone GC")
        .isEqualTo(countAllEntries());
  }

  // ---- Snapshot entry boundary tests ----

  @Test
  public void testHasActiveSnapshotEntriesReturnsTrueForEntryAtExactLwm() {
    // Register a snapshot entry at version exactly == LWM. The subMap
    // query uses inclusive bounds, so an entry at exactly LWM must be
    // detected.
    var snapshot = storage.getIndexSnapshotByEngineName(ENGINE_NAME);
    assertThat(snapshot).isNotNull();
    final long lwm = 50L;
    var userKeyPrefix = new CompositeKey("testKey");
    snapshot.addSnapshotPair(
        new CompositeKey(userKeyPrefix, 1L),
        new CompositeKey(userKeyPrefix, lwm),
        new RecordId(1, 1));

    var result = storage.hasActiveIndexSnapshotEntries(
        ENGINE_NAME, userKeyPrefix, lwm);
    assertThat(result)
        .as("Snapshot entry at exactly LWM should be detected (inclusive)")
        .isTrue();
  }

  @Test
  public void testHasActiveSnapshotEntriesReturnsFalseWhenAllBelowLwm() {
    // Register a snapshot entry at version below LWM. The subMap query
    // range starts at LWM (inclusive), so an entry at version < LWM
    // must not be detected.
    var snapshot = storage.getIndexSnapshotByEngineName(ENGINE_NAME);
    assertThat(snapshot).isNotNull();
    final long lwm = 50L;
    var userKeyPrefix = new CompositeKey("testKey");
    snapshot.addSnapshotPair(
        new CompositeKey(userKeyPrefix, 1L),
        new CompositeKey(userKeyPrefix, 49L),
        new RecordId(1, 1));

    var result = storage.hasActiveIndexSnapshotEntries(
        ENGINE_NAME, userKeyPrefix, lwm);
    assertThat(result)
        .as("Snapshot entry below LWM should not be detected")
        .isFalse();
  }

  // ---- Helpers (overloads for arbitrary trees) ----

  /**
   * Counts entries of a specific RID type in the given tree.
   */
  private long countEntriesOfType(
      BTree<CompositeKey> tree,
      Class<? extends RID> ridType) throws Exception {
    long[] count = {0};
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var firstKey = tree.firstKey(atomicOperation);
      if (firstKey == null) {
        return;
      }
      var lastKey = tree.lastKey(atomicOperation);
      try (var stream = tree.iterateEntriesBetween(
          firstKey, true, lastKey, true, true, atomicOperation)) {
        count[0] = stream.filter(p -> ridType.isInstance(p.second()))
            .count();
      }
    });
    return count[0];
  }

}
