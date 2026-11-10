package com.jetbrains.youtrackdb.internal.core.storage.collection.v2;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.exception.HighLevelException;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.CollectionSizeLimitException;
import com.jetbrains.youtrackdb.internal.core.exception.PaginatedCollectionException;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.storage.collection.CollectionPage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.StorageBirthTestSupport;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.PageFileIdentity;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.PageFileNames;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.PageFileRole;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageFormat;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import java.io.IOException;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@Category(SequentialTest.class)
@RunWith(Parameterized.class)
public class FreeSpaceMapTest {

  @Parameterized.Parameters(name = "layout={0}")
  public static Object[] layouts() {
    return new Object[] {StorageFormat.LAYOUT_27, StorageFormat.LAYOUT_28};
  }

  @Parameterized.Parameter
  public StorageFormat format;

  private PageFileIdentity identity(PageFileRole role, int componentId) {
    return format == StorageFormat.LAYOUT_28 ? new PageFileIdentity(role, componentId) : null;
  }

  private String mapFileName() {
    return format == StorageFormat.LAYOUT_28
        ? PageFileNames.layout28(identity(PageFileRole.FREE_SPACE_MAP, 9000)) : "map.fsm";
  }

  private PaginatedCollectionV2 collection(String name) throws IOException {
    var result = new PaginatedCollectionV2(name, ".pcl", ".cpm", ".fsm", ".dpb", storage,
        identity(PageFileRole.RECORD_DATA, 9001));
    result.configure(55, name, format == StorageFormat.LAYOUT_28 ? 9001 : null);
    return result;
  }

  private static final int N = FreeSpaceMapPage.CELLS_PER_PAGE;
  private static final String DIRECTORY =
      System.getProperty("buildDirectory", "target") + "/threeLevelFsm";
  private YouTrackDBImpl db;
  private AbstractStorage storage;
  private AtomicOperationsManager manager;
  private FreeSpaceMap map;

  @Before
  public void setUp() throws Exception {
    db = (YouTrackDBImpl) YourTracks.instance(DIRECTORY);
    try (var scope = StorageBirthTestSupport.selectBirthFormat(db, "fsm", format)) {
      db.create("fsm", DatabaseType.DISK,
          new LocalUserCredential("admin", "admin", PredefinedLocalRole.ADMIN));
    }
    openMap(false);
    withCreationLock(op -> map.create(op));
  }

  // Identity-owned creation and auxiliary reconstruction hold the state lock through commit.
  private void withCreationLock(
      com.jetbrains.youtrackdb.internal.common.function.TxConsumer action)
      throws IOException {
    final java.util.concurrent.locks.ReadWriteLock stateLock;
    try {
      var field = AbstractStorage.class.getDeclaredField("stateLock");
      field.setAccessible(true);
      stateLock = (java.util.concurrent.locks.ReadWriteLock) field.get(storage);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
    stateLock.writeLock().lock();
    try {
      manager.executeInsideAtomicOperation(action);
    } finally {
      stateLock.writeLock().unlock();
    }
  }

  private void openMap(boolean reopen) throws IOException {
    try (var session = db.open("fsm", "admin", "admin")) {
      storage = session.getStorage();
    }
    manager = storage.getAtomicOperationsManager();
    Assert.assertEquals(format, storage.getStorageFormat());
    map = new FreeSpaceMap(storage, "map", ".fsm", "map",
        identity(PageFileRole.FREE_SPACE_MAP, 9000));
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
        op -> Assert.assertEquals(1, op.filledUpTo(op.fileIdByName(mapFileName()))));
    for (int index : indexes) {
      manager.executeInsideAtomicOperation(op -> {
        map.updatePageFreeSpace(op, index, 512);
        long d = index + 1L;
        Assert.assertEquals(1 + (d + N * N - 1) / (N * N) + (d + N - 1) / N,
            op.filledUpTo(op.fileIdByName(mapFileName())));
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
      long id = op.fileIdByName(mapFileName());
      Assert.assertFalse(op.hasChangesForPage(id, 1));
      Assert.assertFalse(op.hasChangesForPage(id, 0));
      Assert.assertTrue(op.hasChangesForPage(id, 2));
    });
    manager.executeInsideAtomicOperation(op -> {
      map.updatePageFreeSpace(op, N, 512);
      long id = op.fileIdByName(mapFileName());
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
        try (var entry = op.loadPageForWrite(op.fileIdByName(mapFileName()), filePage, 1, true)) {
          new FreeSpaceMapPage(entry).updatePageMaxFreeSpace(0, 0);
        }
        Assert.assertThrows(IllegalStateException.class, () -> map.findFreePage(259, op));
        map.updatePageFreeSpace(op, 0, 0);
        map.updatePageFreeSpace(op, 0, 512);
      });
    }
  }

