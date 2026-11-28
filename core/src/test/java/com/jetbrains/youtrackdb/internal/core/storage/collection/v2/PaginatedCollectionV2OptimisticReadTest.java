package com.jetbrains.youtrackdb.internal.core.storage.collection.v2;

import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.storage.PhysicalPosition;
import com.jetbrains.youtrackdb.internal.core.storage.RawPageBuffer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.OptimisticReadFailedException;
import com.jetbrains.youtrackdb.internal.core.storage.cache.OptimisticReadFailureCounters;
import com.jetbrains.youtrackdb.internal.core.storage.cache.OptimisticReadFailureCounters.Cause;
import com.jetbrains.youtrackdb.internal.core.storage.collection.CollectionPage;
import com.jetbrains.youtrackdb.internal.core.storage.collection.PaginatedCollection;
import com.jetbrains.youtrackdb.internal.core.storage.collection.v2.CollectionAccessProbe.PageRequest;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationTestBridge;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.Assertions;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Tests for optimistic read paths in {@link PaginatedCollectionV2}. Each test inserts records in
 * one atomic operation, commits, then reads in a separate atomic operation with no pending
 * changes. This exercises the optimistic (lock-free) read path that
 * {@code executeOptimisticStorageRead} tries before falling back to the pinned (locked) path.
 *
 * <p>Each test gets a fresh database and collection to ensure full isolation — no state leaks
 * between tests.
 *
 * <p>The following optimistic code paths are covered:
 * <ul>
 *   <li>{@code readRecord} / {@code doReadRecordOptimistic} / {@code doReadRecordOptimisticInner}
 *   <li>{@code getPhysicalPosition} / {@code doGetPhysicalPosition}
 *   <li>{@code exists} / {@code doExists}
 *   <li>{@code getFirstPosition}, {@code getLastPosition}
 *   <li>{@code higherPositions}, {@code ceilingPositions}, {@code lowerPositions},
 *       {@code floorPositions}
 *   <li>{@code getRecordStatus} / {@code doGetRecordStatus}
 *   <li>{@code getEntries} / {@code doGetEntries}
 *   <li>{@code nextPage} / {@code doNextPage}
 * </ul>
 *
 * <p>The FreeSpaceMap and CollectionPositionMapV2 optimistic paths are exercised indirectly
 * through the PaginatedCollectionV2 read operations.
 */
@Category(SequentialTest.class)
public class PaginatedCollectionV2OptimisticReadTest {

  private YouTrackDBImpl youTrackDB;
  private AbstractStorage storage;
  private PaginatedCollectionV2 collection;
  private String buildDirectory;
  private static final String DB_NAME = "optimisticReadTest";

  @Before
  public void setUp() throws IOException {
    buildDirectory = System.getProperty("buildDirectory");
    if (buildDirectory == null || buildDirectory.isEmpty()) {
      buildDirectory = ".";
    }
    buildDirectory += File.separator
        + PaginatedCollectionV2OptimisticReadTest.class.getSimpleName();
    FileUtils.deleteRecursively(new File(buildDirectory));

    youTrackDB = (YouTrackDBImpl) YourTracks.instance(buildDirectory);
    youTrackDB.create(DB_NAME, DatabaseType.DISK,
        new LocalUserCredential("admin", "admin", PredefinedLocalRole.ADMIN));
    var session = youTrackDB.open(DB_NAME, "admin", "admin");
    storage = (AbstractStorage) session.getStorage();

    collection = new PaginatedCollectionV2("optReadCollection", storage);
    collection.configure(55, "optReadCollection");
    atomicOps().executeInsideAtomicOperation(
        op -> collection.create(op));
  }

  @After
  public void tearDown() {
    youTrackDB.drop(DB_NAME);
    youTrackDB.close();
  }

  private AtomicOperationsManager atomicOps() {
    return storage.getAtomicOperationsManager();
  }

