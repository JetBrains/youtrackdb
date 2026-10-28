package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.LogRecordCollector;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.WOWCache;
import com.jetbrains.youtrackdb.internal.core.storage.collection.CollectionPage;
import com.jetbrains.youtrackdb.internal.core.storage.collection.CollectionPageAppendRecordOp;
import com.jetbrains.youtrackdb.internal.core.storage.disk.DiskStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationTestBridge;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsTable;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AtomicUnitEndRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import it.unimi.dsi.fastutil.ints.IntSets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.commons.configuration2.BaseConfiguration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Real disk-WAL retention while a late writer keeps its logged page changes private. */
public class AbstractStorageWALCutDiskTest {

  private static final String DATABASE = "walCut";
  private static final String DATA_FILE = "vacuum-private-page.dat";
  private static final byte[] PAYLOAD = {11, 37, 59, 83, 101};
  private Path root;

  @Before
  public void createDirectory() throws Exception {
    root = Files.createTempDirectory("wal-cut-disk-");
  }

  @After
  public void removeDirectory() {
    FileUtils.deleteRecursively(root.toFile());
  }

  /**
   * Vacuum samples an empty real operation table, then a writer registers in S and logs a
   * real append plus its commit record before publishing any page. WAL rotates to S+1.
   * Vacuum must retain S even though its table observation and later cache observation are
   * empty. The serialized record identifies the operation, file, page, initial LSN and payload.
   * The unchanged data file and shared page prove that the append still needs WAL recovery.
   * This tests segment retention, not power-loss recovery.
   */
  @Test(timeout = 90_000)
  public void vacuumRetainsRequiredPageRecordFromWriterRegisteredAfterTableSample()
      throws Exception {
    final var config = new BaseConfiguration();
    config.setProperty(GlobalConfiguration.WAL_KEEP_SINGLE_SEGMENT.getKey(), false);
    // Only the explicitly scheduled vacuum may maintain this fixture during the test.
    config.setProperty(GlobalConfiguration.WAL_FUZZY_CHECKPOINT_INTERVAL.getKey(), 3600);
    config.setProperty(GlobalConfiguration.STORAGE_COLLECTION_GC_PAUSE_INTERVAL.getKey(), 3600);
    config.setProperty(GlobalConfiguration.WAL_SEGMENTS_INTERVAL.getKey(), 3600);
    try (var manager = (YouTrackDBImpl) YourTracks.instance(root.toString(), config)) {
      manager.create(DATABASE, DatabaseType.DISK, "admin", "admin", "admin");
      try (var session = manager.open(DATABASE, "admin", "admin")) {
        final var storage = (DiskStorage) session.getStorage();
        final var cache = storage.getWriteCache();
        final var wal = storage.getWALInstance();
        cache.pauseBackgroundFlush();
        try {
          final var operations = storage.getAtomicOperationsManager();
          // Use a durable, initialized page. Only this test writes its file.
          final long fileId = operations.calculateInsideAtomicOperation(operation -> {
            final var id = operation.addFile(DATA_FILE, false);
            try (var entry = operation.allocatePageForWrite(id, 0)) {
              new CollectionPage(entry).init();
            }
            return id;
          });
          storage.synch();
          // Resolve the cache mapping because logical names can differ from physical names.
          final var nativeFileName = cache.nativeFileNameById(fileId);
          assertNotNull("initialized file must have a physical cache mapping", nativeFileName);
          final var dataPath = storage.getStoragePath().resolve(nativeFileName);
          final var persistedPage = Files.readAllBytes(dataPath);
          final var originalTable = storage.atomicOperationsTable;
          assertEquals(-1, originalTable.getSegmentEarliestNotPersistedOperation());
          assertEquals(null, cache.getMinimalNotFlushedSegment());
          wal.appendNewSegment();
          wal.flush();
          final var segment = wal.activeSegment();
          assertTrue(wal.nonActiveSegments().length > 0);

          final var sampled = new CountDownLatch(1);
          final var resumeVacuum = new CountDownLatch(1);
          final var beforePageApply = new CountDownLatch(1);
          final var publishPage = new CountDownLatch(1);
          final var commitTs = new AtomicLong(-1);
          final var tableObservation = new AtomicLong(Long.MIN_VALUE);
          // Delegate to the same backing table used by the manager. Do not fabricate owners
          // or cut results. Park only after the real table has completed its observation.
          final var observedTable = mock(AtomicOperationsTable.class, delegatesTo(originalTable));
          doAnswer(invocation -> {
            final var observed = originalTable.getSegmentEarliestNotPersistedOperation();
            tableObservation.set(observed);
            sampled.countDown();
            await(resumeVacuum, "vacuum table observation must be released");
            return observed;
          }).when(observedTable).getSegmentEarliestNotPersistedOperation();
          storage.atomicOperationsTable = observedTable;
          try (var workers = Executors.newFixedThreadPool(2);
              var logs = LogRecordCollector.attachTo(storage.getClass())) {
            final var vacuum = workers.submit(((AbstractStorage) storage)::runWALVacuum);
            java.util.concurrent.Future<?> writer = null;
            try {
              assertTrue("vacuum must complete its real table sample",
                  sampled.await(30, TimeUnit.SECONDS));
              assertEquals(-1, tableObservation.get());
              writer = workers.submit(() -> {
                operations.executeInsideAtomicOperation(operation -> {
                  commitTs.set(operation.getCommitTs());
                  try (var entry = operation.loadPageForWrite(fileId, 0, 1, true)) {
                    assertEquals(0, new CollectionPage(entry).appendRecord(
                        commitTs.get(), PAYLOAD, -1, IntSets.EMPTY_SET));
                  }
                  AtomicOperationTestBridge.installPageApplyHook(operation,
                      new AtomicOperationTestBridge.TestPageApplyHook() {
                        @Override
                        public void beforePageApply(long appliedFile, long pageIndex) {
                          assertEquals(fileId, appliedFile);
                          assertEquals(0, pageIndex);
                          beforePageApply.countDown();
                          await(publishPage, "writer page publication must be released");
                        }
                      });
                });
                return null;
              });
              assertTrue("writer must log its commit before shared page publication",
                  beforePageApply.await(30, TimeUnit.SECONDS));
              assertEquals(segment, originalTable.getSegmentEarliestNotPersistedOperation());
              assertEquals(null, cache.getMinimalNotFlushedSegment());
              wal.flush();
              final var record = requiredAppend(wal, segment, fileId, commitTs.get(), null);
              assertSharedPage(storage, fileId, false);
              assertArrayEquals(persistedPage, Files.readAllBytes(dataPath));
              final var segmentPath = storage.getStoragePath().resolve(
                  ContextConfiguration.WAL_DEFAULT_NAME + "." + segment + ".wal");
              assertTrue("required segment must exist before vacuum", Files.exists(segmentPath));
              wal.appendNewSegment();
              wal.flush();
              assertEquals(segment + 1, wal.activeSegment());
              System.out.printf("Before vacuum: S=%d, operation=%d, file=%d, page=0, LSN=%s, "
                  + "payload verified, commit logged, page unpublished%n",
                  segment, commitTs.get(), fileId, record);

              resumeVacuum.countDown();
              vacuum.get(30, TimeUnit.SECONDS);
              assertFalse("vacuum must complete without a swallowed maintenance failure",
                  logs.messages().stream().anyMatch(message -> message.startsWith("SEVERE")
                      && message.contains("fuzzy checkpoint")));
              assertSharedPage(storage, fileId, false);
              assertArrayEquals("vacuum cannot persist private changes", persistedPage,
                  Files.readAllBytes(dataPath));
              System.out.printf("After vacuum: required segment exists=%s, begin(S)=%s, "
                  + "WAL begin=%s, page still unpublished%n",
                  Files.exists(segmentPath), wal.begin(segment), wal.begin());
              assertTrue("vacuum deleted required segment " + segment
                  + " containing append " + record + " for operation " + commitTs.get()
                  + ", file " + fileId + ", page 0 while its payload was unpublished",
                  Files.exists(segmentPath));
              requiredAppend(wal, segment, fileId, commitTs.get(), record);
              publishPage.countDown();
              writer.get(30, TimeUnit.SECONDS);
              assertSharedPage(storage, fileId, true);
            } finally {
              // Release both schedules even on the expected retention failure. Complete the
              // real writer before normal storage teardown so no freezer admission is leaked.
              resumeVacuum.countDown();
              publishPage.countDown();
              vacuum.get(30, TimeUnit.SECONDS);
              if (writer != null) {
                writer.get(30, TimeUnit.SECONDS);
              }
            }
          } finally {
            storage.atomicOperationsTable = originalTable;
          }
        } finally {
          cache.resumeBackgroundFlush();
        }
      }
    }
  }

