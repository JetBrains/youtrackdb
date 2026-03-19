package com.jetbrains.youtrackdb.internal.core.storage.index.edgebtree.btree;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.EdgeKey;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.LinkBagValue;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.SharedLinkBagBTree;
import java.io.File;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Tests for {@link SharedLinkBagBTree#findCurrentEntry} — the prefix lookup
 * helper that finds the current B-tree entry for a logical edge identified by
 * the 3-tuple (ridBagId, targetCollection, targetPosition), regardless of its
 * timestamp.
 */
public class SharedLinkBagBTreeFindCurrentEntryTest {

  private static final String DB_NAME = "findCurrentEntryTest";
  private static final String DIR_NAME = "/findCurrentEntryTest";

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
    bTree = new SharedLinkBagBTree(storage, "prefixLookup", ".sbc");
    atomicOperationsManager.executeInsideAtomicOperation(
        atomicOperation -> bTree.create(atomicOperation));
  }

  @After
  public void afterMethod() throws Exception {
    atomicOperationsManager.executeInsideAtomicOperation(
        atomicOperation -> bTree.delete(atomicOperation));
  }

  // ---- Empty tree ----

  @Test
  public void testEmptyTreeReturnsNull() throws Exception {
    // An empty tree should return null for any prefix lookup.
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var result = bTree.findCurrentEntry(atomicOperation, 1L, 10, 100L);
      assertThat(result).isNull();
    });
  }

  // ---- Single entry ----

  @Test
  public void testSingleEntryFound() throws Exception {
    // Insert one entry with ts=42 and look it up by 3-tuple prefix.
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var key = new EdgeKey(1L, 10, 100L, 42L);
      var value = new LinkBagValue(7, 0, 0, false);
      bTree.put(atomicOperation, key, value);
    });

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var result = bTree.findCurrentEntry(atomicOperation, 1L, 10, 100L);
      assertThat(result).isNotNull();
      assertThat(result.first()).isEqualTo(new EdgeKey(1L, 10, 100L, 42L));
      assertThat(result.second()).isEqualTo(new LinkBagValue(7, 0, 0, false));
    });
  }

  @Test
  public void testSingleEntryWithMinValueTs() throws Exception {
    // Edge case: entry stored with ts=Long.MIN_VALUE (exact match of search key).
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var key = new EdgeKey(1L, 10, 100L, Long.MIN_VALUE);
      var value = new LinkBagValue(1, 0, 0, false);
      bTree.put(atomicOperation, key, value);
    });

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var result = bTree.findCurrentEntry(atomicOperation, 1L, 10, 100L);
      assertThat(result).isNotNull();
      assertThat(result.first().ts).isEqualTo(Long.MIN_VALUE);
    });
  }

  @Test
  public void testSingleEntryWithNegativeTs() throws Exception {
    // Verify that moderately negative ts values (between Long.MIN_VALUE and 0)
    // are found correctly — compareTo uses < (no overflow risk from subtraction).
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var key = new EdgeKey(1L, 10, 100L, -100L);
      var value = new LinkBagValue(1, 0, 0, false);
      bTree.put(atomicOperation, key, value);
    });

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var result = bTree.findCurrentEntry(atomicOperation, 1L, 10, 100L);
      assertThat(result).isNotNull();
      assertThat(result.first().ts).isEqualTo(-100L);
    });
  }

  @Test
  public void testSingleEntryWithMaxValueTs() throws Exception {
    // Entry stored with ts=Long.MAX_VALUE — furthest from the search key.
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var key = new EdgeKey(1L, 10, 100L, Long.MAX_VALUE);
      var value = new LinkBagValue(1, 0, 0, false);
      bTree.put(atomicOperation, key, value);
    });

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var result = bTree.findCurrentEntry(atomicOperation, 1L, 10, 100L);
      assertThat(result).isNotNull();
      assertThat(result.first().ts).isEqualTo(Long.MAX_VALUE);
    });
  }

  // ---- Entry not found (different ridBagId/tc/tp) ----

  @Test
  public void testDifferentRidBagIdNotFound() throws Exception {
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      bTree.put(atomicOperation, new EdgeKey(1L, 10, 100L, 5L),
          new LinkBagValue(1, 0, 0, false));
    });

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      // Same tc and tp, different ridBagId
      assertThat(bTree.findCurrentEntry(atomicOperation, 2L, 10, 100L)).isNull();
    });
  }

  @Test
  public void testDifferentTargetCollectionNotFound() throws Exception {
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      bTree.put(atomicOperation, new EdgeKey(1L, 10, 100L, 5L),
          new LinkBagValue(1, 0, 0, false));
    });

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      // Same ridBagId and tp, different tc
      assertThat(bTree.findCurrentEntry(atomicOperation, 1L, 11, 100L)).isNull();
    });
  }

  @Test
  public void testDifferentTargetPositionNotFound() throws Exception {
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      bTree.put(atomicOperation, new EdgeKey(1L, 10, 100L, 5L),
          new LinkBagValue(1, 0, 0, false));
    });

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      // Same ridBagId and tc, different tp
      assertThat(bTree.findCurrentEntry(atomicOperation, 1L, 10, 101L)).isNull();
    });
  }

  // ---- Multiple entries — correct entry identified ----

  @Test
  public void testMultipleEntriesDifferentRidBags() throws Exception {
    // Multiple entries with different ridBagId — findCurrentEntry returns the
    // correct one.
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      bTree.put(atomicOperation, new EdgeKey(1L, 10, 100L, 5L),
          new LinkBagValue(1, 0, 0, false));
      bTree.put(atomicOperation, new EdgeKey(2L, 10, 100L, 7L),
          new LinkBagValue(2, 0, 0, false));
      bTree.put(atomicOperation, new EdgeKey(3L, 10, 100L, 9L),
          new LinkBagValue(3, 0, 0, false));
    });

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var result = bTree.findCurrentEntry(atomicOperation, 2L, 10, 100L);
      assertThat(result).isNotNull();
      assertThat(result.first().ridBagId).isEqualTo(2L);
      assertThat(result.first().ts).isEqualTo(7L);
      assertThat(result.second().counter()).isEqualTo(2);
    });
  }

  @Test
  public void testMultipleEntriesDifferentTargetPositions() throws Exception {
    // Multiple entries same ridBagId and tc, different tp — lookup finds exact
    // tp match.
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      for (int i = 0; i < 20; i++) {
        bTree.put(atomicOperation, new EdgeKey(42L, 5, i, 100L),
            new LinkBagValue(i, 0, 0, false));
      }
    });

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var result = bTree.findCurrentEntry(atomicOperation, 42L, 5, 10L);
      assertThat(result).isNotNull();
      assertThat(result.first().targetPosition).isEqualTo(10L);
      assertThat(result.second().counter()).isEqualTo(10);

      // Non-existent target position
      assertThat(bTree.findCurrentEntry(atomicOperation, 42L, 5, 999L)).isNull();
    });
  }

  // ---- Tombstone entries ----

  @Test
  public void testFindsTombstoneEntry() throws Exception {
    // findCurrentEntry should return tombstone entries — it's up to the caller
    // to decide what to do with them.
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      bTree.put(atomicOperation, new EdgeKey(1L, 10, 100L, 50L),
          new LinkBagValue(1, 0, 0, true));
    });

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var result = bTree.findCurrentEntry(atomicOperation, 1L, 10, 100L);
      assertThat(result).isNotNull();
      assertThat(result.second().tombstone()).isTrue();
    });
  }

  // ---- Boundary: first and last entries in tree ----

  @Test
  public void testFindsFirstEntryInTree() throws Exception {
    // The entry we search for is the very first entry in the tree.
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      bTree.put(atomicOperation, new EdgeKey(1L, 0, 0L, 1L),
          new LinkBagValue(1, 0, 0, false));
      bTree.put(atomicOperation, new EdgeKey(2L, 10, 100L, 5L),
          new LinkBagValue(2, 0, 0, false));
      bTree.put(atomicOperation, new EdgeKey(3L, 20, 200L, 9L),
          new LinkBagValue(3, 0, 0, false));
    });

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var result = bTree.findCurrentEntry(atomicOperation, 1L, 0, 0L);
      assertThat(result).isNotNull();
      assertThat(result.first()).isEqualTo(new EdgeKey(1L, 0, 0L, 1L));
    });
  }

  @Test
  public void testFindsLastEntryInTree() throws Exception {
    // The entry we search for is the very last entry in the tree.
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      bTree.put(atomicOperation, new EdgeKey(1L, 0, 0L, 1L),
          new LinkBagValue(1, 0, 0, false));
      bTree.put(atomicOperation, new EdgeKey(2L, 10, 100L, 5L),
          new LinkBagValue(2, 0, 0, false));
      bTree.put(atomicOperation, new EdgeKey(3L, 20, 200L, 9L),
          new LinkBagValue(3, 0, 0, false));
    });

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var result = bTree.findCurrentEntry(atomicOperation, 3L, 20, 200L);
      assertThat(result).isNotNull();
      assertThat(result.first()).isEqualTo(new EdgeKey(3L, 20, 200L, 9L));
    });
  }

  // ---- After bucket splits (many entries) ----

  @Test
  public void testFindAfterBucketSplit() throws Exception {
    // Insert enough entries to trigger multiple bucket splits, then verify
    // that findCurrentEntry correctly finds entries that may have been placed
    // on sibling pages after splits.
    final int entryCount = 500;

    for (int i = 0; i < entryCount; i++) {
      final int idx = i;
      atomicOperationsManager
          .executeInsideAtomicOperation(atomicOperation -> bTree.put(atomicOperation,
              new EdgeKey(42L, idx % 32000, idx, (long) idx + 1),
              new LinkBagValue(idx, 0, 0, false)));
    }

    // Verify every entry can be found by prefix lookup
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      for (int i = 0; i < entryCount; i++) {
        var result = bTree.findCurrentEntry(atomicOperation, 42L, i % 32000, i);
        assertThat(result)
            .as("Entry with targetPosition=%d should be found", i)
            .isNotNull();
        assertThat(result.first().targetPosition).isEqualTo(i);
        assertThat(result.first().ts).isEqualTo((long) i + 1);
        assertThat(result.second().counter()).isEqualTo(i);
      }
    });
  }

  @Test
  public void testFindAfterSplitWithVariousTimestamps() throws Exception {
    // Insert entries with the same ridBagId and targetCollection but different
    // targetPositions and varied timestamps. This exercises the split boundary
    // case where the separator key may fall between Long.MIN_VALUE and the
    // actual ts, requiring the right-sibling fallback.
    final int entryCount = 300;

    for (int i = 0; i < entryCount; i++) {
      final int idx = i;
      // Use large ts values to maximize distance from Long.MIN_VALUE search key
      final long ts = Long.MAX_VALUE - idx;
      atomicOperationsManager
          .executeInsideAtomicOperation(atomicOperation -> bTree.put(atomicOperation,
              new EdgeKey(1L, 1, idx, ts),
              new LinkBagValue(idx, 0, 0, false)));
    }

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      for (int i = 0; i < entryCount; i++) {
        var result = bTree.findCurrentEntry(atomicOperation, 1L, 1, i);
        assertThat(result)
            .as("Entry with targetPosition=%d should be found", i)
            .isNotNull();
        assertThat(result.first().targetPosition).isEqualTo(i);
        assertThat(result.second().counter()).isEqualTo(i);
      }

      // Non-existent entries
      assertThat(bTree.findCurrentEntry(atomicOperation, 1L, 1, entryCount)).isNull();
      assertThat(bTree.findCurrentEntry(atomicOperation, 1L, 2, 0L)).isNull();
      assertThat(bTree.findCurrentEntry(atomicOperation, 99L, 1, 0L)).isNull();
    });
  }

  @Test
  public void testFindWithDenseEntriesSamePrefix() throws Exception {
    // Insert entries whose 3-tuple prefixes are adjacent (consecutive
    // targetPositions). After splits, prefix lookup must distinguish between
    // closely-spaced entries.
    final int entryCount = 200;

    for (int i = 0; i < entryCount; i++) {
      final int idx = i;
      atomicOperationsManager
          .executeInsideAtomicOperation(atomicOperation -> bTree.put(atomicOperation,
              new EdgeKey(10L, 5, idx, 1000L + idx),
              new LinkBagValue(idx, 0, 0, false)));
    }

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      // Check a few specific entries
      var first = bTree.findCurrentEntry(atomicOperation, 10L, 5, 0L);
      assertThat(first).isNotNull();
      assertThat(first.first().ts).isEqualTo(1000L);

      var last = bTree.findCurrentEntry(atomicOperation, 10L, 5, entryCount - 1);
      assertThat(last).isNotNull();
      assertThat(last.first().ts).isEqualTo(1000L + entryCount - 1);

      var mid = bTree.findCurrentEntry(atomicOperation, 10L, 5, entryCount / 2);
      assertThat(mid).isNotNull();
      assertThat(mid.first().ts).isEqualTo(1000L + entryCount / 2);

      // Gap between existing entries
      assertThat(bTree.findCurrentEntry(atomicOperation, 10L, 5, entryCount)).isNull();
    });
  }

  // ---- Non-match at candidate index (exercises checkEntryPrefix false branches) ----

  @Test
  public void testNonMatchAtCandidateIndexDifferentRidBagId() throws Exception {
    // Insert entries so that the candidate at the insertion point has a different
    // ridBagId. Search key (1, 5, 50, MIN) lands at an entry with ridBagId=2.
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      bTree.put(atomicOperation, new EdgeKey(0L, 5, 50L, 10L),
          new LinkBagValue(0, 0, 0, false));
      bTree.put(atomicOperation, new EdgeKey(2L, 5, 50L, 10L),
          new LinkBagValue(2, 0, 0, false));
    });

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      // (1, 5, 50, MIN) sorts between (0,5,50,10) and (2,5,50,10).
      // Candidate at insertion point is (2,5,50,10) — ridBagId mismatch.
      assertThat(bTree.findCurrentEntry(atomicOperation, 1L, 5, 50L)).isNull();
    });
  }

  @Test
  public void testNonMatchAtCandidateIndexDifferentTargetCollection() throws Exception {
    // Candidate entry has same ridBagId but different targetCollection.
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      bTree.put(atomicOperation, new EdgeKey(1L, 4, 50L, 10L),
          new LinkBagValue(0, 0, 0, false));
      bTree.put(atomicOperation, new EdgeKey(1L, 6, 50L, 10L),
          new LinkBagValue(1, 0, 0, false));
    });

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      // (1, 5, 50, MIN) sorts between (1,4,50,10) and (1,6,50,10).
      // Candidate is (1,6,50,10) — ridBagId matches but tc doesn't.
      assertThat(bTree.findCurrentEntry(atomicOperation, 1L, 5, 50L)).isNull();
    });
  }

  @Test
  public void testNonMatchAtCandidateIndexDifferentTargetPosition() throws Exception {
    // Candidate entry has same ridBagId and targetCollection but different
    // targetPosition.
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      bTree.put(atomicOperation, new EdgeKey(1L, 5, 49L, 10L),
          new LinkBagValue(0, 0, 0, false));
      bTree.put(atomicOperation, new EdgeKey(1L, 5, 51L, 10L),
          new LinkBagValue(1, 0, 0, false));
    });

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      // (1, 5, 50, MIN) sorts between (1,5,49,10) and (1,5,51,10).
      // Candidate is (1,5,51,10) — ridBagId and tc match, tp doesn't.
      assertThat(bTree.findCurrentEntry(atomicOperation, 1L, 5, 50L)).isNull();
    });
  }
}
