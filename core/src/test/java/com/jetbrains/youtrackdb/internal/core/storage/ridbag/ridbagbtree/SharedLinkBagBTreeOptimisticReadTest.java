package com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
    youTrackDB.create(DB_NAME, DatabaseType.DISK,
        new LocalUserCredential("admin", "admin", PredefinedLocalRole.ADMIN));

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
        // targetCollection cycles through 0..31 (simulating 32 target collections),
        // targetPosition equals the entry index, ts=0 (no tombstone timestamp).
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
    long fileId = bTree.getFileId();
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
                bTree.lastKey(reader);
              } else {
                bTree.firstKey(reader);
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

  private static int pinState(
      com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry entry) throws Exception {
    var field = com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntryImpl.class
        .getDeclaredField("state");
    field.setAccessible(true);
    return field.getInt(entry);
  }

  private enum AnchorChange {
    NONE, DELETE, REPLACE
  }

  @Test
  public void forwardRefillResumesAtTheNextLeafWithAnExistingAnchor() throws Exception {
    assertResumeAcrossLeafBoundary(true, AnchorChange.NONE);
  }

  @Test
  public void backwardRefillResumesAtThePreviousLeafWithAnExistingAnchor() throws Exception {
    assertResumeAcrossLeafBoundary(false, AnchorChange.NONE);
  }

  @Test
  public void forwardRefillResumesAtTheNextLeafAfterPhysicalDelete() throws Exception {
    assertResumeAcrossLeafBoundary(true, AnchorChange.DELETE);
  }

  @Test
  public void backwardRefillResumesAtThePreviousLeafAfterPhysicalDelete() throws Exception {
    assertResumeAcrossLeafBoundary(false, AnchorChange.DELETE);
  }

  @Test
  public void backwardRefillResumesAfterAnOlderReaderLosesItsPhysicalKey() throws Exception {
    assertResumeAcrossLeafBoundary(false, AnchorChange.REPLACE);
  }

  private void assertResumeAcrossLeafBoundary(boolean forward, AnchorChange change)
      throws Exception {
    atomicOperationsManager.executeInsideAtomicOperation(op -> {
      var keys = orderedKeys(forward);
      int boundary = firstSiblingOffset(keys, op);
      var anchor = keys.get(boundary - 1);
      var cursor = bTree.spliteratorEntriesBetween(
          forward ? keys.get(boundary - 10) : reference.firstKey(), true,
          forward ? reference.lastKey() : keys.get(boundary - 10), true, forward, op);
      var seen = new ArrayList<EdgeKey>();
      for (int i = 0; i < 10; i++) {
        Assert.assertTrue(cursor.tryAdvance(pair -> seen.add(pair.first())));
      }
      Assertions.assertThat(seen).containsExactlyElementsOf(keys.subList(boundary - 10, boundary));
      var anchorPosition = search(anchor, op);
      long anchorPage = anchorPosition.getPageIndex();
      Assert.assertTrue(anchorPosition.getItemIndex() >= 0);
      // The consecutive keys straddle a leaf boundary. The tenth entry is its last scan slot.
      Assert.assertNotEquals(anchorPage, search(keys.get(boundary), op).getPageIndex());
      if (change == AnchorChange.REPLACE) {
        EdgeKey newer;
        // Closing the executor waits for the writer even if the timed get fails.
        try (var executor = Executors.newSingleThreadExecutor()) {
          newer = executor.submit(() -> atomicOperationsManager.calculateInsideAtomicOperation(
              writer -> {
                var key = new EdgeKey(anchor.ridBagId, anchor.targetCollection,
                    anchor.targetPosition, writer.getCommitTs());
                bTree.put(writer, key, new LinkBagValue(999, 0, 0, false));
                return key;
              })).get(30, TimeUnit.SECONDS);
        }
        Assert.assertTrue(search(newer, op).getItemIndex() >= 0);
        Assert.assertEquals(anchor, bTree.findVisibleEntry(op,
            anchor.ridBagId, anchor.targetCollection, anchor.targetPosition).first());
      } else if (change == AnchorChange.DELETE) {
        Assert.assertNotNull(bTree.remove(op, anchor));
      }
      if (change != AnchorChange.NONE) {
        var missing = search(anchor, op);
        Assert.assertEquals(anchorPage, missing.getPageIndex());
        Assert.assertTrue(missing.getItemIndex() < 0);
        if (!forward) {
          Assert.assertEquals("The insertion slot must be the first slot on this leaf",
              -1, missing.getItemIndex());
        }
      }
      cursor.forEachRemaining(pair -> seen.add(pair.first()));
      Assertions.assertThat(seen)
          .containsExactlyElementsOf(keys.subList(boundary - 10, keys.size()));
      Assert.assertFalse(cursor.tryAdvance(pair -> Assert.fail("Exhausted cursor emitted data")));
    });
  }

  @Test
  public void forwardSiblingLoadFailureKeepsTheOtherPinAndLetsTheCursorRetry() throws Exception {
    assertSiblingLoadFailureIsAtomic(true, new IllegalStateException("sibling load"));
  }

  @Test
  public void backwardSiblingLoadFailureKeepsTheOtherPinAndLetsTheCursorRetry() throws Exception {
    assertSiblingLoadFailureIsAtomic(false, new IllegalStateException("sibling load"));
  }

  @Test
  public void checkedSiblingLoadFailureKeepsTheCauseAndPinAndLetsTheCursorRetry()
      throws Exception {
    assertSiblingLoadFailureIsAtomic(true, new IOException("sibling load"));
  }

  private void assertSiblingLoadFailureIsAtomic(boolean forward, Exception failure)
      throws Exception {
    atomicOperationsManager.executeInsideAtomicOperation(op -> {
      var keys = orderedKeys(forward);
      int boundary = firstSiblingOffset(keys, op);
      int start = boundary - 15;
      long leaf = search(keys.get(start), op).getPageIndex();
      long sibling = search(keys.get(boundary), op).getPageIndex();
      var fired = new AtomicBoolean();
      var startingLeafLoads = new AtomicInteger();
      var reader = spy(op);
      var cursor = bTree.spliteratorEntriesBetween(
          forward ? keys.get(start) : reference.firstKey(), true,
          forward ? reference.lastKey() : keys.get(start), true, forward, reader);
      var seen = new ArrayList<EdgeKey>();
      for (int i = 0; i < 10; i++) {
        Assert.assertTrue(cursor.tryAdvance(pair -> seen.add(pair.first())));
      }
      Assertions.assertThat(seen).containsExactlyElementsOf(keys.subList(start, start + 10));
      var owner = op.loadPageForRead(bTree.getFileId(), leaf);
      try {
        int pins = pinState(owner);
        Assert.assertTrue(pins > 0);
        Assert.assertEquals(leaf, search(keys.get(boundary - 5), op).getPageIndex());
        doAnswer(call -> {
          long page = call.getArgument(1);
          if (page == leaf) {
            startingLeafLoads.incrementAndGet();
          }
          if (page == sibling && fired.compareAndSet(false, true)) {
            Assert.assertTrue("The scan must load its starting leaf before the sibling",
                startingLeafLoads.get() > 0);
            throw failure;
          }
          return call.callRealMethod();
        }).when(reader).loadPageForRead(eq(bTree.getFileId()), anyLong());

        // Five entries remain on the first leaf. The failure must publish none of them.
        if (failure instanceof IOException) {
          var reported = Assert.assertThrows(StorageException.class,
              () -> cursor.tryAdvance(pair -> seen.add(pair.first())));
          Assert.assertTrue(reported.getMessage().contains("Error during entity iteration"));
          Assert.assertSame(failure, reported.getCause());
        } else {
          Assert.assertSame(failure, Assert.assertThrows(IllegalStateException.class,
              () -> cursor.tryAdvance(pair -> seen.add(pair.first()))));
        }
        Assert.assertTrue(fired.get());
        Assert.assertEquals("Another owner's pin must remain", pins, pinState(owner));
        Assert.assertEquals(10, seen.size());

        // Check the first retry call. Draining alone can hide a partially published batch.
        startingLeafLoads.set(0);
        Assert.assertTrue(cursor.tryAdvance(pair -> seen.add(pair.first())));
        Assert.assertTrue("Retry must reread the starting leaf", startingLeafLoads.get() > 0);
        Assert.assertEquals(keys.get(start + 10), seen.getLast());
        cursor.forEachRemaining(pair -> seen.add(pair.first()));
        Assertions.assertThat(seen).containsExactlyElementsOf(keys.subList(start, keys.size()));
      } finally {
        // Do not mask the assertion if a broken scan already released the owner's pin.
        if (pinState(owner) > 0) {
          owner.close();
        }
      }
    });
  }

  @Test
  public void refillFindsVisibleDataBeyondSeveralInvisibleLeavesInBothDirections()
      throws Exception {
    long bag = RID_BAG_ID + 1;
    var low = new ArrayList<EdgeKey>();
    var high = new ArrayList<EdgeKey>();
    long invisibleTs = Long.MAX_VALUE - 1;
    atomicOperationsManager.executeInsideAtomicOperation(op -> {
      for (int i = 0; i < 10; i++) {
        low.add(new EdgeKey(bag, 0, i, 0));
        high.add(new EdgeKey(bag, 0, 2058 + i, 0));
        bTree.put(op, low.getLast(), new LinkBagValue(1, 0, 0, false));
        bTree.put(op, high.getLast(), new LinkBagValue(2, 0, 0, false));
      }
      for (int i = 10; i < 2058; i++) {
        bTree.put(op, new EdgeKey(bag, 0, i, invisibleTs), new LinkBagValue(1, 0, 0, false));
      }
    });
    atomicOperationsManager.executeInsideAtomicOperation(op -> {
      var pages = new HashSet<Long>();
      for (int i : new int[] {512, 1024, 1536}) {
        var position = search(new EdgeKey(bag, 0, i, invisibleTs), op);
        Assert.assertTrue(position.getItemIndex() >= 0);
        pages.add(position.getPageIndex());
      }
      Assert.assertEquals(3, pages.size());
      var insideLow = new EdgeKey(bag, 0, 10, invisibleTs);
      var insideHigh = new EdgeKey(bag, 0, 2057, invisibleTs);
      for (boolean forward : new boolean[] {true, false}) {
        var beyondGap = forward ? high : low.reversed();

        // The initial refill starts inside the invisible run with an empty batch.
        try (var stream = bTree.streamEntriesBetween(
            forward ? insideLow : low.getFirst(), true,
            forward ? high.getLast() : insideHigh, true, forward, op)) {
          Assertions.assertThat(stream.map(RawPair::first).toList())
              .containsExactlyElementsOf(beyondGap);
        }

        // A full batch ends just before the gap. The next refill must cross it empty.
        var cursor = bTree.spliteratorEntriesBetween(
            low.getFirst(), true, high.getLast(), true, forward, op);
        var seen = new ArrayList<EdgeKey>();
        for (int i = 0; i < 10; i++) {
          Assert.assertTrue(cursor.tryAdvance(pair -> seen.add(pair.first())));
        }
        Assertions.assertThat(seen).containsExactlyElementsOf(forward ? low : high.reversed());
        Assert.assertTrue("The next refill must reach visible data beyond the gap",
            cursor.tryAdvance(pair -> seen.add(pair.first())));
        Assert.assertEquals(beyondGap.getFirst(), seen.getLast());
        cursor.forEachRemaining(pair -> seen.add(pair.first()));
        Assertions.assertThat(seen.subList(10, seen.size())).containsExactlyElementsOf(beyondGap);
      }
    });
  }

  private List<EdgeKey> orderedKeys(boolean forward) {
    return new ArrayList<>(forward ? reference.keySet() : reference.descendingKeySet());
  }

  private int firstSiblingOffset(List<EdgeKey> keys, AtomicOperation op) throws Exception {
    long firstPage = search(keys.getFirst(), op).getPageIndex();
    for (int i = 1; i < keys.size(); i++) {
      if (search(keys.get(i), op).getPageIndex() != firstPage) {
        Assert.assertTrue("The starting leaf must hold at least 15 entries", i >= 15);
        return i;
      }
    }
    throw new AssertionError("The fixture must span more than one leaf");
  }

  private BucketSearchResult search(EdgeKey key, AtomicOperation op) throws Exception {
    var method = SharedLinkBagBTree.class.getDeclaredMethod(
        "findBucket", EdgeKey.class, AtomicOperation.class);
    method.setAccessible(true);
    return (BucketSearchResult) method.invoke(bTree, key, op);
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
   * the forward cursor refill.
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
   * the backward cursor refill.
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
   * four combinations of bound inclusivity in both directions.
   */
  @Test
  public void testStreamEntriesBetweenAllInclusivityCombinations() throws Exception {
    var first = reference.firstKey();
    var last = reference.lastKey();
    var missingFrom = new EdgeKey(first.ridBagId, first.targetCollection, first.targetPosition, 1);
    var missingTo = new EdgeKey(last.ridBagId, last.targetCollection, last.targetPosition, -1);
    Assert.assertFalse(reference.containsKey(missingFrom));
    Assert.assertFalse(reference.containsKey(missingTo));

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      for (boolean present : new boolean[] {true, false}) {
        var fromKey = present ? first : missingFrom;
        var toKey = present ? last : missingTo;
        for (boolean fromInclusive : new boolean[] {true, false}) {
          for (boolean toInclusive : new boolean[] {true, false}) {
            var expected = reference.subMap(fromKey, fromInclusive, toKey, toInclusive);
            for (boolean forward : new boolean[] {true, false}) {
              var ordered = forward ? expected : expected.descendingMap();
              try (var stream = bTree.streamEntriesBetween(
                  fromKey, fromInclusive, toKey, toInclusive, forward, atomicOperation)) {
                assertStreamMatchesIterator(stream.iterator(), ordered.entrySet().iterator());
              }
            }
          }
        }
      }
    });
  }

  // ------------------------------------------------------------------
  // Validated-null pinned re-check (belt-and-braces, YTDB-1178)
  // ------------------------------------------------------------------

  /**
   * A lookup of a truly absent key still returns null through both point-lookup entry
   * points. With all pages cached, the optimistic attempt validates cleanly (stamps and
   * epoch), its null result triggers the pinned re-check under the component shared
   * lock, the re-check confirms the miss (agreement), and the null is returned silently
   * — no disagreement report, no behavior change for legitimate misses.
   */
  @Test
  public void testAbsentKeyLookupsConfirmNullThroughPinnedRecheck() throws Exception {
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      for (var i = ENTRY_COUNT; i < ENTRY_COUNT + 25; i++) {
        Assert.assertNull(
            "findCurrentEntry must confirm the miss for absent key index " + i,
            bTree.findCurrentEntry(atomicOperation, RID_BAG_ID, i % 32, i));
        Assert.assertNull(
            "findVisibleEntry must confirm the miss for absent key index " + i,
            bTree.findVisibleEntry(atomicOperation, RID_BAG_ID, i % 32, i));
      }
    });
  }

  // ------------------------------------------------------------------
  // Helpers
  // ------------------------------------------------------------------

  // ------------------------------------------------------------------
  // remove() — exercises removeEntryByKey() and the lambda$remove$13 path
  // ------------------------------------------------------------------

  /**
   * remove() on a key that was inserted in the same B-tree session must return the
   * original value and make the key absent on subsequent get() calls.  This exercises
   * the remove() body and the lambda$remove$13 path.
   */
  @Test
  public void testRemoveExistingKeyReturnsOldValue() throws Exception {
    var key = new EdgeKey(RID_BAG_ID, 0, 0, 0L); // key inserted in setUp()
    var expectedValue = reference.get(key);

    LinkBagValue removed = atomicOperationsManager.calculateInsideAtomicOperation(
        atomicOperation -> bTree.remove(atomicOperation, key));

    Assert.assertNotNull("remove() should return the old value", removed);
    Assert.assertEquals(expectedValue.counter(), removed.counter());

    // The key must now be absent.
    LinkBagValue afterRemove = atomicOperationsManager.calculateInsideAtomicOperation(
        atomicOperation -> bTree.get(key, atomicOperation));
    Assert.assertNull("get() after remove() should return null", afterRemove);
  }

  /**
   * remove() on a key that does not exist must return null without throwing.
   */
  @Test
  public void testRemoveAbsentKeyReturnsNull() throws Exception {
    var absentKey = new EdgeKey(RID_BAG_ID + 9999, 0, 0, 0L);

    LinkBagValue result = atomicOperationsManager.calculateInsideAtomicOperation(
        atomicOperation -> bTree.remove(atomicOperation, absentKey));
    Assert.assertNull("remove() on absent key should return null", result);
  }

  // ------------------------------------------------------------------
  // put() cross-transaction replacement path (lambda$put$8 lines)
  // ------------------------------------------------------------------

  /**
   * Inserting a key with the same logical edge (ridBagId, targetCollection, targetPosition)
   * but a different ts (cross-transaction update) must replace the old entry and
   * preserve it in the snapshot index. This exercises the cross-transaction branch
   * inside the put() lambda.
   *
   * <p>The previous version of this test only asserted that {@code bTree.get(key2)}
   * returned non-null after the second put — but EdgeKey includes ts in equality, so
   * key1=(unique, 5, 10, 1) and key2=(unique, 5, 10, 2) are two distinct keys; an
   * INSERT (rather than the documented REPLACE) would also have left key2 in the tree
   * and the test would have passed.
   *
   * <p>Three falsifiable pins now distinguish replace from insert:
   * <ol>
   *   <li>{@code put(key2)} must return {@code false} — the production code
   *       overrides the standard "fresh insert" {@code true} result to {@code false}
   *       at the end of the lambda when {@code crossTxReplacement} fired. A pure
   *       insert path would return {@code true}.</li>
   *   <li>{@code get(key1)} must return {@code null} — the cross-tx branch calls
   *       {@code removeEntryByKey(oldKey)}; if replacement degraded to insert, key1
   *       would still be readable.</li>
   *   <li>{@code get(key2)} must return the new value — pins the new entry's
   *       presence.</li>
   * </ol>
   */
  @Test
  public void testPutCrossTransactionReplacement() throws Exception {
    // Use a unique ridBagId to avoid collisions with setUp() data.
    long uniqueRidBagId = RID_BAG_ID + 1000;
    var key1 = new EdgeKey(uniqueRidBagId, 5, 10L, 1L); // ts=1
    var value1 = new LinkBagValue(100, 0, 0, false);

    // First insert (ts=1) is a fresh insert and must return true.
    boolean firstInsertResult = atomicOperationsManager.calculateInsideAtomicOperation(
        atomicOperation -> bTree.put(atomicOperation, key1, value1));
    Assert.assertTrue("first put on a new logical edge must report a fresh insert",
        firstInsertResult);

    // Cross-transaction update: same logical edge, higher ts.
    var key2 = new EdgeKey(uniqueRidBagId, 5, 10L, 2L); // ts=2 (same edge, newer ts)
    var value2 = new LinkBagValue(200, 0, 0, false);

    // The cross-tx branch overrides result to false; a pure insert would return true.
    boolean replaceResult = atomicOperationsManager.calculateInsideAtomicOperation(
        atomicOperation -> bTree.put(atomicOperation, key2, value2));
    Assert.assertFalse(
        "cross-tx put on the same logical edge must report replacement (false)",
        replaceResult);

    // The OLD key (ts=1) must no longer be in the tree — the cross-tx branch
    // calls removeEntryByKey(oldKey) before inserting the new key. If replacement
    // degraded to insert, key1 would still be readable.
    LinkBagValue oldEntryAfterReplace = atomicOperationsManager.calculateInsideAtomicOperation(
        atomicOperation -> bTree.get(key1, atomicOperation));
    Assert.assertNull("old key (ts=1) must be absent after cross-tx replacement",
        oldEntryAfterReplace);

    // The NEW key must be present with its new value.
    LinkBagValue newEntry = atomicOperationsManager.calculateInsideAtomicOperation(
        atomicOperation -> bTree.get(key2, atomicOperation));
    Assert.assertNotNull("new key must be present after cross-tx replacement", newEntry);
    Assert.assertEquals(200, newEntry.counter());

    // Clean up the test entry.
    atomicOperationsManager.executeInsideAtomicOperation(
        atomicOperation -> bTree.remove(atomicOperation, key2));
  }

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
