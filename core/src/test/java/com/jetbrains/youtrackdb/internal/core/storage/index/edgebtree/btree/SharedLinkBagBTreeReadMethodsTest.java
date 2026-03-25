package com.jetbrains.youtrackdb.internal.core.storage.index.edgebtree.btree;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.EdgeKey;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.LinkBagValue;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.SharedLinkBagBTree;
import java.io.File;
import java.util.ArrayList;
import java.util.stream.Collectors;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Tests for SharedLinkBagBTree public read methods that were previously
 * untested: firstKey, lastKey, get, iterateEntriesMinor, iterateEntriesMajor,
 * streamEntriesBetween, and spliteratorEntriesBetween.
 *
 * <p>Uses DISK storage so the LockFreeReadCache can return PageFrame objects,
 * allowing the optimistic read paths to be exercised. Data is written in one
 * atomic operation and read in separate atomic operations, so the optimistic
 * path is not bypassed by hasChangesForPage().
 */
public class SharedLinkBagBTreeReadMethodsTest {

  private static final String DB_NAME = "sharedLinkBagReadMethodsTest";
  private static final String DIR_NAME = "/sharedLinkBagReadMethodsTest";

  private static YouTrackDBImpl youTrackDB;
  private static AtomicOperationsManager atomicOperationsManager;
  private static AbstractStorage storage;
  private static String buildDirectory;

  private SharedLinkBagBTree bTree;