  /**
   * Closes and reopens the database so all pages are evicted from the write
   * cache. After reopening, any page load goes through the read cache,
   * enabling the optimistic read path.
   */
  private void flushToReadCache() {
    youTrackDB.close();
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(buildDirectory);
    var session = youTrackDB.open(DB_NAME, "admin", "admin");
    storage = (AbstractStorage) session.getStorage();

    // Reopen the collection with the new storage reference.
    collection = new PaginatedCollectionV2("optReadCollection", storage);
    try {
      collection.configure(55, "optReadCollection");
      atomicOps().executeInsideAtomicOperation(
          op -> collection.open(op));

      // Warm-up: read all existing records to populate the read cache
      // with all collection pages. The optimistic path only works for
      // pages already in the read cache.
      atomicOps().executeInsideAtomicOperation(op -> {
        var first = collection.getFirstPosition(op);
        var last = collection.getLastPosition(op);
        collection.getEntries(op);
        var positions = collection.ceilingPositions(
            new PhysicalPosition(first), Integer.MAX_VALUE, op);
        for (var pos : positions) {
          collection.readRecord(pos.collectionPosition, op).toRawBuffer();
        }
      });
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  public void emptyReadsAndMissingRecordWritesNeverCreatePagesEvenAfterReopen()
      throws IOException {
    assertRootExtents(0, 0, 0, 0);
    assertEmptyReads();
    atomicOps().executeInsideAtomicOperation(op -> {
      Assert.assertFalse(collection.deleteRecord(op, 0));
      Assert.assertThrows(RecordNotFoundException.class,
          () -> collection.updateRecord(0, new byte[] {1}, (byte) 2, op));
      Assert.assertThrows(RecordNotFoundException.class,
          () -> collection.updateRecordVersion(0, op));
    });
    assertRootExtents(0, 0, 0, 0);
    flushToReadCache();
    assertEmptyReads();
    assertRootExtents(0, 0, 0, 0);
  }

  @Test
  public void pagelessPointReadDoesNotRequestRootEvenOnPinnedFallback() throws IOException {
    var probe = new CollectionAccessProbe(storage, collection);
    var epoch = AtomicOperationTestBridge.applyPhaseEpoch(atomicOps(), collection.getLockName());
    var map = fileId(".cpm");
    atomicOps().executeInsideAtomicOperation(op -> {
      var observed = probe.observe(op);
      Assert.assertThrows(RecordNotFoundException.class, () -> collection.readRecord(0, observed));
      Assertions.assertThat(probe.extents).containsExactly(map);
      Assertions.assertThat(probe.pages).isEmpty();

      probe.clear();
      epoch.enterApplyPhase();
      try {
        Assert.assertThrows(RecordNotFoundException.class,
            () -> collection.readRecord(0, observed));
        // One extent check in each attempt proves that pinned fallback was reached.
        Assertions.assertThat(probe.extents).containsExactly(map, map);
        Assertions.assertThat(probe.pages).isEmpty();
      } finally {
        epoch.exitApplyPhase();
      }
    });
    assertRootExtents(0, 0, 0, 0);
  }

  @Test
  public void completeCollectionWithExistingTailOverlayDiscoversRootsOnlyOnce() throws IOException {
    atomicOps().executeInsideAtomicOperation(
        op -> collection.createRecord(new byte[] {9}, (byte) 2, null, op));
    var data = collection.getFileId();
    var map = fileId(".cpm");
    var fsm = fileId(".fsm");
    var dirty = fileId(".dpb");
    var probe = new CollectionAccessProbe(storage, collection);
    atomicOps().executeInsideAtomicOperation(op -> {
      // Reclamation can acquire a tail write overlay before its first map lookup.
      try (var ignored = op.loadPageForWrite(data, 1, 1, true)) {
        var observed = probe.observe(op);
        Assert.assertEquals(PaginatedCollection.RECORD_STATUS.PRESENT,
            collection.getRecordStatus(0, observed));
        Assertions.assertThat(probe.extents).containsExactly(map, map, data, fsm, dirty);
        probe.clear();
        Assert.assertEquals(PaginatedCollection.RECORD_STATUS.PRESENT,
            collection.getRecordStatus(0, observed));
        Assertions.assertThat(probe.extents).isEmpty();
      }
    });
  }

  @Test
  public void repeatedReservationsReuseLocalBootstrapBesideThreeCommittedRoots()
      throws IOException {
    var data = collection.getFileId();
    var map = fileId(".cpm");
    var fsm = fileId(".fsm");
    var dirty = fileId(".dpb");
    atomicOps().executeInsideAtomicOperation(op -> {
      try (var entry = op.allocatePageForWrite(data, 0)) {
        new PaginatedCollectionStateV2(entry).setFileSize(0);
      }
      try (var entry = op.allocatePageForWrite(fsm, 0)) {
        new FreeSpaceMapPage(entry).init();
      }
      try (var entry = op.allocatePageForWrite(dirty, 0)) {
        new DirtyPageBitSetPage(entry).init();
      }
    });

    var probe = new CollectionAccessProbe(storage, collection);
    atomicOps().executeInsideAtomicOperation(op -> {
      var observed = probe.observe(op);
      Assert.assertEquals(0, collection.allocatePosition((byte) 2, observed).collectionPosition);
      Assertions.assertThat(probe.extents).contains(data, fsm, dirty, map);
      probe.clear();

      Assert.assertEquals(1, collection.allocatePosition((byte) 2, observed).collectionPosition);
      Assertions.assertThat(probe.extents).isEmpty();
      assertRequests(probe, new PageRequest("write", map, 0), new PageRequest("write", map, 1));
      Assert.assertFalse(rootsPresent());
    });
    assertRootExtents(1, 2, 1, 1);
  }

  @Test
  public void warmReadsAndWritesMatchBasePageRequestsAndExtentQueries() throws IOException {
    atomicOps().executeInsideAtomicOperation(
        op -> collection.createRecord(new byte[] {9}, (byte) 2, null, op));
    var data = collection.getFileId();
    var map = fileId(".cpm");
    var fsm = fileId(".fsm");
    var dirty = fileId(".dpb");
    var probe = new CollectionAccessProbe(storage, collection);

    // A read-only operation discovers complete roots under lock after the optimistic map probe.
    atomicOps().executeInsideAtomicOperation(op -> {
      Assert.assertTrue(collection.exists(0, probe.observe(op)));
      Assertions.assertThat(probe.extents).containsExactly(map, map, data, fsm, dirty);
    });

    // Base 3a12fe9dd5: getWithStatusOptimistic loads the map root and bucket,
    // and doReadRecordOptimisticInner reads the only data page. doExists uses
    // the pinned map and data loads instead. Neither reads the physical horizon.
    probe.clear();
    atomicOps().executeInsideAtomicOperation(op -> {
      var observed = probe.observe(op);
      var result = collection.readRecord(0, observed);
      Assert.assertTrue(result instanceof RawPageBuffer);
      Assert.assertArrayEquals(new byte[] {9}, result.toRawBuffer().buffer());
      assertRequests(probe, new PageRequest("optimistic", map, 0),
          new PageRequest("optimistic", map, 1), new PageRequest("optimistic", data, 1));
      Assertions.assertThat(probe.extents).isEmpty();

      probe.clear();
      Assert.assertTrue(collection.exists(0, observed));
      assertRequests(probe, new PageRequest("pinned", map, 0),
          new PageRequest("pinned", map, 1), new PageRequest("pinned", data, 1));
      Assertions.assertThat(probe.extents).isEmpty();
    });

    // Map allocate writes the root and non-full bucket. Reserved create skips allocate.
    probe.clear();
    var reserved = atomicOps().calculateInsideAtomicOperation(op -> {
      var position = collection.allocatePosition((byte) 2, probe.observe(op));
      assertRequests(probe, new PageRequest("write", map, 0), new PageRequest("write", map, 1));
      Assertions.assertThat(probe.extents).isEmpty();
      return position;
    });
    assertWarmCreateRequests(probe, data, map, fsm, reserved, 1);
    assertWarmCreateRequests(probe, data, map, fsm, null, 2);
  }

  private void assertWarmCreateRequests(CollectionAccessProbe probe, long data, long map, long fsm,
      PhysicalPosition reserved, long expectedPosition) throws IOException {
    probe.clear();
    atomicOps().executeInsideAtomicOperation(op -> {
      var created = collection.createRecord(new byte[700], (byte) 2, reserved, probe.observe(op));
      Assert.assertEquals(expectedPosition, created.collectionPosition);

      // Base findFreePage probes three FSM levels. Inserting into the existing data page
      // changes the only leaf maximum and both parent maxima. The FSM growth pre-read is
      // also in the base, even when no growth is needed. There are no root discovery queries.
      var expected = new ArrayList<>(java.util.List.of(
          new PageRequest("optimistic", fsm, 0), new PageRequest("optimistic", fsm, 1),
          new PageRequest("optimistic", fsm, 2), new PageRequest("write", data, 1),
          new PageRequest("write", fsm, 2), new PageRequest("write", fsm, 1),
          new PageRequest("write", fsm, 0), new PageRequest("pinned", map, 0),
          new PageRequest("write", map, 1), new PageRequest("write", data, 0)));
      if (reserved == null) {
        expected.add(new PageRequest("write", map, 0));
        expected.add(new PageRequest("write", map, 1));
      }
      Assertions.assertThat(probe.pages).containsExactlyInAnyOrderElementsOf(expected);
      Assertions.assertThat(probe.extents).containsExactly(fsm);
    });
  }

  private long fileId(String extension) throws IOException {
    return atomicOps().calculateInsideAtomicOperation(
        op -> op.fileIdByName(collection.getName() + extension));
  }

  private static void assertRequests(CollectionAccessProbe probe, PageRequest... expected) {
    Assertions.assertThat(probe.pages).containsExactlyInAnyOrder(expected);
  }

  @Test
  public void reservationAlonePersistsAllRootsBeforeAReservedInsert() throws IOException {
    var reserved = atomicOps().calculateInsideAtomicOperation(
        op -> collection.allocatePosition((byte) 2, op));
    assertRootExtents(1, 2, 1, 1);
    Assert.assertEquals(0L,
        (long) atomicOps().calculateInsideAtomicOperation(collection::getEntries));
    flushToReadCache();
    assertRootExtents(1, 2, 1, 1);
    var payload = new byte[] {9};
    var created = atomicOps().calculateInsideAtomicOperation(
        op -> collection.createRecord(payload, (byte) 2, reserved, op));
    Assert.assertEquals(reserved.collectionPosition, created.collectionPosition);
    assertRootExtents(2, 2, 3, 1);
    flushToReadCache();
    assertRootExtents(2, 2, 3, 1);
    assertRecord(created, payload);
    atomicOps().executeInsideAtomicOperation(
        op -> collection.createRecord(new byte[] {8}, (byte) 2, null, op));
    assertRecord(created, payload);
  }

  @Test
  public void unreservedFirstRecordPersistsAllRootsAcrossReopen() throws IOException {
    var payload = new byte[] {7};
    var record = atomicOps().calculateInsideAtomicOperation(
        op -> collection.createRecord(payload, (byte) 2, null, op));
    assertRootExtents(2, 2, 3, 1);
    flushToReadCache();
    assertRootExtents(2, 2, 3, 1);
    assertRecord(record, payload);
  }

  @Test
  public void legacyEmptyRootsReopenAndAcceptAFirstRecord() throws IOException {
    atomicOps().executeInsideAtomicOperation(op -> {
      try (var entry = op.allocatePageForWrite(collection.getFileId(), 0)) {
        new PaginatedCollectionStateV2(entry).setFileSize(0);
      }
      try (var entry = op.allocatePageForWrite(op.fileIdByName("optReadCollection.cpm"), 0)) {
        new MapEntryPoint(entry).setFileSize(0);
      }
      try (var entry = op.allocatePageForWrite(op.fileIdByName("optReadCollection.fsm"), 0)) {
        new FreeSpaceMapPage(entry).init();
      }
      try (var entry = op.allocatePageForWrite(op.fileIdByName("optReadCollection.dpb"), 0)) {
        new DirtyPageBitSetPage(entry).init();
      }
    });
    flushToReadCache();
    assertRootExtents(1, 1, 1, 1);
    assertEmptyReads();
    var record = atomicOps().calculateInsideAtomicOperation(
        op -> collection.createRecord(new byte[] {7}, (byte) 2, null, op));
    assertRecord(record, new byte[] {7});
  }

  @Test
  public void pointReadAndReverseBrowseOverlapTheFirstDiskEpochApply() throws Exception {
    assertReadDuringFirstDiskApply(false, false);
    assertReadDuringFirstDiskApply(false, true);
  }

  @Test
  public void pointReadAndReverseBrowseOverlapTheFirstDiskPageSetApply() throws Exception {
    assertReadDuringFirstDiskApply(true, false);
    assertReadDuringFirstDiskApply(true, true);
  }

  private void assertReadDuringFirstDiskApply(boolean pageSet, boolean browse) throws Exception {
    createStandaloneCollection("overlap" + pageSet + browse);
    var paused = new CountDownLatch(1);
    var resume = new CountDownLatch(1);
    var atMapProbe = new CountDownLatch(1);
    var mapId = atomicOps().calculateInsideAtomicOperation(
        op -> op.fileIdByName(collection.getName() + ".cpm"));
    try (var executor = Executors.newFixedThreadPool(2)) {
      var writer = executor.submit(() -> {
        atomicOps().executeInsideAtomicOperation(op -> {
          collection.createRecord(new byte[] {9}, (byte) 2, null, op);
          if (pageSet) {
            AtomicOperationTestBridge.installPageSetPauseHook(op, (pages, firstPublished) -> {
              if (!firstPublished) {
                pauseApply(paused, resume);
              }
            });
          } else {
            AtomicOperationTestBridge.installPageApplyHook(op,
                new AtomicOperationTestBridge.TestPageApplyHook() {
                  @Override
                  public void beforePageApply(long fileId, long pageIndex) {
                    pauseApply(paused, resume);
                  }
                });
          }
        });
        return null;
      });
      try {
        Assert.assertTrue("Writer reached the selected apply protocol",
            paused.await(30, TimeUnit.SECONDS));
        var reader = executor.submit(() -> {
          atomicOps().executeInsideAtomicOperation(op -> {
            var observed = mock(AtomicOperation.class, delegatesTo(op));
            doAnswer(call -> {
              long id = call.getArgument(0);
              long extent = op.filledUpTo(id);
              if (id == mapId && op.getOptimisticReadScope().isAttemptActive()) {
                atMapProbe.countDown();
              }
              return extent;
            }).when(observed).filledUpTo(anyLong());
            if (browse) {
              Assert.assertNull(collection.nextPage(Long.MAX_VALUE, false, observed));
            } else {
              Assert.assertThrows(RecordNotFoundException.class,
                  () -> collection.readRecord(0, observed));
            }
          });
          return null;
        });
        Assert.assertTrue("Reader reached the map probe while apply was paused",
            atMapProbe.await(30, TimeUnit.SECONDS));
        resume.countDown();
        writer.get(30, TimeUnit.SECONDS);
        reader.get(30, TimeUnit.SECONDS);
        assertRootExtents(2, 2, 3, 1);
        assertRecord(new PhysicalPosition(0L), new byte[] {9});
      } finally {
        resume.countDown();
      }
    }
  }

  private static void pauseApply(CountDownLatch paused, CountDownLatch resume) {
    paused.countDown();
    try {
      Assert.assertTrue(resume.await(30, TimeUnit.SECONDS));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }

  @Test
  public void optimisticMemoryReaderCannotPublishAnotherOperationsRoots() throws Exception {
    var memory = DB_NAME + "_overlap";
    youTrackDB.create(memory, DatabaseType.MEMORY,
        new LocalUserCredential("admin", "admin", PredefinedLocalRole.ADMIN));
    try (var session = youTrackDB.open(memory, "admin", "admin");
        var executor = Executors.newSingleThreadExecutor()) {
      storage = session.getStorage();
      createStandaloneCollection("memoryOverlap");
      var writer = atomicOps().startAtomicOperation();
      atomicOps().startToApplyOperations(writer);
      try {
        collection.allocatePosition((byte) 2, writer);
        Assert.assertFalse(rootsPresent());
        executor.submit(() -> {
          atomicOps().executeInsideAtomicOperation(op -> {
            // An enclosing attempt tests the optimistic phase without letting it
            // enter the locked fallback while this thread's writer is still active.
            var scope = op.getOptimisticReadScope();
            scope.beginAttempt(AtomicOperationTestBridge.applyPhaseEpoch(
                atomicOps(), collection.getLockName()));
            try {
              collection.getRecordStatus(0, op);
            } catch (OptimisticReadFailedException expected) {
              // A positive extent requires retrying with the lock.
            } finally {
              scope.endAttempt();
            }
          });
          return null;
        }).get(30, TimeUnit.SECONDS);
        Assert.assertFalse("The writer is still active", rootsPresent());
      } finally {
        writer.rollbackInProgress();
        atomicOps().endAtomicOperation(writer, null);
      }
      assertEmptyReads();
    }
  }

  @Test
  public void diskFirstWriteRollbackLeavesNoPagesForEitherWriteBoundary() throws IOException {
    firstWriteRollbackAndRetry(false);
  }

  @Test
  public void memoryFirstWriteRollbackToleratesBlankRootsForEitherWriteBoundary()
      throws IOException {
    var memory = DB_NAME + "_memory";
    youTrackDB.create(memory, DatabaseType.MEMORY,
        new LocalUserCredential("admin", "admin", PredefinedLocalRole.ADMIN));
    try (var session = youTrackDB.open(memory, "admin", "admin")) {
      storage = session.getStorage();
      firstWriteRollbackAndRetry(true);
    }
  }

  private void firstWriteRollbackAndRetry(boolean memory) throws IOException {
    for (var reserve : new boolean[] {true, false}) {
      createStandaloneCollection(reserve ? "reserveRollback" : "insertRollback");
      var op = atomicOps().startAtomicOperation();
      atomicOps().startToApplyOperations(op);
      try {
        if (reserve) {
          Assert.assertEquals(0, collection.allocatePosition((byte) 2, op).collectionPosition);
          Assert.assertEquals(1, collection.allocatePosition((byte) 2, op).collectionPosition);
        } else {
          collection.createRecord(new byte[] {4}, (byte) 2, null, op);
        }
      } finally {
        op.rollbackInProgress();
        atomicOps().endAtomicOperation(op, null);
      }
      Assert.assertFalse(rootsPresent());
      if (!memory) {
        assertRootExtents(0, 0, 0, 0);
      }
      assertEmptyReads();
      var first = atomicOps().calculateInsideAtomicOperation(tx -> {
        var position = reserve ? collection.allocatePosition((byte) 2, tx) : null;
        return collection.createRecord(new byte[] {5}, (byte) 2, position, tx);
      });
      assertRootExtents(2, 2, 3, 1);
      var second = atomicOps().calculateInsideAtomicOperation(
          tx -> collection.createRecord(new byte[] {6}, (byte) 2, null, tx));
      Assert.assertEquals(0, first.collectionPosition);
      Assert.assertEquals(1, second.collectionPosition);
      assertRecord(first, new byte[] {5});
      assertRecord(second, new byte[] {6});
    }
  }

  @Test
  public void diskUpdateRollbackDoesNotPublishNewFreeSpaceRoot() throws IOException {
    mixedRootUpdateRollbackAndRetry(false);
  }

  @Test
  public void diskVersionUpdateRollbackDoesNotPublishNewDirtyBitsRoot() throws IOException {
    mixedRootUpdateRollbackAndRetry(true);
  }

  private void mixedRootUpdateRollbackAndRetry(boolean versionOnly) throws IOException {
    var original = atomicOps().calculateInsideAtomicOperation(
        op -> collection.createRecord(new byte[] {9}, (byte) 2, null, op));
    var missingId = fileId(versionOnly ? ".dpb" : ".fsm");
    storage.getReadCache().shrinkFile(missingId, 0, storage.getWriteCache());
    var mixedExtents = new long[] {2, 2, versionOnly ? 3 : 0, versionOnly ? 0 : 1};
    assertRootExtents(mixedExtents);
    Assert.assertFalse(rootsPresent());

    var writer = atomicOps().startAtomicOperation();
    atomicOps().startToApplyOperations(writer);
    try {
      if (versionOnly) {
        collection.updateRecordVersion(original.collectionPosition, writer);
      } else {
        collection.updateRecord(original.collectionPosition, new byte[] {8}, (byte) 2, writer);
        Assert.assertTrue("Update must allocate a new data page",
            writer.filledUpTo(collection.getFileId()) > mixedExtents[0]);
      }
      Assert.assertTrue("Update must allocate the missing root", writer.filledUpTo(missingId) > 0);
    } finally {
      writer.rollbackInProgress();
      atomicOps().endAtomicOperation(writer, null);
    }
    assertRootExtents(mixedExtents);
    var published = rootsPresent();

    var probe = new CollectionAccessProbe(storage, collection);
    var inserted = atomicOps().calculateInsideAtomicOperation(op -> {
      var observed = probe.observe(op);
      var reserved = collection.allocatePosition((byte) 2, observed);
      return collection.createRecord(new byte[] {5}, (byte) 2, reserved, observed);
    });
    Assert.assertFalse("Rolled-back update published uncommitted root presence", published);
    // Check the allocation order against the mixed fixture's committed extents.
    var files = new long[] {collection.getFileId(), fileId(".cpm"), fileId(".fsm"), fileId(".dpb")};
    for (var request : probe.pages) {
      for (int i = 0; i < files.length; i++) {
        if (request.file() == files[i]) {
          if (request.kind().equals("allocate")) {
            Assert.assertEquals(mixedExtents[i]++, request.page());
          } else {
            Assert.assertTrue("Requested an unallocated page: " + request,
                request.page() < mixedExtents[i]);
          }
        }
      }
    }
    Assert.assertTrue("Retry must explicitly allocate the missing root",
        probe.pages.contains(new PageRequest("allocate", missingId, 0)));
    assertRootExtents(mixedExtents);
    for (var extent : mixedExtents) {
      Assert.assertTrue(extent > 0);
    }
    assertRecord(original, new byte[] {9});
    assertRecord(inserted, new byte[] {5});
    Assert.assertTrue("A later operation must discover the committed roots", rootsPresent());
  }

  @Test
  public void memoryVersionUpdatePublishesDirtyBitsRootOnlyAfterRollbackReleasesIt()
      throws IOException {
    var memory = DB_NAME + "_dirtyBitsRollback";
    youTrackDB.create(memory, DatabaseType.MEMORY,
        new LocalUserCredential("admin", "admin", PredefinedLocalRole.ADMIN));
    try (var session = youTrackDB.open(memory, "admin", "admin")) {
      storage = session.getStorage();
      createStandaloneCollection("memoryDirtyBitsRollback");
      var original = atomicOps().calculateInsideAtomicOperation(
          op -> collection.createRecord(new byte[] {9}, (byte) 2, null, op));
      var dirty = fileId(".dpb");
      var writes = storage.getWriteCache();
      var reads = storage.getReadCache();
      // Memory does not support shrinking. Replace this fixture's file with an empty one.
      var dirtyName = writes.fileNameById(dirty);
      reads.deleteFile(dirty, writes);
      reads.addFile(dirtyName, dirty, writes);
      assertRootExtents(2, 2, 3, 0);
      Assert.assertFalse(rootsPresent());

      var writer = atomicOps().startAtomicOperation();
      atomicOps().startToApplyOperations(writer);
      try {
        collection.updateRecordVersion(original.collectionPosition, writer);
        Assert.assertTrue("The new page is already in the memory cache",
            writes.physicalSizeForBackupSnapshot(dirty) > 0);
        Assert.assertFalse("Active update published uncommitted root presence", rootsPresent());
      } finally {
        writer.rollbackInProgress();
        atomicOps().endAtomicOperation(writer, null);
      }
      Assert.assertFalse(rootsPresent());
      assertRootExtents(2, 2, 3, 1);

      var inserted = atomicOps().calculateInsideAtomicOperation(op -> {
        var reserved = collection.allocatePosition((byte) 2, op);
        Assert.assertTrue("The retained blank page is safe to discover", rootsPresent());
        return collection.createRecord(new byte[] {5}, (byte) 2, reserved, op);
      });
      assertRootExtents(2, 2, 3, 1);
      assertRecord(original, new byte[] {9});
      assertRecord(inserted, new byte[] {5});
    }
  }

  private boolean rootsPresent() {
    try {
      var field = PaginatedCollectionV2.class.getDeclaredField("rootsPresent");
      field.setAccessible(true);
      return field.getBoolean(collection);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  private void createStandaloneCollection(String name) throws IOException {
    collection = new PaginatedCollectionV2(name, storage);
    collection.configure(55, name);
    atomicOps().executeInsideAtomicOperation(collection::create);
  }

  private void assertRootExtents(long... expected) throws IOException {
    atomicOps().executeInsideAtomicOperation(op -> {
      var extents = new long[4];
      var extensions = new String[] {".pcl", ".cpm", ".fsm", ".dpb"};
      for (int i = 0; i < extensions.length; i++) {
        extents[i] = op.filledUpTo(op.fileIdByName(collection.getName() + extensions[i]));
      }
      Assert.assertArrayEquals(expected, extents);
    });
  }

  private void assertEmptyReads() throws IOException {
    atomicOps().executeInsideAtomicOperation(op -> {
      var zero = new PhysicalPosition(0L);
      Assert.assertThrows(RecordNotFoundException.class, () -> collection.readRecord(0, op));
      Assert.assertFalse(collection.exists(0, op));
      Assert.assertNull(collection.getPhysicalPosition(zero, op));
      for (long position : new long[] {-1, 0, Long.MAX_VALUE}) {
        Assert.assertEquals(PaginatedCollection.RECORD_STATUS.NOT_EXISTENT,
            collection.getRecordStatus(position, op));
      }
      Assert.assertEquals(0, collection.getEntries(op));
      Assert.assertEquals(-1, collection.getFirstPosition(op));
      Assert.assertEquals(-1, collection.getLastPosition(op));
      Assert.assertEquals(0, collection.higherPositions(zero, 1, op).length);
      Assert.assertEquals(0, collection.ceilingPositions(zero, 1, op).length);
      Assert.assertEquals(0, collection.lowerPositions(new PhysicalPosition(1L), 1, op).length);
      Assert.assertEquals(0, collection.floorPositions(zero, 1, op).length);
      Assert.assertNull(collection.nextPage(-1, true, op));
      Assert.assertNull(collection.nextPage(Long.MAX_VALUE, false, op));
    });
  }

  private void assertRecord(PhysicalPosition position, byte[] bytes) throws IOException {
    atomicOps().executeInsideAtomicOperation(op -> Assert.assertArrayEquals(bytes,
        collection.readRecord(position.collectionPosition, op).toRawBuffer().buffer()));
  }

  /** A validated zero-copy result must reject a removed page even with a valid old stamp. */
  @Test
  public void testZeroCopyResultRejectsRemovedRetainedPage() throws IOException {
    var pos = atomicOps().calculateInsideAtomicOperation(
        op -> collection.createRecord(new byte[] {1, 2, 3}, (byte) 'd', null, op));
    flushToReadCache();
    var result = atomicOps().calculateInsideAtomicOperation(
        op -> collection.readRecord(pos.collectionPosition, op));
    Assert.assertTrue(result instanceof RawPageBuffer);
    var raw = (RawPageBuffer) result;
    var pointer = raw.cacheEntry().getCachePointer();
    pointer.incrementReadersReferrer();
    try {
      // The optimistic read has ended, so the file lifecycle precondition holds.
      storage.getReadCache().closeFile(collection.getFileId(), false, storage.getWriteCache());
      Assert.assertTrue(raw.pageFrame().validate(raw.stamp()));
      long before = OptimisticReadFailureCounters.count(Cause.FRAME_IDENTITY);
      Assert.assertThrows(OptimisticReadFailedException.class, raw::toRawBuffer);
      Assert.assertEquals(before + 1, OptimisticReadFailureCounters.count(Cause.FRAME_IDENTITY));
    } finally {
      pointer.decrementReadersReferrer();
    }
  }

  // --- readRecord optimistic path ---

  // Verifies that reading a small (single-page) record in a separate read-only atomic operation
  // exercises the optimistic read path in doReadRecordOptimistic and returns the correct data.
  @Test
  public void testReadRecordOptimisticSmallRecord() throws IOException {
    var data = new byte[] {10, 20, 30, 40, 50};
    var pos = atomicOps().calculateInsideAtomicOperation(
        op -> collection.createRecord(data, (byte) 2, null, op));

    flushToReadCache();

    // Read in a separate atomic operation (no pending changes) to hit the optimistic path
    var rawResult = atomicOps().calculateInsideAtomicOperation(
        op -> collection.readRecord(pos.collectionPosition, op));
    Assert.assertTrue("Optimistic path should return RawPageBuffer for single-page record",
        rawResult instanceof RawPageBuffer);
    var buffer = rawResult.toRawBuffer();

    Assert.assertNotNull("Optimistic read should return a non-null buffer", buffer);
    Assertions.assertThat(buffer.buffer()).isEqualTo(data);
    Assert.assertEquals(2, buffer.recordType());
    // The record version encodes the commit timestamp from the atomic operation.
    // It must be positive and non-zero for any committed record.
    Assert.assertTrue("Version should be positive for a committed record, got: "
        + buffer.version(), buffer.version() > 0);
  }

  // Verifies that reading multiple small records in the same read-only atomic operation
  // exercises the optimistic path repeatedly and returns correct data for each.
  @Test
  public void testReadRecordOptimisticMultipleRecords() throws IOException {
    var records = new ArrayList<byte[]>();
    var positions = new ArrayList<Long>();
    for (var i = 0; i < 10; i++) {
      var data = new byte[] {(byte) (i * 10), (byte) (i * 10 + 1)};
      records.add(data);
      var pos = atomicOps().calculateInsideAtomicOperation(
          op -> collection.createRecord(data, (byte) 1, null, op));
      positions.add(pos.collectionPosition);
    }

    flushToReadCache();

    // Read all records in a single read-only atomic operation
    atomicOps().executeInsideAtomicOperation(op -> {
      for (var i = 0; i < positions.size(); i++) {
        var buffer = collection.readRecord(positions.get(i), op).toRawBuffer();
        Assertions.assertThat(buffer.buffer())
            .as("Record %d should match inserted data", i)
            .isEqualTo(records.get(i));
      }
    });
  }

  // Verifies that reading a large (multi-page) record falls back from the optimistic path
  // to the pinned path and still returns the correct data.
  @Test
  public void testReadRecordOptimisticFallbackForMultiPageRecord() throws IOException {
    // 131172 bytes = (2 << 16) + 100 = 128 KB + 100 bytes. This is much larger than
    // the process page sizes of 4 KB and 8 KB, so the record spans multiple pages. The +100
    // avoids landing exactly on a page boundary, exercising partial-page handling.
    var bigData = new byte[(2 << 16) + 100];
    new Random(42).nextBytes(bigData);

    var pos = atomicOps().calculateInsideAtomicOperation(
        op -> collection.createRecord(bigData, (byte) 3, null, op));

    flushToReadCache();

    // The optimistic path detects the multi-page pointer and falls back to the pinned path
    var buffer = atomicOps().calculateInsideAtomicOperation(
        op -> collection.readRecord(pos.collectionPosition, op).toRawBuffer());

    Assert.assertNotNull(buffer);
    Assertions.assertThat(buffer.buffer()).isEqualTo(bigData);
    Assert.assertEquals(3, buffer.recordType());
  }

  // Verifies that reading a non-existent position throws RecordNotFoundException even
  // through the optimistic path (the RuntimeException catch in doReadRecordOptimistic
  // converts RecordNotFoundException to OptimisticReadFailedException, so the pinned
  // path produces the authoritative answer). The exception must reference the requested
  // position so the caller can diagnose which record was missing.
  @Test
  public void testReadRecordOptimisticNonExistentThrows() throws IOException {
    // Insert at least one record so the collection is not empty (getLastPosition needs it)
    var data = new byte[] {1};
    atomicOps().executeInsideAtomicOperation(
        op -> collection.createRecord(data, (byte) 1, null, op));

    var lastPos = atomicOps().calculateInsideAtomicOperation(
        op -> collection.getLastPosition(op));
    var nonExistent = lastPos + 5000;

    flushToReadCache();

    try {
      atomicOps().calculateInsideAtomicOperation(
          op -> collection.readRecord(nonExistent, op).toRawBuffer());
      Assert.fail("Should throw RecordNotFoundException for non-existent position");
    } catch (RecordNotFoundException e) {
      // The exception should reference the non-existent position.
      Assertions.assertThat(e.getMessage())
          .as("Exception message should contain the requested position")
          .contains(String.valueOf(nonExistent));
    }
  }

  // --- getPhysicalPosition optimistic path ---

  // Verifies that getPhysicalPosition in a read-only atomic operation exercises the optimistic
  // path and returns correct metadata (type, version, position).
  @Test
  public void testGetPhysicalPositionOptimistic() throws IOException {
    var data = new byte[] {1, 2, 3, 4, 5};
    var created = atomicOps().calculateInsideAtomicOperation(
        op -> collection.createRecord(data, (byte) 7, null, op));

    flushToReadCache();

    var query = new PhysicalPosition();
    query.collectionPosition = created.collectionPosition;

    var result = atomicOps().calculateInsideAtomicOperation(
        op -> collection.getPhysicalPosition(query, op));

    Assert.assertNotNull("Physical position should be non-null for existing record",
        result);
    Assert.assertEquals(created.collectionPosition, result.collectionPosition);
    Assert.assertEquals(7, result.recordType);
    // The record version encodes the commit timestamp from the atomic operation.
    // It must be positive and non-zero for any committed record.
    Assert.assertTrue("Record version should be positive for a committed record, got: "
        + result.recordVersion, result.recordVersion > 0);
    // PaginatedCollectionV2.getPhysicalPosition() does not compute record size — it sets
    // recordSize = -1 as a sentinel meaning "size not available" (see doGetPhysicalPosition).
    Assert.assertEquals("recordSize should be -1 (not computed by getPhysicalPosition)",
        -1, result.recordSize);
  }

  // Verifies that getPhysicalPosition returns null for a non-existent position through the
  // optimistic path.
  @Test
  public void testGetPhysicalPositionOptimisticNonExistent() throws IOException {
    // Insert at least one record so the collection is not empty
    var data = new byte[] {1};
    atomicOps().executeInsideAtomicOperation(
        op -> collection.createRecord(data, (byte) 1, null, op));

    var lastPos = atomicOps().calculateInsideAtomicOperation(
        op -> collection.getLastPosition(op));

    flushToReadCache();

    var query = new PhysicalPosition();
    query.collectionPosition = lastPos + 5000;

    var result = atomicOps().calculateInsideAtomicOperation(
        op -> collection.getPhysicalPosition(query, op));
    Assert.assertNull("Should return null for non-existent position", result);
  }

  // Verifies that getPhysicalPosition returns null for a deleted record through the
  // optimistic path.
  @Test
  public void testGetPhysicalPositionOptimisticDeletedRecord() throws IOException {
    var data = new byte[] {1, 2, 3};
    var pos = atomicOps().calculateInsideAtomicOperation(
        op -> collection.createRecord(data, (byte) 1, null, op));

    atomicOps().executeInsideAtomicOperation(
        op -> collection.deleteRecord(op, pos.collectionPosition));

    flushToReadCache();

    var query = new PhysicalPosition();
    query.collectionPosition = pos.collectionPosition;

    var result = atomicOps().calculateInsideAtomicOperation(
        op -> collection.getPhysicalPosition(query, op));
    Assert.assertNull("Should return null for deleted record", result);
  }

  // --- exists optimistic path ---

  // Verifies that exists() in a read-only atomic operation exercises the optimistic path
  // and returns true for an existing record.
  @Test
  public void testExistsOptimisticTrue() throws IOException {
    var data = new byte[] {5, 6, 7};
    var pos = atomicOps().calculateInsideAtomicOperation(
        op -> collection.createRecord(data, (byte) 1, null, op));

    flushToReadCache();

    var exists = atomicOps().calculateInsideAtomicOperation(
        op -> collection.exists(pos.collectionPosition, op));
    Assert.assertTrue("Record should exist", exists);
  }

  // Verifies that exists() returns false for a non-existent position through the optimistic
  // path.
  @Test
  public void testExistsOptimisticFalseNonExistent() throws IOException {
    // Insert at least one record so the collection is not empty
    var data = new byte[] {1};
    atomicOps().executeInsideAtomicOperation(
        op -> collection.createRecord(data, (byte) 1, null, op));

    var lastPos = atomicOps().calculateInsideAtomicOperation(
        op -> collection.getLastPosition(op));

    flushToReadCache();

    var exists = atomicOps().calculateInsideAtomicOperation(
        op -> collection.exists(lastPos + 5000, op));
    Assert.assertFalse("Non-existent position should not exist", exists);
  }

  // Verifies that exists() returns false after a record is deleted, exercising the optimistic
  // path for the deleted-record case.
  @Test
  public void testExistsOptimisticFalseAfterDelete() throws IOException {
    var data = new byte[] {8, 9, 10};
    var pos = atomicOps().calculateInsideAtomicOperation(
        op -> collection.createRecord(data, (byte) 1, null, op));

    atomicOps().executeInsideAtomicOperation(
        op -> collection.deleteRecord(op, pos.collectionPosition));

    flushToReadCache();

    var exists = atomicOps().calculateInsideAtomicOperation(
        op -> collection.exists(pos.collectionPosition, op));
    Assert.assertFalse("Deleted record should not exist", exists);
  }

  // --- getFirstPosition / getLastPosition optimistic paths ---

  // Verifies that getFirstPosition and getLastPosition return correct bounds after inserting
  // multiple records, exercising the optimistic path for position map boundary queries.
  @Test
  public void testGetFirstAndLastPositionOptimistic() throws IOException {
    var positions = new ArrayList<Long>();
    for (var i = 0; i < 5; i++) {
      var data = new byte[] {(byte) i};
      var pos = atomicOps().calculateInsideAtomicOperation(
          op -> collection.createRecord(data, (byte) 1, null, op));
      positions.add(pos.collectionPosition);
    }

    flushToReadCache();

    var first = atomicOps().calculateInsideAtomicOperation(
        op -> collection.getFirstPosition(op));
    var last = atomicOps().calculateInsideAtomicOperation(
        op -> collection.getLastPosition(op));

    Assert.assertEquals("First position should equal smallest created position",
        positions.get(0).longValue(), (long) first);
    Assert.assertEquals("Last position should equal largest created position",
        positions.get(positions.size() - 1).longValue(), (long) last);
  }

  // --- higherPositions / ceilingPositions / lowerPositions / floorPositions ---

  // Verifies that higherPositions returns positions strictly greater than the given position,
  // exercising the optimistic path in the position map.
  @Test
  public void testHigherPositionsOptimistic() throws IOException {
    var positions = new ArrayList<Long>();
    for (var i = 0; i < 5; i++) {
      var data = new byte[] {(byte) i};
      var pos = atomicOps().calculateInsideAtomicOperation(
          op -> collection.createRecord(data, (byte) 1, null, op));
      positions.add(pos.collectionPosition);
    }

    flushToReadCache();

    var query = new PhysicalPosition(positions.get(0));
    var higher = atomicOps().calculateInsideAtomicOperation(
        op -> collection.higherPositions(query, Integer.MAX_VALUE, op));

    Assert.assertEquals("Should have exactly 4 higher positions",
        4, higher.length);
    for (var pp : higher) {
      Assert.assertTrue("All positions should be strictly higher than the query",
          pp.collectionPosition > positions.get(0));
    }
  }

  // Verifies that ceilingPositions returns positions >= the given position, exercising the
  // optimistic path.
  @Test
  public void testCeilingPositionsOptimistic() throws IOException {
    var positions = new ArrayList<Long>();
    for (var i = 0; i < 5; i++) {
      var data = new byte[] {(byte) i};
      var pos = atomicOps().calculateInsideAtomicOperation(
          op -> collection.createRecord(data, (byte) 1, null, op));
      positions.add(pos.collectionPosition);
    }

    flushToReadCache();

    var query = new PhysicalPosition(positions.get(0));
    var ceiling = atomicOps().calculateInsideAtomicOperation(
        op -> collection.ceilingPositions(query, Integer.MAX_VALUE, op));

    Assert.assertEquals("Should have exactly 5 ceiling positions",
        5, ceiling.length);
    Assert.assertEquals("First ceiling position should equal the query position",
        positions.get(0).longValue(), ceiling[0].collectionPosition);
  }

  // Verifies that lowerPositions returns positions strictly less than the given position,
  // exercising the optimistic path.
  @Test
  public void testLowerPositionsOptimistic() throws IOException {
    var positions = new ArrayList<Long>();
    for (var i = 0; i < 5; i++) {
      var data = new byte[] {(byte) i};
      var pos = atomicOps().calculateInsideAtomicOperation(
          op -> collection.createRecord(data, (byte) 1, null, op));
      positions.add(pos.collectionPosition);
    }

    flushToReadCache();

    var lastPos = positions.get(positions.size() - 1);
    var query = new PhysicalPosition(lastPos);
    var lower = atomicOps().calculateInsideAtomicOperation(
        op -> collection.lowerPositions(query, Integer.MAX_VALUE, op));

    Assert.assertEquals("Should have exactly 4 lower positions",
        4, lower.length);
    for (var pp : lower) {
      Assert.assertTrue("All positions should be strictly lower than the query",
          pp.collectionPosition < lastPos);
    }
  }

  // Verifies that floorPositions returns positions <= the given position, exercising the
  // optimistic path.
  @Test
  public void testFloorPositionsOptimistic() throws IOException {
    var positions = new ArrayList<Long>();
    for (var i = 0; i < 5; i++) {
      var data = new byte[] {(byte) i};
      var pos = atomicOps().calculateInsideAtomicOperation(
          op -> collection.createRecord(data, (byte) 1, null, op));
      positions.add(pos.collectionPosition);
    }

    flushToReadCache();

    var lastPos = positions.get(positions.size() - 1);
    var query = new PhysicalPosition(lastPos);
    var floor = atomicOps().calculateInsideAtomicOperation(
        op -> collection.floorPositions(query, Integer.MAX_VALUE, op));

    Assert.assertEquals("Should have exactly 5 floor positions",
        5, floor.length);
    var lastFloor = floor[floor.length - 1];
    Assert.assertEquals("Last floor position should equal the query position",
        lastPos.longValue(), (long) lastFloor.collectionPosition);
  }

  // --- getRecordStatus optimistic path ---

  // Verifies that getRecordStatus returns PRESENT for a filled record through the optimistic
  // path.
  @Test
  public void testGetRecordStatusOptimisticPresent() throws IOException {
    var data = new byte[] {1, 2, 3};
    var pos = atomicOps().calculateInsideAtomicOperation(
        op -> collection.createRecord(data, (byte) 1, null, op));

    flushToReadCache();

    var status = atomicOps().calculateInsideAtomicOperation(
        op -> collection.getRecordStatus(pos.collectionPosition, op));
    Assert.assertEquals(PaginatedCollection.RECORD_STATUS.PRESENT, status);
  }

  // Verifies that getRecordStatus returns NOT_EXISTENT for a non-existent position through
  // the optimistic path.
  @Test
  public void testGetRecordStatusOptimisticNotExistent() throws IOException {
    // Insert at least one record so the collection is not empty
    var data = new byte[] {1};
    atomicOps().executeInsideAtomicOperation(
        op -> collection.createRecord(data, (byte) 1, null, op));

    var lastPos = atomicOps().calculateInsideAtomicOperation(
        op -> collection.getLastPosition(op));

    flushToReadCache();

    var status = atomicOps().calculateInsideAtomicOperation(
        op -> collection.getRecordStatus(lastPos + 5000, op));
    Assert.assertEquals(PaginatedCollection.RECORD_STATUS.NOT_EXISTENT, status);
  }

  // Verifies that getRecordStatus returns REMOVED for a deleted record through the optimistic
  // path.
  @Test
  public void testGetRecordStatusOptimisticRemoved() throws IOException {
    var data = new byte[] {4, 5, 6};
    var pos = atomicOps().calculateInsideAtomicOperation(
        op -> collection.createRecord(data, (byte) 1, null, op));

    atomicOps().executeInsideAtomicOperation(
        op -> collection.deleteRecord(op, pos.collectionPosition));

    flushToReadCache();

    var status = atomicOps().calculateInsideAtomicOperation(
        op -> collection.getRecordStatus(pos.collectionPosition, op));
    Assert.assertEquals(PaginatedCollection.RECORD_STATUS.REMOVED, status);
  }

  // --- getEntries optimistic path ---

  // Verifies that getEntries in a read-only atomic operation exercises the optimistic path
  // and returns the correct count of records.
  @Test
  public void testGetEntriesOptimistic() throws IOException {
    var count = 7;
    for (var i = 0; i < count; i++) {
      var data = new byte[] {(byte) i, (byte) (i + 1)};
      atomicOps().executeInsideAtomicOperation(
          op -> collection.createRecord(data, (byte) 1, null, op));
    }

    flushToReadCache();

    var entries = atomicOps().calculateInsideAtomicOperation(
        op -> collection.getEntries(op));
    Assert.assertEquals("Should have exactly the inserted records",
        count, entries.longValue());
  }

  // Verifies that getEntries correctly reflects the count after deletions, exercising the
  // REMOVED-entry logic in doGetEntries through the optimistic path.
  @Test
  public void testGetEntriesOptimisticAfterDeletions() throws IOException {
    var positions = new ArrayList<Long>();
    for (var i = 0; i < 5; i++) {
      var data = new byte[] {(byte) i};
      var pos = atomicOps().calculateInsideAtomicOperation(
          op -> collection.createRecord(data, (byte) 1, null, op));
      positions.add(pos.collectionPosition);
    }

    // Delete 2 records
    atomicOps().executeInsideAtomicOperation(
        op -> collection.deleteRecord(op, positions.get(0)));
    atomicOps().executeInsideAtomicOperation(
        op -> collection.deleteRecord(op, positions.get(1)));

    flushToReadCache();

    var entriesAfter = atomicOps().calculateInsideAtomicOperation(
        op -> collection.getEntries(op));
    Assert.assertEquals("Entry count should be 3 after inserting 5 and deleting 2",
        3L, entriesAfter.longValue());
  }

  // --- nextPage optimistic path ---

  // Verifies that forward page browsing via nextPage exercises the optimistic path in
  // doNextPage and visits all inserted records.
  @Test
  public void testNextPageOptimisticForward() throws IOException {
    var createdPositions = new ArrayList<Long>();
    for (var i = 0; i < 20; i++) {
      var data = new byte[] {(byte) i, (byte) (i + 1), (byte) (i + 2)};
      var pos = atomicOps().calculateInsideAtomicOperation(
          op -> collection.createRecord(data, (byte) 1, null, op));
      createdPositions.add(pos.collectionPosition);
    }

    flushToReadCache();

    var visitedPositions = new ArrayList<Long>();
    var page = atomicOps().calculateInsideAtomicOperation(
        op -> collection.nextPage(-1, true, op));

    while (page != null) {
      for (var entry : page) {
        visitedPositions.add(entry.collectionPosition());
      }
      var nextStart = page.getLastPosition();
      page = atomicOps().calculateInsideAtomicOperation(
          op -> collection.nextPage(nextStart, true, op));
    }

    Assert.assertTrue("Should visit all created records",
        visitedPositions.containsAll(createdPositions));
    Assert.assertEquals("Should visit exactly the created records",
        createdPositions.size(), visitedPositions.size());

    // Verify ascending order of visited positions.
    for (var i = 1; i < visitedPositions.size(); i++) {
      Assert.assertTrue(
          "Positions should be in ascending order, but position at index " + (i - 1)
              + " (" + visitedPositions.get(i - 1) + ") >= position at index " + i
              + " (" + visitedPositions.get(i) + ")",
          visitedPositions.get(i - 1) < visitedPositions.get(i));
    }
  }

  // Verifies that backward page browsing via nextPage exercises the optimistic path in
  // doNextPage and returns entries in descending order.
  @Test
  public void testNextPageOptimisticBackward() throws IOException {
    for (var i = 0; i < 10; i++) {
      var data = new byte[] {(byte) i};
      atomicOps().executeInsideAtomicOperation(
          op -> collection.createRecord(data, (byte) 1, null, op));
    }

    flushToReadCache();

    var page = atomicOps().calculateInsideAtomicOperation(
        op -> collection.nextPage(Long.MAX_VALUE, false, op));
    Assert.assertNotNull("Should get a page when browsing backward", page);

    var prevPos = Long.MAX_VALUE;
    var hasEntries = false;
    for (var entry : page) {
      hasEntries = true;
      Assert.assertTrue("Positions should be descending",
          entry.collectionPosition() < prevPos);
      prevPos = entry.collectionPosition();
    }
    Assert.assertTrue("Page should have entries", hasEntries);
  }

  // --- Zero-length record edge case ---

  // Verifies that a zero-length (empty byte[0]) record can be created, flushed, and read
  // back through the optimistic path. Empty records could trigger edge cases in size
  // calculations or optimistic read buffer allocation.
  @Test
  public void testReadRecordOptimisticZeroLengthRecord() throws IOException {
    var emptyData = new byte[0];
    var pos = atomicOps().calculateInsideAtomicOperation(
        op -> collection.createRecord(emptyData, (byte) 4, null, op));

    flushToReadCache();

    var buffer = atomicOps().calculateInsideAtomicOperation(
        op -> collection.readRecord(pos.collectionPosition, op).toRawBuffer());

    Assert.assertNotNull("Optimistic read should return a non-null buffer for empty record",
        buffer);
    Assertions.assertThat(buffer.buffer())
        .as("Empty record should return a zero-length byte array")
        .isEqualTo(emptyData);
    Assert.assertEquals(4, buffer.recordType());
    Assert.assertTrue("Version should be positive for a committed record, got: "
        + buffer.version(), buffer.version() > 0);
  }

  // --- Combined read scenario ---

  // Verifies that all optimistic read paths work correctly within a single read-only
  // atomic operation, exercising the optimistic read scope reuse across multiple calls.
  @Test
  public void testCombinedOptimisticReadsInSingleAtomicOperation() throws IOException {
    var data = new byte[] {100, (byte) 200, 42, 7};
    var pos = atomicOps().calculateInsideAtomicOperation(
        op -> collection.createRecord(data, (byte) 5, null, op));

    flushToReadCache();

    atomicOps().executeInsideAtomicOperation(op -> {
      // readRecord
      var buffer = collection.readRecord(pos.collectionPosition, op).toRawBuffer();
      Assertions.assertThat(buffer.buffer()).isEqualTo(data);
      Assert.assertEquals(5, buffer.recordType());

      // exists
      Assert.assertTrue(collection.exists(pos.collectionPosition, op));

      // getPhysicalPosition
      var query = new PhysicalPosition();
      query.collectionPosition = pos.collectionPosition;
      var physPos = collection.getPhysicalPosition(query, op);
      Assert.assertNotNull(physPos);
      Assert.assertEquals(5, physPos.recordType);

      // getRecordStatus
      var status = collection.getRecordStatus(pos.collectionPosition, op);
      Assert.assertEquals(PaginatedCollection.RECORD_STATUS.PRESENT, status);

      // getFirstPosition / getLastPosition
      var first = collection.getFirstPosition(op);
      var last = collection.getLastPosition(op);
      Assert.assertEquals(pos.collectionPosition, first);
      Assert.assertEquals(pos.collectionPosition, last);

      // getEntries
      var entries = collection.getEntries(op);
      Assert.assertEquals("Should have exactly 1 entry", 1L, entries);

      // higherPositions from (first - 1) should include our record
      var higher = collection.higherPositions(
          new PhysicalPosition(first - 1), 10, op);
      Assert.assertEquals("Should have 1 position above (first - 1)", 1, higher.length);

      // ceilingPositions from our position (should include our record)
      var ceiling = collection.ceilingPositions(
          new PhysicalPosition(pos.collectionPosition), 10, op);
      Assert.assertEquals("Should have 1 ceiling position", 1, ceiling.length);
      Assert.assertEquals(pos.collectionPosition, ceiling[0].collectionPosition);

      // lowerPositions from Long.MAX_VALUE (should include our record)
      var lower = collection.lowerPositions(
          new PhysicalPosition(Long.MAX_VALUE), 10, op);
      Assert.assertEquals("Should have 1 lower position", 1, lower.length);

      // floorPositions from our position (should include our record)
      var floor = collection.floorPositions(
          new PhysicalPosition(pos.collectionPosition), 10, op);
      Assert.assertEquals("Should have 1 floor position", 1, floor.length);
    });
  }

  // --- Delete scenario: verify status transitions through optimistic paths ---

  // Verifies the full lifecycle of a record — create, verify present, delete, verify removed
  // and non-existent — all through optimistic read paths in separate atomic operations.
  @Test
  public void testDeleteLifecycleThroughOptimisticPaths() throws IOException {
    var data = new byte[] {11, 22, 33, 44};
    var pos = atomicOps().calculateInsideAtomicOperation(
        op -> collection.createRecord(data, (byte) 1, null, op));

    flushToReadCache();

    // Verify PRESENT status and existence through optimistic path
    atomicOps().executeInsideAtomicOperation(op -> {
      var status = collection.getRecordStatus(pos.collectionPosition, op);
      Assert.assertEquals(PaginatedCollection.RECORD_STATUS.PRESENT, status);
      Assert.assertTrue(collection.exists(pos.collectionPosition, op));

      var buffer = collection.readRecord(pos.collectionPosition, op).toRawBuffer();
      Assertions.assertThat(buffer.buffer()).isEqualTo(data);
    });

    // Delete the record
    atomicOps().executeInsideAtomicOperation(
        op -> collection.deleteRecord(op, pos.collectionPosition));

    flushToReadCache();

    // Verify REMOVED status and non-existence through optimistic path
    atomicOps().executeInsideAtomicOperation(op -> {
      var status = collection.getRecordStatus(pos.collectionPosition, op);
      Assert.assertEquals(PaginatedCollection.RECORD_STATUS.REMOVED, status);
      Assert.assertFalse(collection.exists(pos.collectionPosition, op));
    });

    // Verify readRecord throws RecordNotFoundException for deleted record
    try {
      atomicOps().calculateInsideAtomicOperation(
          op -> collection.readRecord(pos.collectionPosition, op).toRawBuffer());
      Assert.fail("Should throw RecordNotFoundException for deleted record");
    } catch (RecordNotFoundException e) {
      // expected
    }

    // Verify getPhysicalPosition returns null for deleted record
    var query = new PhysicalPosition();
    query.collectionPosition = pos.collectionPosition;
    var physPos = atomicOps().calculateInsideAtomicOperation(
        op -> collection.getPhysicalPosition(query, op));
    Assert.assertNull("Physical position should be null for deleted record", physPos);
  }

  // --- Bulk insert then bulk read: exercises optimistic paths with many entries ---

  // Verifies that inserting many records and then reading them all in separate read-only
  // atomic operations exercises the optimistic paths across multiple position map buckets
  // and data pages, indirectly covering FreeSpaceMap and CollectionPositionMapV2 optimistic
  // paths.
  @Test
  public void testBulkInsertThenBulkOptimisticRead() throws IOException {
    var random = new Random(99);
    var positions = new ArrayList<Long>();
    var dataList = new ArrayList<byte[]>();

    // Insert 50 records of varying sizes
    for (var i = 0; i < 50; i++) {
      var size = 10 + random.nextInt(200);
      var data = new byte[size];
      random.nextBytes(data);
      dataList.add(data);

      var pos = atomicOps().calculateInsideAtomicOperation(
          op -> collection.createRecord(data, (byte) 1, null, op));
      positions.add(pos.collectionPosition);
    }

    flushToReadCache();

    // Read all records in a single read-only atomic operation
    atomicOps().executeInsideAtomicOperation(op -> {
      for (var i = 0; i < positions.size(); i++) {
        var buffer = collection.readRecord(positions.get(i), op).toRawBuffer();
        Assert.assertNotNull("Record " + i + " should be readable", buffer);
        Assertions.assertThat(buffer.buffer())
            .as("Record %d data should match", i)
            .isEqualTo(dataList.get(i));
      }
    });

    // Verify entry count through optimistic path
    var entries = atomicOps().calculateInsideAtomicOperation(
        op -> collection.getEntries(op));
    Assert.assertEquals("Should have exactly 50 entries", 50L, entries.longValue());

    // Navigate positions with ceiling/higher/lower/floor in a read-only op
    atomicOps().executeInsideAtomicOperation(op -> {
      var first = collection.getFirstPosition(op);
      var last = collection.getLastPosition(op);

      var ceiling = collection.ceilingPositions(
          new PhysicalPosition(first), Integer.MAX_VALUE, op);
      Assert.assertEquals("Ceiling from first should cover all records",
          50, ceiling.length);

      var floor = collection.floorPositions(
          new PhysicalPosition(last), Integer.MAX_VALUE, op);
      Assert.assertEquals("Floor from last should cover all records",
          50, floor.length);
    });
  }

  // --- Integration tests for CollectionPage offset methods and RawPageBuffer ---

  // Verifies that CollectionPage.getRecordContentOffset and getRecordContentLength
  // return values consistent with the bytes returned by readRecord. Creates a record
  // via PaginatedCollectionV2, loads the page directly, and compares the content
  // bytes at the computed offset with the readRecord result.
  @Test
  public void testCollectionPageContentOffsetMatchesReadRecord() throws IOException {
    var data = new byte[] {10, 20, 30, 40, 50, 60, 70, 80, 90};
    var pos = atomicOps().calculateInsideAtomicOperation(
        op -> collection.createRecord(data, (byte) 'd', null, op));

    flushToReadCache();

    atomicOps().executeInsideAtomicOperation(op -> {
      // Read via normal path to get expected content
      var rawBuffer = collection.readRecord(pos.collectionPosition, op).toRawBuffer();
      byte[] expected = rawBuffer.buffer();

      // Load the page directly and check offset/length
      long fileId = collection.getFileId();
      // Page 1 is the first data page (page 0 is the state page)
      try (CacheEntry entry = storage.getReadCache().loadForRead(
          fileId, 1, storage.getWriteCache(), false)) {
        var page = new CollectionPage(entry);

        // The first record is at position 0 on the page
        int contentOffset = page.getRecordContentOffset(0);
        int contentLength = page.getRecordContentLength(0);

        Assert.assertEquals("Content length should match record data length",
            expected.length, contentLength);
        // Content offset must be at least past the entry header (3 ints)
        // and metadata header within the page.
        int minContentOffset =
            3 * IntegerSerializer.INT_SIZE + CollectionPage.RECORD_METADATA_HEADER_SIZE;
        Assert.assertTrue("Content offset " + contentOffset + " should be >= "
            + minContentOffset, contentOffset >= minContentOffset);
        Assert.assertTrue("Content must fit within page",
            contentOffset + contentLength <= CollectionPage.PAGE_SIZE);

        // Read bytes directly from the page buffer at the content offset
        ByteBuffer pageBuffer = entry.getCachePointer().getPageFrame().getBuffer();
        byte[] actual = new byte[contentLength];
        for (int i = 0; i < contentLength; i++) {
          actual[i] = pageBuffer.get(contentOffset + i);
        }
        Assert.assertArrayEquals("Bytes at content offset must match readRecord result",
            expected, actual);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    });
  }

  // Verifies the end-to-end zero-copy path: constructs a RawPageBuffer from a real
  // PageFrame obtained via the read cache, calls sliceContent(), wraps in
  // ReadBytesContainer, and verifies the bytes match the expected record content.
  @Test
  public void testRawPageBufferSliceContentIntegration() throws IOException {
    var data = new byte[] {1, 2, 3, 4, 5, 6, 7, 8};
    var pos = atomicOps().calculateInsideAtomicOperation(
        op -> collection.createRecord(data, (byte) 'd', null, op));

    flushToReadCache();

    atomicOps().executeInsideAtomicOperation(op -> {
      // Read via normal path to get expected content
      var rawBuffer = collection.readRecord(pos.collectionPosition, op).toRawBuffer();
      byte[] expected = rawBuffer.buffer();

      // Load the page and construct a RawPageBuffer
      long fileId = collection.getFileId();
      try (CacheEntry entry = storage.getReadCache().loadForRead(
          fileId, 1, storage.getWriteCache(), false)) {
        var page = new CollectionPage(entry);
        int contentOffset = page.getRecordContentOffset(0);
        int contentLength = page.getRecordContentLength(0);

        // Construct RawPageBuffer from the real PageFrame
        var pageFrame = entry.getCachePointer().getPageFrame();
        long stamp = pageFrame.tryOptimisticRead();
        var rawPageBuffer = new RawPageBuffer(
            pageFrame, stamp, contentOffset, contentLength,
            rawBuffer.version(), rawBuffer.recordType(), entry);

        // sliceContent should return a ByteBuffer with matching content
        ByteBuffer slice = rawPageBuffer.sliceContent();
        Assert.assertEquals(contentLength, slice.capacity());

        // Read bytes from the slice
        byte[] actual = new byte[contentLength];
        slice.get(actual);
        Assert.assertArrayEquals("sliceContent bytes must match readRecord result",
            expected, actual);

        // Stamp should still be valid (no writes happened)
        Assert.assertTrue("Stamp should still be valid after reading",
            pageFrame.validate(stamp));
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    });
  }

  // Verifies that CollectionPage content offset/length work for zero-length record content.
  // This exercises the contentLength == 0 boundary (recordSize == metadata + tail exactly).
  @Test
  public void testCollectionPageContentOffsetEmptyRecord() throws IOException {
    var pos = atomicOps().calculateInsideAtomicOperation(
        op -> collection.createRecord(new byte[0], (byte) 'd', null, op));

    flushToReadCache();

    atomicOps().executeInsideAtomicOperation(op -> {
      var rawBuffer = collection.readRecord(pos.collectionPosition, op).toRawBuffer();
      Assert.assertEquals(0, rawBuffer.buffer().length);

      long fileId = collection.getFileId();
      try (CacheEntry entry = storage.getReadCache().loadForRead(
          fileId, 1, storage.getWriteCache(), false)) {
        var page = new CollectionPage(entry);
        Assert.assertEquals(0, page.getRecordContentLength(0));

        var pageFrame = entry.getCachePointer().getPageFrame();
        long stamp = pageFrame.tryOptimisticRead();
        var rpb = new RawPageBuffer(pageFrame, stamp,
            page.getRecordContentOffset(0), 0,
            rawBuffer.version(), rawBuffer.recordType(), entry);
        ByteBuffer slice = rpb.sliceContent();
        Assert.assertEquals(0, slice.capacity());
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    });
  }

  // Verifies that CollectionPage content offset/length work for a non-first record
  // (position > 0) on the same page, catching pointer-array indexing bugs.
  @Test
  public void testCollectionPageContentOffsetSecondRecord() throws IOException {
    var data1 = new byte[] {10, 20, 30};
    var data2 = new byte[] {40, 50, 60, 70, 80};
    atomicOps().calculateInsideAtomicOperation(
        op -> collection.createRecord(data1, (byte) 'd', null, op));
    var pos2 = atomicOps().calculateInsideAtomicOperation(
        op -> collection.createRecord(data2, (byte) 'd', null, op));

    flushToReadCache();

    atomicOps().executeInsideAtomicOperation(op -> {
      var rawBuffer = collection.readRecord(pos2.collectionPosition, op).toRawBuffer();
      byte[] expected = rawBuffer.buffer();

      long fileId = collection.getFileId();
      try (CacheEntry entry = storage.getReadCache().loadForRead(
          fileId, 1, storage.getWriteCache(), false)) {
        var page = new CollectionPage(entry);
        int offset = page.getRecordContentOffset(1);
        int length = page.getRecordContentLength(1);
        Assert.assertEquals(expected.length, length);

        ByteBuffer buf = entry.getCachePointer().getPageFrame().getBuffer();
        byte[] actual = new byte[length];
        for (int i = 0; i < length; i++) {
          actual[i] = buf.get(offset + i);
        }
        Assert.assertArrayEquals(expected, actual);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    });
  }

  // --- PaginatedCollectionV2 metadata methods ---

  /**
   * toString() must return a non-null, non-empty string that includes the collection name
   * so it is identifiable in logs.
   */
  @Test
  public void testToString() {
    var s = collection.toString();
    Assert.assertNotNull(s);
    Assert.assertFalse(s.isEmpty());
    Assert.assertTrue("toString must include the collection name",
        s.contains("optReadCollection"));
  }

  /**
   * encryption() must return null for a non-encrypted collection.
   */
  @Test
  public void testEncryptionReturnsNull() {
    Assert.assertNull("non-encrypted collection must return null encryption",
        collection.encryption());
  }

  /**
   * Pins the close() contract: close() must flush in-memory state to disk and release the
   * collection's file handles cleanly.
   *
   * <p>The original version of this test only asserted "close() does not throw" — that
   * assertion was satisfied by an empty close() body, leaving the actual flush-and-close
   * contract unverified. This test now reads the inserted record back AFTER close() and a
   * fresh open() and asserts the bytes match what was written: this fails if close() left
   * state un-flushed (the new collection instance would not see the record) or if close()
   * left file handles in a state that reopen() rejects.
   *
   * <p>The companion test {@code testOpenReloadsStateFromDisk} covers the related but
   * distinct scenario where the entire DB is closed and reopened (full read-cache
   * eviction). This test covers the narrower scope: close()-only on the collection,
   * with the underlying database still open, exercising the flush-and-close path
   * without involving DB-level eviction.
   */
  @Test
  public void testCloseAndReopen() throws IOException {
    // Insert a record so there is something to flush.
    final byte[] data = {1, 2, 3};
    final var pos = atomicOps().calculateInsideAtomicOperation(
        op -> collection.createRecord(data, (byte) 1, null, op));

    // close() must not throw.
    collection.close();

    // Re-open. If close() did not flush the record's storage state to the underlying
    // pages (or if it left file handles in a bad state), the open() call below will
    // either throw or the readRecord() that follows will return stale/null data.
    collection = new PaginatedCollectionV2("optReadCollection", storage);
    collection.configure(55, "optReadCollection");
    atomicOps().executeInsideAtomicOperation(op -> collection.open(op));

    // Read back the previously-inserted record to verify close() did persist the state.
    // A close() that didn't actually do anything (empty body) would leave the new
    // collection instance unaware of the pre-close insert; readRecord would either
    // return null or throw, and this assertion would fail.
    final var buf = atomicOps().calculateInsideAtomicOperation(
        op -> collection.readRecord(pos.collectionPosition, op)).toRawBuffer();
    Assert.assertNotNull("Record must be readable after close+reopen", buf);
    Assert.assertArrayEquals(
        "Record bytes after close+reopen must match what was inserted before close",
        data, buf.buffer());
  }

  /**
   * setRecordConflictStrategy() must install the named strategy so a subsequent
   * getRecordConflictStrategy() returns a strategy whose name matches what was set.
   *
   * <p>The previous version of this test only asserted that toString() did not throw
   * after the call — that pin was satisfied even by an empty setRecordConflictStrategy
   * body (and even by Object.toString()). The current shape pins the round-trip
   * (set → get) so a regression that drops the assignment is detectable.
   */
  @Test
  public void testSetRecordConflictStrategy() {
    // "version" is a valid built-in conflict strategy in YouTrackDB.
    collection.setRecordConflictStrategy("version");

    // The collection must now report "version" as its active strategy.
    var strategy = collection.getRecordConflictStrategy();
    Assert.assertNotNull("getRecordConflictStrategy must return a non-null strategy after set",
        strategy);
    Assert.assertEquals("strategy name must round-trip set -> get",
        "version", strategy.getName());
  }

  /**
   * setCollectionName() must rename the backing files AND keep the collection usable
   * for subsequent reads. The previous version of this test only asserted that
   * toString() contained the new name — a no-op rename that updated the in-memory
   * label but skipped writeCache.renameFile / collectionPositionMap.rename /
   * freeSpaceMap.rename / dirtyPageBitSet.rename would still pass that pin.
   *
   * <p>The current shape exercises the full rename path by reading back a record
   * inserted before the rename: if any of the file-system rename operations is
   * dropped or mis-routed, readRecord either returns null/zero bytes (because the
   * collection looks for files under the new name that were never moved) or throws.
   */
  @Test
  public void testSetCollectionName() throws IOException {
    // Insert a record so the collection has files to rename and a payload to read back.
    final byte[] data = {7, 8, 9, 10, 11};
    final var pos = atomicOps().calculateInsideAtomicOperation(
        op -> collection.createRecord(data, (byte) 1, null, op));

    // Rename the collection — this must update writeCache, collectionPositionMap,
    // freeSpaceMap, and dirtyPageBitSet.
    collection.setCollectionName("renamedOptReadCollection");

    // The collection's reported name must be updated.
    Assert.assertEquals("getName must reflect the new collection name",
        "renamedOptReadCollection", collection.getName());

    // Reading back the record after rename exercises the full file-system rename:
    // the read path resolves files via the new name; if any rename was dropped,
    // the read either fails or returns wrong bytes.
    final var buf = atomicOps().calculateInsideAtomicOperation(
        op -> collection.readRecord(pos.collectionPosition, op)).toRawBuffer();
    Assert.assertNotNull("Record must be readable after rename", buf);
    Assert.assertArrayEquals(
        "Record bytes after rename must match what was inserted before rename",
        data, buf.buffer());

    // Rename back so @After tearDown can clean up normally.
    collection.setCollectionName("optReadCollection");
    Assert.assertEquals("getName must reflect the restored collection name",
        "optReadCollection", collection.getName());
  }

  /**
   * open() via flushToReadCache() exercises the open() lambda (the 23 uncovered lines in
   * lambda$open$1) by closing and reopening the database, then calling collection.open().
   * This test verifies that open() correctly reloads the collection state from disk and
   * that subsequently inserted records are readable.
   */
  @Test
  public void testOpenReloadsStateFromDisk() throws IOException {
    // Insert a record before the close/reopen cycle.
    byte[] data = {42, 43, 44};
    var pos = atomicOps().calculateInsideAtomicOperation(
        op -> collection.createRecord(data, (byte) 1, null, op));

    // Close and reopen the DB (exercises open() lambda).
    flushToReadCache();

    // The record inserted before reopen must still be readable.
    var buf = atomicOps().calculateInsideAtomicOperation(
        op -> collection.readRecord(pos.collectionPosition, op)).toRawBuffer();
    Assert.assertNotNull(buf);
    Assertions.assertThat(buf.buffer()).isEqualTo(data);
  }
}
