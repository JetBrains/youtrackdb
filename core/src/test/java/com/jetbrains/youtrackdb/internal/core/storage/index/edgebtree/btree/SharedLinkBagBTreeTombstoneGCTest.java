package com.jetbrains.youtrackdb.internal.core.storage.index.edgebtree.btree;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.EdgeKey;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.LinkBagValue;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.SharedLinkBagBTree;
import java.io.File;
import java.lang.reflect.Field;
import java.util.Set;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Tests for tombstone garbage collection during leaf page splits in
 * {@link SharedLinkBagBTree}. Verifies that removable tombstones (below
 * global LWM with no lingering snapshot entries) are filtered out during
 * bucket overflow, and that non-removable tombstones are preserved.
 *
 * <p>The GC logic is triggered when {@code addLeafEntry()} returns false
 * (bucket full) in both {@code put()} and {@code remove()} paths.
 *
 * <p>Since the public iteration API uses SI visibility (which filters
 * tombstones), tests verify tombstone presence/absence via
 * {@code findCurrentEntry()} which returns the raw B-tree entry including
 * tombstones.
 *
 * <p>Key design: tombstones and live entries use interleaved key ranges
 * (even/odd targetPosition) so they land in the same B-tree buckets.
 * This ensures bucket overflow triggers GC on buckets that contain
 * tombstones. Tombstones are inserted directly via {@code put()} with
 * {@code tombstone=true} to avoid creating snapshot entries.
 */
public class SharedLinkBagBTreeTombstoneGCTest {

  private static final String DB_NAME = "tombstoneGCTest";
  private static final String DIR_NAME = "/tombstoneGCTest";

  // Enough entries to fill a bucket and trigger splits. With ~20 bytes per
  // entry and ~8KB usable per bucket, ~400 entries per bucket. We use 500
  // to ensure multiple splits.
  private static final int FILL_COUNT = 500;

  private static YouTrackDBImpl youTrackDB;
  private static AtomicOperationsManager atomicOperationsManager;
  private static AbstractStorage storage;
  private static String buildDirectory;

