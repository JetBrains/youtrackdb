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
 * Tests for the snapshot-isolation aware remove() in {@link SharedLinkBagBTree}.
 * Verifies that cross-transaction removes preserve old values in the snapshot
 * index and insert tombstone entries, same-transaction removes replace the value
 * in place, and removing non-existent entries returns null.
 *
 * <p>Cross-transaction removes are tested across separate atomic operations
 * (matching production semantics: the initial insert is committed first, then
 * the remove happens in a new transaction).
 */
public class SharedLinkBagBTreeRemoveSITest {

  private static final String DB_NAME = "removeSITest";
  private static final String DIR_NAME = "/removeSITest";

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
    bTree = new SharedLinkBagBTree(storage, "removeSI", ".sbc");
    atomicOperationsManager.executeInsideAtomicOperation(
        atomicOperation -> bTree.create(atomicOperation));
  }

  @After
  public void afterMethod() throws Exception {
    atomicOperationsManager.executeInsideAtomicOperation(
        atomicOperation -> bTree.delete(atomicOperation));
  }

  // ---- Remove non-existent entry ----

  @Test
  public void testRemoveNonExistentReturnsNull() throws Exception {
    // Removing an entry that doesn't exist should return null.
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var result = bTree.remove(atomicOperation, new EdgeKey(100L, 10, 100L, 5L));
      assertThat(result).isNull();
    });
  }

  // ---- Cross-transaction remove (different ts, separate atomic operations) ----

  @Test
  public void testCrossTransactionRemoveCreatesTombstone() throws Exception {
    // Insert with ts=5, then remove with ts=10 in a separate atomic op.
    // The B-tree should contain a tombstone entry with ts=10.
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      bTree.put(atomicOperation, new EdgeKey(200L, 10, 100L, 5L),
          new LinkBagValue(42, 0, 0, false));
    });

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var oldValue = bTree.remove(atomicOperation, new EdgeKey(200L, 10, 100L, 10L));

      // Returns the old (live) value
      assertThat(oldValue).isNotNull();
      assertThat(oldValue.counter()).isEqualTo(42);
      assertThat(oldValue.tombstone()).isFalse();

      // Verify tombstone is in the B-tree with the new ts
      var current = bTree.findCurrentEntry(atomicOperation, 200L, 10, 100L);
      assertThat(current).isNotNull();
      assertThat(current.first().ts).isEqualTo(10L);
      assertThat(current.second().tombstone()).isTrue();
      assertThat(current.second().counter()).isEqualTo(42);
    });
  }

  @Test
  public void testCrossTransactionRemovePreservesSnapshot() throws Exception {
    // Insert with ts=5, then remove with ts=10 in a separate atomic op.
    // The old value (ts=5) should be preserved in the snapshot index.
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      bTree.put(atomicOperation, new EdgeKey(300L, 10, 100L, 5L),
          new LinkBagValue(42, 3, 77, false));
    });

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      bTree.remove(atomicOperation, new EdgeKey(300L, 10, 100L, 10L));

      // Verify old value is preserved in snapshot index
      final int componentId = (int) bTree.getFileId();
      var snapshotKey = new EdgeSnapshotKey(componentId, 300L, 10, 100L, 5L);
      var snapshotValue = atomicOperation.getEdgeSnapshotEntry(snapshotKey);
      assertThat(snapshotValue).isNotNull();
      assertThat(snapshotValue.counter()).isEqualTo(42);
      assertThat(snapshotValue.secondaryCollectionId()).isEqualTo(3);
      assertThat(snapshotValue.secondaryPosition()).isEqualTo(77);
      assertThat(snapshotValue.tombstone()).isFalse();

      // Verify visibility index entry was created
      var visKey = new EdgeVisibilityKey(5L, componentId, 300L, 10, 100L);
      assertThat(atomicOperation.containsEdgeVisibilityEntry(visKey)).isTrue();
    });
  }

  @Test
  public void testCrossTransactionRemoveTreeSizeUnchanged() throws Exception {
    // Insert two entries, then remove one with a new ts. Tree size stays at 2
    // because the live entry is replaced by a tombstone (not physically deleted).
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      bTree.put(atomicOperation, new EdgeKey(400L, 10, 100L, 5L),
          new LinkBagValue(1, 0, 0, false));
      bTree.put(atomicOperation, new EdgeKey(400L, 20, 200L, 5L),
          new LinkBagValue(2, 0, 0, false));
    });

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      bTree.remove(atomicOperation, new EdgeKey(400L, 10, 100L, 10L));

      // Both entries still present (one is a tombstone, one is live)
      var entry1 = bTree.findCurrentEntry(atomicOperation, 400L, 10, 100L);
      assertThat(entry1).isNotNull();
      assertThat(entry1.second().tombstone()).isTrue();

      var entry2 = bTree.findCurrentEntry(atomicOperation, 400L, 20, 200L);
      assertThat(entry2).isNotNull();
      assertThat(entry2.second().tombstone()).isFalse();

      // Old key (ts=5) should NOT be in the tree for the removed edge
      assertThat(bTree.get(new EdgeKey(400L, 10, 100L, 5L), atomicOperation)).isNull();
    });
  }

  // ---- Same-transaction remove (same ts) ----

  @Test
  public void testSameTransactionRemovePhysicalDelete() throws Exception {
    // Insert and then remove within the same atomic operation (same ts).
    // The entry is physically deleted (no tombstone) because the caller
    // created it in this transaction — no other transaction can see it.
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var key = new EdgeKey(500L, 10, 100L, 5L);
      bTree.put(atomicOperation, key, new LinkBagValue(42, 0, 0, false));

      // Same-ts remove
      var oldValue = bTree.remove(atomicOperation, key);
      assertThat(oldValue).isNotNull();
      assertThat(oldValue.counter()).isEqualTo(42);
      assertThat(oldValue.tombstone()).isFalse();

      // Entry is physically gone from the B-tree
      var current = bTree.findCurrentEntry(atomicOperation, 500L, 10, 100L);
      assertThat(current).isNull();

      // No snapshot entry was created
      final int componentId = (int) bTree.getFileId();
      var visKey = new EdgeVisibilityKey(5L, componentId, 500L, 10, 100L);
      assertThat(atomicOperation.containsEdgeVisibilityEntry(visKey)).isFalse();
    });
  }

  // ---- Verify tombstone data fidelity ----

  @Test
  public void testTombstonePreservesEdgeData() throws Exception {
    // The tombstone entry should preserve counter, secondaryCollectionId,
    // and secondaryPosition from the original value.
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      bTree.put(atomicOperation, new EdgeKey(600L, 15, 250L, 3L),
          new LinkBagValue(99, 7, 42, false));
    });

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      bTree.remove(atomicOperation, new EdgeKey(600L, 15, 250L, 8L));

      var current = bTree.findCurrentEntry(atomicOperation, 600L, 15, 250L);
      assertThat(current).isNotNull();
      assertThat(current.second().tombstone()).isTrue();
      assertThat(current.second().counter()).isEqualTo(99);
      assertThat(current.second().secondaryCollectionId()).isEqualTo(7);
      assertThat(current.second().secondaryPosition()).isEqualTo(42);
    });
  }

  // ---- Multiple removes ----

  @Test
  public void testMultipleCrossTransactionRemoves() throws Exception {
    // Insert, remove (creating tombstone), then put again (re-creating),
    // then remove again. Each step in a separate atomic operation.
    atomicOperationsManager.executeInsideAtomicOperation(
        atomicOperation -> bTree.put(atomicOperation, new EdgeKey(700L, 10, 100L, 1L),
            new LinkBagValue(10, 0, 0, false)));

    // First remove at ts=5
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      bTree.remove(atomicOperation, new EdgeKey(700L, 10, 100L, 5L));

      final int componentId = (int) bTree.getFileId();
      var snap1 = new EdgeSnapshotKey(componentId, 700L, 10, 100L, 1L);
      assertThat(atomicOperation.getEdgeSnapshotEntry(snap1)).isNotNull();
      assertThat(atomicOperation.getEdgeSnapshotEntry(snap1).counter()).isEqualTo(10);
      assertThat(atomicOperation.getEdgeSnapshotEntry(snap1).tombstone()).isFalse();
    });

    // Re-insert (put on top of tombstone) at ts=10
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      bTree.put(atomicOperation, new EdgeKey(700L, 10, 100L, 10L),
          new LinkBagValue(20, 0, 0, false));

      // The tombstone (ts=5) should now be in snapshot index
      final int componentId = (int) bTree.getFileId();
      var snap5 = new EdgeSnapshotKey(componentId, 700L, 10, 100L, 5L);
      assertThat(atomicOperation.getEdgeSnapshotEntry(snap5)).isNotNull();
      assertThat(atomicOperation.getEdgeSnapshotEntry(snap5).tombstone()).isTrue();

      var current = bTree.findCurrentEntry(atomicOperation, 700L, 10, 100L);
      assertThat(current.first().ts).isEqualTo(10L);
      assertThat(current.second().tombstone()).isFalse();
    });

    // Second remove at ts=15
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      bTree.remove(atomicOperation, new EdgeKey(700L, 10, 100L, 15L));

      final int componentId = (int) bTree.getFileId();
      var snap10 = new EdgeSnapshotKey(componentId, 700L, 10, 100L, 10L);
      assertThat(atomicOperation.getEdgeSnapshotEntry(snap10)).isNotNull();
      assertThat(atomicOperation.getEdgeSnapshotEntry(snap10).counter()).isEqualTo(20);
      assertThat(atomicOperation.getEdgeSnapshotEntry(snap10).tombstone()).isFalse();

      var current = bTree.findCurrentEntry(atomicOperation, 700L, 10, 100L);
      assertThat(current.first().ts).isEqualTo(15L);
      assertThat(current.second().tombstone()).isTrue();
    });
  }

  // ---- Remove after bucket splits ----

  @Test
  public void testCrossTransactionRemoveAfterBucketSplits() throws Exception {
    // Insert many entries to trigger bucket splits, then remove some with a
    // new ts. Verifies tombstone insertion works correctly after splits.
    final int entryCount = 200;

    for (int i = 0; i < entryCount; i++) {
      final int idx = i;
      atomicOperationsManager.executeInsideAtomicOperation(
          atomicOperation -> bTree.put(atomicOperation, new EdgeKey(800L, idx, idx, 1L),
              new LinkBagValue(idx, 0, 0, false)));
    }

    // Remove every 10th entry with new ts
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      final int componentId = (int) bTree.getFileId();
      for (int i = 0; i < entryCount; i += 10) {
        var oldValue = bTree.remove(
            atomicOperation, new EdgeKey(800L, i, i, 5L));

        assertThat(oldValue)
            .as("Old value for entry %d", i)
            .isNotNull();
        assertThat(oldValue.counter()).isEqualTo(i);

        // Verify snapshot preserved
        var snapshotKey = new EdgeSnapshotKey(componentId, 800L, i, i, 1L);
        assertThat(atomicOperation.getEdgeSnapshotEntry(snapshotKey))
            .as("Snapshot for entry %d", i)
            .isNotNull();

        // Verify tombstone in tree
        var current = bTree.findCurrentEntry(atomicOperation, 800L, i, i);
        assertThat(current.first().ts).isEqualTo(5L);
        assertThat(current.second().tombstone()).isTrue();
      }

      // Non-removed entries are still live
      for (int i = 1; i < entryCount; i++) {
        if (i % 10 != 0) {
          var current = bTree.findCurrentEntry(atomicOperation, 800L, i, i);
          assertThat(current)
              .as("Entry %d should still be live", i)
              .isNotNull();
          assertThat(current.second().tombstone()).isFalse();
        }
      }
    });
  }
}
