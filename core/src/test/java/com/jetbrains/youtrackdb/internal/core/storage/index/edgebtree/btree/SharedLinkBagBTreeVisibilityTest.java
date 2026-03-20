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
}
