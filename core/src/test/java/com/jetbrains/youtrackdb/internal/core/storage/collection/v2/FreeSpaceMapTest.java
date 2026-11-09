package com.jetbrains.youtrackdb.internal.core.storage.collection.v2;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import java.io.IOException;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

@Category(SequentialTest.class)
public class FreeSpaceMapTest {

  private static final int N = FreeSpaceMapPage.CELLS_PER_PAGE;
  private static final String DIRECTORY =
      System.getProperty("buildDirectory", "target") + "/threeLevelFsm";
  private YouTrackDBImpl db;
  private AbstractStorage storage;
  private AtomicOperationsManager manager;
  private FreeSpaceMap map;

  @Before
  public void setUp() throws IOException {
    db = (YouTrackDBImpl) YourTracks.instance(DIRECTORY);
    db.create("fsm", DatabaseType.DISK,
        new LocalUserCredential("admin", "admin", PredefinedLocalRole.ADMIN));
    openMap(false);
    manager.executeInsideAtomicOperation(op -> map.create(op));
  }

  private void openMap(boolean reopen) throws IOException {
    try (var session = db.open("fsm", "admin", "admin")) {
      storage = session.getStorage();
    }
    manager = storage.getAtomicOperationsManager();
    map = new FreeSpaceMap(storage, "map", ".fsm", "map");
    if (reopen) {
      manager.executeInsideAtomicOperation(op -> map.open(op));
    }
  }

  @After
  public void tearDown() {
    if (db != null) {
      try {
        db.drop("fsm");
      } finally {
        db.close();
      }
    }
  }

  // Crosses both leaf and middle boundaries. Each committed extent matches the size formula.
  // Removing matches in index order proves that search returns the lowest qualifying page.
  @Test
  public void boundariesGrowthAndLowestMatch() throws IOException {
    final int[] indexes = {N - 1, N, N * N - 1, N * N, N * N + 1};
    manager.executeInsideAtomicOperation(
        op -> Assert.assertEquals(1, op.filledUpTo(op.fileIdByName("map.fsm"))));
    for (int index : indexes) {
      manager.executeInsideAtomicOperation(op -> {
        map.updatePageFreeSpace(op, index, 512);
        long d = index + 1L;
        Assert.assertEquals(1 + (d + N * N - 1) / (N * N) + (d + N - 1) / N,
            op.filledUpTo(op.fileIdByName("map.fsm")));
      });
    }
    for (int index : indexes) {
      manager.executeInsideAtomicOperation(op -> {
        Assert.assertEquals(index, map.findFreePage(259, op));
        map.updatePageFreeSpace(op, index, 0);
      });
    }
    manager.executeInsideAtomicOperation(op -> Assert.assertEquals(-1, map.findFreePage(1, op)));
  }

  // CN4: disk pages come from an earlier commit. Reopen and warm the cache, then require
  // all three optimistic stamps in a fresh operation above N^2. No own-transaction pages qualify.
  @Test
  public void committedDiskSearchUsesThreeOptimisticPages() throws IOException {
    final int index = N * N + 1;
    manager.executeInsideAtomicOperation(op -> map.updatePageFreeSpace(op, index, 512));
    db.close();
    db = (YouTrackDBImpl) YourTracks.instance(DIRECTORY);
    openMap(true);
    manager
        .executeInsideAtomicOperation(op -> Assert.assertEquals(index, map.findFreePage(259, op)));
    manager.executeInsideAtomicOperation(op -> {
      Assert.assertEquals(index, map.findFreePage(259, op));
      Assert.assertEquals(3, op.getOptimisticReadScope().count());
      op.getOptimisticReadScope().validateOrThrow();
      Assert.assertEquals(-1, map.findFreePage(1024, op));
      Assert.assertEquals(1, op.getOptimisticReadScope().count());
    });
  }

  // An unchanged leaf maximum skips both parents. A changed leaf maximum below another
  // leaf maximum updates only the middle. Skipped pages have no overlay and cannot emit WAL.
  @Test
  public void unchangedMaximaDoNotLoadParentsForWrite() throws IOException {
    manager.executeInsideAtomicOperation(op -> map.updatePageFreeSpace(op, 0, 1024));
    manager.executeInsideAtomicOperation(op -> {
      map.updatePageFreeSpace(op, 1, 512);
      long id = op.fileIdByName("map.fsm");
      Assert.assertFalse(op.hasChangesForPage(id, 1));
      Assert.assertFalse(op.hasChangesForPage(id, 0));
      Assert.assertTrue(op.hasChangesForPage(id, 2));
    });
    manager.executeInsideAtomicOperation(op -> {
      map.updatePageFreeSpace(op, N, 512);
      long id = op.fileIdByName("map.fsm");
      Assert.assertTrue(op.hasChangesForPage(id, 1));
      Assert.assertFalse(op.hasChangesForPage(id, 0));
    });
  }

  // A qualifying root with an empty middle and a qualifying middle with an empty leaf
  // are internal inconsistencies, not negative data indexes or ordinary no-match results.
  @Test
  public void qualifyingParentWithEmptyChildRaisesError() throws IOException {
    manager.executeInsideAtomicOperation(op -> map.updatePageFreeSpace(op, 0, 512));
    for (long filePage : new long[] {1, 2}) {
      manager.executeInsideAtomicOperation(op -> {
        try (var entry = op.loadPageForWrite(op.fileIdByName("map.fsm"), filePage, 1, true)) {
          new FreeSpaceMapPage(entry).updatePageMaxFreeSpace(0, 0);
        }
        Assert.assertThrows(IllegalStateException.class, () -> map.findFreePage(259, op));
        map.updatePageFreeSpace(op, 0, 0);
        map.updatePageFreeSpace(op, 0, 512);
      });
    }
  }

  // Reconstructs every boundary through Integer.MAX_VALUE without creating large files.
  // The next representable tree entry must not wrap into a negative or low data page index.
  @Test
  public void addressArithmeticUsesLongsAndClampsSearchResults() {
    for (long index : new long[] {N - 1, N, N * N - 1, N * N, Integer.MAX_VALUE}) {
      long leaf = index / N;
      long middle = leaf / N;
      Assert.assertEquals(1 + middle * (N + 1L), FreeSpaceMap.middlePageIndex(middle));
      Assert.assertEquals(leaf + middle + 2, FreeSpaceMap.leafPageIndex(leaf));
      Assert.assertEquals((int) index, FreeSpaceMap.dataPageIndex(leaf, (int) (index % N)));
    }
    long above = Integer.MAX_VALUE + 1L;
    Assert.assertEquals(-1, FreeSpaceMap.dataPageIndex(above / N, (int) (above % N)));
  }
}