  /** Fuzzy checkpoint still synchronizes and retains real dirty-page WAL with an error latched. */
  @Test(timeout = 90_000)
  public void fuzzyCheckpointRetainsRequiredRecordsWithLatchedFlushError() throws Exception {
    assertLatchedErrorRetention(Maintenance.FUZZY);
  }

  /** Vacuum reports its failed flush and keeps the dirty page's real append and commit records. */
  @Test(timeout = 90_000)
  public void vacuumRetainsRequiredRecordsWithLatchedFlushError() throws Exception {
    assertLatchedErrorRetention(Maintenance.VACUUM);
  }

  /** Synch propagates the latched cache error without cutting required WAL or clearing recovery. */
  @Test(timeout = 90_000)
  public void synchRetainsRequiredRecordsWithLatchedFlushError() throws Exception {
    assertLatchedErrorRetention(Maintenance.SYNCH);
  }

  private enum Maintenance {
    FUZZY, VACUUM, SYNCH
  }

  /** Seed a durable page, publish a committed append without flushing it, then latch cache failure. */
  private void assertLatchedErrorRetention(Maintenance route) throws Exception {
    final var config = new BaseConfiguration();
    config.setProperty(GlobalConfiguration.WAL_KEEP_SINGLE_SEGMENT.getKey(), false);
    config.setProperty(GlobalConfiguration.WAL_FUZZY_CHECKPOINT_INTERVAL.getKey(), 3600);
    config.setProperty(GlobalConfiguration.STORAGE_COLLECTION_GC_PAUSE_INTERVAL.getKey(), 3600);
    config.setProperty(GlobalConfiguration.WAL_SEGMENTS_INTERVAL.getKey(), 3600);
    try (var manager = (YouTrackDBImpl) YourTracks.instance(root.toString(), config)) {
      manager.create(DATABASE, DatabaseType.DISK, "admin", "admin", "admin");
      try (var session = manager.open(DATABASE, "admin", "admin")) {
        final var storage = (DiskStorage) session.getStorage();
        final var cache = storage.getWriteCache();
        final var wal = storage.getWALInstance();
        cache.pauseBackgroundFlush();
        final var errorField = WOWCache.class.getDeclaredField("flushError");
        errorField.setAccessible(true);
        try {
          final var operations = storage.getAtomicOperationsManager();
          final long fileId = operations.calculateInsideAtomicOperation(operation -> {
            final var id = operation.addFile(DATA_FILE, false);
            try (var entry = operation.allocatePageForWrite(id, 0)) {
              new CollectionPage(entry).init();
            }
            return id;
          });
          storage.synch();
          final var dataPath = storage.getStoragePath().resolve(cache.nativeFileNameById(fileId));
          final var persistedPage = Files.readAllBytes(dataPath);
          wal.appendNewSegment();
          final long segment = wal.activeSegment();
          final var commitTs = new AtomicLong();
          operations.executeInsideAtomicOperation(operation -> {
            commitTs.set(operation.getCommitTs());
            try (var entry = operation.loadPageForWrite(fileId, 0, 1, true)) {
              assertEquals(0, new CollectionPage(entry).appendRecord(
                  commitTs.get(), PAYLOAD, -1, IntSets.EMPTY_SET));
            }
          });
          wal.flush();
          final var appendLsn = requiredAppend(wal, segment, fileId, commitTs.get(), null);
          assertSharedPage(storage, fileId, true);
          assertArrayEquals(persistedPage, Files.readAllBytes(dataPath));
          final var owner = cache.getMinimalNotFlushedSegment();
          assertNotNull("the published page must retain its recovery WAL", owner);
          assertTrue(owner <= segment);
          final var beginBefore = wal.begin();
          assertTrue("an older segment must be removable", beginBefore.getSegment() < owner);
          wal.appendNewSegment();
          wal.flush();
          assertEquals(segment + 1, wal.activeSegment());
          final var failure = new IOException("injected latched write failure");
          // Inject only the cache latch. Synch must reach the real flush failure, not an
          // independently poisoned storage gate. Background writes remain paused throughout.
          assertEquals(null, errorField.get(cache));
          errorField.set(cache, failure);
          final var floorSaved = new AtomicBoolean();
          storage.setAfterMaintenanceFloorActionForTesting(ignored -> floorSaved.set(true));
          try (var logs = LogRecordCollector.attachTo(storage.getClass())) {
            switch (route) {
              case FUZZY -> {
                storage.makeFuzzyCheckpoint();
                assertTrue("fuzzy checkpoint must reach its real synchronization cut",
                    floorSaved.get());
                assertEquals(owner.longValue(), wal.begin().getSegment());
              }
              case VACUUM -> {
                ((AbstractStorage) storage).runWALVacuum();
                assertTrue("vacuum must report its failed cache flush", logs.messages().stream()
                    .anyMatch(message -> message.startsWith("SEVERE")
                        && message.contains("fuzzy checkpoint")));
                assertEquals(beginBefore, wal.begin());
              }
              case SYNCH -> {
                Throwable cause = assertThrows(RuntimeException.class, storage::synch);
                while (cause.getCause() != null) {
                  cause = cause.getCause();
                }
                assertSame("synch must propagate the injected cache failure", failure, cause);
                assertEquals(beginBefore, wal.begin());
              }
            }
          }
          assertSame("maintenance must not clear the cache failure", failure,
              errorField.get(cache));
          assertTrue("required real segment must remain on disk", Files.exists(
              storage.getStoragePath().resolve(
                  ContextConfiguration.WAL_DEFAULT_NAME + "." + segment + ".wal")));
          requiredAppend(wal, segment, fileId, commitTs.get(), appendLsn);
          assertSharedPage(storage, fileId, true);
          assertArrayEquals("latched failure must leave the needed payload off disk", persistedPage,
              Files.readAllBytes(dataPath));
          assertTrue("unflushed committed data must keep recovery indicated",
              storage.readStartupMetadataForTesting()[12] != 0);
        } finally {
          // Restore the injected latch only for normal fixture teardown.
          storage.clearCheckpointActionsForTesting();
          errorField.set(cache, null);
          cache.resumeBackgroundFlush();
        }
      }
    }
  }

