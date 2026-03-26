package com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.function.Consumer;
import org.assertj.core.api.Assertions;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Integration test that exercises the optimistic read paths in
 * {@link SharedLinkBagBTree}. Data is inserted and committed in one atomic
 * operation so all pages reside in the read cache. Read operations are then
 * performed in a separate (read-only) atomic operation where optimistic reads
 * succeed without falling back to the pinned CAS path.
 *
 * <p>Covered optimistic paths:
 * <ul>
 *   <li>{@code getOptimistic()} — via {@code get()}</li>
 *   <li>{@code findCurrentEntryOptimistic()} — via {@code findCurrentEntry()}</li>
 *   <li>{@code findVisibleEntryOptimistic()} — via {@code findVisibleEntry()}</li>
 *   <li>{@code firstKey()} / {@code lastKey()} optimistic dispatch</li>
 *   <li>{@code iterateEntriesMinor()} — ascending and descending</li>
 *   <li>{@code iterateEntriesMajor()} — ascending and descending</li>
 *   <li>{@code streamEntriesBetween()} — ascending and descending</li>
 *   <li>{@code spliteratorEntriesBetween()} — ascending and descending,
 *       forward and backward cache fetching</li>
 * </ul>
 */
@Category(SequentialTest.class)
public class SharedLinkBagBTreeOptimisticReadTest {

  private static final String DB_NAME = "sharedLinkBagBTreeOptimisticReadTest";
  private static final String DIR_NAME = "/optimisticReadBTreeTest";

  private static YouTrackDBImpl youTrackDB;
  private static AbstractStorage storage;
  private static AtomicOperationsManager atomicOperationsManager;
  private static String buildDirectory;

  private SharedLinkBagBTree bTree;

  /**
   * Reference map that mirrors the B-tree content so we can verify read
   * results against a known-good data structure.
   */
  private final NavigableMap<EdgeKey, LinkBagValue> reference = new TreeMap<>();

  // Use ridBagId=100 to avoid collisions with other test suites.
  private static final long RID_BAG_ID = 100L;
  // Number of entries — large enough to span multiple B-tree leaf pages.
  private static final int ENTRY_COUNT = 512;

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

