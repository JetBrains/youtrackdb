package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.common.serialization.types.UTF8Serializer;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import java.io.File;
import java.util.stream.Collectors;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for BTree public read methods: get (optimistic path), firstKey, lastKey,
 * iterateEntriesMinor, iterateEntriesMajor, iterateEntriesBetween, keyStream,
 * allEntries, and assertFreePages.
 *
 * <p>Uses DISK storage so the optimistic read paths through LockFreeReadCache
 * can be exercised. Data is written in one atomic operation and read in separate
 * atomic operations.
 */
public class BTreeReadMethodsTest {

  private static final int ENTRY_COUNT = 10;

  private AtomicOperationsManager atomicOperationsManager;
  private BTree<String> tree;
  private YouTrackDBImpl youTrackDB;
  private String dbName;
  private String buildDirectory;

  @Before
  public void before() throws Exception {
    buildDirectory =
        System.getProperty("buildDirectory", ".")
            + File.separator
            + BTreeReadMethodsTest.class.getSimpleName();

    dbName = "btreeReadMethodsTest";
    var dbDirectory = new File(buildDirectory, dbName);
    FileUtils.deleteRecursively(dbDirectory);

    youTrackDB = (YouTrackDBImpl) YourTracks.instance(buildDirectory);
    youTrackDB.create(dbName, DatabaseType.DISK, "admin", "admin", "admin");

    AbstractStorage storage;
    try (var session = youTrackDB.open(dbName, "admin", "admin")) {
      storage = session.getStorage();
    }
    tree = new BTree<>("readMethodsTree", ".sbt", ".nbt", storage);
    atomicOperationsManager = storage.getAtomicOperationsManager();
    atomicOperationsManager.executeInsideAtomicOperation(
        atomicOperation -> tree.create(atomicOperation, UTF8Serializer.INSTANCE, null, 1));
  }

  @After
  public void after() {
    youTrackDB.drop(dbName);
    youTrackDB.close();
    FileUtils.deleteRecursively(new File(buildDirectory));
  }

