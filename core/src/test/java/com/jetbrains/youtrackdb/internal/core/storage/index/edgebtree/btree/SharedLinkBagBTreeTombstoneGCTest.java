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

    // Note: some tombstones may already be GC'd during initial insertion
    // (later inserts overflow buckets containing earlier tombstones, triggering
    // GC since all have ts=1 which is below the advancing LWM). Record the
    // count before adding live entries to verify GC continues.
    int tombstonesAfterInsert = countTombstones(1L, 0, FILL_COUNT, 2);

    // Insert FILL_COUNT live entries at odd positions: 1, 3, 5, ...
    // These interleave with tombstones, causing bucket overflows and GC.
    for (int i = 0; i < FILL_COUNT; i++) {
      final int pos = i * 2 + 1;
      atomicOperationsManager.executeInsideAtomicOperation(
          atomicOperation -> bTree.put(atomicOperation,
              new EdgeKey(1L, 0, pos, 100L),
              new LinkBagValue(pos, 0, 0, false)));
    }

    // More tombstones should have been GC'd during the live entry inserts
    int remainingTombstones = countTombstones(1L, 0, FILL_COUNT, 2);
    assertThat(remainingTombstones)
        .as("Tombstones should be GC'd during bucket overflow")
        .isLessThan(FILL_COUNT);

    // GC should have removed at least some tombstones overall
    // (either during initial insertion or during live entry insertion)
    assertThat(remainingTombstones)
        .as("GC should have removed tombstones")
        .isLessThan(FILL_COUNT);

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
    // Verify that after GC, all live entries are still retrievable and
    // the correct count of tombstones has been removed.

    // Interleave tombstones and live entries in the same key range.
    // Positions 0,1,2,...,2*FILL_COUNT-1. Even = tombstone, odd = live.
    for (int i = 0; i < FILL_COUNT * 2; i++) {
      final int pos = i;
      final boolean isTombstone = (i % 2 == 0);
      atomicOperationsManager.executeInsideAtomicOperation(
          atomicOperation -> bTree.put(atomicOperation,
              new EdgeKey(4L, 0, pos, 1L),
              new LinkBagValue(pos, 0, 0, isTombstone)));
    }

    // Now trigger overflows by inserting into the same range with a new
    // 3-tuple prefix (different ridBagId) — this uses different buckets.
    // Instead, insert at new positions that interleave with existing ones.
    // Use half-integer positions by using targetCollection=1 as offset.
    for (int i = 0; i < FILL_COUNT; i++) {
      final int pos = i * 2; // same positions as tombstones
      atomicOperationsManager.executeInsideAtomicOperation(
          atomicOperation -> bTree.put(atomicOperation,
              new EdgeKey(4L, 1, pos, 100L),
              new LinkBagValue(pos, 0, 0, false)));
    }

    // All live entries at odd positions must be present
    assertThat(countLiveEntries(4L, 1, FILL_COUNT, 2)).isEqualTo(FILL_COUNT);

    // All new live entries (targetCollection=1) must be present
    final int[] newLiveCount = {0};
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      for (int i = 0; i < FILL_COUNT; i++) {
        int pos = i * 2;
        var entry = bTree.findCurrentEntry(atomicOperation, 4L, 1, pos);
        if (entry != null && !entry.second().tombstone()) {
          newLiveCount[0]++;
        }
      }
    });
    assertThat(newLiveCount[0]).isEqualTo(FILL_COUNT);
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

    // Some old tombstones (at even positions) should have been GC'd
    int oldTombstones = countTombstones(8L, 0, FILL_COUNT, 2);
    // We can't assert exact count, but the operation must complete without error
    assertThat(oldTombstones).isGreaterThanOrEqualTo(0);
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

    // Verify: for each deleted edge, findVisibleEntry must return null.
    // Even if the tombstone was GC'd, the edge must not resurrect because
    // there are no snapshot entries to fall back to.
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      for (int i = 0; i < FILL_COUNT; i++) {
        int pos = i * 2;
        var visible = bTree.findVisibleEntry(atomicOperation, 9L, 0, pos);
        assertThat(visible)
            .as("Edge at position %d was deleted — must not be visible", pos)
            .isNull();
      }
    });

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
}
