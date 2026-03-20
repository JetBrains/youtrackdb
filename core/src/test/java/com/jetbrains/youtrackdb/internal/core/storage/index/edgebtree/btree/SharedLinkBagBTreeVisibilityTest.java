package com.jetbrains.youtrackdb.internal.core.storage.index.edgebtree.btree;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.EdgeKey;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.EdgeSnapshotKey;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.LinkBagValue;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.SharedLinkBagBTree;
import java.io.File;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Tests for SI visibility helpers in {@link SharedLinkBagBTree}:
 * {@code isEdgeVersionVisible()}, {@code resolveVisibleEntry()}, and
 * {@code findVisibleEntry()}.
 *
 * <p>Tests verify that the correct entry (or null) is returned based on
 * the transaction's snapshot view: fast-path for visible live entries,
 * null for visible tombstones, and snapshot-index fallback for invisible
 * B-tree entries.
 */
public class SharedLinkBagBTreeVisibilityTest {

  private static final String DB_NAME = "visibilityTest";
  private static final String DIR_NAME = "/visibilityTest";

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
    bTree = new SharedLinkBagBTree(storage, "visSI", ".sbc");
    atomicOperationsManager.executeInsideAtomicOperation(
        atomicOperation -> bTree.create(atomicOperation));
  }

  @After
  public void afterMethod() throws Exception {
    atomicOperationsManager.executeInsideAtomicOperation(
        atomicOperation -> bTree.delete(atomicOperation));
  }

  // ---- findVisibleEntry() full-stack tests ----

  @Test
  public void testFindVisibleEntry_visibleLiveEntry() throws Exception {
    // Insert a live entry in tx1 (committed). In tx2, findVisibleEntry should
    // see it because tx1's commitTs is visible in tx2's snapshot.
    atomicOperationsManager.executeInsideAtomicOperation(
        atomicOperation -> bTree.put(atomicOperation,
            new EdgeKey(100L, 10, 100L, 5L),
            new LinkBagValue(42, 0, 0, false)));

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var result = bTree.findVisibleEntry(atomicOperation, 100L, 10, 100L);
      assertThat(result).isNotNull();
      assertThat(result.first().ts).isEqualTo(5L);
      assertThat(result.second().counter()).isEqualTo(42);
      assertThat(result.second().tombstone()).isFalse();
    });
  }

  @Test
  public void testFindVisibleEntry_visibleTombstone() throws Exception {
    // Insert a tombstone entry in tx1. In tx2, findVisibleEntry should return
    // null because the visible entry is a tombstone (edge deleted).
    atomicOperationsManager.executeInsideAtomicOperation(
        atomicOperation -> bTree.put(atomicOperation,
            new EdgeKey(200L, 10, 100L, 5L),
            new LinkBagValue(42, 0, 0, true)));

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var result = bTree.findVisibleEntry(atomicOperation, 200L, 10, 100L);
      assertThat(result).isNull();
    });
  }

  @Test
  public void testFindVisibleEntry_noEntry() throws Exception {
    // No entry exists for this logical edge. findVisibleEntry returns null.
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var result = bTree.findVisibleEntry(atomicOperation, 999L, 99, 999L);
      assertThat(result).isNull();
    });
  }

  @Test
  public void testFindVisibleEntry_selfRead() throws Exception {
    // Within the same transaction, a write followed by findVisibleEntry
    // should return the written entry (self-read shortcut).
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      long commitTs = atomicOperation.getCommitTsUnsafe();
      bTree.put(atomicOperation,
          new EdgeKey(300L, 10, 100L, commitTs),
          new LinkBagValue(77, 0, 0, false));

      var result = bTree.findVisibleEntry(atomicOperation, 300L, 10, 100L);
      assertThat(result).isNotNull();
      assertThat(result.first().ts).isEqualTo(commitTs);
      assertThat(result.second().counter()).isEqualTo(77);
    });
  }

  @Test
  public void testFindVisibleEntry_invisibleBtreeEntryWithSnapshotFallback() throws Exception {
    // Simulate an invisible B-tree entry by inserting with a very high ts
    // (higher than any real commitTs). Then manually populate the snapshot
    // index with a visible older version. findVisibleEntry should fall back
    // to the snapshot and return the older version.
    final long futureTs = Long.MAX_VALUE - 1;
    final long olderTs = 3L;

    // tx1: insert entry with a "future" ts. This entry will be invisible to
    // any later transaction because its ts >= maxActiveOperationTs.
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      bTree.put(atomicOperation,
          new EdgeKey(400L, 10, 100L, futureTs),
          new LinkBagValue(99, 0, 0, false));

      // Also insert the "old version" into the snapshot index, as if a
      // prior committed tx created it and the update moved it there.
      final int componentId = (int) bTree.getFileId();
      atomicOperation.putEdgeSnapshotEntry(
          new EdgeSnapshotKey(componentId, 400L, 10, 100L, olderTs),
          new LinkBagValue(42, 0, 0, false));
    });

    // tx2: the B-tree entry (ts=futureTs) is NOT visible because
    // futureTs >= maxActiveOperationTs. The snapshot entry (ts=3) IS visible
    // because 3 < minActiveOperationTs of tx2's snapshot.
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var result = bTree.findVisibleEntry(atomicOperation, 400L, 10, 100L);
      assertThat(result).isNotNull();
      assertThat(result.first().ts).isEqualTo(olderTs);
      assertThat(result.second().counter()).isEqualTo(42);
      assertThat(result.second().tombstone()).isFalse();
    });
  }

  @Test
  public void testFindVisibleEntry_invisibleBtreeWithVisibleLiveSnapshotOverTombstone()
      throws Exception {
    // B-tree has invisible tombstone. Snapshot has two versions: a tombstone
    // at ts=4 and a live entry at ts=2. The descending iteration should
    // find the tombstone at ts=4 first. If ts=4 is also not visible (placed
    // by same future tx), it continues to ts=2 which is visible and live.
    final long futureTs = Long.MAX_VALUE - 1;

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      // B-tree: invisible tombstone
      bTree.put(atomicOperation,
          new EdgeKey(500L, 10, 100L, futureTs),
          new LinkBagValue(0, 0, 0, true));

      final int componentId = (int) bTree.getFileId();
      // Snapshot: visible live version at ts=2
      atomicOperation.putEdgeSnapshotEntry(
          new EdgeSnapshotKey(componentId, 500L, 10, 100L, 2L),
          new LinkBagValue(33, 0, 0, false));
    });

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var result = bTree.findVisibleEntry(atomicOperation, 500L, 10, 100L);
      assertThat(result).isNotNull();
      assertThat(result.first().ts).isEqualTo(2L);
      assertThat(result.second().counter()).isEqualTo(33);
    });
  }

  @Test
  public void testFindVisibleEntry_invisibleBtreeWithOnlySnapshotTombstone() throws Exception {
    // B-tree has invisible entry. Snapshot only has a visible tombstone.
    // findVisibleEntry should return null (newest visible version is deleted).
    final long futureTs = Long.MAX_VALUE - 1;

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      bTree.put(atomicOperation,
          new EdgeKey(600L, 10, 100L, futureTs),
          new LinkBagValue(99, 0, 0, false));

      final int componentId = (int) bTree.getFileId();
      atomicOperation.putEdgeSnapshotEntry(
          new EdgeSnapshotKey(componentId, 600L, 10, 100L, 2L),
          new LinkBagValue(0, 0, 0, true));
    });

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var result = bTree.findVisibleEntry(atomicOperation, 600L, 10, 100L);
      assertThat(result).isNull();
    });
  }

  @Test
  public void testFindVisibleEntry_invisibleBtreeNoSnapshotEntries() throws Exception {
    // B-tree has invisible entry. No snapshot entries exist.
    // findVisibleEntry should return null.
    final long futureTs = Long.MAX_VALUE - 1;

    atomicOperationsManager.executeInsideAtomicOperation(
        atomicOperation -> bTree.put(atomicOperation,
            new EdgeKey(700L, 10, 100L, futureTs),
            new LinkBagValue(99, 0, 0, false)));

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var result = bTree.findVisibleEntry(atomicOperation, 700L, 10, 100L);
      assertThat(result).isNull();
    });
  }

  @Test
  public void testFindVisibleEntry_multipleSnapshotVersionsReturnsNewestVisible()
      throws Exception {
    // B-tree has invisible entry. Snapshot has multiple versions (ts=2, 4, 6).
    // All visible. findVisibleEntry should return ts=6 (newest visible).
    final long futureTs = Long.MAX_VALUE - 1;

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      bTree.put(atomicOperation,
          new EdgeKey(800L, 10, 100L, futureTs),
          new LinkBagValue(99, 0, 0, false));

      final int componentId = (int) bTree.getFileId();
      atomicOperation.putEdgeSnapshotEntry(
          new EdgeSnapshotKey(componentId, 800L, 10, 100L, 2L),
          new LinkBagValue(20, 0, 0, false));
      atomicOperation.putEdgeSnapshotEntry(
          new EdgeSnapshotKey(componentId, 800L, 10, 100L, 4L),
          new LinkBagValue(40, 0, 0, false));
      atomicOperation.putEdgeSnapshotEntry(
          new EdgeSnapshotKey(componentId, 800L, 10, 100L, 6L),
          new LinkBagValue(60, 0, 0, false));
    });

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var result = bTree.findVisibleEntry(atomicOperation, 800L, 10, 100L);
      assertThat(result).isNotNull();
      assertThat(result.first().ts).isEqualTo(6L);
      assertThat(result.second().counter()).isEqualTo(60);
    });
  }

  @Test
  public void testFindVisibleEntry_crossTxUpdateThenVisibleRead() throws Exception {
    // Full end-to-end: tx1 inserts, tx2 updates (cross-tx, creates snapshot).
    // tx3 reads — should see tx2's version (both are committed and visible).
    atomicOperationsManager.executeInsideAtomicOperation(
        atomicOperation -> bTree.put(atomicOperation,
            new EdgeKey(900L, 10, 100L, 5L),
            new LinkBagValue(42, 0, 0, false)));

    atomicOperationsManager.executeInsideAtomicOperation(
        atomicOperation -> bTree.put(atomicOperation,
            new EdgeKey(900L, 10, 100L, 10L),
            new LinkBagValue(99, 0, 0, false)));

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var result = bTree.findVisibleEntry(atomicOperation, 900L, 10, 100L);
      assertThat(result).isNotNull();
      // Both ts=5 and ts=10 are committed. B-tree has ts=10. Visible.
      assertThat(result.first().ts).isEqualTo(10L);
      assertThat(result.second().counter()).isEqualTo(99);
    });
  }

  @Test
  public void testFindVisibleEntry_crossTxRemoveThenVisibleRead() throws Exception {
    // tx1 inserts live edge, tx2 removes (creates tombstone + snapshot).
    // tx3 reads — should see null because the visible B-tree entry is a
    // tombstone.
    atomicOperationsManager.executeInsideAtomicOperation(
        atomicOperation -> bTree.put(atomicOperation,
            new EdgeKey(1000L, 10, 100L, 5L),
            new LinkBagValue(42, 0, 0, false)));

    atomicOperationsManager.executeInsideAtomicOperation(
        atomicOperation -> bTree.remove(atomicOperation,
            new EdgeKey(1000L, 10, 100L, 10L)));

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var result = bTree.findVisibleEntry(atomicOperation, 1000L, 10, 100L);
      // Tombstone is visible → returns null
      assertThat(result).isNull();
    });
  }

  @Test
  public void testFindVisibleEntry_noBtreeEntryWithSnapshotFallback() throws Exception {
    // No B-tree entry exists for this logical edge, but the snapshot index
    // has a visible version. This exercises the current==null branch in
    // findVisibleEntry that falls back directly to the snapshot index.
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      final int componentId = (int) bTree.getFileId();
      atomicOperation.putEdgeSnapshotEntry(
          new EdgeSnapshotKey(componentId, 1100L, 10, 100L, 3L),
          new LinkBagValue(55, 0, 0, false));
    });

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var result = bTree.findVisibleEntry(atomicOperation, 1100L, 10, 100L);
      assertThat(result).isNotNull();
      assertThat(result.first().ts).isEqualTo(3L);
      assertThat(result.second().counter()).isEqualTo(55);
    });
  }

  // ---- Spliterator visibility tests (forward/backward iteration) ----

  @Test
  public void testForwardIteration_skipsInvisibleAndTombstoneEntries() throws Exception {
    // Insert 5 entries: 3 visible live, 1 invisible (future ts), 1 visible tombstone.
    // Forward iteration should return only the 3 visible live entries.
    final long futureTs = Long.MAX_VALUE - 1;

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      bTree.put(atomicOperation,
          new EdgeKey(1200L, 10, 100L, 5L), new LinkBagValue(1, 0, 0, false));
      bTree.put(atomicOperation,
          new EdgeKey(1200L, 20, 200L, 5L), new LinkBagValue(2, 0, 0, false));
      // Invisible entry (future ts)
      bTree.put(atomicOperation,
          new EdgeKey(1200L, 30, 300L, futureTs), new LinkBagValue(3, 0, 0, false));
      // Visible tombstone
      bTree.put(atomicOperation,
          new EdgeKey(1200L, 40, 400L, 5L), new LinkBagValue(0, 0, 0, true));
      bTree.put(atomicOperation,
          new EdgeKey(1200L, 50, 500L, 5L), new LinkBagValue(5, 0, 0, false));
    });

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var fromKey = new EdgeKey(1200L, 0, 0L, Long.MIN_VALUE);
      var toKey = new EdgeKey(1200L, Integer.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
      var entries = bTree.streamEntriesBetween(
          fromKey, true, toKey, true, true, atomicOperation).toList();

      // Should have 3 entries: (10,100), (20,200), (50,500)
      // (30,300) is invisible (future ts), (40,400) is tombstone
      assertThat(entries).hasSize(3);
      assertThat(entries.get(0).second().counter()).isEqualTo(1);
      assertThat(entries.get(1).second().counter()).isEqualTo(2);
      assertThat(entries.get(2).second().counter()).isEqualTo(5);
    });
  }

  @Test
  public void testBackwardIteration_skipsInvisibleAndTombstoneEntries() throws Exception {
    // Same setup as forward, but iterate backward (descending order).
    final long futureTs = Long.MAX_VALUE - 1;

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      bTree.put(atomicOperation,
          new EdgeKey(1300L, 10, 100L, 5L), new LinkBagValue(1, 0, 0, false));
      bTree.put(atomicOperation,
          new EdgeKey(1300L, 20, 200L, 5L), new LinkBagValue(2, 0, 0, false));
      bTree.put(atomicOperation,
          new EdgeKey(1300L, 30, 300L, futureTs), new LinkBagValue(3, 0, 0, false));
      bTree.put(atomicOperation,
          new EdgeKey(1300L, 40, 400L, 5L), new LinkBagValue(0, 0, 0, true));
      bTree.put(atomicOperation,
          new EdgeKey(1300L, 50, 500L, 5L), new LinkBagValue(5, 0, 0, false));
    });

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var fromKey = new EdgeKey(1300L, 0, 0L, Long.MIN_VALUE);
      var toKey = new EdgeKey(1300L, Integer.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
      var entries = bTree.streamEntriesBetween(
          fromKey, true, toKey, true, false, atomicOperation).toList();

      // Descending: (50,500), (20,200), (10,100)
      assertThat(entries).hasSize(3);
      assertThat(entries.get(0).second().counter()).isEqualTo(5);
      assertThat(entries.get(1).second().counter()).isEqualTo(2);
      assertThat(entries.get(2).second().counter()).isEqualTo(1);
    });
  }

  @Test
  public void testForwardIteration_snapshotFallbackDuringIteration() throws Exception {
    // Insert entries: one visible, one invisible with snapshot fallback.
    // Forward iteration should return both (the invisible one resolved
    // via snapshot).
    final long futureTs = Long.MAX_VALUE - 1;

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      bTree.put(atomicOperation,
          new EdgeKey(1400L, 10, 100L, 5L), new LinkBagValue(1, 0, 0, false));
      // Invisible entry with snapshot fallback
      bTree.put(atomicOperation,
          new EdgeKey(1400L, 20, 200L, futureTs), new LinkBagValue(99, 0, 0, false));

      final int componentId = (int) bTree.getFileId();
      atomicOperation.putEdgeSnapshotEntry(
          new EdgeSnapshotKey(componentId, 1400L, 20, 200L, 3L),
          new LinkBagValue(2, 0, 0, false));
    });

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var fromKey = new EdgeKey(1400L, 0, 0L, Long.MIN_VALUE);
      var toKey = new EdgeKey(1400L, Integer.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
      var entries = bTree.streamEntriesBetween(
          fromKey, true, toKey, true, true, atomicOperation).toList();

      assertThat(entries).hasSize(2);
      // First: visible entry (10,100) counter=1
      assertThat(entries.get(0).second().counter()).isEqualTo(1);
      // Second: snapshot fallback (20,200) counter=2.
      // The key retains the original B-tree ts (for position tracking),
      // but the value is the resolved snapshot version.
      assertThat(entries.get(1).second().counter()).isEqualTo(2);
    });
  }

  @Test
  public void testForwardIteration_allInvisibleReturnsEmpty() throws Exception {
    // All entries are invisible. Iteration should return empty.
    final long futureTs = Long.MAX_VALUE - 1;

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      bTree.put(atomicOperation,
          new EdgeKey(1500L, 10, 100L, futureTs), new LinkBagValue(1, 0, 0, false));
      bTree.put(atomicOperation,
          new EdgeKey(1500L, 20, 200L, futureTs), new LinkBagValue(2, 0, 0, false));
    });

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var fromKey = new EdgeKey(1500L, 0, 0L, Long.MIN_VALUE);
      var toKey = new EdgeKey(1500L, Integer.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
      var entries = bTree.streamEntriesBetween(
          fromKey, true, toKey, true, true, atomicOperation).toList();

      assertThat(entries).isEmpty();
    });
  }
}
