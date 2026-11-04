package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.storage.cache.ApplyPhaseEpoch;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.OptimisticReadFailedException;
import com.jetbrains.youtrackdb.internal.core.storage.cache.OptimisticReadScope;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationBinaryTracking.PageApplyHook;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsTable.AtomicOperationsSnapshot;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.StorageComponent;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.FileCreatedWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.FileDeletedWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageAllocatedWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.TestPageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordsFactory;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.common.WriteableWALRecord;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/**
 * Tests for the TEST-ONLY {@link PageApplyHook} seam in
 * {@link AtomicOperationBinaryTracking#commitChanges} and for the apply-phase epoch
 * bracket around the page-apply loop (YTDB-1178).
 *
 * <p>The seam exists because the production page-apply order is fastutil hash order,
 * which makes the mixed-state race (a reader overlapping a partially applied commit)
 * practically impossible to reproduce. These tests verify that:
 *
 * <ul>
 *   <li>the hook can dictate and observe the page-apply order;
 *   <li>a reader overlapping a writer paused mid-apply (between two page applications,
 *       inside the epoch bracket) deterministically fails epoch validation;
 *   <li>the epoch bracket is exactly one enter/exit pair per commit, with the exit in a
 *       finally block that runs even when the hook throws.
 * </ul>
 */
public class CommitChangesPageApplyHookTest {

  private static final int STORAGE_ID = 1;
  private static final int PAGE_SIZE = DurablePage.MAX_PAGE_SIZE_BYTES;

  private ReadCache readCache;
  private WriteCache writeCache;
  private WriteAheadLog wal;
  private AtomicInteger lsnCounter;
  private AtomicLong fileIdCounter;
  private ApplyPhaseEpoch epoch;

  // Order in which pages hit readCache.loadOrAddForWrite — the authoritative signal of
  // apply order. Synchronized because the writer may run on a separate thread.
  private List<Long> appliedPageOrder;

  @Before
  public void setUp() throws IOException {
    readCache = mock(ReadCache.class);
    writeCache = mock(WriteCache.class);
    when(writeCache.getStorageName()).thenReturn("test-storage");
    wal = mock(WriteAheadLog.class);
    lsnCounter = new AtomicInteger(1);
    fileIdCounter = new AtomicLong(100);
    epoch = new ApplyPhaseEpoch();
    appliedPageOrder = Collections.synchronizedList(new ArrayList<>());

    WALRecordsFactory.INSTANCE.registerNewRecord(
        TestPageOperation.TEST_RECORD_ID, TestPageOperation.class);

    when(wal.log(any(WriteableWALRecord.class))).thenAnswer(invocation -> {
      var record = invocation.getArgument(0, WriteableWALRecord.class);
      var lsn = new LogSequenceNumber(0, lsnCounter.getAndIncrement());
      record.setLsn(lsn);
      return lsn;
    });
    when(wal.logAtomicOperationStartRecord(anyBoolean(), anyLong()))
        .thenAnswer(invocation -> new LogSequenceNumber(0, lsnCounter.getAndIncrement()));
    when(wal.end()).thenAnswer(inv -> new LogSequenceNumber(0, lsnCounter.get()));

    // loadOrAddForWrite is the first cache touch of each page application: record the
    // order and return a pre-built mock entry. Mock entries are created lazily on
    // whichever thread runs commitChanges — creating plain Mockito mocks off the test
    // thread is safe, and per-invocation stubbing is avoided by building the entry with
    // a self-contained answer.
    when(readCache.loadOrAddForWrite(anyLong(), anyLong(), any(), anyBoolean(), any()))
        .thenAnswer(invocation -> {
          final long fileId = invocation.getArgument(0);
          final long pageIndex = invocation.getArgument(1);
          appliedPageOrder.add(pageIndex);
          return newCacheEntry(fileId, (int) pageIndex);
        });
  }

  private static CacheEntry newCacheEntry(long fileId, int pageIndex) {
    var buffer = ByteBuffer.allocateDirect(PAGE_SIZE).order(ByteOrder.nativeOrder());
    var cachePointer = mock(CachePointer.class);
    when(cachePointer.getBuffer()).thenReturn(buffer);

    var cacheEntry = mock(CacheEntry.class);
    when(cacheEntry.getPageIndex()).thenReturn(pageIndex);
    when(cacheEntry.getFileId()).thenReturn(fileId);
    when(cacheEntry.getCachePointer()).thenReturn(cachePointer);
    return cacheEntry;
  }