  // The tree capacity limits 2 KB pages. Int bookkeeping limits 4 KB and 8 KB pages.
  // The last allowed index fits and the first refused index lies outside the shared ceiling.
  @Test
  public void collectionLimitUsesTreeCapacityAndIntBookkeeping() {
    Assert.assertEquals(991_026_972, FreeSpaceMap.maxDataPageIndex(997));
    Assert.assertEquals(Integer.MAX_VALUE - 1, FreeSpaceMap.maxDataPageIndex(2021));
    Assert.assertEquals(Integer.MAX_VALUE - 1, FreeSpaceMap.maxDataPageIndex(4069));
    long last = FreeSpaceMap.MAX_DATA_PAGE_INDEX;
    Assert.assertEquals((int) last, FreeSpaceMap.dataPageIndex(last / N, (int) (last % N)));
    long refused = last + 1;
    Assert.assertEquals(-1, FreeSpaceMap.dataPageIndex(refused / N, (int) (refused % N)));
  }

  // A real storage commit runs out of collection address space after writing into an existing
  // page. Rollback preserves data, counters and file extents. A later commit still succeeds.
  @Test
  public void sizeRefusalRollsBackAndStorageRemainsUsable() throws IOException {
    try (var session = db.open("fsm", "admin", "admin")) {
      storage.getContextConfiguration().setValue(
          com.jetbrains.youtrackdb.api.config.GlobalConfiguration.CLASS_COLLECTIONS_COUNT, 1);
      session.command("CREATE CLASS SizeLimit");
      session.begin();
      var kept = session.newEntity("SizeLimit");
      kept.setProperty("value", "kept");
      session.commit();
      var collectionId = kept.getIdentity().getCollectionId();
      Assert.assertEquals(1, session.getClass("SizeLimit").getCollectionIds().length);
      var collection = (PaginatedCollectionV2) storage.getCollectionInstances().stream()
          .filter(c -> c.getId() == collectionId).findFirst().orElseThrow();
      long fileId = collection.getFileId();
      final int[] originalSize = new int[1];
      final long[] physicalSize = new long[1];
      manager.executeInsideAtomicOperation(op -> {
        try (var entry = op.loadPageForWrite(fileId, 0, 1, true)) {
          var state = new PaginatedCollectionStateV2(entry);
          originalSize[0] = state.getFileSize();
          state.setFileSize(FreeSpaceMap.MAX_DATA_PAGE_INDEX);
        }
        physicalSize[0] = op.filledUpTo(fileId);
      });

      manager.executeInsideAtomicOperation(op -> {
        try (var entry = op.loadPageForRead(fileId, 0)) {
          Assert.assertEquals(FreeSpaceMap.MAX_DATA_PAGE_INDEX,
              new PaginatedCollectionStateV2(entry).getFileSize());
        }
      });
      session.begin();
      session.newEntity("SizeLimit").setProperty("value", "rolled back");
      var payload = new byte[32_768];
      new java.util.Random(42).nextBytes(payload);
      session.newEntity("SizeLimit").setProperty("value", payload);
      var refusal = Assert.assertThrows(CollectionSizeLimitException.class, session::commit);
      Assert.assertTrue(refusal instanceof BaseException);
      Assert.assertTrue(refusal instanceof HighLevelException);
      var copy = new CollectionSizeLimitException(refusal);
      Assert.assertTrue(copy.getMessage().contains(refusal.getMessage()));
      Assert.assertEquals(refusal.getDbName(), copy.getDbName());
      Assert.assertTrue(refusal.getMessage().contains(collection.getName()));
      Assert.assertTrue(refusal.getMessage().contains(
          Integer.toString(FreeSpaceMap.MAX_DATA_PAGE_INDEX)));
      if (session.isTxActive()) {
        session.rollback();
      }
      manager.executeInsideAtomicOperation(op -> {
        try (var entry = op.loadPageForRead(fileId, 0)) {
          var state = new PaginatedCollectionStateV2(entry);
          Assert.assertEquals(FreeSpaceMap.MAX_DATA_PAGE_INDEX, state.getFileSize());
          Assert.assertEquals(1, state.getApproximateRecordsCount());
        }
        Assert.assertEquals(physicalSize[0], op.filledUpTo(fileId));
      });
      session.begin();
      try (var result = session.query("SELECT value FROM SizeLimit")) {
        Assert.assertEquals("kept", result.next().getProperty("value"));
        Assert.assertFalse(result.hasNext());
      }
      session.commit();

      // Remove only the injected logical size before exercising a normal committed allocation.
      manager.executeInsideAtomicOperation(op -> {
        try (var entry = op.loadPageForWrite(fileId, 0, 1, true)) {
          new PaginatedCollectionStateV2(entry).setFileSize(originalSize[0]);
        }
      });
      session.begin();
      session.newEntity("SizeLimit").setProperty("value", payload);
      session.commit();
      session.begin();
      try (var result = session.query("SELECT count(*) AS total FROM SizeLimit")) {
        Assert.assertEquals(2L, (long) result.next().getProperty("total"));
      }
      session.commit();
    }
  }

