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
 * {@link AtomicOperationBinaryTracking#commitChanges} and for the per-component
 * apply-phase epoch bracket around the page-apply loop (YTDB-1178 / YTDB-1203).
 *
 * <p>The seam exists because the production page-apply order is fastutil hash order,
 * which makes the mixed-state race (a reader overlapping a partially applied commit)
 * practically impossible to reproduce. These tests verify that:
 *
 * <ul>
 *   <li>the hook can dictate and observe the page-apply order;
 *   <li>a reader overlapping a writer paused mid-apply (between two page applications,
 *       inside the epoch bracket) deterministically fails epoch validation, while a
 *       reader of a component the commit does not touch keeps passing (per-component
 *       granularity, YTDB-1203);
 *   <li>the epoch bracket is exactly one enter/exit pair per MUTATED COMPONENT —
 *       resolved through a real {@link ComponentEpochRegistry}, deduplicated by epoch
 *       identity across files of the same component — with the exits in a finally
 *       block that runs even when the hook throws;
 *   <li>the commit-time mutated-set predicate and registry lifecycle rules hold:
 *       deleted files bump their owner's epoch, merely-loaded files bump nothing,
 *       fileId reuse follows the overwritten registration, and a mutated file missing
 *       from the registry fails the commit loudly (AR-2).
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

  // Real per-storage registry (not uniform()): every file a test creates is explicitly
  // registered to a component epoch, exercising the same resolution path production
  // commits take.
  private ComponentEpochRegistry registry;

  // Default component epoch: files created via the 3-arg setupNewFileWithPages overload
  // register here, mimicking one storage component owning all of them.
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
    registry = new ComponentEpochRegistry();
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
        registry);
    op.startToApplyOperations(42);
    return op;
  }

  private static long composeFileId(long internalId, int storageId) {
    return internalId | ((long) storageId << 32);
  }

  /**
   * Creates a new durable file with {@code pageCount} changed pages (indexes 0..n-1),
   * registers a logical PageOperation per page, and flushes so every page carries a
   * changeLSN — the precondition for reaching the commit-time apply loop. The file is
   * registered to the default component {@link #epoch}, as the production
   * StorageComponent funnel would do for the owning component.
   */
  private long setupNewFileWithPages(
      AtomicOperationBinaryTracking op, String fileName, int pageCount) throws IOException {
    return setupNewFileWithPages(op, fileName, pageCount, epoch);
  }

  /**
   * Variant of {@link #setupNewFileWithPages(AtomicOperationBinaryTracking, String, int)}
   * registering the file to an explicit component epoch — used by the per-component
   * granularity tests to simulate files owned by different components.
   */
  private long setupNewFileWithPages(
      AtomicOperationBinaryTracking op, String fileName, int pageCount,
      ApplyPhaseEpoch componentEpoch) throws IOException {
    long fileId = setupUnregisteredNewFileWithPages(op, fileName, pageCount);
    registry.register(fileId, componentEpoch);
    return fileId;
  }

  /**
   * Same file/page setup but WITHOUT registering an epoch for the file — only for the
   * AR-2 fail-loud test; every other test must register, as the production funnel does.
   */
  private long setupUnregisteredNewFileWithPages(
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

      // Overlapping reader of the SAME component (a different operation that resolved
      // the mutated file's epoch through the registry): capture now → validation must
      // fail deterministically.
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
  public void testCommitWithoutHookBumpsComponentEpochExactlyOnce() throws IOException {
    // The production path (no hook installed) must bracket the apply section with
    // exactly one enter/exit pair per MUTATED COMPONENT. Both files here belong to the
    // same component (registered to the same epoch instance — the sub-component sharing
    // shape), so identity dedupe must collapse them into ONE pair for the whole commit,
    // not one per page or per file.
    var op = createOperation();
    setupNewFileWithPages(op, "no-hook-a.dat", 2);
    setupNewFileWithPages(op, "no-hook-b.dat", 1);

    Assert.assertNotNull(op.commitChanges(42L, wal));

    Assert.assertEquals(3, appliedPageOrder.size());
    Assert.assertEquals(1, epoch.enterSeq());
    Assert.assertEquals(1, epoch.exitSeq());
  }

  @Test
  public void testTwoComponentCommitBumpsEachComponentEpochOnce() throws IOException {
    // A commit spanning files of TWO components must bump each component's epoch
    // exactly once: component A owns two mutated files (deduped to one pair by epoch
    // identity), component B owns one. This pins the resolved-set semantics of
    // collectMutatedComponentEpochs — per component, not per commit and not per file.
    var op = createOperation();
    var epochA = new ApplyPhaseEpoch();
    var epochB = new ApplyPhaseEpoch();
    setupNewFileWithPages(op, "comp-a1.dat", 1, epochA);
    setupNewFileWithPages(op, "comp-a2.dat", 2, epochA);
    setupNewFileWithPages(op, "comp-b.dat", 1, epochB);

    Assert.assertNotNull(op.commitChanges(42L, wal));

    Assert.assertEquals(1, epochA.enterSeq());
    Assert.assertEquals(1, epochA.exitSeq());
    Assert.assertEquals(1, epochB.enterSeq());
    Assert.assertEquals(1, epochB.exitSeq());
  }

  @Test
  public void testReaderOfUntouchedComponentSurvivesConcurrentCommitMidApply()
      throws Exception {
    // THE per-component granularity payoff (YTDB-1203): a writer thread is paused
    // mid-apply on component A's file — inside A's epoch bracket, mixed state visible —
    // while a reader that captured component B's epoch validates successfully, because
    // the commit never touches B. A reader of A's epoch captured at the same moment
    // must still fail. Under the old storage-wide epoch both readers failed.
    var op = createOperation();
    var epochA = new ApplyPhaseEpoch();
    var epochB = new ApplyPhaseEpoch();
    setupNewFileWithPages(op, "touched-a.dat", 2, epochA);
    // Component B exists in the registry (its file was opened at some point) but the
    // commit does not mutate it.
    registry.register(composeFileId(fileIdCounter.getAndIncrement(), STORAGE_ID), epochB);

    var midApplyReached = new CountDownLatch(1);
    var resume = new CountDownLatch(1);
    op.setPageApplyHook(new PageApplyHook() {
      @Override
      public long[] orderPageApplications(long fileId, long[] pageIndexes) {
        return new long[] {0, 1};
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
    var writer = new Thread(() -> {
      try {
        op.commitChanges(42L, wal);
      } catch (Throwable t) {
        writerError.set(t);
      }
    });
    writer.start();

    try {
      Assert.assertTrue(
          "Writer never reached the mid-apply barrier",
          midApplyReached.await(10, TimeUnit.SECONDS));

      // Writer paused inside A's bracket: A's epoch is mid-apply, B's untouched.
      Assert.assertEquals(1, epochA.enterSeq());
      Assert.assertEquals(0, epochA.exitSeq());
      Assert.assertEquals(0, epochB.enterSeq());
      Assert.assertEquals(0, epochB.exitSeq());

      // Reader of the UNTOUCHED component B: capture and validation both succeed while
      // the commit into A is still mid-apply.
      var readerB = new OptimisticReadScope();
      readerB.reset(epochB);
      readerB.validateOrThrow();

      // Reader of the MUTATED component A captured at the same moment must fail.
      var readerA = new OptimisticReadScope();
      readerA.reset(epochA);
      try {
        readerA.validateOrThrow();
        Assert.fail("Reader of the mutated component must fail while mid-apply");
      } catch (OptimisticReadFailedException expected) {
        // expected — A's apply phase in flight at capture time
      }
    } finally {
      resume.countDown();
    }

    writer.join(TimeUnit.SECONDS.toMillis(10));
    Assert.assertFalse("Writer thread did not finish", writer.isAlive());
    Assert.assertNull("Writer failed: " + writerError.get(), writerError.get());

    // Commit finished: A bumped once, B never touched.
    Assert.assertEquals(1, epochA.enterSeq());
    Assert.assertEquals(1, epochA.exitSeq());
    Assert.assertEquals(0, epochB.enterSeq());
    Assert.assertEquals(0, epochB.exitSeq());
  }

  @Test
  public void testSubComponentFileCommitInvalidatesReaderOfSharedFamilyEpoch()
      throws IOException {
    // Sub-component epoch sharing, behavioral half (YTDB-1203): a collection family
    // registers ALL its files (.pcl data file + .cpm position map + ...) under ONE
    // shared epoch instance. A reader that captured the family epoch (e.g., while
    // reading the collection) must be invalidated by a commit that mutates ONLY the
    // sub-component's file — that is exactly what makes the readRecord .pcl+.cpm
    // two-file optimistic scope sound under per-component epochs.
    var op = createOperation();
    var familyEpoch = new ApplyPhaseEpoch();
    // .pcl-like file: registered to the family epoch but NOT mutated by this commit.
    registry.register(composeFileId(fileIdCounter.getAndIncrement(), STORAGE_ID),
        familyEpoch);
    // .cpm-like file: same family epoch, mutated below.
    setupNewFileWithPages(op, "family.cpm", 1, familyEpoch);

    var reader = new OptimisticReadScope();
    reader.reset(familyEpoch); // reader captured the family epoch pre-commit

    Assert.assertNotNull(op.commitChanges(42L, wal));

    Assert.assertEquals(1, familyEpoch.enterSeq());
    Assert.assertEquals(1, familyEpoch.exitSeq());
    try {
      reader.validateOrThrow();
      Assert.fail("Commit on the sub-component file must invalidate the family reader");
    } catch (OptimisticReadFailedException expected) {
      // expected — the family epoch moved since the reader's capture
    }
  }

  @Test
  public void testDeletingCommitBumpsDeletedFilesComponentEpoch() throws IOException {
    // Delete-bump ordering (YTDB-1203): a commit whose deletedFiles set contains file F
    // must resolve F through the registry and bump F's owner epoch. This only works
    // because registry entries are NEVER removed — the owning component is unregistered
    // from storage maps before the deleting commit applies, so a remove-on-drop registry
    // would make this very commit miss its own bump.
    var op = createOperation();
    var victimEpoch = new ApplyPhaseEpoch();
    long victimFileId = composeFileId(fileIdCounter.getAndIncrement(), STORAGE_ID);
    registry.register(victimFileId, victimEpoch);
    when(writeCache.fileNameById(victimFileId)).thenReturn("victim.dat");

    // Delete a PRE-EXISTING file (not created inside this operation): lands in
    // deletedFiles rather than just dropping an in-TX fileChanges entry.
    op.deleteFile(victimFileId);

    Assert.assertNotNull(op.commitChanges(42L, wal));

    Assert.assertEquals(1, victimEpoch.enterSeq());
    Assert.assertEquals(1, victimEpoch.exitSeq());
  }

  @Test
  public void testFileIdReuseFollowsOverwrittenRegistration() throws IOException {
    // FileId-reuse overwrite (YTDB-1203): re-registering an existing fileId (the disk
    // engine reuses the internal id on same-name delete+recreate) must overwrite the
    // mapping — a subsequent commit mutating that fileId bumps ONLY the new owner's
    // epoch; the dead component's epoch stays untouched.
    var op = createOperation();
    var oldOwnerEpoch = new ApplyPhaseEpoch();
    var newOwnerEpoch = new ApplyPhaseEpoch();
    long fileId = setupNewFileWithPages(op, "reused.dat", 1, oldOwnerEpoch);
    // The recreated component re-registers the same fileId before the commit applies.
    registry.register(fileId, newOwnerEpoch);

    Assert.assertNotNull(op.commitChanges(42L, wal));

    Assert.assertEquals(0, oldOwnerEpoch.enterSeq());
    Assert.assertEquals(0, oldOwnerEpoch.exitSeq());
    Assert.assertEquals(1, newOwnerEpoch.enterSeq());
    Assert.assertEquals(1, newOwnerEpoch.exitSeq());
  }

  @Test
  public void testCommitFailsLoudWhenMutatedFileMissingFromRegistry() throws IOException {
    // AR-2 fail-loud contract: a mutated fileId with no registry entry must abort the
    // commit with an IllegalStateException naming the fileId — silently skipping the
    // bump would leave that component's optimistic readers permanently unprotected
    // against this commit's apply phase.
    var op = createOperation();
    long fileId = setupUnregisteredNewFileWithPages(op, "behind-funnel.dat", 1);

    try {
      op.commitChanges(42L, wal);
      Assert.fail("Expected IllegalStateException for the unregistered mutated file");
    } catch (IllegalStateException e) {
      Assert.assertTrue(
          "Message should name the unregistered fileId: " + e.getMessage(),
          e.getMessage().contains(String.valueOf(fileId)));
      Assert.assertTrue(
          "Message should point at the component funnel: " + e.getMessage(),
          e.getMessage().contains("StorageComponent.addFile/openFile funnel"));
    }

    // The resolution failure happens BEFORE any epoch is entered — nothing was applied
    // to the shared cache and no bracket was left open.
    Assert.assertEquals(0, appliedPageOrder.size());
  }

  @Test
  public void testReadOnlyLoadedFileDoesNotBumpItsEpoch() throws IOException {
    // Mutated-set predicate: a file that was merely LOADED by the operation (present in
    // fileChanges with an empty change set — the read-only shape) is not part of the
    // apply section, so its component epoch must NOT be bumped; bumping it would
    // spuriously invalidate every overlapping optimistic read of that component.
    var op = createOperation();
    var readOnlyEpoch = new ApplyPhaseEpoch();
    long loadedFileId = composeFileId(fileIdCounter.getAndIncrement(), STORAGE_ID);
    when(writeCache.loadFile("read-only.dat")).thenReturn(loadedFileId);
    registry.register(loadedFileId, readOnlyEpoch);

    Assert.assertEquals(loadedFileId, op.loadFile("read-only.dat"));

    // Pure read-only commit: nothing to apply, no WAL unit — and no bump.
    Assert.assertNull(op.commitChanges(42L, wal));

    Assert.assertEquals(0, readOnlyEpoch.enterSeq());
    Assert.assertEquals(0, readOnlyEpoch.exitSeq());
  }
}