  // Pre-inserted keys (ridBagId=1, targetCollection=10, positions 100..104, ts=42)
  private static final long RID_BAG_ID = 1L;
  private static final int TARGET_COLLECTION = 10;
  private static final long TS = 42L;
  private static final int ENTRY_COUNT = 5;

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
    bTree = new SharedLinkBagBTree(storage, "readMethods", ".sbc");
    atomicOperationsManager.executeInsideAtomicOperation(
        atomicOperation -> bTree.create(atomicOperation));
  }

  @After
  public void afterMethod() throws Exception {
    atomicOperationsManager.executeInsideAtomicOperation(
        atomicOperation -> bTree.delete(atomicOperation));
  }

  /**
   * Populates the B-tree with ENTRY_COUNT entries in a separate atomic operation.
   * Each entry has ridBagId=1, targetCollection=10, positions [100..104], ts=42.
   */
  private void populateTree() throws Exception {
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      for (int i = 0; i < ENTRY_COUNT; i++) {
        var key = new EdgeKey(RID_BAG_ID, TARGET_COLLECTION, 100L + i, TS);
        var value = new LinkBagValue(i + 1, 0, 0, false);
        bTree.put(atomicOperation, key, value);
      }
    });
  }

  /**
   * Warms the read cache by reading all entries through the pinned path.
   * This ensures that subsequent reads in a new atomic operation can exercise
   * the optimistic read path.
   */
  private void warmReadCache() throws Exception {
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      for (int i = 0; i < ENTRY_COUNT; i++) {
        bTree.findCurrentEntry(atomicOperation, RID_BAG_ID, TARGET_COLLECTION, 100L + i);
      }
    });
  }

  // ---- firstKey ----

  @Test
  public void testFirstKeyOnEmptyTree() throws Exception {
    // firstKey on an empty tree should return null.
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var result = bTree.firstKey(atomicOperation);
      assertThat(result).isNull();
    });
  }

  @Test
  public void testFirstKeyReturnsSmallestEntry() throws Exception {
    // firstKey should return the entry with the smallest EdgeKey.
    populateTree();

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var result = bTree.firstKey(atomicOperation);
      assertThat(result).isNotNull();
      assertThat(result.ridBagId).isEqualTo(RID_BAG_ID);
      assertThat(result.targetCollection).isEqualTo(TARGET_COLLECTION);
      assertThat(result.targetPosition).isEqualTo(100L);
      assertThat(result.ts).isEqualTo(TS);
    });
  }

  // ---- lastKey ----

  @Test
  public void testLastKeyOnEmptyTree() throws Exception {
    // lastKey on an empty tree should return null.
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var result = bTree.lastKey(atomicOperation);
      assertThat(result).isNull();
    });
  }

  @Test
  public void testLastKeyReturnsLargestEntry() throws Exception {
    // lastKey should return the entry with the largest EdgeKey.
    populateTree();

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var result = bTree.lastKey(atomicOperation);
      assertThat(result).isNotNull();
      assertThat(result.ridBagId).isEqualTo(RID_BAG_ID);
      assertThat(result.targetCollection).isEqualTo(TARGET_COLLECTION);
      assertThat(result.targetPosition).isEqualTo(104L);
      assertThat(result.ts).isEqualTo(TS);
    });
  }

  // ---- get ----

  @Test
  public void testGetExistingKey() throws Exception {
    // get() should return the value for an existing key.
    populateTree();

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var key = new EdgeKey(RID_BAG_ID, TARGET_COLLECTION, 102L, TS);
      var result = bTree.get(key, atomicOperation);
      assertThat(result).isNotNull();
      assertThat(result.counter()).isEqualTo(3); // i=2 → value(3,0,0,false)
    });
  }

  @Test
  public void testGetNonExistingKey() throws Exception {
    // get() should return null for a key that doesn't exist.
    populateTree();

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var key = new EdgeKey(RID_BAG_ID, TARGET_COLLECTION, 999L, TS);
      var result = bTree.get(key, atomicOperation);
      assertThat(result).isNull();
    });
  }

  @Test
  public void testGetWithWarmCache() throws Exception {
    // After warming the read cache, the optimistic path should be exercised.
    populateTree();
    warmReadCache();

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var key = new EdgeKey(RID_BAG_ID, TARGET_COLLECTION, 101L, TS);
      var result = bTree.get(key, atomicOperation);
      assertThat(result).isNotNull();
      assertThat(result.counter()).isEqualTo(2); // i=1 → value(2,0,0,false)
    });
  }

  // ---- findCurrentEntry with warm cache (optimistic path) ----

  @Test
  public void testFindCurrentEntryOptimisticPath() throws Exception {
    // After warming the cache, findCurrentEntry should exercise the optimistic path.
    populateTree();
    warmReadCache();

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var result = bTree.findCurrentEntry(
          atomicOperation, RID_BAG_ID, TARGET_COLLECTION, 103L);
      assertThat(result).isNotNull();
      assertThat(result.first().ridBagId).isEqualTo(RID_BAG_ID);
      assertThat(result.first().targetCollection).isEqualTo(TARGET_COLLECTION);
      assertThat(result.first().targetPosition).isEqualTo(103L);
      assertThat(result.second().counter()).isEqualTo(4); // i=3 → value(4,0,0,false)
      assertThat(result.second().tombstone()).isFalse();
    });
  }

  @Test
  public void testFindCurrentEntryOptimisticNotFound() throws Exception {
    // Optimistic path should return null for a non-existing 3-tuple.
    populateTree();
    warmReadCache();

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var result = bTree.findCurrentEntry(
          atomicOperation, RID_BAG_ID, TARGET_COLLECTION, 999L);
      assertThat(result).isNull();
    });
  }

  // ---- iterateEntriesMinor ----

  @Test
  public void testIterateEntriesMinorAscending() throws Exception {
    // iterateEntriesMinor ascending: entries with key < pivot, sorted ascending.
    populateTree();

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var pivot = new EdgeKey(RID_BAG_ID, TARGET_COLLECTION, 103L, TS);
      var entries = bTree.iterateEntriesMinor(pivot, false, true, atomicOperation)
          .collect(Collectors.toList());
      // Entries with position 100, 101, 102 (all < 103)
      assertThat(entries).hasSize(3);
      assertThat(entries.get(0).first().targetPosition).isEqualTo(100L);
      assertThat(entries.get(0).second().counter()).isEqualTo(1);
      assertThat(entries.get(2).first().targetPosition).isEqualTo(102L);
      assertThat(entries.get(2).second().counter()).isEqualTo(3);
    });
  }

  @Test
  public void testIterateEntriesMinorDescending() throws Exception {
    // iterateEntriesMinor descending: entries with key <= pivot, sorted descending.
    populateTree();

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var pivot = new EdgeKey(RID_BAG_ID, TARGET_COLLECTION, 103L, TS);
      var entries = bTree.iterateEntriesMinor(pivot, true, false, atomicOperation)
          .collect(Collectors.toList());
      // Entries with position <= 103: 100, 101, 102, 103 — in descending order
      assertThat(entries).hasSize(4);
      assertThat(entries.get(0).first().targetPosition).isEqualTo(103L);
      assertThat(entries.get(3).first().targetPosition).isEqualTo(100L);
    });
  }

  // ---- iterateEntriesMajor ----

  @Test
  public void testIterateEntriesMajorAscending() throws Exception {
    // iterateEntriesMajor ascending: entries with key > pivot, sorted ascending.
    populateTree();

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var pivot = new EdgeKey(RID_BAG_ID, TARGET_COLLECTION, 101L, TS);
      var entries = bTree.iterateEntriesMajor(pivot, false, true, atomicOperation)
          .collect(Collectors.toList());
      // Entries with position > 101: 102, 103, 104
      assertThat(entries).hasSize(3);
      assertThat(entries.get(0).first().targetPosition).isEqualTo(102L);
      assertThat(entries.get(2).first().targetPosition).isEqualTo(104L);
    });
  }

  @Test
  public void testIterateEntriesMajorDescending() throws Exception {
    // iterateEntriesMajor descending: entries with key >= pivot, sorted descending.
    populateTree();

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var pivot = new EdgeKey(RID_BAG_ID, TARGET_COLLECTION, 101L, TS);
      var entries = bTree.iterateEntriesMajor(pivot, true, false, atomicOperation)
          .collect(Collectors.toList());
      // Entries with position >= 101: 101, 102, 103, 104 — in descending order
      assertThat(entries).hasSize(4);
      assertThat(entries.get(0).first().targetPosition).isEqualTo(104L);
      assertThat(entries.get(3).first().targetPosition).isEqualTo(101L);
    });
  }

  // ---- streamEntriesBetween ----

  @Test
  public void testStreamEntriesBetweenAscending() throws Exception {
    // streamEntriesBetween ascending: entries in [keyFrom, keyTo], sorted ascending.
    populateTree();

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var from = new EdgeKey(RID_BAG_ID, TARGET_COLLECTION, 101L, TS);
      var to = new EdgeKey(RID_BAG_ID, TARGET_COLLECTION, 103L, TS);
      var entries = bTree.streamEntriesBetween(from, true, to, true, true, atomicOperation)
          .collect(Collectors.toList());
      assertThat(entries).hasSize(3);
      assertThat(entries.get(0).first().targetPosition).isEqualTo(101L);
      assertThat(entries.get(2).first().targetPosition).isEqualTo(103L);
    });
  }

  @Test
  public void testStreamEntriesBetweenDescending() throws Exception {
    // streamEntriesBetween descending: entries in [keyFrom, keyTo], sorted descending.
    populateTree();

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var from = new EdgeKey(RID_BAG_ID, TARGET_COLLECTION, 101L, TS);
      var to = new EdgeKey(RID_BAG_ID, TARGET_COLLECTION, 103L, TS);
      var entries = bTree.streamEntriesBetween(from, true, to, true, false, atomicOperation)
          .collect(Collectors.toList());
      assertThat(entries).hasSize(3);
      assertThat(entries.get(0).first().targetPosition).isEqualTo(103L);
      assertThat(entries.get(2).first().targetPosition).isEqualTo(101L);
    });
  }

  // ---- spliteratorEntriesBetween ----

  @Test
  public void testSpliteratorEntriesBetween() throws Exception {
    // spliteratorEntriesBetween should produce the same entries as streamEntriesBetween.
    populateTree();

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var from = new EdgeKey(RID_BAG_ID, TARGET_COLLECTION, 100L, TS);
      var to = new EdgeKey(RID_BAG_ID, TARGET_COLLECTION, 104L, TS);
      var spliterator =
          bTree.spliteratorEntriesBetween(from, true, to, true, true, atomicOperation);

      var entries = new ArrayList<RawPair<EdgeKey, LinkBagValue>>();
      spliterator.forEachRemaining(entries::add);
      assertThat(entries).hasSize(ENTRY_COUNT);
      assertThat(entries.get(0).first().targetPosition).isEqualTo(100L);
      assertThat(entries.get(0).second().counter()).isEqualTo(1);
      assertThat(entries.get(ENTRY_COUNT - 1).first().targetPosition).isEqualTo(104L);
      assertThat(entries.get(ENTRY_COUNT - 1).second().counter()).isEqualTo(5);
    });
  }

  @Test
  public void testSpliteratorEntriesBetweenDescending() throws Exception {
    // spliteratorEntriesBetween descending order.
    populateTree();

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var from = new EdgeKey(RID_BAG_ID, TARGET_COLLECTION, 100L, TS);
      var to = new EdgeKey(RID_BAG_ID, TARGET_COLLECTION, 104L, TS);
      var spliterator =
          bTree.spliteratorEntriesBetween(from, true, to, true, false, atomicOperation);

      var entries = new ArrayList<RawPair<EdgeKey, LinkBagValue>>();
      spliterator.forEachRemaining(entries::add);
      assertThat(entries).hasSize(ENTRY_COUNT);
      // First entry should be the largest
      assertThat(entries.get(0).first().targetPosition).isEqualTo(104L);
    });
  }
}