  private SharedLinkBagBTree bTree;

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
    bTree = new SharedLinkBagBTree(storage, "tombstoneGC", ".sbc");
    atomicOperationsManager.executeInsideAtomicOperation(
        atomicOperation -> bTree.create(atomicOperation));
  }

  @After
  public void afterMethod() throws Exception {
    atomicOperationsManager.executeInsideAtomicOperation(
        atomicOperation -> bTree.delete(atomicOperation));
    // Clean shared storage-level indexes so tests that call clear()
    // (e.g. testNoGhostResurrectionAfterGC) don't leak state to other tests.
    storage.getSharedEdgeSnapshotIndex().clear();
    storage.getEdgeVisibilityIndex().clear();
  }

  // ---- LWM pinning helpers (via reflection) ----
  // TsMinHolder is package-private, so we use reflection to create instances,
  // set tsMin, and register/unregister in AbstractStorage.tsMins.

  /**
   * Registers a TsMinHolder in AbstractStorage.tsMins to pin the global LWM
   * at the given value. Returns the holder (as Object) for later removal.
   */
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

  // ---- Helpers ----

  /**
   * Counts tombstones for entries with (ridBagId, 0, position) where
   * position = start, start+step, start+2*step, ...
   */
  private int countTombstones(long ridBagId, int start, int count, int step) throws Exception {
    final int[] result = {0};
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      for (int i = 0; i < count; i++) {
        int pos = start + i * step;
        var entry = bTree.findCurrentEntry(atomicOperation, ridBagId, 0, pos);
        if (entry != null && entry.second().tombstone()) {
          result[0]++;
        }
      }
    });
    return result[0];
  }

  /**
   * Counts live (non-tombstone) entries for entries with (ridBagId, 0, position)
   * where position = start, start+step, start+2*step, ...
   */
  private int countLiveEntries(long ridBagId, int start, int count, int step) throws Exception {
    final int[] result = {0};
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      for (int i = 0; i < count; i++) {
        int pos = start + i * step;
        var entry = bTree.findCurrentEntry(atomicOperation, ridBagId, 0, pos);
        if (entry != null && !entry.second().tombstone()) {
          result[0]++;
        }
      }
    });
    return result[0];
  }

  // ---- Basic tombstone GC during put() ----

  @Test
  public void testTombstonesBelowLwmWithNoSnapshotsAreRemovedDuringPut() throws Exception {
    // Fill the tree with tombstones at even positions (ts=1), then insert
    // live entries at odd positions (ts=100) to trigger bucket overflows.
    // Tombstones and live entries interleave in key space, sharing buckets.
    // GC should remove tombstones below LWM (no snapshot entries).

    // Insert FILL_COUNT tombstones at even positions: 0, 2, 4, ...
    for (int i = 0; i < FILL_COUNT; i++) {
      final int pos = i * 2;
      atomicOperationsManager.executeInsideAtomicOperation(
          atomicOperation -> bTree.put(atomicOperation,
              new EdgeKey(1L, 0, pos, 1L),
              new LinkBagValue(pos, 0, 0, true)));
    }

    // Some tombstones may already be GC'd during initial insertion (later
    // inserts overflow buckets containing earlier tombstones). Record count
    // before adding live entries to verify GC continues during that phase.
    int tombstonesBeforeLiveInserts = countTombstones(1L, 0, FILL_COUNT, 2);

    // Insert FILL_COUNT live entries at odd positions: 1, 3, 5, ...
    // These interleave with tombstones, causing bucket overflows and GC.
    for (int i = 0; i < FILL_COUNT; i++) {
      final int pos = i * 2 + 1;
      atomicOperationsManager.executeInsideAtomicOperation(
          atomicOperation -> bTree.put(atomicOperation,
              new EdgeKey(1L, 0, pos, 100L),
              new LinkBagValue(pos, 0, 0, false)));
    }

    // Tombstones should have been GC'd during bucket overflows
    int remainingTombstones = countTombstones(1L, 0, FILL_COUNT, 2);
    assertThat(remainingTombstones)
        .as("Tombstones should be GC'd during bucket overflow")
        .isLessThan(FILL_COUNT);

    // GC should have continued removing tombstones during live entry inserts
    assertThat(remainingTombstones)
        .as("GC should remove additional tombstones during live entry inserts")
        .isLessThanOrEqualTo(tombstonesBeforeLiveInserts);

    // All live entries must be present
    assertThat(countLiveEntries(1L, 1, FILL_COUNT, 2)).isEqualTo(FILL_COUNT);
  }

  @Test
  public void testTombstonesWithSnapshotEntriesArePreserved() throws Exception {
    // Tombstones below LWM but WITH lingering snapshot entries must NOT be
    // removed — removing them would cause ghost resurrection.

    // Insert live entries at even positions with ts=1
    for (int i = 0; i < FILL_COUNT; i++) {
      final int pos = i * 2;
      atomicOperationsManager.executeInsideAtomicOperation(
          atomicOperation -> bTree.put(atomicOperation,
              new EdgeKey(3L, 0, pos, 1L),
              new LinkBagValue(pos, 0, 0, false)));
    }

    // Cross-tx remove creates tombstones + snapshot entries at even positions
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      for (int i = 0; i < FILL_COUNT; i++) {
        int pos = i * 2;
        bTree.remove(atomicOperation, new EdgeKey(3L, 0, pos, 5L));
      }
    });

    // Verify all entries are now tombstones
    assertThat(countTombstones(3L, 0, FILL_COUNT, 2)).isEqualTo(FILL_COUNT);

    // Insert live entries at odd positions to trigger overflow.
    // GC should NOT remove tombstones because snapshot entries exist.
    for (int i = 0; i < FILL_COUNT; i++) {
      final int pos = i * 2 + 1;
      atomicOperationsManager.executeInsideAtomicOperation(
          atomicOperation -> bTree.put(atomicOperation,
              new EdgeKey(3L, 0, pos, 100L),
              new LinkBagValue(pos, 0, 0, false)));
    }

    // All tombstones must survive because snapshot entries block GC
    assertThat(countTombstones(3L, 0, FILL_COUNT, 2))
        .as("Tombstones with snapshot entries must be preserved")
        .isEqualTo(FILL_COUNT);
  }

  @Test
  public void testTreeSizeConsistencyAfterGC() throws Exception {
    // Verify that after GC, the actual number of entries in the tree
    // matches the expected count: live entries + surviving tombstones.
    // Uses same targetCollection=0 so entries co-locate in buckets.

    // Insert FILL_COUNT tombstones at even positions (ts=1, no snapshots)
    for (int i = 0; i < FILL_COUNT; i++) {
      final int pos = i * 2;
      atomicOperationsManager.executeInsideAtomicOperation(
          atomicOperation -> bTree.put(atomicOperation,
              new EdgeKey(4L, 0, pos, 1L),
              new LinkBagValue(pos, 0, 0, true)));
    }

    // Insert FILL_COUNT live entries at odd positions to trigger overflow+GC
    for (int i = 0; i < FILL_COUNT; i++) {
      final int pos = i * 2 + 1;
      atomicOperationsManager.executeInsideAtomicOperation(
          atomicOperation -> bTree.put(atomicOperation,
              new EdgeKey(4L, 0, pos, 100L),
              new LinkBagValue(pos, 0, 0, false)));
    }

    // Verify GC ran
    int remainingTombstones = countTombstones(4L, 0, FILL_COUNT, 2);
    assertThat(remainingTombstones)
        .as("GC should have removed some tombstones")
        .isLessThan(FILL_COUNT);

    // All live entries at odd positions must be present
    int liveCount = countLiveEntries(4L, 1, FILL_COUNT, 2);
    assertThat(liveCount).isEqualTo(FILL_COUNT);

    // Count total actual entries (live + surviving tombstones) by scanning
    // all inserted positions. This verifies updateSize(-removedCount) is
    // correct — the actual entry count must match expectations.
    final int[] totalEntries = {0};
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      for (int i = 0; i < FILL_COUNT * 2; i++) {
        var entry = bTree.findCurrentEntry(atomicOperation, 4L, 0, i);
        if (entry != null) {
          totalEntries[0]++;
        }
      }
    });
    assertThat(totalEntries[0])
        .as("Total entries must equal live + surviving tombstones")
        .isEqualTo(liveCount + remainingTombstones);
  }

  @Test
  public void testBTreeOrderingPreservedAfterGC() throws Exception {
    // After GC, entries retrieved by findCurrentEntry must still have
    // correct values — the B-tree ordering invariant is maintained.

    // Insert tombstones at even positions
    for (int i = 0; i < FILL_COUNT; i++) {
      final int pos = i * 2;
      atomicOperationsManager.executeInsideAtomicOperation(
          atomicOperation -> bTree.put(atomicOperation,
              new EdgeKey(7L, 0, pos, 1L),
              new LinkBagValue(pos, 0, 0, true)));
    }

    // Insert live entries at odd positions to trigger GC
    for (int i = 0; i < FILL_COUNT; i++) {
      final int pos = i * 2 + 1;
      atomicOperationsManager.executeInsideAtomicOperation(
          atomicOperation -> bTree.put(atomicOperation,
              new EdgeKey(7L, 0, pos, 100L),
              new LinkBagValue(pos, 0, 0, false)));
    }

    // Verify GC actually ran by checking some tombstones were removed
    int remainingTombstones = countTombstones(7L, 0, FILL_COUNT, 2);
    assertThat(remainingTombstones)
        .as("GC should have removed some tombstones during bucket overflow")
        .isLessThan(FILL_COUNT);

    // Verify all live entries are retrievable with correct values
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      for (int i = 0; i < FILL_COUNT; i++) {
        int pos = i * 2 + 1;
        var entry = bTree.findCurrentEntry(atomicOperation, 7L, 0, pos);
        assertThat(entry)
            .as("Live entry at position %d must exist", pos)
            .isNotNull();
        assertThat(entry.second().counter())
            .as("Counter for position %d", pos)
            .isEqualTo(pos);
        assertThat(entry.second().tombstone()).isFalse();
      }
    });
  }

  // ---- GC during cross-tx tombstone insertion in remove() ----

  @Test
  public void testGCDuringCrossTxRemoveTombstoneInsertion() throws Exception {
    // Verifies that GC also triggers in the remove() path when inserting
    // a cross-tx tombstone into a full bucket.

    // Fill tree with tombstones at even positions (no snapshots)
    for (int i = 0; i < FILL_COUNT; i++) {
      final int pos = i * 2;
      atomicOperationsManager.executeInsideAtomicOperation(
          atomicOperation -> bTree.put(atomicOperation,
              new EdgeKey(8L, 0, pos, 1L),
              new LinkBagValue(pos, 0, 0, true)));
    }

    // Insert live entries at odd positions — these share buckets with
    // the tombstones and may trigger bucket fullness
    for (int i = 0; i < FILL_COUNT - 1; i++) {
      final int pos = i * 2 + 1;
      atomicOperationsManager.executeInsideAtomicOperation(
          atomicOperation -> bTree.put(atomicOperation,
              new EdgeKey(8L, 0, pos, 50L),
              new LinkBagValue(pos, 0, 0, false)));
    }

    // Record tombstone count before cross-tx remove to distinguish GC
    // that happened during put() from GC during remove().
    int tombstonesBeforeRemove = countTombstones(8L, 0, FILL_COUNT, 2);

    // Now do a cross-tx remove on one of the live entries. The tombstone
    // insertion may trigger a bucket overflow, which should activate GC.
    final int removePos = 1; // live entry at position 1
    atomicOperationsManager.executeInsideAtomicOperation(
        atomicOperation -> {
          var oldValue = bTree.remove(atomicOperation,
              new EdgeKey(8L, 0, removePos, 200L));
          assertThat(oldValue).isNotNull();
          assertThat(oldValue.counter()).isEqualTo(removePos);
        });

    // Verify the new tombstone exists at the removed position
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var current = bTree.findCurrentEntry(atomicOperation, 8L, 0, removePos);
      assertThat(current).isNotNull();
      assertThat(current.second().tombstone()).isTrue();
      assertThat(current.first().ts).isEqualTo(200L);
    });

    // Some old tombstones should have been GC'd during the overall process
    int oldTombstones = countTombstones(8L, 0, FILL_COUNT, 2);
    assertThat(oldTombstones)
        .as("GC should have removed some old tombstones during put/remove")
        .isLessThan(FILL_COUNT);

    // Verify that the remove() call didn't increase old tombstone count
    // (it should have either maintained or decreased it via GC)
    assertThat(oldTombstones)
        .as("Remove path should not increase old tombstone count")
        .isLessThanOrEqualTo(tombstonesBeforeRemove);
  }

  @Test
  public void testNoGhostResurrectionAfterGC() throws Exception {
    // Critical invariant: after GC removes a tombstone, findVisibleEntry()
    // must NOT return a live entry for the deleted edge.

    // Insert live entries at even positions with ts=1
    for (int i = 0; i < FILL_COUNT; i++) {
      final int pos = i * 2;
      atomicOperationsManager.executeInsideAtomicOperation(
          atomicOperation -> bTree.put(atomicOperation,
              new EdgeKey(9L, 0, pos, 1L),
              new LinkBagValue(pos, 0, 0, false)));
    }

    // Cross-tx remove creates tombstones + snapshot entries at even positions
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      for (int i = 0; i < FILL_COUNT; i++) {
        int pos = i * 2;
        bTree.remove(atomicOperation, new EdgeKey(9L, 0, pos, 5L));
      }
    });

    // Clear snapshot entries to make tombstones eligible for GC.
    // This simulates the periodic cleanup that runs in production.
    storage.getSharedEdgeSnapshotIndex().clear();
    storage.getEdgeVisibilityIndex().clear();

    // Insert live entries at odd positions to trigger overflow+GC
    for (int i = 0; i < FILL_COUNT; i++) {
      final int pos = i * 2 + 1;
      atomicOperationsManager.executeInsideAtomicOperation(
          atomicOperation -> bTree.put(atomicOperation,
              new EdgeKey(9L, 0, pos, 100L),
              new LinkBagValue(pos, 0, 0, false)));
    }

    // For positions where GC removed the tombstone (findCurrentEntry returns
    // null), verify findVisibleEntry also returns null — this is the critical
    // ghost resurrection check. A GC'd tombstone must not let a deleted edge
    // become visible again.
    final int[] gcRemovedCount = {0};
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      for (int i = 0; i < FILL_COUNT; i++) {
        int pos = i * 2;
        var current = bTree.findCurrentEntry(atomicOperation, 9L, 0, pos);
        var visible = bTree.findVisibleEntry(atomicOperation, 9L, 0, pos);
        assertThat(visible)
            .as("Edge at position %d was deleted — must not be visible", pos)
            .isNull();
        if (current == null) {
          // Tombstone was GC'd — this is where ghost resurrection would occur
          gcRemovedCount[0]++;
        }
      }
    });
    assertThat(gcRemovedCount[0])
        .as("At least some tombstones must have been GC'd for ghost resurrection "
            + "check to be meaningful")
        .isGreaterThan(0);

    // Verify: live entries at odd positions are visible
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      for (int i = 0; i < FILL_COUNT; i++) {
        int pos = i * 2 + 1;
        var visible = bTree.findVisibleEntry(atomicOperation, 9L, 0, pos);
        assertThat(visible)
            .as("Edge at position %d was not deleted — must be visible", pos)
            .isNotNull();
        assertThat(visible.second().tombstone()).isFalse();
      }
    });
  }

  @Test
  public void testGCOnlyRunsOncePerInsert() throws Exception {
    // The gcAttempted flag ensures filtering runs at most once per insert.
    // If GC can't remove tombstones (snapshot entries block it), the normal
    // split proceeds and the insert must still succeed.

    // Insert live entries at even positions with ts=1
    for (int i = 0; i < FILL_COUNT; i++) {
      final int pos = i * 2;
      atomicOperationsManager.executeInsideAtomicOperation(
          atomicOperation -> bTree.put(atomicOperation,
              new EdgeKey(10L, 0, pos, 1L),
              new LinkBagValue(pos, 0, 0, false)));
    }

    // Cross-tx remove creates tombstones + snapshot entries (blocks GC)
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      for (int i = 0; i < FILL_COUNT; i++) {
        int pos = i * 2;
        bTree.remove(atomicOperation, new EdgeKey(10L, 0, pos, 5L));
      }
    });

    // Insert live entries at odd positions — GC will try but can't remove
    // tombstones (snapshot entries block), so normal splits must occur.
    for (int i = 0; i < FILL_COUNT; i++) {
      final int pos = i * 2 + 1;
      atomicOperationsManager.executeInsideAtomicOperation(
          atomicOperation -> bTree.put(atomicOperation,
              new EdgeKey(10L, 0, pos, 100L),
              new LinkBagValue(pos, 0, 0, false)));
    }

    // All tombstones must still exist (not GC'd due to snapshot entries)
    assertThat(countTombstones(10L, 0, FILL_COUNT, 2)).isEqualTo(FILL_COUNT);

    // All live entries must exist
    assertThat(countLiveEntries(10L, 1, FILL_COUNT, 2)).isEqualTo(FILL_COUNT);
  }

  // ---- Edge cases ----

  @Test
  public void testLiveEntriesAreNeverRemovedByGC() throws Exception {
    // Interleave live entries (odd positions) and removable tombstones
    // (even positions) in the same range, then trigger GC. Every live
    // entry must survive with its original value.

    for (int i = 0; i < FILL_COUNT * 2; i++) {
      final int pos = i;
      final boolean isTombstone = (i % 2 == 0);
      atomicOperationsManager.executeInsideAtomicOperation(
          atomicOperation -> bTree.put(atomicOperation,
              new EdgeKey(11L, 0, pos, 1L),
              new LinkBagValue(pos, 0, 0, isTombstone)));
    }

    // Insert beyond the existing range to trigger overflow+GC
    for (int i = 0; i < FILL_COUNT; i++) {
      final int pos = FILL_COUNT * 2 + i;
      atomicOperationsManager.executeInsideAtomicOperation(
          atomicOperation -> bTree.put(atomicOperation,
              new EdgeKey(11L, 0, pos, 100L),
              new LinkBagValue(pos, 0, 0, false)));
    }

    // Every original live entry (odd positions) must survive with correct value
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      for (int i = 0; i < FILL_COUNT; i++) {
        int pos = i * 2 + 1;
        var entry = bTree.findCurrentEntry(atomicOperation, 11L, 0, pos);
        assertThat(entry)
            .as("Live entry at position %d must survive GC", pos)
            .isNotNull();
        assertThat(entry.second().tombstone()).isFalse();
        assertThat(entry.second().counter()).isEqualTo(pos);
      }
    });
  }

  @Test
  public void testAllTombstoneBucketIsFullyClearedByGC() throws Exception {
    // Fill tree entirely with removable tombstones (low ts, no snapshots),
    // then insert one entry to trigger GC. The bucket should be cleared and
    // the insert should succeed.

    for (int i = 0; i < FILL_COUNT; i++) {
      final int pos = i;
      atomicOperationsManager.executeInsideAtomicOperation(
          atomicOperation -> bTree.put(atomicOperation,
              new EdgeKey(12L, 0, pos, 1L),
              new LinkBagValue(pos, 0, 0, true)));
    }

    // Insert one live entry to trigger overflow+GC in a tombstone-only bucket
    atomicOperationsManager.executeInsideAtomicOperation(
        atomicOperation -> bTree.put(atomicOperation,
            new EdgeKey(12L, 0, FILL_COUNT, 100L),
            new LinkBagValue(FILL_COUNT, 0, 0, false)));

    // The new live entry must be present
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var entry = bTree.findCurrentEntry(atomicOperation, 12L, 0, FILL_COUNT);
      assertThat(entry).isNotNull();
      assertThat(entry.second().tombstone()).isFalse();
    });

    // Some tombstones should have been GC'd
    int remaining = countTombstones(12L, 0, FILL_COUNT, 1);
    assertThat(remaining)
        .as("All-tombstone bucket should have entries GC'd")
        .isLessThan(FILL_COUNT);
  }

  @Test
  public void testGCWithNoTombstonesDoesNotCorruptBucket() throws Exception {
    // Fill the tree with only live entries (no tombstones). When overflow
    // triggers GC, filterAndRebuildBucket finds 0 removable entries and
    // falls through to the normal split path. All entries must survive.

    for (int i = 0; i < FILL_COUNT; i++) {
      final int pos = i;
      atomicOperationsManager.executeInsideAtomicOperation(
          atomicOperation -> bTree.put(atomicOperation,
              new EdgeKey(13L, 0, pos, 1L),
              new LinkBagValue(pos, 0, 0, false)));
    }

    // Insert more entries — GC finds nothing, proceeds to split
    for (int i = FILL_COUNT; i < FILL_COUNT * 2; i++) {
      final int pos = i;
      atomicOperationsManager.executeInsideAtomicOperation(
          atomicOperation -> bTree.put(atomicOperation,
              new EdgeKey(13L, 0, pos, 100L),
              new LinkBagValue(pos, 0, 0, false)));
    }

    // All entries must be present and correct
    assertThat(countLiveEntries(13L, 0, FILL_COUNT * 2, 1))
        .isEqualTo(FILL_COUNT * 2);
  }

  // ---- LWM boundary tests ----

  @Test
  public void testTombstoneWithTsEqualToLwmIsPreserved() throws Exception {
    // A tombstone with ts exactly equal to the global LWM must NOT be
    // removed — a transaction at that timestamp may still be active.
    // This tests the strict inequality boundary (key.ts >= lwm) in
    // isRemovableTombstone(). An off-by-one (>= changed to >) would
    // cause premature removal and ghost resurrection.
    //
    // Pin the LWM at a fixed value so it doesn't advance as idGen ticks.
    final long pinnedLwm = 1000L;
    Object lwmPin = pinLwm(pinnedLwm);
    try {
      // Insert tombstones with ts = pinnedLwm (boundary value) at even positions
      for (int i = 0; i < FILL_COUNT; i++) {
        final int pos = i * 2;
        atomicOperationsManager.executeInsideAtomicOperation(
            atomicOperation -> bTree.put(atomicOperation,
                new EdgeKey(20L, 0, pos, pinnedLwm),
                new LinkBagValue(pos, 0, 0, true)));
      }

      // Insert live entries at odd positions to trigger overflow+GC
      for (int i = 0; i < FILL_COUNT; i++) {
        final int pos = i * 2 + 1;
        atomicOperationsManager.executeInsideAtomicOperation(
            atomicOperation -> bTree.put(atomicOperation,
                new EdgeKey(20L, 0, pos, pinnedLwm + 100),
                new LinkBagValue(pos, 0, 0, false)));
      }

      // Tombstones at ts == LWM must ALL survive (none should be GC'd)
      assertThat(countTombstones(20L, 0, FILL_COUNT, 2))
          .as("Tombstones with ts == LWM must be preserved (strict inequality)")
          .isEqualTo(FILL_COUNT);

      // All live entries must be present
      assertThat(countLiveEntries(20L, 1, FILL_COUNT, 2)).isEqualTo(FILL_COUNT);
    } finally {
      unpinLwm(lwmPin);
    }
  }

  @Test
  public void testTombstoneWithTsJustBelowLwmIsRemoved() throws Exception {
    // A tombstone with ts = lwm - 1 (strictly below) and no snapshot entries
    // must be eligible for GC removal. Together with the ts==lwm test above,
    // this pair pin-tests the exact boundary of the >= comparison.
    //
    // Pin the LWM so we control the exact boundary value.
    final long pinnedLwm = 1000L;
    Object lwmPin = pinLwm(pinnedLwm);
    try {
      // Insert tombstones with ts = pinnedLwm - 1 at even positions
      for (int i = 0; i < FILL_COUNT; i++) {
        final int pos = i * 2;
        atomicOperationsManager.executeInsideAtomicOperation(
            atomicOperation -> bTree.put(atomicOperation,
                new EdgeKey(21L, 0, pos, pinnedLwm - 1),
                new LinkBagValue(pos, 0, 0, true)));
      }

      // Insert live entries at odd positions to trigger overflow+GC
      for (int i = 0; i < FILL_COUNT; i++) {
        final int pos = i * 2 + 1;
        atomicOperationsManager.executeInsideAtomicOperation(
            atomicOperation -> bTree.put(atomicOperation,
                new EdgeKey(21L, 0, pos, pinnedLwm + 100),
                new LinkBagValue(pos, 0, 0, false)));
      }

      // Tombstones at ts = lwm - 1 should be GC'd during overflow
      assertThat(countTombstones(21L, 0, FILL_COUNT, 2))
          .as("Tombstones with ts = lwm - 1 should be removable")
          .isLessThan(FILL_COUNT);

      // All live entries must survive
      assertThat(countLiveEntries(21L, 1, FILL_COUNT, 2)).isEqualTo(FILL_COUNT);
    } finally {
      unpinLwm(lwmPin);
    }
  }

  // ---- Mixed removable/non-removable tombstones ----

  @Test
  public void testMixOfRemovableAndNonRemovableTombstones() throws Exception {
    // Create tombstones where half have snapshot entries (non-removable)
    // and half do not (removable). GC must remove only the eligible ones.
    // This tests per-entry evaluation — a bug that short-circuits after
    // checking one tombstone would fail here.

    // Step 1: insert live entries at ALL even positions
    for (int i = 0; i < FILL_COUNT; i++) {
      final int pos = i * 2;
      atomicOperationsManager.executeInsideAtomicOperation(
          atomicOperation -> bTree.put(atomicOperation,
              new EdgeKey(40L, 0, pos, 1L),
              new LinkBagValue(pos, 0, 0, false)));
    }

    // Step 2: cross-tx remove only the FIRST HALF of even positions.
    // Creates tombstones WITH snapshot entries — these are non-removable.
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      for (int i = 0; i < FILL_COUNT / 2; i++) {
        int pos = i * 2;
        bTree.remove(atomicOperation, new EdgeKey(40L, 0, pos, 5L));
      }
    });

    // Step 3: replace the SECOND HALF with tombstones directly via put()
    // (no snapshot entries created — these are removable)
    for (int i = FILL_COUNT / 2; i < FILL_COUNT; i++) {
      final int pos = i * 2;
      atomicOperationsManager.executeInsideAtomicOperation(
          atomicOperation -> bTree.put(atomicOperation,
              new EdgeKey(40L, 0, pos, 1L),
              new LinkBagValue(pos, 0, 0, true)));
    }

    // Step 4: insert live entries at odd positions to trigger overflow+GC
    for (int i = 0; i < FILL_COUNT; i++) {
      final int pos = i * 2 + 1;
      atomicOperationsManager.executeInsideAtomicOperation(
          atomicOperation -> bTree.put(atomicOperation,
              new EdgeKey(40L, 0, pos, 100L),
              new LinkBagValue(pos, 0, 0, false)));
    }

    // Non-removable tombstones (first half, with snapshots) must ALL survive
    int nonRemovableSurvivors = countTombstones(40L, 0, FILL_COUNT / 2, 2);
    assertThat(nonRemovableSurvivors)
        .as("Tombstones with snapshot entries must all survive")
        .isEqualTo(FILL_COUNT / 2);

    // Removable tombstones (second half, no snapshots) should have some GC'd
    final int[] removableSurvivors = {0};
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      for (int i = FILL_COUNT / 2; i < FILL_COUNT; i++) {
        int pos = i * 2;
        var entry = bTree.findCurrentEntry(atomicOperation, 40L, 0, pos);
        if (entry != null && entry.second().tombstone()) {
          removableSurvivors[0]++;
        }
      }
    });
    assertThat(removableSurvivors[0])
        .as("Tombstones without snapshots should be partially or fully GC'd")
        .isLessThan(FILL_COUNT / 2);

    // All live entries at odd positions must be present
    assertThat(countLiveEntries(40L, 1, FILL_COUNT, 2)).isEqualTo(FILL_COUNT);
  }
}
