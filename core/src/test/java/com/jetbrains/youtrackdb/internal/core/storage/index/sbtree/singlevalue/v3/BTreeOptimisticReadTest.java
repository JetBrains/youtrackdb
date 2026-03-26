package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.common.serialization.types.UTF8Serializer;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import java.io.File;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Tests for the optimistic (lock-free) read paths in {@link BTree}.
 *
 * <p>The optimistic read paths are exercised when pages reside in the
 * {@code LockFreeReadCache} and no exclusive locks or uncommitted atomic
 * operation changes exist. This test inserts data in one committed atomic
 * operation, then reads it back in a separate (clean) atomic operation so
 * that the optimistic path succeeds.
 *
 * <p>Uses {@link DatabaseType#DISK} because the optimistic read path requires
 * the {@code LockFreeReadCache} which is only available with disk storage
 * ({@code DirectMemoryOnlyDiskCache} used by MEMORY databases returns null
 * from {@code loadPageOptimistic}).
 */
@Category(SequentialTest.class)
public class BTreeOptimisticReadTest {

  private static final int KEYS_COUNT = 1_000;
  private static final RecordId NULL_KEY_RID = new RecordId(0, 999_999);

  private AtomicOperationsManager atomicOperationsManager;
  private BTree<String> singleValueTree;
  private AbstractStorage storage;
  private YouTrackDBImpl youTrackDB;
  private String buildDirectory;
  private String dbName;

  @Before
  public void before() throws Exception {
    buildDirectory =
        System.getProperty("buildDirectory", ".")
            + File.separator
            + BTreeOptimisticReadTest.class.getSimpleName();

    dbName = "optimisticReadBTreeTest";
    final var dbDirectory = new File(buildDirectory, dbName);
    FileUtils.deleteRecursively(dbDirectory);

    youTrackDB = (YouTrackDBImpl) YourTracks.instance(buildDirectory);
    youTrackDB.create(dbName, DatabaseType.DISK,
        new LocalUserCredential("admin", "admin", PredefinedLocalRole.ADMIN));

    try (var databaseDocumentTx = youTrackDB.open(dbName, "admin", "admin")) {
      storage = databaseDocumentTx.getStorage();
    }
    singleValueTree = new BTree<>("optimisticBTree", ".sbt", ".nbt", storage);
    atomicOperationsManager = storage.getAtomicOperationsManager();

    // Create the tree structure.
    atomicOperationsManager.executeInsideAtomicOperation(
        atomicOperation -> singleValueTree.create(
            atomicOperation, UTF8Serializer.INSTANCE, null, 1));

    // Insert all keys in a single committed atomic operation.
    atomicOperationsManager.executeInsideAtomicOperation(
        atomicOperation -> {
          for (var i = 0; i < KEYS_COUNT; i++) {
            singleValueTree.put(
                atomicOperation,
                Integer.toString(i),
                new RecordId(i % 32000, i));
          }
          // Also insert a null-key entry to exercise getNullKeyOptimistic().
          singleValueTree.put(atomicOperation, null, NULL_KEY_RID);
        });

    // Close and reopen the database so all pages are evicted from the write
    // cache. After reopening, any page load goes through the read cache,
    // enabling the optimistic read path.
    youTrackDB.close();
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(buildDirectory);
    try (var databaseDocumentTx = youTrackDB.open(dbName, "admin", "admin")) {
      storage = databaseDocumentTx.getStorage();
    }
    singleValueTree = new BTree<>("optimisticBTree", ".sbt", ".nbt", storage);
    atomicOperationsManager = storage.getAtomicOperationsManager();
    atomicOperationsManager.executeInsideAtomicOperation(
        atomicOperation -> singleValueTree.load(
            "optimisticBTree", -1, null,
            UTF8Serializer.INSTANCE, atomicOperation));

    // Warm-up: read ALL keys to populate the read cache with all tree pages.
    // The optimistic path only works for pages already in the read cache.
    atomicOperationsManager.executeInsideAtomicOperation(
        atomicOperation -> {
          for (var i = 0; i < KEYS_COUNT; i++) {
            singleValueTree.get(Integer.toString(i), atomicOperation);
          }
          singleValueTree.get(null, atomicOperation);
          singleValueTree.size(atomicOperation);
          singleValueTree.firstKey(atomicOperation);
          singleValueTree.lastKey(atomicOperation);
        });
  }

  @After
  public void afterMethod() {
    youTrackDB.drop(dbName);
    youTrackDB.close();
  }

  /**
   * Verifies that get() for an existing key succeeds via the optimistic path.
   * After a committed insert, the pages are in the read cache and there are
   * no pending atomic operation changes, so getOptimistic() should succeed.
   */
  @Test
  public void testGetOptimistic() throws Exception {
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      for (var i = 0; i < KEYS_COUNT; i++) {
        final var rid = singleValueTree.get(Integer.toString(i), atomicOperation);
        Assert.assertEquals(
            "Key " + i + " should return the correct RID",
            new RecordId(i % 32000, i), rid);
      }
    });
  }

  /**
   * Verifies that get() for a non-existent key returns null via the
   * optimistic path (the leaf is found but the key is not present).
   */
  @Test
  public void testGetOptimisticMissing() throws Exception {
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      Assert.assertNull(
          "Non-existent key should return null",
          singleValueTree.get(Integer.toString(KEYS_COUNT + 1), atomicOperation));
    });
  }

  /**
   * Verifies that get(null) succeeds via the getNullKeyOptimistic() path.
   */
  @Test
  public void testGetNullKeyOptimistic() throws Exception {
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      final var rid = singleValueTree.get(null, atomicOperation);
      Assert.assertEquals(
          "Null key should return the correct RID",
          NULL_KEY_RID, rid);
    });
  }

  /**
   * Verifies that size() reads the entry-point page optimistically and
   * returns the correct count (KEYS_COUNT regular keys + 1 null key).
   */
  @Test
  public void testSizeOptimistic() throws Exception {
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      final var size = singleValueTree.size(atomicOperation);
      // size() counts all keys including the null key.
      Assert.assertEquals(
          "Size should match the number of inserted keys (including null)",
          KEYS_COUNT + 1, size);
    });
  }

  /**
   * Verifies that firstKey() traverses the leftmost leaf optimistically
   * and returns the lexicographically smallest key.
   */
  @Test
  public void testFirstKeyOptimistic() throws Exception {
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      final var first = singleValueTree.firstKey(atomicOperation);
      Assert.assertEquals(
          "First key should be '0' (lexicographic order of string keys)",
          "0", first);
    });
  }

  /**
   * Verifies that lastKey() traverses the rightmost leaf optimistically
   * and returns the lexicographically largest key.
   */
  @Test
  public void testLastKeyOptimistic() throws Exception {
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      final var last = singleValueTree.lastKey(atomicOperation);
      // String ordering: "999" is the largest among "0".."999".
      Assert.assertEquals(
          "Last key should be '999' (lexicographic order of string keys)",
          "999", last);
    });
  }

  /**
   * Verifies that iterateEntriesBetween() in ascending order exercises
   * the optimistic path and returns entries in the correct order.
   */
  @Test
  public void testIterateEntriesBetweenAscOptimistic() throws Exception {
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      // Pick a small range in lexicographic order: "100" to "200" inclusive.
      try (Stream<RawPair<String, RID>> stream =
          singleValueTree.iterateEntriesBetween(
              "100", true, "200", true, true, atomicOperation)) {
        final var entries = stream.collect(Collectors.toList());

        Assert.assertFalse("Should return at least one entry", entries.isEmpty());

        // Verify ascending order.
        for (var i = 1; i < entries.size(); i++) {
          Assert.assertTrue(
              "Entries should be in ascending key order",
              entries.get(i - 1).first().compareTo(entries.get(i).first()) <= 0);
        }

        // Verify boundary inclusion: first key >= "100", last key <= "200".
        Assert.assertTrue(
            "First entry key should be >= '100'",
            entries.get(0).first().compareTo("100") >= 0);
        Assert.assertTrue(
            "Last entry key should be <= '200'",
            entries.get(entries.size() - 1).first().compareTo("200") <= 0);
      }
    });
  }

  /**
   * Verifies that iterateEntriesBetween() in descending order exercises
   * the optimistic path and returns entries in reverse order.
   */
  @Test
  public void testIterateEntriesBetweenDescOptimistic() throws Exception {
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      try (Stream<RawPair<String, RID>> stream =
          singleValueTree.iterateEntriesBetween(
              "100", true, "200", true, false, atomicOperation)) {
        final var entries = stream.collect(Collectors.toList());

        Assert.assertFalse("Should return at least one entry", entries.isEmpty());

        // Verify descending order.
        for (var i = 1; i < entries.size(); i++) {
          Assert.assertTrue(
              "Entries should be in descending key order",
              entries.get(i - 1).first().compareTo(entries.get(i).first()) >= 0);
        }
      }
    });
  }

  /**
   * Verifies that iterateEntriesMinor() in ascending order exercises the
   * optimistic path. Returns all entries with keys less than (or equal to)
   * the given key.
   */
  @Test
  public void testIterateEntriesMinorAscOptimistic() throws Exception {
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      try (Stream<RawPair<String, RID>> stream =
          singleValueTree.iterateEntriesMinor(
              "200", true, true, atomicOperation)) {
        final var entries = stream.collect(Collectors.toList());

        Assert.assertFalse("Should return at least one entry", entries.isEmpty());

        // Verify ascending order.
        for (var i = 1; i < entries.size(); i++) {
          Assert.assertTrue(
              "Entries should be in ascending key order",
              entries.get(i - 1).first().compareTo(entries.get(i).first()) <= 0);
        }

        // All keys should be <= "200".
        for (var entry : entries) {
          Assert.assertTrue(
              "All keys should be <= '200'",
              entry.first().compareTo("200") <= 0);
        }
      }
    });
  }

  /**
   * Verifies that iterateEntriesMinor() in descending order exercises the
   * optimistic path.
   */
  @Test
  public void testIterateEntriesMinorDescOptimistic() throws Exception {
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      try (Stream<RawPair<String, RID>> stream =
          singleValueTree.iterateEntriesMinor(
              "200", true, false, atomicOperation)) {
        final var entries = stream.collect(Collectors.toList());

        Assert.assertFalse("Should return at least one entry", entries.isEmpty());

        // Verify descending order.
        for (var i = 1; i < entries.size(); i++) {
          Assert.assertTrue(
              "Entries should be in descending key order",
              entries.get(i - 1).first().compareTo(entries.get(i).first()) >= 0);
        }
      }
    });
  }

  /**
   * Verifies that iterateEntriesMajor() in ascending order exercises the
   * optimistic path. Returns all entries with keys greater than (or equal to)
   * the given key.
   */
  @Test
  public void testIterateEntriesMajorAscOptimistic() throws Exception {
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      try (Stream<RawPair<String, RID>> stream =
          singleValueTree.iterateEntriesMajor(
              "800", true, true, atomicOperation)) {
        final var entries = stream.collect(Collectors.toList());

        Assert.assertFalse("Should return at least one entry", entries.isEmpty());

        // Verify ascending order.
        for (var i = 1; i < entries.size(); i++) {
          Assert.assertTrue(
              "Entries should be in ascending key order",
              entries.get(i - 1).first().compareTo(entries.get(i).first()) <= 0);
        }

        // All keys should be >= "800".
        for (var entry : entries) {
          Assert.assertTrue(
              "All keys should be >= '800'",
              entry.first().compareTo("800") >= 0);
        }
      }
    });
  }

  /**
   * Verifies that iterateEntriesMajor() in descending order exercises the
   * optimistic path.
   */
  @Test
  public void testIterateEntriesMajorDescOptimistic() throws Exception {
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      try (Stream<RawPair<String, RID>> stream =
          singleValueTree.iterateEntriesMajor(
              "800", true, false, atomicOperation)) {
        final var entries = stream.collect(Collectors.toList());

        Assert.assertFalse("Should return at least one entry", entries.isEmpty());

        // Verify descending order.
        for (var i = 1; i < entries.size(); i++) {
          Assert.assertTrue(
              "Entries should be in descending key order",
              entries.get(i - 1).first().compareTo(entries.get(i).first()) >= 0);
        }
      }
    });
  }

  /**
   * Verifies that keyStream() exercises the optimistic path and returns
   * all non-null keys in ascending order.
   */
  @Test
  public void testKeyStreamOptimistic() throws Exception {
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      try (Stream<String> stream = singleValueTree.keyStream(atomicOperation)) {
        final var keys = stream.collect(Collectors.toList());

        Assert.assertEquals(
            "keyStream should return all non-null keys",
            KEYS_COUNT, keys.size());

        // Verify ascending order.
        for (var i = 1; i < keys.size(); i++) {
          Assert.assertTrue(
              "Keys should be in ascending order",
              keys.get(i - 1).compareTo(keys.get(i)) <= 0);
        }
      }
    });
  }

  /**
   * Verifies that allEntries() exercises the optimistic path and returns
   * all key-RID pairs in ascending key order.
   */
  @Test
  public void testAllEntriesOptimistic() throws Exception {
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      try (Stream<RawPair<String, RID>> stream =
          singleValueTree.allEntries(atomicOperation)) {
        final var entries = stream.collect(Collectors.toList());

        Assert.assertEquals(
            "allEntries should return all non-null key entries",
            KEYS_COUNT, entries.size());

        // Verify ascending order.
        for (var i = 1; i < entries.size(); i++) {
          Assert.assertTrue(
              "Entries should be in ascending key order",
              entries.get(i - 1).first().compareTo(entries.get(i).first()) <= 0);
        }

        // Verify each entry has the correct RID.
        for (var entry : entries) {
          final var keyVal = Integer.parseInt(entry.first());
          Assert.assertEquals(
              "RID should match for key " + entry.first(),
              new RecordId(keyVal % 32000, keyVal), entry.second());
        }
      }
    });
  }
}