  // Corrupt negative counts must be rejected before the FSM recovery scan.
  @Test
  public void openRejectsNegativeDataPageCount() throws IOException {
    assertInvalidDataPageCount(-1);
  }

  // The first unsupported stored count must be rejected without a huge recovery scan.
  @Test
  public void openRejectsDataPageCountAboveLimit() throws IOException {
    assertInvalidDataPageCount(FreeSpaceMap.MAX_DATA_PAGE_INDEX + 1);
  }

  private void assertInvalidDataPageCount(int invalid) throws IOException {
    var collection = collection("invalidlimit");
    withCreationLock(collection::create);
    manager.executeInsideAtomicOperation(op -> {
      try (var entry = op.loadPageForWrite(collection.getFileId(), 0, 1, true)) {
        new PaginatedCollectionStateV2(entry).setFileSize(invalid);
      }
    });
    var failure = Assert.assertThrows(StorageException.class,
        () -> manager.executeInsideAtomicOperation(collection::open));
    Throwable cause = failure;
    while (cause.getCause() != null) {
      cause = cause.getCause();
    }
    Assert.assertTrue(cause.toString(), cause instanceof PaginatedCollectionException);
    Assert.assertTrue(cause.getMessage(),
        cause.getMessage().contains("Invalid data page count " + invalid));
  }

  // A one-page rebuild restores the data page with its normalized free space and record.
  // An empty collection rebuild has no qualifying page.
  @Test
  public void rebuildRecoversEmptyAndNonemptyCollections() throws IOException {
    var collection = collection("rebuildlimit");
    withCreationLock(collection::create);
    var rebuiltMap = new FreeSpaceMap(storage, "rebuildlimit", ".fsm", "rebuildlimit",
        identity(PageFileRole.FREE_SPACE_MAP, 9001));
    var rebuiltName = format == StorageFormat.LAYOUT_28
        ? PageFileNames.layout28(identity(PageFileRole.FREE_SPACE_MAP, 9001)) : "rebuildlimit.fsm";
    withCreationLock(op -> {
      op.deleteFile(op.fileIdByName(rebuiltName));
      collection.open(op);
      rebuiltMap.open(op);
      Assert.assertEquals(-1, rebuiltMap.findFreePage(1, op));
    });
    final long[] position = new long[1];
    manager.executeInsideAtomicOperation(op -> {
      position[0] =
          collection.createRecord(new byte[] {1, 2, 3}, (byte) 'b', null, op).collectionPosition;
    });
    withCreationLock(op -> {
      op.deleteFile(op.fileIdByName(rebuiltName));
      collection.open(op);
      Assert.assertArrayEquals(new byte[] {1, 2, 3},
          collection.readRecord(position[0], op).toRawBuffer().buffer());
      try (var entry = op.loadPageForRead(collection.getFileId(), 0)) {
        Assert.assertEquals(1, new PaginatedCollectionStateV2(entry).getFileSize());
      }
      final int normalizedFreeSpace;
      try (var entry = op.loadPageForRead(collection.getFileId(), 1)) {
        normalizedFreeSpace = new CollectionPage(entry).getMaxRecordSize()
            / FreeSpaceMap.NORMALIZATION_INTERVAL * FreeSpaceMap.NORMALIZATION_INTERVAL;
      }
      Assert.assertTrue(normalizedFreeSpace > 0);
      rebuiltMap.open(op);
      // Search rounds the request up. Check both sides of the stored free-space boundary.
      Assert.assertEquals(1, rebuiltMap.findFreePage(normalizedFreeSpace - 1, op));
      Assert.assertEquals(-1, rebuiltMap.findFreePage(normalizedFreeSpace, op));
    });
  }

  // Reconstructs every boundary through the supported ceiling without creating large files.
  // The next representable tree entry must not wrap into a negative or low data page index.
  @Test
  public void addressArithmeticUsesLongsAndClampsSearchResults() {
    for (long index : new long[] {N - 1, N, N * N - 1, N * N, FreeSpaceMap.MAX_DATA_PAGE_INDEX}) {
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
