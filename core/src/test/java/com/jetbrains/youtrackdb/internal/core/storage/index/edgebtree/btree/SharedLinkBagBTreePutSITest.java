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
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.EdgeVisibilityKey;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.LinkBagValue;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.SharedLinkBagBTree;
import java.io.File;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Tests for the snapshot-isolation aware put() in {@link SharedLinkBagBTree}.
 * Verifies that cross-transaction updates preserve old versions in the snapshot
 * index, same-transaction overwrites skip snapshot preservation, and new
 * inserts work as before.
 *
 * <p>Cross-transaction updates are tested across separate atomic operations
 * (matching production semantics: the initial insert is committed first, then
 * the update happens in a new transaction).
 */
public class SharedLinkBagBTreePutSITest {

  private static final String DB_NAME = "putSITest";
  private static final String DIR_NAME = "/putSITest";

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
    bTree = new SharedLinkBagBTree(storage, "putSI", ".sbc");
    atomicOperationsManager.executeInsideAtomicOperation(
        atomicOperation -> bTree.create(atomicOperation));
  }

  @After
  public void afterMethod() throws Exception {
    atomicOperationsManager.executeInsideAtomicOperation(
        atomicOperation -> bTree.delete(atomicOperation));
  }

  // ---- New insert (no existing entry) ----

  @Test
  public void testNewInsertIsNew() throws Exception {
    // Inserting a new entry should return true (new key).
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var key = new EdgeKey(100L, 10, 100L, 5L);
      var value = new LinkBagValue(42, 0, 0, false);

      boolean isNew = bTree.put(atomicOperation, key, value);
      assertThat(isNew).isTrue();

      // Verify entry is in B-tree
      assertThat(bTree.get(key, atomicOperation)).isEqualTo(value);
    });
  }

  // ---- Cross-transaction update (different ts, separate atomic operations) ----

  @Test
  public void testCrossTransactionUpdatePreservesSnapshot() throws Exception {
    // Insert with ts=5 in one atomic op, then update with ts=10 in another.
    // The old value (ts=5) should be preserved in the snapshot index.
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      bTree.put(atomicOperation, new EdgeKey(200L, 10, 100L, 5L),
          new LinkBagValue(42, 0, 0, false));
    });

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      boolean isNew = bTree.put(atomicOperation, new EdgeKey(200L, 10, 100L, 10L),
          new LinkBagValue(99, 0, 0, false));

      // Returns false — replacing an existing logical edge
      assertThat(isNew).isFalse();

      // Verify new entry is in B-tree with new ts
      var current = bTree.findCurrentEntry(atomicOperation, 200L, 10, 100L);
      assertThat(current).isNotNull();
      assertThat(current.first().ts).isEqualTo(10L);
      assertThat(current.second().counter()).isEqualTo(99);

      // Verify old value is preserved in snapshot index
      final int componentId = (int) bTree.getFileId();
      var snapshotKey = new EdgeSnapshotKey(componentId, 200L, 10, 100L, 5L);
      var snapshotValue = atomicOperation.getEdgeSnapshotEntry(snapshotKey);
      assertThat(snapshotValue).isNotNull();
      assertThat(snapshotValue.counter()).isEqualTo(42);
      assertThat(snapshotValue.tombstone()).isFalse();

      // Verify visibility index entry was created
      var visKey = new EdgeVisibilityKey(5L, componentId, 200L, 10, 100L);
      assertThat(atomicOperation.containsEdgeVisibilityEntry(visKey)).isTrue();
    });
  }

  @Test
  public void testCrossTransactionUpdateTreeSizeUnchanged() throws Exception {
    // Cross-tx update should not change tree size (remove + insert = net zero).
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      bTree.put(atomicOperation, new EdgeKey(300L, 10, 100L, 5L),
          new LinkBagValue(1, 0, 0, false));
      bTree.put(atomicOperation, new EdgeKey(300L, 20, 200L, 5L),
          new LinkBagValue(2, 0, 0, false));
    });

    // Update one entry with a new ts — tree size should remain 2
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      bTree.put(atomicOperation, new EdgeKey(300L, 10, 100L, 10L),
          new LinkBagValue(99, 0, 0, false));

      // Both entries still present (one updated, one unchanged)
      assertThat(bTree.findCurrentEntry(atomicOperation, 300L, 10, 100L)).isNotNull();
      assertThat(bTree.findCurrentEntry(atomicOperation, 300L, 20, 200L)).isNotNull();

      // Old key (ts=5) should NOT be in the tree anymore
      assertThat(bTree.get(new EdgeKey(300L, 10, 100L, 5L), atomicOperation)).isNull();
    });
  }

  @Test
  public void testMultipleCrossTransactionUpdates() throws Exception {
    // Insert with ts=1, update to ts=5, then update to ts=10.
    // Each update in a separate atomic op preserves the previous version.
    atomicOperationsManager.executeInsideAtomicOperation(
        atomicOperation -> bTree.put(atomicOperation, new EdgeKey(400L, 10, 100L, 1L),
            new LinkBagValue(1, 0, 0, false)));

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      bTree.put(atomicOperation, new EdgeKey(400L, 10, 100L, 5L),
          new LinkBagValue(5, 0, 0, false));

      final int componentId = (int) bTree.getFileId();
      var snap1 = new EdgeSnapshotKey(componentId, 400L, 10, 100L, 1L);
      assertThat(atomicOperation.getEdgeSnapshotEntry(snap1)).isNotNull();
      assertThat(atomicOperation.getEdgeSnapshotEntry(snap1).counter()).isEqualTo(1);
    });

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      bTree.put(atomicOperation, new EdgeKey(400L, 10, 100L, 10L),
          new LinkBagValue(10, 0, 0, false));

      final int componentId = (int) bTree.getFileId();
      var snap5 = new EdgeSnapshotKey(componentId, 400L, 10, 100L, 5L);
      assertThat(atomicOperation.getEdgeSnapshotEntry(snap5)).isNotNull();
      assertThat(atomicOperation.getEdgeSnapshotEntry(snap5).counter()).isEqualTo(5);

      var current = bTree.findCurrentEntry(atomicOperation, 400L, 10, 100L);
      assertThat(current).isNotNull();
      assertThat(current.first().ts).isEqualTo(10L);
    });
  }

  // ---- Same-transaction overwrite (same ts) ----

  @Test
  public void testSameTransactionOverwriteNoSnapshot() throws Exception {
    // Insert and then overwrite with the same ts — no snapshot should be
    // created because the previous version belongs to the same transaction.
    // Uses a unique ridBagId to avoid shared snapshot index interference.
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var key = new EdgeKey(500L, 10, 100L, 5L);
      bTree.put(atomicOperation, key, new LinkBagValue(42, 0, 0, false));

      // Same-ts overwrite
      bTree.put(atomicOperation, key, new LinkBagValue(99, 0, 0, false));

      // Value updated
      assertThat(bTree.get(key, atomicOperation).counter()).isEqualTo(99);

      // No snapshot entry was created for the same-ts overwrite
      // (checking local buffer only — fresh atomic operation has no residual)
      final int componentId = (int) bTree.getFileId();
      var snapshotKey = new EdgeSnapshotKey(componentId, 500L, 10, 100L, 5L);
      var visKey = new EdgeVisibilityKey(5L, componentId, 500L, 10, 100L);
      assertThat(atomicOperation.containsEdgeVisibilityEntry(visKey)).isFalse();
    });
  }

  // ---- Multiple independent edges ----

  @Test
  public void testIndependentEdgesSnapshotPreservation() throws Exception {
    // Multiple edges inserted, then some updated in cross-tx fashion.
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      bTree.put(atomicOperation, new EdgeKey(600L, 10, 100L, 1L),
          new LinkBagValue(10, 0, 0, false));
      bTree.put(atomicOperation, new EdgeKey(600L, 20, 200L, 1L),
          new LinkBagValue(20, 0, 0, false));
      bTree.put(atomicOperation, new EdgeKey(700L, 10, 100L, 1L),
          new LinkBagValue(30, 0, 0, false));
    });

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      // Update first and third edges with new ts
      bTree.put(atomicOperation, new EdgeKey(600L, 10, 100L, 5L),
          new LinkBagValue(11, 0, 0, false));
      bTree.put(atomicOperation, new EdgeKey(700L, 10, 100L, 5L),
          new LinkBagValue(31, 0, 0, false));

      final int componentId = (int) bTree.getFileId();

      // First edge: old value (ts=1, counter=10) preserved
      var snap1 = new EdgeSnapshotKey(componentId, 600L, 10, 100L, 1L);
      assertThat(atomicOperation.getEdgeSnapshotEntry(snap1)).isNotNull();
      assertThat(atomicOperation.getEdgeSnapshotEntry(snap1).counter()).isEqualTo(10);

      // Second edge: NOT updated, still in B-tree with ts=1
      var current2 = bTree.findCurrentEntry(atomicOperation, 600L, 20, 200L);
      assertThat(current2).isNotNull();
      assertThat(current2.first().ts).isEqualTo(1L);

      // Third edge: old value (ts=1, counter=30) preserved
      var snap3 = new EdgeSnapshotKey(componentId, 700L, 10, 100L, 1L);
      assertThat(atomicOperation.getEdgeSnapshotEntry(snap3)).isNotNull();
      assertThat(atomicOperation.getEdgeSnapshotEntry(snap3).counter()).isEqualTo(30);
    });
  }

  // ---- Edge cases ----

  @Test
  public void testCrossTransactionUpdateOnTombstone() throws Exception {
    // Updating a tombstone entry should also preserve it in the snapshot index.
    atomicOperationsManager.executeInsideAtomicOperation(
        atomicOperation -> bTree.put(atomicOperation, new EdgeKey(800L, 10, 100L, 5L),
            new LinkBagValue(42, 0, 0, true)));

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      bTree.put(atomicOperation, new EdgeKey(800L, 10, 100L, 10L),
          new LinkBagValue(99, 0, 0, false));

      final int componentId = (int) bTree.getFileId();
      var snapshotKey = new EdgeSnapshotKey(componentId, 800L, 10, 100L, 5L);
      var snapshotValue = atomicOperation.getEdgeSnapshotEntry(snapshotKey);
      assertThat(snapshotValue).isNotNull();
      assertThat(snapshotValue.tombstone()).isTrue();
      assertThat(snapshotValue.counter()).isEqualTo(42);
    });
  }

  @Test
  public void testCrossTransactionUpdateAfterBucketSplits() throws Exception {
    // Insert many entries to trigger splits, then update some with new ts.
    final int entryCount = 200;

    for (int i = 0; i < entryCount; i++) {
      final int idx = i;
      atomicOperationsManager.executeInsideAtomicOperation(
          atomicOperation -> bTree.put(atomicOperation, new EdgeKey(900L, idx, idx, 1L),
              new LinkBagValue(idx, 0, 0, false)));
    }

    // Update every 10th entry with new ts
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      final int componentId = (int) bTree.getFileId();
      for (int i = 0; i < entryCount; i += 10) {
        bTree.put(atomicOperation, new EdgeKey(900L, i, i, 5L),
            new LinkBagValue(i + 1000, 0, 0, false));

        var snapshotKey = new EdgeSnapshotKey(componentId, 900L, i, i, 1L);
        assertThat(atomicOperation.getEdgeSnapshotEntry(snapshotKey))
            .as("Snapshot for entry %d", i)
            .isNotNull();
        assertThat(atomicOperation.getEdgeSnapshotEntry(snapshotKey).counter())
            .isEqualTo(i);

        var current = bTree.findCurrentEntry(atomicOperation, 900L, i, i);
        assertThat(current.first().ts).isEqualTo(5L);
        assertThat(current.second().counter()).isEqualTo(i + 1000);
      }
    });
  }
}