  /** Read deserialized WAL records, including the successful commit that requires the append. */
  private static LogSequenceNumber requiredAppend(WriteAheadLog wal, long segment,
      long fileId, long commitTs, LogSequenceNumber expectedLsn) throws Exception {
    final var begin = wal.begin(segment);
    assertNotNull("required WAL segment must be readable", begin);
    final var records = wal.read(begin, 0);
    final var appends = records.stream()
        .filter(record -> record instanceof CollectionPageAppendRecordOp append
            && append.getOperationUnitId() == commitTs)
        .map(record -> (CollectionPageAppendRecordOp) record).toList();
    assertEquals("exactly one required append must survive WAL serialization", 1, appends.size());
    final var append = appends.getFirst();
    assertEquals(fileId, append.getFileId());
    assertEquals(0, append.getPageIndex());
    assertEquals(commitTs, append.getRecordVersion());
    assertEquals(0, append.getAllocatedIndex());
    assertArrayEquals(PAYLOAD, append.getRecord());
    assertNotNull(append.getInitialLsn());
    assertTrue(append.getInitialLsn().getSegment() < segment);
    assertEquals(segment, append.getLsn().getSegment());
    if (expectedLsn != null) {
      assertEquals(expectedLsn, append.getLsn());
    }
    assertTrue("the required append belongs to a successfully ended WAL unit",
        records.stream().anyMatch(record -> record instanceof AtomicUnitEndRecord end
            && end.getOperationUnitId() == commitTs && !end.isRollback()));
    return append.getLsn();
  }

  /** Check the shared page independently from the writer's private binary changes. */
  private static void assertSharedPage(DiskStorage storage, long fileId, boolean published)
      throws Exception {
    final var reader = storage.getAtomicOperationsManager().startAtomicOperation();
    final var entry = reader.loadPageForRead(fileId, 0);
    try {
      final var page = new CollectionPage(entry);
      assertEquals(published ? 1 : 0, page.getRecordsCount());
      if (published) {
        assertArrayEquals(PAYLOAD, page.getRecordBinaryValue(0, 0, PAYLOAD.length));
      }
    } finally {
      reader.releasePageFromRead(entry);
      reader.deactivate();
    }
  }

  private static void await(CountDownLatch latch, String description) {
    try {
      assertTrue(description, latch.await(30, TimeUnit.SECONDS));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(description, e);
    }
  }
}