    var session = youTrackDB.open(DB_NAME, "admin", "admin");
    storage = session.getStorage();
    atomicOperationsManager = storage.getAtomicOperationsManager();
    session.close();
  }

  @AfterClass
  public static void afterClass() {
    youTrackDB.drop(DB_NAME);
    youTrackDB.close();

    FileUtils.deleteRecursively(new File(buildDirectory));
  }

  @Before
  public void setUp() throws Exception {
    bTree = new SharedLinkBagBTree(storage, "optimisticBTree", ".sbc");
    atomicOperationsManager.executeInsideAtomicOperation(
        atomicOperation -> bTree.create(atomicOperation));

    // Insert entries in a single atomic operation.
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      for (var i = 0; i < ENTRY_COUNT; i++) {
        var key = new EdgeKey(RID_BAG_ID, i % 32, i, 0L);
        var value = new LinkBagValue(i + 1, 0, 0, false);
        bTree.put(atomicOperation, key, value);
        reference.put(key, value);
      }
    });

    // Close and reopen the database so that pages are flushed to disk and
    // evicted from the write cache. On reopen, reads will go through the
    // read cache, enabling optimistic read paths.
    youTrackDB.close();
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(buildDirectory);

    var session = youTrackDB.open(DB_NAME, "admin", "admin");
    storage = session.getStorage();
    atomicOperationsManager = storage.getAtomicOperationsManager();
    session.close();

    // Reopen the B-tree with the fresh storage reference.
    bTree = new SharedLinkBagBTree(storage, "optimisticBTree", ".sbc");
    atomicOperationsManager.executeInsideAtomicOperation(
        atomicOperation -> bTree.load(atomicOperation));

    // Warm-up: read ALL keys to populate the read cache with all tree pages.
    atomicOperationsManager.executeInsideAtomicOperation(
        atomicOperation -> {
          for (var key : reference.keySet()) {
            bTree.get(key, atomicOperation);
          }
          bTree.firstKey(atomicOperation);
          bTree.lastKey(atomicOperation);
        });
  }

  @After
  public void tearDown() throws Exception {
    reference.clear();
    atomicOperationsManager.executeInsideAtomicOperation(
        atomicOperation -> bTree.delete(atomicOperation));
  }

  // ------------------------------------------------------------------
  // get() — exercises getOptimistic()
  // ------------------------------------------------------------------

  /**
   * Verifies that every inserted key can be retrieved via {@code get()} in a
   * clean read-only atomic operation, exercising the optimistic read path.
   */
  @Test
  public void testGetOptimistic() throws Exception {
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      for (var entry : reference.entrySet()) {
        var result = bTree.get(entry.getKey(), atomicOperation);
        Assertions.assertThat(result)
            .as("get() for key %s", entry.getKey())
            .isNotNull();
        Assertions.assertThat(result.counter())
            .isEqualTo(entry.getValue().counter());
      }
    });
  }

  /**
   * Verifies that {@code get()} returns null for keys that were never inserted.
   */
  @Test
  public void testGetOptimisticMissingKeys() throws Exception {
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      for (var i = ENTRY_COUNT; i < ENTRY_COUNT + 50; i++) {
        var key = new EdgeKey(RID_BAG_ID, i % 32, i, 0L);
        Assert.assertNull(
            "get() should return null for absent key " + key,
            bTree.get(key, atomicOperation));
      }
    });
  }

  // ------------------------------------------------------------------
  // findCurrentEntry() — exercises findCurrentEntryOptimistic()
  // ------------------------------------------------------------------

  /**
   * Verifies that {@code findCurrentEntry()} locates every inserted entry by
   * its 3-tuple prefix, exercising the optimistic prefix-search path.
   */
  @Test
  public void testFindCurrentEntryOptimistic() throws Exception {
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      for (var entry : reference.entrySet()) {
        var key = entry.getKey();
        var pair = bTree.findCurrentEntry(
            atomicOperation, key.ridBagId, key.targetCollection,
            key.targetPosition);
        Assertions.assertThat(pair)
            .as("findCurrentEntry for key %s", key)
            .isNotNull();
        Assertions.assertThat(pair.first()).isEqualTo(key);
        Assertions.assertThat(pair.second().counter())
            .isEqualTo(entry.getValue().counter());
      }
    });
  }

  /**
   * Verifies that {@code findCurrentEntry()} returns null for a non-existent
   * 3-tuple prefix.
   */
  @Test
  public void testFindCurrentEntryOptimisticMissing() throws Exception {
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var result = bTree.findCurrentEntry(
          atomicOperation, RID_BAG_ID + 999, 0, 0);
      Assert.assertNull(
          "findCurrentEntry should return null for missing prefix", result);
    });
  }

  // ------------------------------------------------------------------
  // findVisibleEntry() — exercises findVisibleEntryOptimistic()
  // ------------------------------------------------------------------

  /**
   * Verifies that {@code findVisibleEntry()} returns the correct entry for
   * every inserted key, exercising the optimistic visibility-resolution path.
   */
  @Test
  public void testFindVisibleEntryOptimistic() throws Exception {
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      for (var entry : reference.entrySet()) {
        var key = entry.getKey();
        var pair = bTree.findVisibleEntry(
            atomicOperation, key.ridBagId, key.targetCollection,
            key.targetPosition);
        Assertions.assertThat(pair)
            .as("findVisibleEntry for key %s", key)
            .isNotNull();
        Assertions.assertThat(pair.second().counter())
            .isEqualTo(entry.getValue().counter());
      }
    });
  }

  // ------------------------------------------------------------------
  // firstKey() / lastKey() — optimistic dispatch
  // ------------------------------------------------------------------

  /**
   * Verifies that {@code firstKey()} and {@code lastKey()} return the expected
   * boundary keys from a clean read-only atomic operation.
   */
  @Test
  public void testFirstKeyLastKeyOptimistic() throws Exception {
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      Assert.assertEquals(reference.firstKey(), bTree.firstKey(atomicOperation));
      Assert.assertEquals(reference.lastKey(), bTree.lastKey(atomicOperation));
    });
  }

  // ------------------------------------------------------------------
  // iterateEntriesMajor() — ascending and descending
  // ------------------------------------------------------------------

  /**
   * Verifies {@code iterateEntriesMajor()} in ascending order with inclusive
   * key, comparing every entry against the reference map's tailMap.
   */
  @Test
  public void testIterateEntriesMajorAscInclusive() throws Exception {
    // Pick a key in the middle of the range.
    var fromKey = referenceKeyAtFraction(0.25);

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      try (var stream =
          bTree.iterateEntriesMajor(fromKey, true, true, atomicOperation)) {
        var expected = reference.tailMap(fromKey, true).entrySet().iterator();
        assertStreamMatchesIterator(stream.iterator(), expected);
      }
    });
  }

  /**
   * Verifies {@code iterateEntriesMajor()} in descending order with exclusive
   * key.
   */
  @Test
  public void testIterateEntriesMajorDescExclusive() throws Exception {
    var fromKey = referenceKeyAtFraction(0.25);

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      try (var stream =
          bTree.iterateEntriesMajor(fromKey, false, false, atomicOperation)) {
        var expected = reference.descendingMap()
            .subMap(reference.lastKey(), true, fromKey, false)
            .entrySet().iterator();
        assertStreamMatchesIterator(stream.iterator(), expected);
      }
    });
  }

  // ------------------------------------------------------------------
  // iterateEntriesMinor() — ascending and descending
  // ------------------------------------------------------------------

  /**
   * Verifies {@code iterateEntriesMinor()} in ascending order with inclusive
   * key.
   */
  @Test
  public void testIterateEntriesMinorAscInclusive() throws Exception {
    var toKey = referenceKeyAtFraction(0.75);

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      try (var stream =
          bTree.iterateEntriesMinor(toKey, true, true, atomicOperation)) {
        var expected = reference.headMap(toKey, true).entrySet().iterator();
        assertStreamMatchesIterator(stream.iterator(), expected);
      }
    });
  }

  /**
   * Verifies {@code iterateEntriesMinor()} in descending order with exclusive
   * key.
   */
  @Test
  public void testIterateEntriesMinorDescExclusive() throws Exception {
    var toKey = referenceKeyAtFraction(0.75);

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      try (var stream =
          bTree.iterateEntriesMinor(toKey, false, false, atomicOperation)) {
        var expected = reference.headMap(toKey, false)
            .descendingMap().entrySet().iterator();
        assertStreamMatchesIterator(stream.iterator(), expected);
      }
    });
  }

  // ------------------------------------------------------------------
  // streamEntriesBetween() — ascending and descending
  // ------------------------------------------------------------------

  /**
   * Verifies {@code streamEntriesBetween()} in ascending order with both
   * bounds inclusive.
   */
  @Test
  public void testStreamEntriesBetweenAscBothInclusive() throws Exception {
    var fromKey = referenceKeyAtFraction(0.2);
    var toKey = referenceKeyAtFraction(0.8);

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      try (var stream =
          bTree.streamEntriesBetween(
              fromKey, true, toKey, true, true, atomicOperation)) {
        var expected = reference.subMap(fromKey, true, toKey, true)
            .entrySet().iterator();
        assertStreamMatchesIterator(stream.iterator(), expected);
      }
    });
  }

  /**
   * Verifies {@code streamEntriesBetween()} in descending order with both
   * bounds exclusive.
   */
  @Test
  public void testStreamEntriesBetweenDescBothExclusive() throws Exception {
    var fromKey = referenceKeyAtFraction(0.2);
    var toKey = referenceKeyAtFraction(0.8);

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      try (var stream =
          bTree.streamEntriesBetween(
              fromKey, false, toKey, false, false, atomicOperation)) {
        var expected = reference.descendingMap()
            .subMap(toKey, false, fromKey, false)
            .entrySet().iterator();
        assertStreamMatchesIterator(stream.iterator(), expected);
      }
    });
  }

  // ------------------------------------------------------------------
  // spliteratorEntriesBetween() — forward and backward cache fetching
  // ------------------------------------------------------------------

  /**
   * Verifies {@code spliteratorEntriesBetween()} in ascending order,
   * consuming all entries via {@code forEachRemaining()}. This exercises
   * {@code fetchNextCachePortionForward()}.
   */
  @Test
  public void testSpliteratorBetweenAscForward() throws Exception {
    var fromKey = referenceKeyAtFraction(0.1);
    var toKey = referenceKeyAtFraction(0.9);

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var spliterator = bTree.spliteratorEntriesBetween(
          fromKey, true, toKey, true, true, atomicOperation);
      var expectedEntries = new ArrayList<>(
          reference.subMap(fromKey, true, toKey, true).entrySet());

      var collected = new ArrayList<RawPair<EdgeKey, LinkBagValue>>();
      spliterator.forEachRemaining(collected::add);

      Assertions.assertThat(collected).hasSize(expectedEntries.size());
      for (var i = 0; i < collected.size(); i++) {
        Assertions.assertThat(collected.get(i).first())
            .isEqualTo(expectedEntries.get(i).getKey());
        Assertions.assertThat(collected.get(i).second().counter())
            .isEqualTo(expectedEntries.get(i).getValue().counter());
      }
    });
  }

  /**
   * Verifies {@code spliteratorEntriesBetween()} in descending order,
   * consuming all entries via {@code forEachRemaining()}. This exercises
   * {@code fetchNextCachePortionBackward()}.
   */
  @Test
  public void testSpliteratorBetweenDescBackward() throws Exception {
    var fromKey = referenceKeyAtFraction(0.1);
    var toKey = referenceKeyAtFraction(0.9);

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var spliterator = bTree.spliteratorEntriesBetween(
          fromKey, true, toKey, true, false, atomicOperation);
      var expectedEntries = new ArrayList<>(
          reference.descendingMap()
              .subMap(toKey, true, fromKey, true)
              .entrySet());

      var collected = new ArrayList<RawPair<EdgeKey, LinkBagValue>>();
      spliterator.forEachRemaining(collected::add);

      Assertions.assertThat(collected).hasSize(expectedEntries.size());
      for (var i = 0; i < collected.size(); i++) {
        Assertions.assertThat(collected.get(i).first())
            .isEqualTo(expectedEntries.get(i).getKey());
        Assertions.assertThat(collected.get(i).second().counter())
            .isEqualTo(expectedEntries.get(i).getValue().counter());
      }
    });
  }

  /**
   * Verifies {@code spliteratorEntriesBetween()} using {@code tryAdvance()}
   * one element at a time in ascending order, ensuring correct element-by-
   * element iteration through the cache portions.
   */
  @Test
  public void testSpliteratorTryAdvanceAsc() throws Exception {
    var fromKey = referenceKeyAtFraction(0.3);
    var toKey = referenceKeyAtFraction(0.7);

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var spliterator = bTree.spliteratorEntriesBetween(
          fromKey, true, toKey, true, true, atomicOperation);
      var expectedEntries = new ArrayList<>(
          reference.subMap(fromKey, true, toKey, true).entrySet());

      var index = 0;
      var holder = new RawPair[1];
      //noinspection unchecked
      Consumer<RawPair<EdgeKey, LinkBagValue>> consumer = p -> holder[0] = p;

      while (spliterator.tryAdvance(consumer)) {
        Assertions.assertThat(index)
            .as("tryAdvance should not exceed expected count")
            .isLessThan(expectedEntries.size());
        //noinspection unchecked
        RawPair<EdgeKey, LinkBagValue> pair = holder[0];
        Assertions.assertThat(pair.first())
            .isEqualTo(expectedEntries.get(index).getKey());
        Assertions.assertThat(pair.second().counter())
            .isEqualTo(expectedEntries.get(index).getValue().counter());
        index++;
      }
      Assertions.assertThat(index).isEqualTo(expectedEntries.size());
    });
  }

  /**
   * Verifies {@code spliteratorEntriesBetween()} using {@code tryAdvance()}
   * one element at a time in descending order, ensuring backward cache
   * portions are fetched correctly.
   */
  @Test
  public void testSpliteratorTryAdvanceDesc() throws Exception {
    var fromKey = referenceKeyAtFraction(0.3);
    var toKey = referenceKeyAtFraction(0.7);

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var spliterator = bTree.spliteratorEntriesBetween(
          fromKey, true, toKey, true, false, atomicOperation);
      var expectedEntries = new ArrayList<>(
          reference.descendingMap()
              .subMap(toKey, true, fromKey, true)
              .entrySet());

      var index = 0;
      var holder = new RawPair[1];
      //noinspection unchecked
      Consumer<RawPair<EdgeKey, LinkBagValue>> consumer = p -> holder[0] = p;

      while (spliterator.tryAdvance(consumer)) {
        Assertions.assertThat(index)
            .as("tryAdvance should not exceed expected count")
            .isLessThan(expectedEntries.size());
        //noinspection unchecked
        RawPair<EdgeKey, LinkBagValue> pair = holder[0];
        Assertions.assertThat(pair.first())
            .isEqualTo(expectedEntries.get(index).getKey());
        Assertions.assertThat(pair.second().counter())
            .isEqualTo(expectedEntries.get(index).getValue().counter());
        index++;
      }
      Assertions.assertThat(index).isEqualTo(expectedEntries.size());
    });
  }

  // ------------------------------------------------------------------
  // Full-range streaming covering all four inclusivity combinations
  // ------------------------------------------------------------------

  /**
   * Verifies {@code streamEntriesBetween()} over the full key range with all
   * four combinations of from/to inclusivity in ascending order.
   */
  @Test
  public void testStreamEntriesBetweenAllInclusivityCombinations() throws Exception {
    var fromKey = reference.firstKey();
    var toKey = reference.lastKey();

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      for (var fromInclusive : new boolean[] {true, false}) {
        for (var toInclusive : new boolean[] {true, false}) {
          try (var stream = bTree.streamEntriesBetween(
              fromKey, fromInclusive, toKey, toInclusive, true,
              atomicOperation)) {
            var expected = reference
                .subMap(fromKey, fromInclusive, toKey, toInclusive)
                .entrySet().iterator();
            assertStreamMatchesIterator(stream.iterator(), expected);
          }
        }
      }
    });
  }

  // ------------------------------------------------------------------
  // Helpers
  // ------------------------------------------------------------------

  /**
   * Returns the key at approximately the given fraction (0.0–1.0) of the
   * sorted reference map.
   */
  private EdgeKey referenceKeyAtFraction(double fraction) {
    var keys = new ArrayList<>(reference.keySet());
    var index = (int) (keys.size() * fraction);
    index = Math.max(0, Math.min(index, keys.size() - 1));
    return keys.get(index);
  }

  /**
   * Asserts that the B-tree iterator produces exactly the same sequence of
   * key-value pairs as the reference iterator.
   */
  private static void assertStreamMatchesIterator(
      Iterator<RawPair<EdgeKey, LinkBagValue>> actual,
      Iterator<Map.Entry<EdgeKey, LinkBagValue>> expected) {
    while (expected.hasNext()) {
      Assert.assertTrue(
          "B-tree iterator exhausted before reference", actual.hasNext());
      var actualEntry = actual.next();
      var expectedEntry = expected.next();
      Assert.assertEquals(expectedEntry.getKey(), actualEntry.first());
      Assert.assertEquals(
          expectedEntry.getValue().counter(), actualEntry.second().counter());
    }
    Assert.assertFalse(
        "B-tree iterator has more entries than reference", actual.hasNext());
  }
}