  private AtomicOperationBinaryTracking createOperation() {
    var snapshot = new AtomicOperationsSnapshot(0, 100, new LongOpenHashSet(), 100);
    var op = new AtomicOperationBinaryTracking(
        readCache, writeCache, wal, STORAGE_ID,
        snapshot,
        new ConcurrentSkipListMap<>(),
        new ConcurrentSkipListMap<>(),
        new AtomicLong(),
        new ConcurrentSkipListMap<>(),
        new ConcurrentSkipListMap<>(),
        new AtomicLong(),
        epoch);
    op.startToApplyOperations(42);
    return op;
  }

  private static long composeFileId(long internalId, int storageId) {
    return internalId | ((long) storageId << 32);
  }

  /**
   * Creates a new durable file with {@code pageCount} changed pages (indexes 0..n-1),
   * registers a logical PageOperation per page, and flushes so every page carries a
   * changeLSN — the precondition for reaching the commit-time apply loop.
   */
  private long setupNewFileWithPages(
      AtomicOperationBinaryTracking op, String fileName, int pageCount) throws IOException {
    long internalId = fileIdCounter.getAndIncrement();
    long fullFileId = composeFileId(internalId, STORAGE_ID);
    when(writeCache.bookFileId(fileName)).thenReturn(fullFileId);
    long fileId = op.addFile(fileName);

    for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
      var page = op.allocatePageForWrite(fileId, pageIndex);
      page.getChanges().setByteValue(null, (byte) 1, 100);
      page.setInitialLSN(new LogSequenceNumber(-1, -1));
      op.registerPageOperation(fileId, pageIndex,
          new TestPageOperation(
              pageIndex, fileId, 0, new LogSequenceNumber(0, 0), pageIndex));
    }
    op.flushPendingOperations();
    return fileId;
  }

  /** Durable new-file gaps are declared in WAL but only changed pages enter the apply set. */
  @Test
  public void testChangedSetForNewDurableFile() throws IOException {
    assertChangedSetMatchesApply(true, false, true);
  }

  /** Non-durable new-file pages need the same exact set without WAL records. */
  @Test
  public void testChangedSetForNewNonDurableFile() throws IOException {
    assertChangedSetMatchesApply(true, true, true);
  }

  /** Existing durable files reuse the set in the production path without a test hook. */
  @Test
  public void testChangedSetForExistingDurableFileWithoutHook() throws IOException {
    assertChangedSetMatchesApply(false, false, false);
  }

  /** Existing non-durable files exclude unchanged allocations even with hook ordering. */
  @Test
  public void testChangedSetForExistingNonDurableFile() throws IOException {
    assertChangedSetMatchesApply(false, true, true);
  }

  private void assertChangedSetMatchesApply(boolean newFile, boolean nonDurable, boolean hook)
      throws IOException {
    var op = createOperation();
    long fileId = composeFileId(100, STORAGE_ID);
    when(writeCache.isNonDurable(fileId)).thenReturn(nonDurable);
    if (newFile) {
      when(writeCache.bookFileId("set.dat")).thenReturn(fileId);
      Assert.assertEquals(fileId, op.addFile("set.dat", nonDurable));
    } else {
      when(writeCache.loadFile("set.dat")).thenReturn(fileId);
      Assert.assertEquals(fileId, op.loadFile("set.dat"));
      when(writeCache.getFilledUpTo(fileId)).thenReturn(1L);
      var existingPage = newCacheEntry(fileId, 0);
      when(readCache.loadForRead(fileId, 0, writeCache, true)).thenReturn(existingPage);
    }

    // Page 1 is an allocation gap and page 3 is an unused tail. Neither is changed.
    for (int i = 0; i < 4; i++) {
      var page = !newFile && i == 0 ? op.loadPageForWrite(fileId, i, 1, true)
          : op.allocatePageForWrite(fileId, i);
      if (i == 0 || i == 2) {
        page.getChanges().setByteValue(null, (byte) (i + 1), 100);
        page.setInitialLSN(new LogSequenceNumber(-1, -1));
        if (!nonDurable) {
          op.registerPageOperation(fileId, i,
              new TestPageOperation(i, fileId, 0, new LogSequenceNumber(0, 0), i));
        }
      }
    }

    var released = new ArrayList<Long>();
    doAnswer(invocation -> {
      var entry = invocation.getArgument(0, CacheEntry.class);
      Assert.assertEquals(fileId, entry.getFileId());
      Assert.assertEquals(entry.getPageIndex() + 1,
          entry.getCachePointer().getBuffer().get(100));
      released.add((long) entry.getPageIndex());
      return null;
    }).when(readCache).releaseFromWrite(any(), eq(writeCache), eq(true));

    var offered = new ArrayList<Long>();
    if (hook) {
      op.setPageApplyHook(new PageApplyHook() {
        @Override
        public long[] orderPageApplications(long id, long[] pages) {
          Assert.assertEquals(fileId, id);
          var sorted = pages.clone();
          java.util.Arrays.sort(sorted);
          Assert.assertArrayEquals(new long[] {0, 2}, sorted);
          for (long page : pages) {
            offered.add(page);
          }
          return new long[] {2, 0};
        }
      });
    }

    var end = op.commitChanges(42, wal);
    Assert.assertEquals(!nonDurable, end != null);
    var sortedApplied = new ArrayList<>(appliedPageOrder);
    Collections.sort(sortedApplied);
    Assert.assertEquals(List.of(0L, 2L), sortedApplied);
    Assert.assertEquals(appliedPageOrder, released);
    if (hook) {
      Collections.sort(offered);
      Assert.assertEquals(sortedApplied, offered);
      Assert.assertEquals(List.of(2L, 0L), appliedPageOrder);
    }
    if (nonDurable) {
      verify(wal, never()).log(any());
    } else {
      var records = ArgumentCaptor.forClass(WriteableWALRecord.class);
      verify(wal, atLeastOnce()).log(records.capture());
      Assert.assertEquals(newFile ? List.of(0L, 1L, 2L) : List.of(1L, 2L),
          records.getAllValues().stream()
              .filter(PageAllocatedWALRecord.class::isInstance)
              .map(PageAllocatedWALRecord.class::cast)
              .map(PageAllocatedWALRecord::getPageIndex).toList());
    }
    Assert.assertEquals(1, epoch.enterSeq());
    Assert.assertEquals(1, epoch.exitSeq());
  }

  /** Deleted-file changes never enter the set or apply, while deletion still brackets the epoch. */
  @Test
  public void testDeletedFileHasNoPagesToApply() throws IOException {
    var op = createOperation();
    long fileId = composeFileId(100, STORAGE_ID);
    when(writeCache.loadFile("deleted.dat")).thenReturn(fileId);
    when(writeCache.fileNameById(fileId)).thenReturn("deleted.dat");
    op.loadFile("deleted.dat");
    op.allocatePageForWrite(fileId, 0).getChanges().setByteValue(null, (byte) 1, 100);
    op.deleteFile(fileId);
    op.setPageApplyHook(new PageApplyHook() {
      @Override
      public long[] orderPageApplications(long id, long[] pages) {
        Assert.fail("Deleted files must not offer pages");
        return null;
      }
    });

    Assert.assertNotNull(op.commitChanges(42, wal));
    Assert.assertTrue(appliedPageOrder.isEmpty());
    verify(readCache).deleteFile(fileId, writeCache);
    Assert.assertEquals(1, epoch.enterSeq());
    Assert.assertEquals(1, epoch.exitSeq());
  }

  /**
   * Same-name recreation reuses the loaded file without physical deletion or creation.
   * Only the changed allocation is applied, not the unchanged tail allocation.
   */
  @Test
  public void testDeletedThenRecreatedSameNameAppliesChangedPage() throws IOException {
    var op = createOperation();
    long fileId = composeFileId(100, STORAGE_ID);
    when(writeCache.loadFile("recreated.dat")).thenReturn(fileId);
    when(writeCache.fileNameById(fileId)).thenReturn("recreated.dat");
    Assert.assertEquals(fileId, op.loadFile("recreated.dat"));
    op.deleteFile(fileId);
    Assert.assertEquals(fileId, op.addFile("recreated.dat"));
    var page = op.allocatePageForWrite(fileId, 0);
    page.getChanges().setByteValue(null, (byte) 7, 100);
    page.setInitialLSN(new LogSequenceNumber(-1, -1));
    op.registerPageOperation(fileId, 0,
        new TestPageOperation(0, fileId, 0, new LogSequenceNumber(0, 0), 0));
    op.allocatePageForWrite(fileId, 1);

    var offered = new HashMap<Long, List<Long>>();
    observePerFileSet(op, offered);
    Assert.assertNotNull(op.commitChanges(42, wal));
    Assert.assertEquals(Map.of(fileId, List.of(0L)), offered);
    Assert.assertEquals(List.of(0L), appliedPageOrder);
    verify(readCache).loadOrAddForWrite(eq(fileId), eq(0L), eq(writeCache),
        anyBoolean(), any());
    verify(readCache, never()).deleteFile(anyLong(), eq(writeCache));
    verify(readCache, never()).addFile(any(), anyLong(), eq(writeCache), anyBoolean());
    var records = ArgumentCaptor.forClass(WriteableWALRecord.class);
    verify(wal, atLeastOnce()).log(records.capture());
    Assert.assertTrue(records.getAllValues().stream().noneMatch(
        record -> record instanceof FileDeletedWALRecord
            || record instanceof FileCreatedWALRecord));
    Assert.assertEquals(1, epoch.enterSeq());
    Assert.assertEquals(1, epoch.exitSeq());
  }

  /**
   * A mixed commit applies exactly each surviving file's changed pages, excludes the
   * deleted file, and logs deletion before creation of the fresh durable file.
   */
  @Test
  public void testMixedFilesApplyPerFileSetsAndDeleteBeforeCreate() throws IOException {
    var op = createOperation();
    long deletedId = composeFileId(99, STORAGE_ID);
    when(writeCache.loadFile("deleted.dat")).thenReturn(deletedId);
    when(writeCache.fileNameById(deletedId)).thenReturn("deleted.dat");
    op.loadFile("deleted.dat");
    op.allocatePageForWrite(deletedId, 4).getChanges().setByteValue(null, (byte) 9, 100);
    op.deleteFile(deletedId);

    long durableId = setupNewFileWithPages(op, "durable.dat", 2);
    long nonDurableId = composeFileId(101, STORAGE_ID);
    when(writeCache.bookFileId("non-durable.dat")).thenReturn(nonDurableId);
    Assert.assertEquals(nonDurableId, op.addFile("non-durable.dat", true));
    op.allocatePageForWrite(nonDurableId, 2).getChanges()
        .setByteValue(null, (byte) 3, 100);
    op.allocatePageForWrite(nonDurableId, 3);

    var offered = new HashMap<Long, List<Long>>();
    observePerFileSet(op, offered);
    Assert.assertNotNull(op.commitChanges(42, wal));
    var expected = Map.of(durableId, List.of(0L, 1L), nonDurableId, List.of(2L));
    Assert.assertEquals(expected, offered);

    var files = ArgumentCaptor.forClass(Long.class);
    var pages = ArgumentCaptor.forClass(Long.class);
    verify(readCache, org.mockito.Mockito.times(3)).loadOrAddForWrite(
        files.capture(), pages.capture(), eq(writeCache), anyBoolean(), any());
    var applied = new HashMap<Long, List<Long>>();
    for (int i = 0; i < files.getAllValues().size(); i++) {
      applied.computeIfAbsent(files.getAllValues().get(i), id -> new ArrayList<>())
          .add(pages.getAllValues().get(i));
    }
    applied.values().forEach(Collections::sort);
    Assert.assertEquals(expected, applied);
    verify(readCache).deleteFile(deletedId, writeCache);
    var records = ArgumentCaptor.forClass(WriteableWALRecord.class);
    verify(wal, atLeastOnce()).log(records.capture());
    Assert.assertEquals(List.of(FileDeletedWALRecord.class, FileCreatedWALRecord.class),
        records.getAllValues().stream()
            .filter(record -> record instanceof FileDeletedWALRecord
                || record instanceof FileCreatedWALRecord)
            .map(Object::getClass).toList());
    Assert.assertEquals(1, epoch.enterSeq());
    Assert.assertEquals(1, epoch.exitSeq());
  }

  private static void observePerFileSet(
      AtomicOperationBinaryTracking op, Map<Long, List<Long>> offered) {
    op.setPageApplyHook(new PageApplyHook() {
      @Override
      public long[] orderPageApplications(long fileId, long[] pages) {
        var sorted = pages.clone();
        java.util.Arrays.sort(sorted);
        var indexes = new ArrayList<Long>();
        for (long page : sorted) {
          indexes.add(page);
        }
        Assert.assertNull("Each file is offered once", offered.put(fileId, indexes));
        return null;
      }
    });
  }

  /** Opened-but-unchanged non-durable pages produce an empty set and no epoch entry. */
  @Test
  public void testUnchangedExistingFileDoesNotApplyOrEnterEpoch() throws IOException {
    var op = createOperation();
    long fileId = composeFileId(100, STORAGE_ID);
    when(writeCache.isNonDurable(fileId)).thenReturn(true);
    when(writeCache.loadFile("unchanged.dat")).thenReturn(fileId);
    op.loadFile("unchanged.dat");
    when(writeCache.getFilledUpTo(fileId)).thenReturn(1L);
    var existingPage = newCacheEntry(fileId, 0);
    when(readCache.loadForRead(fileId, 0, writeCache, true)).thenReturn(existingPage);
    Assert.assertNotNull(op.loadPageForWrite(fileId, 0, 1, true));
    op.setPageApplyHook(new PageApplyHook() {
      @Override
      public long[] orderPageApplications(long id, long[] pages) {
        Assert.assertEquals(fileId, id);
        Assert.assertArrayEquals(new long[0], pages);
        return null;
      }
    });

    Assert.assertNull(op.commitChanges(42, wal));
    Assert.assertTrue(appliedPageOrder.isEmpty());
    Assert.assertEquals(0, epoch.enterSeq());
    Assert.assertEquals(0, epoch.exitSeq());
  }

  @Test
  public void testHookControlsAndObservesApplyOrder() throws IOException {
    // The hook dictates a reversed apply order (page 1 before page 0) and observes each
    // application via beforePageApply. Both the hook's observations and the cache-level
    // loadOrAddForWrite order must match the requested order — proving the seam really
    // controls the order pages become visible in the shared cache.
    var op = createOperation();
    setupNewFileWithPages(op, "ordered.dat", 2);

    var observedOrder = Collections.synchronizedList(new ArrayList<Long>());
    op.setPageApplyHook(new PageApplyHook() {
      @Override
      public long[] orderPageApplications(long fileId, long[] pageIndexes) {
        Assert.assertEquals(2, pageIndexes.length);
        return new long[] {1, 0};
      }

      @Override
      public void beforePageApply(long fileId, long pageIndex) {
        observedOrder.add(pageIndex);
      }
    });

    var txEndLsn = op.commitChanges(42L, wal);
    Assert.assertNotNull(txEndLsn);

    Assert.assertEquals(List.of(1L, 0L), observedOrder);
    Assert.assertEquals(List.of(1L, 0L), appliedPageOrder);
    // Exactly one epoch bracket for the whole commit, even with multiple pages.
    Assert.assertEquals(1, epoch.enterSeq());
    Assert.assertEquals(1, epoch.exitSeq());
  }

  @Test
  public void testHookObservesDefaultOrderWhenOrderingReturnsNull() throws IOException {
    // orderPageApplications returning null keeps the default order; beforePageApply must
    // still observe every changed page exactly once (the default order itself is hash
    // order and deliberately not asserted).
    var op = createOperation();
    setupNewFileWithPages(op, "default-order.dat", 2);

    var observed = Collections.synchronizedList(new ArrayList<Long>());
    op.setPageApplyHook(new PageApplyHook() {
      @Override
      public void beforePageApply(long fileId, long pageIndex) {
        observed.add(pageIndex);
      }
    });

    Assert.assertNotNull(op.commitChanges(42L, wal));

    var sorted = new ArrayList<>(observed);
    Collections.sort(sorted);
    Assert.assertEquals(List.of(0L, 1L), sorted);
    Assert.assertEquals(observed, appliedPageOrder);
  }

  @Test
  public void testReaderFailsDeterministicallyWhileWriterPausedMidApply()
      throws Exception {
    // THE seam test: a writer thread commits two pages and is paused by the hook
    // barrier BETWEEN the two page applications — inside the epoch bracket, page 0
    // already visible in the cache, page 1 not yet (the mixed state). An overlapping
    // reader capturing the epoch at that moment must deterministically fail
    // validateOrThrow(), because enterSeq != exitSeq at capture. After the writer
    // resumes and finishes, a fresh capture must pass.
    var op = createOperation();
    setupNewFileWithPages(op, "mid-apply.dat", 2);

    var midApplyReached = new CountDownLatch(1);
    var resume = new CountDownLatch(1);
    op.setPageApplyHook(new PageApplyHook() {
      @Override
      public long[] orderPageApplications(long fileId, long[] pageIndexes) {
        return new long[] {0, 1}; // deterministic order: pause before the SECOND page
      }

      @Override
      public void beforePageApply(long fileId, long pageIndex) {
        if (pageIndex == 1) {
          midApplyReached.countDown();
          try {
            if (!resume.await(10, TimeUnit.SECONDS)) {
              throw new IllegalStateException("Timed out waiting for resume signal");
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
          }
        }
      }
    });

    var writerError = new AtomicReference<Throwable>();
    var txEndLsn = new AtomicReference<LogSequenceNumber>();
    var writer = new Thread(() -> {
      try {
        txEndLsn.set(op.commitChanges(42L, wal));
      } catch (Throwable t) {
        writerError.set(t);
      }
    });
    writer.start();

    try {
      Assert.assertTrue(
          "Writer never reached the mid-apply barrier",
          midApplyReached.await(10, TimeUnit.SECONDS));

      // Writer is paused mid-apply: page 0 applied, page 1 pending, bracket open.
      Assert.assertEquals(List.of(0L), appliedPageOrder);
      Assert.assertEquals(1, epoch.enterSeq());
      Assert.assertEquals(0, epoch.exitSeq());

      // Overlapping reader (a different operation sharing the storage epoch): capture
      // now → validation must fail deterministically.
      var readerScope = new OptimisticReadScope(epoch);
      readerScope.reset();
      try {
        readerScope.validateOrThrow();
        Assert.fail("Reader overlapping a mid-apply writer must fail epoch validation");
      } catch (OptimisticReadFailedException expected) {
        // expected — apply phase in flight at capture time
      }
    } finally {
      resume.countDown();
    }

    writer.join(TimeUnit.SECONDS.toMillis(10));
    Assert.assertFalse("Writer thread did not finish", writer.isAlive());
    Assert.assertNull("Writer failed: " + writerError.get(), writerError.get());
    Assert.assertNotNull(txEndLsn.get());

    // Commit finished: both pages applied, bracket closed, fresh reader passes.
    Assert.assertEquals(List.of(0L, 1L), appliedPageOrder);
    Assert.assertEquals(1, epoch.enterSeq());
    Assert.assertEquals(1, epoch.exitSeq());
    var readerScope = new OptimisticReadScope(epoch);
    readerScope.reset();
    readerScope.validateOrThrow();
  }

  @Test
  public void testEpochExitsInFinallyWhenHookThrows() throws IOException {
    // A hook that throws mid-apply aborts the commit, but the epoch exit sits in a
    // finally block: the bracket must still close (enterSeq == exitSeq afterwards), so
    // optimistic reads are not permanently disabled by the failed commit.
    var op = createOperation();
    setupNewFileWithPages(op, "hook-throws.dat", 2);

    op.setPageApplyHook(new PageApplyHook() {
      @Override
      public void beforePageApply(long fileId, long pageIndex) {
        throw new IllegalStateException("test hook failure");
      }
    });

    try {
      op.commitChanges(42L, wal);
      Assert.fail("Expected the hook's IllegalStateException to abort the commit");
    } catch (IllegalStateException e) {
      Assert.assertEquals("test hook failure", e.getMessage());
    }

    // Bracket balanced despite the exception — a fresh reader capture passes.
    Assert.assertEquals(1, epoch.enterSeq());
    Assert.assertEquals(1, epoch.exitSeq());
    var readerScope = new OptimisticReadScope(epoch);
    readerScope.reset();
    readerScope.validateOrThrow();
  }

  @Test
  public void testComponentEpochsExitWhenPageApplyThrows() throws IOException {
    // A real page-apply failure after all component entries must rebalance every domain.
    // Otherwise later optimistic attempts would reject forever despite a quiescent writer.
    var op = createOperation();
    setupNewFileWithPages(op, "component-hook-throws.dat", 2);

    var firstEpoch = new ApplyPhaseEpoch();
    var secondEpoch = new ApplyPhaseEpoch();
    var first = mock(StorageComponent.class);
    var second = mock(StorageComponent.class);
    when(first.getApplyPhaseEpoch()).thenReturn(firstEpoch);
    when(second.getApplyPhaseEpoch()).thenReturn(secondEpoch);
    op.addLockedComponent(first);
    op.addLockedComponent(second);

    op.setPageApplyHook(new PageApplyHook() {
      @Override
      public void beforePageApply(long fileId, long pageIndex) {
        Assert.assertEquals(1, firstEpoch.enterSeq());
        Assert.assertEquals(0, firstEpoch.exitSeq());
        Assert.assertEquals(1, secondEpoch.enterSeq());
        Assert.assertEquals(0, secondEpoch.exitSeq());
        throw new IllegalStateException("component apply failure");
      }
    });

    try {
      op.commitChanges(42L, wal);
      Assert.fail("Expected the page-apply hook to abort the commit");
    } catch (IllegalStateException expected) {
      Assert.assertEquals("component apply failure", expected.getMessage());
    }

    Assert.assertEquals(1, firstEpoch.enterSeq());
    Assert.assertEquals(1, firstEpoch.exitSeq());
    Assert.assertEquals(1, secondEpoch.enterSeq());
    Assert.assertEquals(1, secondEpoch.exitSeq());
    var firstReader = new OptimisticReadScope(firstEpoch);
    firstReader.reset();
    firstReader.validateOrThrow();
    var secondReader = new OptimisticReadScope(secondEpoch);
    secondReader.reset();
    secondReader.validateOrThrow();
    Assert.assertEquals(0, epoch.enterSeq());
    Assert.assertEquals(0, epoch.exitSeq());
  }

  @Test
  public void testHookReturningUnknownPageIndexFailsCommitWithBalancedEpoch()
      throws IOException {
    // A hook ordering that references a page index not present in the change set must
    // fail loudly (it would otherwise silently skip real pages), and the epoch bracket
    // must still close via the finally.
    var op = createOperation();
    setupNewFileWithPages(op, "unknown-page.dat", 2);

    op.setPageApplyHook(new PageApplyHook() {
      @Override
      public long[] orderPageApplications(long fileId, long[] pageIndexes) {
        return new long[] {99};
      }
    });

    try {
      op.commitChanges(42L, wal);
      Assert.fail("Expected IllegalStateException for unknown page index");
    } catch (IllegalStateException e) {
      Assert.assertTrue(
          "Message should mention the unknown page index: " + e.getMessage(),
          e.getMessage().contains("unknown page index 99"));
    }

    Assert.assertEquals(1, epoch.enterSeq());
    Assert.assertEquals(1, epoch.exitSeq());
  }

  @Test
  public void testZeroChangeCommitDoesNotBumpEpoch() throws IOException {
    // Regression guard: a commit with nothing to apply (read-only atomic
    // operation — no deleted files, no new/truncated files, no page changes) performs
    // no shared-cache mutation, so it must NOT enter the epoch bracket. Before the
    // gating fix, every commit bumped the epoch, spuriously invalidating all
    // concurrently overlapping optimistic reads in the storage.
    var op = createOperation();
    var componentEpoch = new ApplyPhaseEpoch();
    var component = mock(StorageComponent.class);
    when(component.getApplyPhaseEpoch()).thenReturn(componentEpoch);
    op.addLockedComponent(component);

    // Pure no-op commit: returns null (no WAL unit was ever started either).
    Assert.assertNull(op.commitChanges(42L, wal));

    Assert.assertEquals(0, epoch.enterSeq());
    Assert.assertEquals(0, epoch.exitSeq());
    Assert.assertEquals(0, componentEpoch.enterSeq());
    Assert.assertEquals(0, componentEpoch.exitSeq());
  }

  @Test
  public void testHookReturningDuplicatePageIndexFailsCommitWithBalancedEpoch()
      throws IOException {
    // A duplicate in the hook-returned order would double-apply a
    // page. The permutation validation must fail the commit loudly, and the epoch
    // bracket must still close via the finally.
    var op = createOperation();
    setupNewFileWithPages(op, "duplicate-page.dat", 2);

    op.setPageApplyHook(new PageApplyHook() {
      @Override
      public long[] orderPageApplications(long fileId, long[] pageIndexes) {
        return new long[] {0, 0};
      }
    });

    try {
      op.commitChanges(42L, wal);
      Assert.fail("Expected IllegalStateException for duplicate page index");
    } catch (IllegalStateException e) {
      Assert.assertTrue(
          "Message should mention the duplicate page index: " + e.getMessage(),
          e.getMessage().contains("duplicate page index 0"));
    }

    Assert.assertEquals(1, epoch.enterSeq());
    Assert.assertEquals(1, epoch.exitSeq());
  }

  @Test
  public void testHookOmittingPagesFailsCommitWithBalancedEpoch() throws IOException {
    // An omission in the hook-returned order would silently drop a
    // WAL-committed page's changes from the cache. The permutation validation must
    // fail the commit loudly (incomplete permutation), and the epoch bracket must
    // still close via the finally.
    var op = createOperation();
    setupNewFileWithPages(op, "omitted-page.dat", 2);

    op.setPageApplyHook(new PageApplyHook() {
      @Override
      public long[] orderPageApplications(long fileId, long[] pageIndexes) {
        return new long[] {0}; // omits page 1
      }
    });

    try {
      op.commitChanges(42L, wal);
      Assert.fail("Expected IllegalStateException for incomplete permutation");
    } catch (IllegalStateException e) {
      Assert.assertTrue(
          "Message should mention the incomplete permutation: " + e.getMessage(),
          e.getMessage().contains("incomplete permutation"));
    }

    Assert.assertEquals(1, epoch.enterSeq());
    Assert.assertEquals(1, epoch.exitSeq());
  }

  @Test
  public void testMultiComponentCommitProtectsEveryDomainForCompleteApply() throws IOException {
    // Every locked domain must enter before any page is applied. An unrelated domain must
    // remain unchanged, and all entered domains must exit only after the complete apply.
    var op = createOperation();
    setupNewFileWithPages(op, "multi-component.dat", 2);

    var firstEpoch = new ApplyPhaseEpoch();
    var secondEpoch = new ApplyPhaseEpoch();
    var unrelatedEpoch = new ApplyPhaseEpoch();
    var first = mock(StorageComponent.class);
    var second = mock(StorageComponent.class);
    when(first.getApplyPhaseEpoch()).thenReturn(firstEpoch);
    when(second.getApplyPhaseEpoch()).thenReturn(secondEpoch);
    op.addLockedComponent(first);
    op.addLockedComponent(second);

    op.setPageApplyHook(new PageApplyHook() {
      @Override
      public void beforePageApply(long fileId, long pageIndex) {
        Assert.assertEquals(1, firstEpoch.enterSeq());
        Assert.assertEquals(0, firstEpoch.exitSeq());
        Assert.assertEquals(1, secondEpoch.enterSeq());
        Assert.assertEquals(0, secondEpoch.exitSeq());
        Assert.assertEquals(0, unrelatedEpoch.enterSeq());
      }
    });

    Assert.assertNotNull(op.commitChanges(42L, wal));
    Assert.assertEquals(1, firstEpoch.enterSeq());
    Assert.assertEquals(1, firstEpoch.exitSeq());
    Assert.assertEquals(1, secondEpoch.enterSeq());
    Assert.assertEquals(1, secondEpoch.exitSeq());
    Assert.assertEquals(0, unrelatedEpoch.enterSeq());
    Assert.assertEquals(0, unrelatedEpoch.exitSeq());
    // A component-backed operation must not use its standalone test epoch.
    Assert.assertEquals(0, epoch.enterSeq());
    Assert.assertEquals(0, epoch.exitSeq());
  }

  @Test
  public void testPartialComponentEntryIsUnwoundBeforeCacheApply() throws IOException {
    // Failure while obtaining a later domain must balance every earlier successful enter
    // and must occur before the first shared-cache mutation.
    var op = createOperation();
    setupNewFileWithPages(op, "partial-entry.dat", 1);

    var firstEpoch = new ApplyPhaseEpoch();
    var first = mock(StorageComponent.class);
    var failing = mock(StorageComponent.class);
    when(first.getApplyPhaseEpoch()).thenReturn(firstEpoch);
    when(failing.getApplyPhaseEpoch()).thenThrow(new IllegalStateException("entry failure"));
    op.addLockedComponent(first);
    op.addLockedComponent(failing);

    try {
      op.commitChanges(42L, wal);
      Assert.fail("Expected the second component entry to fail");
    } catch (IllegalStateException expected) {
      Assert.assertEquals("entry failure", expected.getMessage());
    }

    Assert.assertEquals(1, firstEpoch.enterSeq());
    Assert.assertEquals(1, firstEpoch.exitSeq());
    Assert.assertTrue("No page may be applied after partial entry", appliedPageOrder.isEmpty());
    Assert.assertEquals(0, epoch.enterSeq());
    Assert.assertEquals(0, epoch.exitSeq());
  }

  @Test
  public void testCommitWithoutHookBumpsEpochExactlyOnce() throws IOException {
    // The production path (no hook installed) must also bracket the apply section with
    // exactly one enter/exit pair per commit — one pair for the whole commit, not one
    // per page or per file.
    var op = createOperation();
    setupNewFileWithPages(op, "no-hook-a.dat", 2);
    setupNewFileWithPages(op, "no-hook-b.dat", 1);

    Assert.assertNotNull(op.commitChanges(42L, wal));

    Assert.assertEquals(3, appliedPageOrder.size());
    Assert.assertEquals(1, epoch.enterSeq());
    Assert.assertEquals(1, epoch.exitSeq());
  }
}
