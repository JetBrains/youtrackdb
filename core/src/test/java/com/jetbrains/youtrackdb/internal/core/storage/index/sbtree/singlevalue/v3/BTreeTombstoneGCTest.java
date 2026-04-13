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
import com.jetbrains.youtrackdb.internal.core.index.engine.BaseIndexEngine;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.impl.index.IndexMultiValuKeySerializer;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import java.io.File;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
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
    atomicOperationsManager.executeInsideAtomicOperation(
        atomicOperation -> bTree.create(
            atomicOperation,
            new IndexMultiValuKeySerializer(),
            new PropertyTypeInternal[] {
                PropertyTypeInternal.STRING,
                PropertyTypeInternal.LONG},
            2));

    // Register a stub engine so AbstractStorage.hasActiveSnapshotEntries() resolves it
    registerStubEngine(ENGINE_NAME, STUB_ENGINE_ID);
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

    unregisterStubEngine(ENGINE_NAME);
    atomicOperationsManager.executeInsideAtomicOperation(
        atomicOperation -> bTree.delete(atomicOperation));
  }

  // ---- LWM pinning helpers (via reflection) ----

  @SuppressWarnings("unchecked")
  private static Object pinLwm(long lwmValue) throws Exception {
    Class<?> holderClass = Class.forName(
        "com.jetbrains.youtrackdb.internal.core.storage.impl.local.TsMinHolder");
    var ctor = holderClass.getDeclaredConstructor();
    ctor.setAccessible(true);
    Object holder = ctor.newInstance();
    Field tsMinField = holderClass.getDeclaredField("tsMin");
    tsMinField.setAccessible(true);
    tsMinField.setLong(holder, lwmValue);

    Field tsMinsField = AbstractStorage.class.getDeclaredField("tsMins");
    tsMinsField.setAccessible(true);
    Set<Object> tsMins = (Set<Object>) tsMinsField.get(storage);
    tsMins.add(holder);
    return holder;
  }

  @SuppressWarnings("unchecked")
  private static void unpinLwm(Object holder) throws Exception {
    Field tsMinsField = AbstractStorage.class.getDeclaredField("tsMins");
    tsMinsField.setAccessible(true);
    Set<Object> tsMins = (Set<Object>) tsMinsField.get(storage);
    tsMins.remove(holder);
  }

  // ---- Stub engine registration (via reflection) ----

  /**
   * Registers a minimal stub {@link BaseIndexEngine} in AbstractStorage's
   * {@code indexEngineNameMap} so that
   * {@code AbstractStorage.hasActiveSnapshotEntries()} can resolve the index.
   * Acquires stateLock.writeLock() because indexEngineNameMap is a plain HashMap
   * that requires external synchronization.
   */
  @SuppressWarnings("unchecked")
  private static void registerStubEngine(String name, int id) throws Exception {
    BaseIndexEngine stub = new StubIndexEngine(name, id);
    var lock = getStateLock();
    lock.writeLock().lock();
    try {
      Field mapField = AbstractStorage.class.getDeclaredField("indexEngineNameMap");
      mapField.setAccessible(true);
      Map<String, BaseIndexEngine> map =
          (Map<String, BaseIndexEngine>) mapField.get(storage);
      map.put(name, stub);
    } finally {
      lock.writeLock().unlock();
    }
  }

  @SuppressWarnings("unchecked")
  private static void unregisterStubEngine(String name) throws Exception {
    var lock = getStateLock();
    lock.writeLock().lock();
    try {
      Field mapField = AbstractStorage.class.getDeclaredField("indexEngineNameMap");
      mapField.setAccessible(true);
      Map<String, BaseIndexEngine> map =
          (Map<String, BaseIndexEngine>) mapField.get(storage);
      map.remove(name);
    } finally {
      lock.writeLock().unlock();
    }
  }

  private static ReadWriteLock getStateLock() throws Exception {
    Field lockField = AbstractStorage.class.getDeclaredField("stateLock");
    lockField.setAccessible(true);
    return (ReadWriteLock) lockField.get(storage);
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
    // Some tombstones may already be GC'd during their own insertion phase
    // (bucket overflows during tombstone insertion trigger GC on earlier
    // tombstones). Verify at least some survive.
    assertThat(tombstonesBefore)
        .as("At least some tombstones should exist before live entry inserts")
        .isGreaterThan(0);

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
  }

  @Test
  public void testTombstonesAboveLwmArePreserved() throws Exception {
    // Pin LWM so that tombstones with ts=100 are ABOVE it
    var holder = pinLwm(5L);
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
      unpinLwm(holder);
    }
  }

  @Test
  public void testTombstonesAtExactLwmArePreserved() throws Exception {
    // The GC condition is `version < LWM` (strictly below). Tombstones whose
    // version equals exactly the LWM must NOT be removed — they may still be
    // needed by a transaction reading at exactly that timestamp.

    final long lwmValue = 50L;
    var holder = pinLwm(lwmValue);
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
      unpinLwm(holder);
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
    assertThat(markersBefore)
        .as("Markers should exist before live entry inserts")
        .isGreaterThan(0);

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
    var holder = pinLwm(5L);
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
      unpinLwm(holder);
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
    // Verifies the defensive null-return path in getNullIndexSnapshotByEngineName
    // when the engine name is not registered in indexEngineNameMap.
    var snapshot = storage.getNullIndexSnapshotByEngineName("nonExistentEngine");
    assertThat(snapshot)
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

  // ---- Stub engine for indexEngineNameMap registration ----

  /**
   * Minimal {@link BaseIndexEngine} stub that provides only {@code getId()}
   * and {@code getName()} — the only methods used by
   * {@code AbstractStorage.hasActiveSnapshotEntries()}.
   */
  private static class StubIndexEngine implements BaseIndexEngine {
    private final String name;
    private final int id;

    StubIndexEngine(String name, int id) {
      this.name = name;
      this.id = id;
    }

    @Override
    public int getId() {
      return id;
    }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public void init(
        com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded s,
        com.jetbrains.youtrackdb.internal.core.index.IndexMetadata m) {
    }

    @Override
    public void flush() {
    }

    @Override
    public void create(AtomicOperation o,
        com.jetbrains.youtrackdb.internal.core.config.IndexEngineData d) {
    }

    @Override
    public void load(
        com.jetbrains.youtrackdb.internal.core.config.IndexEngineData d,
        AtomicOperation o) {
    }

    @Override
    public void delete(AtomicOperation o) {
    }

    @Override
    public void clear(
        com.jetbrains.youtrackdb.internal.core.storage.Storage s,
        AtomicOperation o) {
    }

    @Override
    public void close() {
    }

    @Override
    public java.util.stream.Stream<
        com.jetbrains.youtrackdb.internal.common.util.RawPair<Object, RID>>
        iterateEntriesBetween(Object f, boolean fi, Object t, boolean ti,
            boolean a,
            com.jetbrains.youtrackdb.internal.core.index.engine.IndexEngineValuesTransformer tr,
            AtomicOperation o) {
      return java.util.stream.Stream.empty();
    }

    @Override
    public java.util.stream.Stream<
        com.jetbrains.youtrackdb.internal.common.util.RawPair<Object, RID>>
        iterateEntriesMajor(Object k, boolean i, boolean a,
            com.jetbrains.youtrackdb.internal.core.index.engine.IndexEngineValuesTransformer t,
            AtomicOperation o) {
      return java.util.stream.Stream.empty();
    }

    @Override
    public java.util.stream.Stream<
        com.jetbrains.youtrackdb.internal.common.util.RawPair<Object, RID>>
        iterateEntriesMinor(Object k, boolean i, boolean a,
            com.jetbrains.youtrackdb.internal.core.index.engine.IndexEngineValuesTransformer t,
            AtomicOperation o) {
      return java.util.stream.Stream.empty();
    }

    @Override
    public java.util.stream.Stream<
        com.jetbrains.youtrackdb.internal.common.util.RawPair<Object, RID>>
        stream(com.jetbrains.youtrackdb.internal.core.index.engine.IndexEngineValuesTransformer t,
            AtomicOperation o) {
      return java.util.stream.Stream.empty();
    }

    @Override
    public java.util.stream.Stream<
        com.jetbrains.youtrackdb.internal.common.util.RawPair<Object, RID>>
        descStream(
            com.jetbrains.youtrackdb.internal.core.index.engine.IndexEngineValuesTransformer t,
            AtomicOperation o) {
      return java.util.stream.Stream.empty();
    }

    @Override
    public java.util.stream.Stream<Object> keyStream(AtomicOperation o) {
      return java.util.stream.Stream.empty();
    }

    @Override
    public long size(com.jetbrains.youtrackdb.internal.core.storage.Storage s,
        com.jetbrains.youtrackdb.internal.core.index.engine.IndexEngineValuesTransformer t,
        AtomicOperation o) {
      return 0;
    }

    @Override
    public int getEngineAPIVersion() {
      return 0;
    }

    @Override
    public boolean acquireAtomicExclusiveLock(AtomicOperation o) {
      return false;
    }
  }
}
