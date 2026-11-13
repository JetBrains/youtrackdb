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

  /** A failed child load must not release another reader's root pin in firstItem. */
  @Test
  public void testFirstItemLoadFailurePreservesOtherPin() throws Exception {
    assertBoundaryLoadFailurePreservesOtherPin(false, new IllegalStateException("child load"));
  }

  /** An Error in a child load must retain its identity and the lastItem root owner's pin. */
  @Test
  public void testLastItemLoadErrorPreservesOtherPin() throws Exception {
    assertBoundaryLoadFailurePreservesOtherPin(true, new AssertionError("child load"));
  }

  private void assertBoundaryLoadFailurePreservesOtherPin(boolean last, Throwable failure)
      throws Exception {
    var cache = storage.getReadCache();
    long fileId = singleValueTree.getFileId();
    var field = com.jetbrains.youtrackdb.internal.core.storage.cache.chm.LockFreeReadCache.class
        .getDeclaredField("cacheHitRatio");
    field.setAccessible(true);
    var original = field.get(cache);
    var ratio = org.mockito.Mockito.mock(
        com.jetbrains.youtrackdb.internal.common.profiler.metrics.Ratio.class);
    var failNext = new java.util.concurrent.atomic.AtomicBoolean();
    var fired = new java.util.concurrent.atomic.AtomicBoolean();
    org.mockito.Mockito.doAnswer(call -> {
      if (failNext.compareAndSet(true, false)) {
        throw failure;
      }
      return null;
    }).when(ratio).record(org.mockito.ArgumentMatchers.anyBoolean());
    try {
      atomicOperationsManager.executeInsideAtomicOperation(op -> {
        try (var owner = op.loadPageForRead(fileId, 1)) {
          int pins = pinState(owner);
          var reader = org.mockito.Mockito.spy(op);
          org.mockito.Mockito.doAnswer(call -> {
            long page = call.getArgument(1);
            if (page > 1 && op.getOptimisticReadScope().isAttemptActive()
                && fired.compareAndSet(false, true)) {
              failNext.set(true);
            }
            return call.callRealMethod();
          }).when(reader).loadPageForRead(org.mockito.ArgumentMatchers.anyLong(),
              org.mockito.ArgumentMatchers.anyLong());
          field.set(cache, ratio);
          try {
            var reported = Assert.assertThrows(failure.getClass(), () -> {
              if (last) {
                singleValueTree.lastKey(reader);
              } else {
                singleValueTree.firstKey(reader);
              }
            });
            Assert.assertTrue("The traversal must reach a child", fired.get());
            Assert.assertEquals("Another owner's pin must remain", pins, pinState(owner));
            Assert.assertSame(failure, reported);
            Assert.assertFalse(op.getOptimisticReadScope().isAttemptActive());
          } finally {
            field.set(cache, original);
          }
        }
      });
    } finally {
      field.set(cache, original);
    }
  }

  @After
  public void afterMethod() {
    youTrackDB.drop(dbName);
    youTrackDB.close();
  }

  /**
   * A real leaf split between root and leaf reads invalidates the pinned root stamp.
   * A private test epoch isolates stamps from the commit protocol's epoch guard.
   */
  @Test
  public void testLastKeyFallsBackAcrossLeafSplit() throws Exception {
    storage.getReadCache().clear();
    Assert
        .assertNull(storage.getReadCache().getCacheEntryOptimistic(singleValueTree.getFileId(), 1));
    atomicOperationsManager.executeInsideAtomicOperation(op -> {
      try (var root = op.loadPageForRead(singleValueTree.getFileId(), 1)) {
        var bucket = new CellBTreeSingleValueBucketV3<String>(root);
        Assert.assertFalse("The root must be internal", bucket.isLeaf());
        try (var child = op.loadPageForRead(singleValueTree.getFileId(), bucket.getLeft(0))) {
          Assert.assertTrue("The tree must have exactly two levels",
              new CellBTreeSingleValueBucketV3<String>(child).isLeaf());
        }
      }
    });
    storage.getReadCache().clear();
    long stampsBefore =
        com.jetbrains.youtrackdb.internal.core.storage.cache.OptimisticReadFailureCounters.count(
            com.jetbrains.youtrackdb.internal.core.storage.cache.OptimisticReadFailureCounters.Cause.STAMP);
    var tree = org.mockito.Mockito.spy(singleValueTree);
    org.mockito.Mockito.doReturn(
        new com.jetbrains.youtrackdb.internal.core.storage.cache.ApplyPhaseEpoch())
        .when(tree).getApplyPhaseEpoch();
    var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
    var fired = new java.util.concurrent.atomic.AtomicBoolean();
    try {
      atomicOperationsManager.executeInsideAtomicOperation(op -> {
        var reader = org.mockito.Mockito.spy(op);
        org.mockito.Mockito.doAnswer(call -> {
          long page = call.getArgument(1);
          if (page > 1 && op.getOptimisticReadScope().isAttemptActive()
              && fired.compareAndSet(false, true)) {
            long sizeBefore = storage.getWriteCache().getFilledUpTo(tree.getFileId());
            executor.submit(() -> {
              atomicOperationsManager.executeInsideAtomicOperation(writer -> {
                for (int i = 0; i < 2000; i++) {
                  singleValueTree.put(writer, String.format("z%05d", i), new RecordId(1, i));
                }
              });
              return null;
            }).get(30, java.util.concurrent.TimeUnit.SECONDS);
            Assert.assertTrue(storage.getWriteCache().getFilledUpTo(tree.getFileId()) > sizeBefore);
          }
          return call.callRealMethod();
        }).when(reader).loadPageForRead(org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong());
        Assert.assertEquals("z01999", tree.lastKey(reader));
        Assert.assertFalse(op.getOptimisticReadScope().isAttemptActive());
      });
      Assert.assertTrue(fired.get());
      Assert.assertTrue(
          com.jetbrains.youtrackdb.internal.core.storage.cache.OptimisticReadFailureCounters.count(
              com.jetbrains.youtrackdb.internal.core.storage.cache.OptimisticReadFailureCounters.Cause.STAMP)
              > stampsBefore);
    } finally {
      executor.shutdownNow();
      Assert.assertTrue("Split executor must terminate",
          executor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS));
    }
  }

  private static int pinState(
      com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry entry) throws Exception {
    var field = com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntryImpl.class
        .getDeclaredField("state");
    field.setAccessible(true);
    return field.getInt(entry);
  }

  /** A torn first-item child pointer fails without file growth or a leaked root pin. */
  @Test
  public void testFirstItemTornChildDoesNotGrowOrLeak() throws Exception {
    long fileId = singleValueTree.getFileId();
    long size = storage.getWriteCache().getFilledUpTo(fileId);
    var root = storage.getReadCache().getCacheEntryOptimistic(fileId, 1);
    int pins = pinState(root);
    var fired = new java.util.concurrent.atomic.AtomicBoolean();
    atomicOperationsManager.executeInsideAtomicOperation(op -> {
      var reader = org.mockito.Mockito.spy(op);
      org.mockito.Mockito.doAnswer(call -> {
        long page = call.getArgument(1);
        if (page > 1 && op.getOptimisticReadScope().isAttemptActive()
            && fired.compareAndSet(false, true)) {
          return op.loadPageForRead(fileId, size + 100);
        }
        return call.callRealMethod();
      }).when(reader).loadPageForRead(org.mockito.ArgumentMatchers.anyLong(),
          org.mockito.ArgumentMatchers.anyLong());
      Assert.assertEquals("0", singleValueTree.firstKey(reader));
      Assert.assertFalse(op.getOptimisticReadScope().isAttemptActive());
    });
    Assert.assertTrue(fired.get());
    Assert.assertEquals(size, storage.getWriteCache().getFilledUpTo(fileId));
    Assert.assertEquals(pins, pinState(root));
    for (long page = 0; page < size; page++) {
      var entry = storage.getReadCache().getCacheEntryOptimistic(fileId, page);
      if (entry != null) {
        Assert.assertEquals(0, pinState(entry));
      }
    }
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
   * Verifies that iterateEntriesBetween() with exclusive bounds (inclusive=false)
   * exercises a different cursor positioning path than inclusive bounds. The boundary
   * keys "100" and "200" must not appear in the results.
   */
  @Test
  public void testIterateEntriesBetweenExclusiveBoundsOptimistic() throws Exception {
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      try (Stream<RawPair<String, RID>> stream =
          singleValueTree.iterateEntriesBetween(
              "100", false, "200", false, true, atomicOperation)) {
        final var entries = stream.collect(Collectors.toList());

        // Verify exclusive: boundary keys must not be present.
        for (var entry : entries) {
          Assert.assertNotEquals(
              "Exclusive lower bound '100' must not appear", "100", entry.first());
          Assert.assertNotEquals(
              "Exclusive upper bound '200' must not appear", "200", entry.first());
        }

        // Verify ascending order.
        for (var i = 1; i < entries.size(); i++) {
          Assert.assertTrue(
              "Entries should be in ascending key order",
              entries.get(i - 1).first().compareTo(entries.get(i).first()) <= 0);
        }

        // First entry must be strictly greater than "100".
        if (!entries.isEmpty()) {
          Assert.assertTrue(
              "First entry key should be > '100'",
              entries.get(0).first().compareTo("100") > 0);
          Assert.assertTrue(
              "Last entry key should be < '200'",
              entries.get(entries.size() - 1).first().compareTo("200") < 0);
        }
      }
    });
  }

  /**
   * Verifies that iterateEntriesMinor() with exclusive upper bound (inclusive=false)
   * omits the boundary key. Exercises a different cursor positioning path than
   * inclusive=true.
   */
  @Test
  public void testIterateEntriesMinorExclusiveOptimistic() throws Exception {
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      try (Stream<RawPair<String, RID>> stream =
          singleValueTree.iterateEntriesMinor(
              "200", false, true, atomicOperation)) {
        final var entries = stream.collect(Collectors.toList());

        Assert.assertFalse("Should return at least one entry", entries.isEmpty());

        // Verify exclusive: key "200" must not appear.
        for (var entry : entries) {
          Assert.assertNotEquals(
              "Exclusive bound '200' must not appear", "200", entry.first());
        }

        // All keys should be strictly < "200".
        for (var entry : entries) {
          Assert.assertTrue(
              "All keys should be < '200'",
              entry.first().compareTo("200") < 0);
        }
      }
    });
  }

  /**
   * Verifies that iterateEntriesMajor() with exclusive lower bound (inclusive=false)
   * omits the boundary key. Exercises a different cursor positioning path than
   * inclusive=true.
   */
  @Test
  public void testIterateEntriesMajorExclusiveOptimistic() throws Exception {
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      try (Stream<RawPair<String, RID>> stream =
          singleValueTree.iterateEntriesMajor(
              "800", false, true, atomicOperation)) {
        final var entries = stream.collect(Collectors.toList());

        Assert.assertFalse("Should return at least one entry", entries.isEmpty());

        // Verify exclusive: key "800" must not appear.
        for (var entry : entries) {
          Assert.assertNotEquals(
              "Exclusive bound '800' must not appear", "800", entry.first());
        }

        // All keys should be strictly > "800".
        for (var entry : entries) {
          Assert.assertTrue(
              "All keys should be > '800'",
              entry.first().compareTo("800") > 0);
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
