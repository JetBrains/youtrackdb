package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.storage.cache.ApplyPhaseEpoch;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ComponentEpochRegistry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.OptimisticReadFailedException;
import com.jetbrains.youtrackdb.internal.core.storage.cache.OptimisticReadScope;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationBinaryTracking.PageApplyHook;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsTable.AtomicOperationsSnapshot;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
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
import java.util.List;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

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
        // Uniform registry: every fileId resolves to the single shared test epoch,
        // mirroring the pre-per-component (storage-wide) bump semantics these tests
        // assert on (YTDB-1203 compile adaptation; full test rework tracked separately).
        ComponentEpochRegistry.uniform(epoch));
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
      var readerScope = new OptimisticReadScope();
      readerScope.reset(epoch);
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
    var readerScope = new OptimisticReadScope();
    readerScope.reset(epoch);
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
    var readerScope = new OptimisticReadScope();
    readerScope.reset(epoch);
    readerScope.validateOrThrow();
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

    // Pure no-op commit: returns null (no WAL unit was ever started either).
    Assert.assertNull(op.commitChanges(42L, wal));

    Assert.assertEquals(0, epoch.enterSeq());
    Assert.assertEquals(0, epoch.exitSeq());
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