  /**
   * Populates the tree with ENTRY_COUNT entries (keys "key00".."key09",
   * values RID(i%32000, i)) in a separate atomic operation.
   */
  private void populateTree() throws Exception {
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      for (int i = 0; i < ENTRY_COUNT; i++) {
        var key = String.format("key%02d", i);
        tree.put(atomicOperation, key, new RecordId(i % 32000, i));
      }
    });
  }

  /**
   * Warms the read cache by reading all entries through the pinned path,
   * so the next read in a new atomic operation can exercise the optimistic path.
   */
  private void warmReadCache() throws Exception {
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      for (int i = 0; i < ENTRY_COUNT; i++) {
        tree.get(String.format("key%02d", i), atomicOperation);
      }
    });
  }

  // ---- get (optimistic path) ----

  @Test
  public void testGetExistingKeyOptimistic() throws Exception {
    // After warming cache, get() should find the key via the optimistic path.
    populateTree();
    warmReadCache();

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var result = tree.get("key05", atomicOperation);
      assertThat(result).isNotNull();
      assertThat(result.getCollectionId()).isEqualTo(5);
      assertThat(result.getCollectionPosition()).isEqualTo(5);
    });
  }

  @Test
  public void testGetNonExistingKeyOptimistic() throws Exception {
    // After warming cache, get() should return null for a missing key.
    populateTree();
    warmReadCache();

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var result = tree.get("nonexistent", atomicOperation);
      assertThat(result).isNull();
    });
  }

  @Test
  public void testGetNullKeyOptimistic() throws Exception {
    // Test the null-key optimistic path. Insert a null-key value, warm cache, read.
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      tree.put(atomicOperation, null, new RecordId(1, 42));
    });

    // Warm cache for null key page
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      tree.get(null, atomicOperation);
    });

    // Second read should use optimistic path
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var result = tree.get(null, atomicOperation);
      assertThat(result).isNotNull();
      assertThat(result.getCollectionPosition()).isEqualTo(42);
    });
  }

  @Test
  public void testGetNullKeyWhenEmpty() throws Exception {
    // get(null) should return null when no null key exists.
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var result = tree.get(null, atomicOperation);
      assertThat(result).isNull();
    });
  }

  // ---- firstKey / lastKey ----

  @Test
  public void testFirstKeyOnEmptyTree() throws Exception {
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      assertThat(tree.firstKey(atomicOperation)).isNull();
    });
  }

  @Test
  public void testFirstKeyReturnsSmallestKey() throws Exception {
    populateTree();

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var result = tree.firstKey(atomicOperation);
      assertThat(result).isEqualTo("key00");
    });
  }

  @Test
  public void testLastKeyOnEmptyTree() throws Exception {
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      assertThat(tree.lastKey(atomicOperation)).isNull();
    });
  }

  @Test
  public void testLastKeyReturnsLargestKey() throws Exception {
    populateTree();

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var result = tree.lastKey(atomicOperation);
      assertThat(result).isEqualTo("key09");
    });
  }

  // ---- iterateEntriesMinor ----

  @Test
  public void testIterateEntriesMinorAscending() throws Exception {
    // Entries with key < "key05", ascending order.
    populateTree();

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var entries = tree.iterateEntriesMinor("key05", false, true, atomicOperation)
          .collect(Collectors.toList());
      // keys: key00, key01, key02, key03, key04
      assertThat(entries).hasSize(5);
      assertThat(entries.get(0).first()).isEqualTo("key00");
      assertThat(entries.get(4).first()).isEqualTo("key04");
    });
  }

  @Test
  public void testIterateEntriesMinorDescending() throws Exception {
    // Entries with key <= "key05", descending order.
    populateTree();

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var entries = tree.iterateEntriesMinor("key05", true, false, atomicOperation)
          .collect(Collectors.toList());
      // keys: key05, key04, key03, key02, key01, key00
      assertThat(entries).hasSize(6);
      assertThat(entries.get(0).first()).isEqualTo("key05");
      assertThat(entries.get(5).first()).isEqualTo("key00");
    });
  }

  // ---- iterateEntriesMajor ----

  @Test
  public void testIterateEntriesMajorAscending() throws Exception {
    // Entries with key > "key05", ascending order.
    populateTree();

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var entries = tree.iterateEntriesMajor("key05", false, true, atomicOperation)
          .collect(Collectors.toList());
      // keys: key06, key07, key08, key09
      assertThat(entries).hasSize(4);
      assertThat(entries.get(0).first()).isEqualTo("key06");
      assertThat(entries.get(3).first()).isEqualTo("key09");
    });
  }

  @Test
  public void testIterateEntriesMajorDescending() throws Exception {
    // Entries with key >= "key05", descending order.
    populateTree();

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var entries = tree.iterateEntriesMajor("key05", true, false, atomicOperation)
          .collect(Collectors.toList());
      // keys: key09, key08, key07, key06, key05
      assertThat(entries).hasSize(5);
      assertThat(entries.get(0).first()).isEqualTo("key09");
      assertThat(entries.get(4).first()).isEqualTo("key05");
    });
  }

  // ---- iterateEntriesBetween ----

  @Test
  public void testIterateEntriesBetweenAscending() throws Exception {
    populateTree();

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var entries = tree.iterateEntriesBetween(
          "key02", true, "key07", true, true, atomicOperation)
          .collect(Collectors.toList());
      assertThat(entries).hasSize(6);
      assertThat(entries.get(0).first()).isEqualTo("key02");
      assertThat(entries.get(5).first()).isEqualTo("key07");
    });
  }

  @Test
  public void testIterateEntriesBetweenDescending() throws Exception {
    populateTree();

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var entries = tree.iterateEntriesBetween(
          "key02", true, "key07", true, false, atomicOperation)
          .collect(Collectors.toList());
      assertThat(entries).hasSize(6);
      assertThat(entries.get(0).first()).isEqualTo("key07");
      assertThat(entries.get(5).first()).isEqualTo("key02");
    });
  }

  // ---- keyStream / allEntries ----

  @Test
  public void testKeyStream() throws Exception {
    populateTree();

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var keys = tree.keyStream(atomicOperation).collect(Collectors.toList());
      assertThat(keys).hasSize(ENTRY_COUNT);
      assertThat(keys.get(0)).isEqualTo("key00");
      assertThat(keys.get(ENTRY_COUNT - 1)).isEqualTo("key09");
    });
  }

  @Test
  public void testAllEntries() throws Exception {
    populateTree();

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var entries = tree.allEntries(atomicOperation).collect(Collectors.toList());
      assertThat(entries).hasSize(ENTRY_COUNT);
      assertThat(entries.get(0).first()).isEqualTo("key00");
      assertThat(entries.get(0).second().getCollectionPosition()).isEqualTo(0);
    });
  }

  // ---- assertFreePages ----

  @Test
  public void testAssertFreePagesOnPopulatedTree() throws Exception {
    // assertFreePages should not throw on a valid tree with no orphaned pages.
    populateTree();

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      tree.assertFreePages(atomicOperation);
      // Verify the tree is actually populated to confirm assertFreePages
      // ran against a non-trivial tree.
      var entries = tree.allEntries(atomicOperation).collect(Collectors.toList());
      assertThat(entries).hasSize(ENTRY_COUNT);
    });
  }

  @Test
  public void testAssertFreePagesAfterInsertAndDelete() throws Exception {
    // After inserting and deleting entries, free pages should still be consistent.
    populateTree();

    // Delete some entries
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      for (int i = 0; i < 5; i++) {
        tree.remove(atomicOperation, String.format("key%02d", i));
      }
    });

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      tree.assertFreePages(atomicOperation);
      // Verify remaining entries to confirm the tree state is non-trivial.
      var entries = tree.allEntries(atomicOperation).collect(Collectors.toList());
      assertThat(entries).hasSize(ENTRY_COUNT - 5);
    });
  }
}
