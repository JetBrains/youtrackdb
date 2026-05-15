package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.storage.cache.AbstractWriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsTable.AtomicOperationsSnapshot;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import com.jetbrains.youtrackdb.internal.core.storage.memory.DirectMemoryOnlyDiskCache;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;

/**
 * Verifies the new {@link AtomicOperation#allocatePageForWrite(long, long)} method on
 * {@link AtomicOperationBinaryTracking}: allocator contract, bookkeeping, idempotency,
 * and the dual-engine delegate shape.
 *
 * <p><b>Cross-engine asymmetry pinned by this suite: allocator-only on disk;
 * eager-install total on in-memory.</b> Callers state the target {@code pageIndex} up
 * front (typically derived from {@code entryPoint.pagesSize + 1}) instead of letting the
 * cache pick the index. On the disk engine the contract is strictly allocator-only:
 * targeting a {@code pageIndex} below the committed file size raises
 * {@link IllegalStateException}; use {@link AtomicOperation#loadPageForWrite} to mutate
 * an existing page. The in-memory engine bypasses that check to support rollback-orphan
 * re-use — a rolled-back TX leaves eagerly-installed pages in {@code MemoryFile}, and the
 * next TX legitimately re-allocates the same logical page.
 *
 * <p>The tests fall into two groups:
 *
 * <ul>
 *   <li><b>Disk-engine stub-shape tests</b> (default {@link #setUp()} fixture): the
 *       returned overlay wraps a {@link CachePointer} with a null native pointer; the
 *       real cache slot is materialized at commit time inside {@code commitChanges}'s
 *       pageChangesMap replay loop. Allocator-only guard, idempotency, cross-API
 *       interaction with {@code loadPageForWrite}, and bookkeeping are pinned here.
 *   <li><b>In-memory eager-install tests</b> (built via {@link #newInMemoryOp(DirectMemoryOnlyDiskCache)}):
 *       the eager-install branch calls {@code DirectMemoryOnlyDiskCache.loadOrAdd},
 *       wraps the returned pointer in a {@link CacheEntryImpl}, and decrements the
 *       readers-referrer once so the {@code MemoryFile} accounting is balanced.
 *       Rollback-orphan re-use and the loop-style net-zero invariant are pinned here.
 * </ul>
 */
public class AllocatePageForWriteTest {

  private static final int STORAGE_ID = 1;

  private ReadCache readCache;
  private WriteCache writeCache;
  private WriteAheadLog wal;
  private AtomicOperationBinaryTracking op;
  private AtomicLong fileIdCounter;

  @Before
  public void setUp() throws IOException {
    readCache = mock(ReadCache.class);
    writeCache = mock(WriteCache.class);
    when(writeCache.getStorageName()).thenReturn("test-storage");
    wal = mock(WriteAheadLog.class);
    fileIdCounter = new AtomicLong(100);

    var snapshot =
        new AtomicOperationsSnapshot(0, 100, new LongOpenHashSet(), 100);
    op = new AtomicOperationBinaryTracking(
        readCache, writeCache, wal, STORAGE_ID, snapshot,
        new ConcurrentSkipListMap<>(), new ConcurrentSkipListMap<>(),
        new AtomicLong(),
        new ConcurrentSkipListMap<>(), new ConcurrentSkipListMap<>(),
        new AtomicLong());
  }

  // ---------------------------------------------------------------------------
  // Allocator contract: callers must target a genuinely new pageIndex.
  // ---------------------------------------------------------------------------

  /**
   * The method is allocator-only — asking for a pageIndex that already exists in the
   * committed file must throw {@link IllegalStateException}. The error message must name
   * the offending pageIndex, the allocation floor, the fileId, and the actionable
   * {@code loadPageForWrite} alternative so a regression (e.g., an unrelated guard
   * firing, or a refactor that drops fileId / the actionable hint) is distinguishable
   * from this contract violation. The guard rules out a class of bugs where a stale
   * {@code entryPoint.pagesSize} read would point at a page that is already on disk.
   */
  @Test
  public void allocatePageForWriteThrowsForExistingPage() {
    long fileId = composeFileId(fileIdCounter.getAndIncrement(), STORAGE_ID);
    when(writeCache.getFilledUpTo(fileId)).thenReturn(10L); // 10 pages on disk

    assertThatThrownBy(() -> op.allocatePageForWrite(fileId, 5L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("allocation-only")
        .hasMessageContaining("pageIndex 5")
        .hasMessageContaining("allocationFloor 10")
        .hasMessageContaining("file with id " + fileId)
        .hasMessageContaining("loadPageForWrite");
  }

  /**
   * Boundary off-by-one for the allocator-only guard: pageIndex one below the committed
   * filledUpTo must still throw. Pins the strict {@code <} comparison in the guard so a
   * {@code <=} regression (which would let callers silently re-allocate the last committed
   * page) is caught. The existing {@code allocatePageForWriteThrowsForExistingPage} test
   * uses a 4-slot gap (pageIndex=5 vs filledUpTo=10) which would survive a {@code <} →
   * {@code <=} flip.
   */
  @Test
  public void allocatePageForWriteThrowsAtBoundaryPageIndex() {
    long fileId = composeFileId(fileIdCounter.getAndIncrement(), STORAGE_ID);
    when(writeCache.getFilledUpTo(fileId)).thenReturn(10L);

    assertThatThrownBy(() -> op.allocatePageForWrite(fileId, 9L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("pageIndex 9")
        .hasMessageContaining("allocationFloor 10");
  }

  /**
   * After the first allocation on an existing (non-fresh) file, the second allocation
   * must skip the {@code writeCache.getFilledUpTo} probe and derive the allocation floor
   * from {@code maxNewPageIndex + 1} (the per-component lock keeps the committed horizon
   * immutable across this TX, so the in-progress horizon is an equally valid floor). Pins
   * three things at once: (1) the second call exercises the fast-path branch
   * ({@code maxNewPageIndex > -2}), (2) the fast-path-derived floor is the correct value
   * (proven by feeding it back into the throw branch), and (3) {@code writeCache} is
   * touched only once across the two allocations.
   */
  @Test
  public void secondAllocationOnExistingFileUsesMaxNewPageIndexFastPath() throws IOException {
    long fileId = composeFileId(fileIdCounter.getAndIncrement(), STORAGE_ID);
    when(writeCache.getFilledUpTo(fileId)).thenReturn(10L);

    op.allocatePageForWrite(fileId, 10L); // slow path: probes writeCache, bumps maxNewPageIndex
    verify(writeCache).getFilledUpTo(fileId);

    var second = (CacheEntryChanges) op.allocatePageForWrite(fileId, 11L);
    assertThat(second.isNew).isTrue();
    assertThat(op.filledUpTo(fileId)).isEqualTo(12L);
    // Fast-path skipped the second probe (still exactly one invocation).
    verify(writeCache, times(1)).getFilledUpTo(fileId);

    // Fast-path-derived floor (12 = maxNewPageIndex + 1 after the second allocation)
    // flows into the throw branch. pageIndex=9 is below that floor and has no overlay
    // entry (so the idempotency early-return does NOT short-circuit the guard); it
    // must raise IllegalStateException naming the fast-path-derived floor.
    assertThatThrownBy(() -> op.allocatePageForWrite(fileId, 9L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("allocationFloor 12");
  }

  /**
   * The SLBB.splitRootBucket recipe allocates two pages back-to-back in a single method
   * body; the AOBT layer must produce two distinct overlays for the two sequential
   * {@code (fileId, pageIndex)} pairs. Pins the hazard that the SLBB.splitRootBucket
   * comment names: same-pageIndex reuse via the idempotency early-return would silently
   * merge the leftBucketEntry and rightBucketEntry writes into one overlay. This is the
   * AOBT-level unit pin for that recipe, complementing the SLBB-level recipe test.
   */
  @Test
  public void twoConsecutiveAllocationsProduceDistinctOverlays() throws IOException {
    long fileId = composeFileId(fileIdCounter.getAndIncrement(), STORAGE_ID);
    when(writeCache.getFilledUpTo(fileId)).thenReturn(5L);

    var leftEntry = op.allocatePageForWrite(fileId, 5L);
    var rightEntry = op.allocatePageForWrite(fileId, 6L);

    assertThat(rightEntry).isNotSameAs(leftEntry);
    assertThat(leftEntry.getPageIndex()).isEqualTo(5);
    assertThat(rightEntry.getPageIndex()).isEqualTo(6);
    assertThat(op.filledUpTo(fileId)).isEqualTo(7L);
    assertThat(op.hasChangesForPage(fileId, 5L)).isTrue();
    assertThat(op.hasChangesForPage(fileId, 6L)).isTrue();
  }

  /**
   * Fresh-booked files (just allocated via {@link AtomicOperation#addFile})
   * cannot install pages in the read cache because the underlying file is not
   * yet registered — {@code readCache.addFile} runs in {@code commitChanges}
   * only. The new method must therefore use a stub-shape delegate for every
   * allocation: a {@link CacheEntryImpl} wrapping a {@code CachePointer} with a
   * {@code null} native pointer, queued in {@code pageChangesMap} so the
   * commit-time loop installs the real page after {@code readCache.addFile}
   * fires. This test pins that branch — no {@code readCache.loadOrAddForWrite}
   * call and no {@code writeCache.loadOrAdd} fallback fire, but the
   * bookkeeping (overlay, {@code isNew}, {@code maxNewPageIndex},
   * {@code pageChangesMap}) still updates.
   */
  @Test
  public void freshBookedFileSkipsReadCacheUntilCommit() throws IOException {
    long fileId = composeFileId(fileIdCounter.getAndIncrement(), STORAGE_ID);

    when(writeCache.bookFileId("fresh.dat")).thenReturn(fileId);
    op.addFile("fresh.dat");

    var entry = (CacheEntryChanges) op.allocatePageForWrite(fileId, 0);

    // Stub-shape delegate: pageIndex matches, the CachePointer's ByteBuffer is null
    // (the commit loop fills the real page in once readCache.addFile registers the
    // file). releasePageFromWrite at AOBT:551 gates the cache release on this exact
    // accessor — pinning getBuffer() here is the load-bearing contract.
    assertThat(entry.getPageIndex()).isEqualTo(0);
    assertThat(entry.getDelegate().getCachePointer().getBuffer()).isNull();
    // Fresh-file allocations are always isNew=true regardless of pageIndex.
    assertThat(entry.isNew).isTrue();
    // pageChangesMap registration is what commitChanges iterates; cover via the
    // visible SPI (hasChangesForPage queries the same map on the new-file path).
    assertThat(op.hasChangesForPage(fileId, 0)).isTrue();
    assertThat(op.allocatePageForWrite(fileId, 0)).isSameAs(entry);
    // Bookkeeping: maxNewPageIndex bumped, filledUpTo follows.
    assertThat(op.filledUpTo(fileId)).isEqualTo(1L);
    // Neither cache primitive should have been touched on the fresh-booked path.
    verify(readCache, never())
        .loadOrAddForWrite(anyLong(), anyLong(), any(), anyBoolean(), any());
    verify(writeCache, never()).loadOrAdd(anyLong(), anyLong(), anyBoolean());
  }

  /**
   * Idempotency on the fresh-booked file path: a second call for the same
   * {@code (fileId, pageIndex)} must return the previously-registered overlay,
   * and must not double-bump {@code maxNewPageIndex} or overwrite the
   * {@code pageChangesMap} entry. The early-return in
   * {@link AtomicOperation#allocatePageForWrite} is what makes the SLBB
   * two-page recipe safe — re-entering with the same pagesSize-derived
   * pageIndex must produce the same overlay, not a fresh one that would
   * silently merge writes from two distinct allocations.
   */
  @Test
  public void secondCallReturnsExistingOverlayOnFreshBookedFile() throws IOException {
    long fileId = composeFileId(fileIdCounter.getAndIncrement(), STORAGE_ID);
    when(writeCache.bookFileId("fresh.dat")).thenReturn(fileId);
    op.addFile("fresh.dat");

    var first = op.allocatePageForWrite(fileId, 0);
    long filledAfterFirst = op.filledUpTo(fileId);
    var second = op.allocatePageForWrite(fileId, 0);

    assertThat(second).isSameAs(first);
    // No second maxNewPageIndex bump and no pageChangesMap overwrite.
    assertThat(op.filledUpTo(fileId)).isEqualTo(filledAfterFirst);
  }

  // ---------------------------------------------------------------------------
  // Bookkeeping: maxNewPageIndex must track the highest freshly-allocated
  // pageIndex so loadPageForWrite/loadPageForRead/hasChangesForPage see the
  // correct in-progress visibility horizon.
  // ---------------------------------------------------------------------------

  /**
   * After a fresh-file allocation, {@code maxNewPageIndex} on the file's
   * {@code FileChanges} must reflect the new pageIndex. This is the in-progress-TX
   * visibility horizon consulted by {@code loadPageForWrite},
   * {@code loadPageForRead}, and {@code hasChangesForPage}.
   */
  @Test
  public void maxNewPageIndexBumpsAfterFreshAllocation() throws IOException {
    long fileId = composeFileId(fileIdCounter.getAndIncrement(), STORAGE_ID);

    // Fresh file (created in this TX): isNew=true sentinel is set by addFile.
    // Fresh-booked files take the legacy stub branch (no read/write cache
    // interaction until commitChanges) — the bookkeeping under test is the
    // pageChangesMap insert + maxNewPageIndex bump, both of which happen on
    // that branch regardless of the engine.
    when(writeCache.bookFileId("fresh.dat")).thenReturn(fileId);
    op.addFile("fresh.dat");

    op.allocatePageForWrite(fileId, 0);
    op.allocatePageForWrite(fileId, 1);
    op.allocatePageForWrite(fileId, 2);

    // hasChangesForPage queries maxNewPageIndex on the fresh-file path
    // (changesContainer.isNew == true), so it returns true for pageIndex <= 2.
    assertThat(op.hasChangesForPage(fileId, 2)).isTrue();
    // filledUpTo returns maxNewPageIndex + 1 on the isNew path.
    assertThat(op.filledUpTo(fileId)).isEqualTo(3L);
  }

  // ---------------------------------------------------------------------------
  // Cross-API idempotency: loadPageForWrite then allocatePageForWrite for the
  // same (fileId, pageIndex) must collapse to a single overlay.
  // ---------------------------------------------------------------------------

  /**
   * Cross-API idempotency: a {@code loadPageForWrite} that registers a
   * {@link CacheEntryChanges} in {@code pageChangesMap} must be observed by a
   * subsequent {@code allocatePageForWrite} for the same {@code (fileId,
   * pageIndex)}. The new method's early-return short-circuits engine dispatch
   * entirely, so the allocator-only contract never fires on the second call
   * even though the pageIndex is below {@code allocationFloor} — the
   * existing overlay placed by {@code loadPageForWrite} takes precedence.
   * Mirrors the most likely real-world ordering once collection migrations
   * land: a component first loads a known page for write, then later in the
   * same TX needs to ensure a page exists (re-entering the same overlay).
   */
  @Test
  public void loadPageForWriteThenLoadOrAddReturnsSameOverlay() throws IOException {
    long fileId = composeFileId(fileIdCounter.getAndIncrement(), STORAGE_ID);
    long pageIndex = 7;

    // Pre-existing file with committed pages beyond pageIndex.
    when(writeCache.getFilledUpTo(fileId)).thenReturn(20L);
    var delegate = mockCacheEntry(fileId, (int) pageIndex);
    when(readCache.loadForRead(eq(fileId), eq(pageIndex), eq(writeCache), eq(true)))
        .thenReturn(delegate);

    var first = op.loadPageForWrite(fileId, pageIndex, 1, true);
    var second = op.allocatePageForWrite(fileId, pageIndex);

    assertThat(second).isSameAs(first);
    // The early-return short-circuits the allocator-only guard; if it did not, the
    // pageIndex (7) being below the allocation floor (20) would have raised
    // IllegalStateException. Cache primitive must never fire on the second call.
    verify(readCache, never()).loadOrAddForWrite(
        anyLong(), anyLong(), any(), anyBoolean(), any());
  }

  // ---------------------------------------------------------------------------
  // Bookkeeping: extending an existing file beyond allocationFloor must set
  // the in-progress visibility horizon correctly and flag the page as new.
  // ---------------------------------------------------------------------------

  /**
   * Extending an existing (non-fresh) file: with {@code allocationFloor=10}
   * and a {@code pageIndex=10} allocation, the page must be flagged as new
   * (extends beyond the committed horizon), {@code filledUpTo} must advance to
   * {@code 11} (the new in-progress horizon), and {@code hasChangesForPage} must
   * become {@code true} at the allocated index and {@code false} at the next
   * index (the boundary). The stub-shape delegate carries a null
   * {@code CachePointer} buffer, same as the fresh-file path — the real cache slot
   * is materialized at commit time.
   */
  @Test
  public void extendsExistingFileBumpsFilledUpToAndFlagsIsNew() throws IOException {
    long fileId = composeFileId(fileIdCounter.getAndIncrement(), STORAGE_ID);
    long pageIndex = 10; // == allocationFloor, the one-page-extend target

    // Existing file (NOT created in this TX) — no addFile call, so the
    // FileChanges that allocatePageForWrite lazily creates has isNew=false.
    // allocationFloor from the write cache is 10 → allocating pageIndex=10
    // is the extend case.
    when(writeCache.getFilledUpTo(fileId)).thenReturn(10L);

    var entry = (CacheEntryChanges) op.allocatePageForWrite(fileId, pageIndex);

    // Stub-shape delegate: null buffer (commit-time install handles the cache slot).
    assertThat(entry.getDelegate().getCachePointer().getBuffer()).isNull();
    // isNew=true because pageIndex (10) >= allocationFloor (10).
    assertThat(entry.isNew).isTrue();
    // filledUpTo advanced to maxNewPageIndex + 1 = 11.
    assertThat(op.filledUpTo(fileId)).isEqualTo(11L);
    // The allocated index has changes; the next one (the boundary) does not.
    assertThat(op.hasChangesForPage(fileId, pageIndex)).isTrue();
    assertThat(op.hasChangesForPage(fileId, pageIndex + 1)).isFalse();
    // The read-cache write primitive must not fire under the allocator-only contract.
    verify(readCache, never()).loadOrAddForWrite(
        anyLong(), anyLong(), any(), anyBoolean(), any());
    verify(writeCache, never()).loadOrAdd(anyLong(), anyLong(), anyBoolean());
  }

  // ---------------------------------------------------------------------------
  // In-memory engine: eager-install path on a non-fresh (already-registered)
  // file. The new method must call DirectMemoryOnlyDiskCache.loadOrAdd, balance
  // the readers-referrer bump with a single decrement, and wrap the returned
  // CachePointer in a real CacheEntryImpl so commitChanges's replay loop finds
  // the page via MemoryFile.loadPage.
  // ---------------------------------------------------------------------------

  /**
   * Eager-install on the in-memory engine path. When the read cache is a
   * {@link DirectMemoryOnlyDiskCache} and the target file is NOT fresh-booked
   * (i.e., {@code readCache.addFile} has already run), the new method must call
   * {@code DirectMemoryOnlyDiskCache.loadOrAdd(fileId, pageIndex, false)} to
   * install the page in {@code MemoryFile} immediately, rather than handing back
   * a stub-shape delegate as it does for the disk engine.
   *
   * <p>This branch exists because the in-memory engine's read-cache wrappers
   * ({@code loadOrAddForWrite} / {@code loadForRead}) are deliberately non-total —
   * they return {@code null} on miss to preserve diagnostic "page does not exist"
   * semantics for unrelated callers. Without the eager install, the
   * {@code commitChanges} replay loop's call to {@code readCache.loadOrAddForWrite}
   * would return {@code null} for every new-page entry on this engine and the
   * accumulated changes would have no slot to apply against — the exact NPE that
   * blew up the prior migration attempt against a fresh in-memory database.
   *
   * <p>The test pins three things: (1) the eager install fires exactly once with
   * the expected {@code (fileId, pageIndex, false)} arguments, (2) the returned
   * overlay wraps the installed {@link CachePointer} (not a null-buffer stub),
   * and (3) the readers-referrer bump from {@code MemoryFile.loadOrAddPage} is
   * balanced by a single decrement so {@code MemoryFile.clear} can later return
   * the page frame to the pool.
   */
  @Test
  public void inMemoryEngineEagerlyInstallsPageOnNonFreshFile() throws IOException {
    final var inMemoryCache = mock(DirectMemoryOnlyDiskCache.class);
    final var inMemoryOp = newInMemoryOp(inMemoryCache);

    long fileId = composeFileId(fileIdCounter.getAndIncrement(), STORAGE_ID);
    long pageIndex = 10; // == allocationFloor, the one-page-extend target
    when(writeCache.getFilledUpTo(fileId)).thenReturn(10L);
    var pointer = stubPointer(fileId, pageIndex);
    when(inMemoryCache.loadOrAdd(fileId, pageIndex, false)).thenReturn(pointer);

    var entry = (CacheEntryChanges) inMemoryOp.allocatePageForWrite(fileId, pageIndex);

    // The overlay wraps the cache-installed pointer (no null-buffer stub on this branch).
    assertThat(entry.getDelegate().getCachePointer()).isSameAs(pointer);
    // isNew=true because pageIndex (10) >= allocationFloor (10).
    assertThat(entry.isNew).isTrue();
    // The eager install fired exactly once with verifyChecksums=false (mirrors the
    // historical legacy-allocator shape; DirectMemoryOnlyDiskCache ignores the flag anyway).
    verify(inMemoryCache, times(1)).loadOrAdd(fileId, pageIndex, false);
    // The readers-referrer bump that MemoryFile.loadOrAddPage performed for the
    // caller is balanced by exactly one decrement so the in-cache referrer stays
    // at 1 (held by installEmptyPage) — the page remains resident but is no
    // longer pinned by the AOBT overlay. Without this decrement every allocation
    // through this branch would leak a readers reference and MemoryFile.clear
    // (on deleteFile / truncate / drop) could not return the frame to the pool.
    verify(pointer, times(1)).decrementReadersReferrer();
    // Bookkeeping: pageChangesMap was registered (visible via hasChangesForPage),
    // maxNewPageIndex bumped (visible via filledUpTo).
    assertThat(inMemoryOp.hasChangesForPage(fileId, pageIndex)).isTrue();
    assertThat(inMemoryOp.filledUpTo(fileId)).isEqualTo(11L);
  }

  /**
   * Loop-style allocation regression: each allocation through the in-memory
   * eager-install branch must balance its readers-referrer bump. A net-zero
   * invariant on N allocations is the arithmetic the page-frame pool depends on:
   * {@code DirectMemoryOnlyDiskCache.loadOrAdd} bumps once per call (via
   * {@code MemoryFile.loadOrAddPage}), the wrapper's {@code releaseFromRead}
   * never touches readers, so the only path back to a balanced count is the
   * explicit decrement inside the in-memory branch of {@code allocatePageForWrite}.
   * Loop-style allocation (e.g., {@code FreeSpaceMap.updatePageFreeSpace}'s
   * growth loop) is the most likely real-world amplifier of any leak here.
   */
  @Test
  public void inMemoryEagerInstallBalancesReferrerOnEveryCall() throws IOException {
    final var inMemoryCache = mock(DirectMemoryOnlyDiskCache.class);
    final var inMemoryOp = newInMemoryOp(inMemoryCache);

    long fileId = composeFileId(fileIdCounter.getAndIncrement(), STORAGE_ID);
    int allocations = 8;
    // Pre-existing file: getFilledUpTo > 0 keeps changesContainer.isNew=false, so the
    // in-memory eager-install branch fires. The eager-install branch also covers
    // fresh-booked files now (see freshBookedFileTakesEagerInstallBranchOnInMemoryEngine),
    // but using a pre-existing file here keeps the loop bookkeeping straightforward.
    when(writeCache.getFilledUpTo(fileId)).thenReturn((long) allocations);
    // Use a shared mock pointer with per-call answers for getPageIndex so the
    // production-side fileId+pageIndex compatibility assert sees the expected target on
    // every iteration. The pointer is shared deliberately: collapsing the per-iteration
    // decrementReadersReferrer call into a single mock makes the times(allocations)
    // verify below directly measure the loop arithmetic.
    var pointer = mock(CachePointer.class);
    when(pointer.getFileId()).thenReturn((long) AbstractWriteCache.extractFileId(fileId));
    when(pointer.getBuffer()).thenReturn(ByteBuffer.allocate(8));
    when(inMemoryCache.loadOrAdd(eq(fileId), anyLong(), eq(false)))
        .thenAnswer(
            invocation -> {
              long pi = invocation.getArgument(1, Long.class);
              when(pointer.getPageIndex()).thenReturn((int) pi);
              return pointer;
            });

    for (int i = 0; i < allocations; i++) {
      inMemoryOp.allocatePageForWrite(fileId, allocations + i);
    }

    // Each loop iteration must call loadOrAdd once and decrementReadersReferrer once.
    // With a shared mock pointer Mockito sums calls across iterations, so we verify
    // exactly N invocations total — net-zero referrer accounting per allocation.
    verify(inMemoryCache, times(allocations)).loadOrAdd(eq(fileId), anyLong(), eq(false));
    verify(pointer, times(allocations)).decrementReadersReferrer();
    // Post-loop bookkeeping: filledUpTo = highest pageIndex + 1 = allocations*2,
    // and every iteration's pageChangesMap entry is observable via hasChangesForPage.
    assertThat(inMemoryOp.filledUpTo(fileId)).isEqualTo(allocations * 2L);
    for (int i = 0; i < allocations; i++) {
      assertThat(inMemoryOp.hasChangesForPage(fileId, allocations + i)).isTrue();
    }
  }

  /**
   * Rollback-orphan reuse on the in-memory engine. The in-memory branch must NOT
   * raise the allocator-only {@link IllegalStateException} when the target
   * pageIndex is below {@code writeCache.getFilledUpTo} — that condition is the
   * normal aftermath of a rolled-back TX that eagerly installed pages, and the
   * next TX is expected to allocate the same logical page (per
   * {@code mapEntryPoint.fileSize}) by re-using the physical orphan.
   *
   * <p>The disk engine never has this condition (its
   * {@code WriteCache.loadOrAdd} is total at commit time and rollback does not
   * leave orphans), so the strict allocator-only check fires there as documented
   * by {@link #allocatePageForWriteThrowsForExistingPage}. The in-memory engine
   * is structurally different: the eager install above adds to {@code MemoryFile}
   * during the TX, and rollback discards the {@code mapEntryPoint.fileSize}
   * update but does not roll back the cache. {@code MemoryFile.loadOrAddPage}'s
   * {@code putIfAbsent} semantics make the orphan reuse safe — the orphan's
   * magic-empty-LSN header carries no real data and the next TX overwrites it
   * via {@code CacheEntryChanges}.
   *
   * <p>Regression pin: the failing
   * {@code EntityPartialDeserializationLinkBagTest.testOppositeLinkBagSurvives
   * ConcurrentModification} test surfaced this exact scenario — 4 threads
   * racing through {@code CollectionPositionMapV2.allocate}, some TXs rolling
   * back on {@code ConcurrentModificationException}, the next TX seeing
   * {@code mapEntryPoint.fileSize=0} but {@code MemoryFile.size=2}. Without the
   * bypass below, the strict check would raise IllegalStateException on the
   * legal re-allocation.
   */
  @Test
  public void inMemoryEngineReusesRollbackOrphanBelowAllocationFloor() throws IOException {
    final var inMemoryCache = mock(DirectMemoryOnlyDiskCache.class);
    final var inMemoryOp = newInMemoryOp(inMemoryCache);

    long fileId = composeFileId(fileIdCounter.getAndIncrement(), STORAGE_ID);
    // Simulate the rollback-orphan condition: MemoryFile.size reports 2 (a prior
    // TX eagerly installed page 1 and then rolled back), but mapEntryPoint.fileSize
    // logically reports 0. The next TX asks for pageIndex 1 (its logical "first
    // allocation"). The strict disk-engine check would throw because 1 < 2.
    when(writeCache.getFilledUpTo(fileId)).thenReturn(2L);
    var pointer = stubPointer(fileId, 1L);
    when(inMemoryCache.loadOrAdd(fileId, 1L, false)).thenReturn(pointer);

    // The call must succeed (no IllegalStateException) and the in-memory engine
    // must be asked to install/re-use page 1 via the total primitive.
    var entry = (CacheEntryChanges) inMemoryOp.allocatePageForWrite(fileId, 1L);

    assertThat(entry.getDelegate().getCachePointer()).isSameAs(pointer);
    assertThat(entry.isNew).isTrue(); // allocator semantics from the logical TX's view
    verify(inMemoryCache, times(1)).loadOrAdd(fileId, 1L, false);
    verify(pointer, times(1)).decrementReadersReferrer();
    // Bookkeeping pin: pageChangesMap.put fired and maxNewPageIndex bumped on the
    // orphan-reuse branch, same as a non-orphan allocation. A regression that skipped
    // either step only on the orphan path would otherwise silently pass.
    assertThat(inMemoryOp.hasChangesForPage(fileId, 1L)).isTrue();
    assertThat(inMemoryOp.filledUpTo(fileId)).isEqualTo(2L);
  }

  /**
   * Boundary variant of {@link #inMemoryEngineReusesRollbackOrphanBelowAllocationFloor}:
   * the rollback orphan sits at pageIndex 0, the entry-point bootstrap case for
   * components that allocate page 0 first (CPMV2 / PCV2 / IHM). Pins that the eager-
   * install branch handles pageIndex 0 the same way as higher indices.
   */
  @Test
  public void inMemoryEngineReusesRollbackOrphanAtPageZero() throws IOException {
    final var inMemoryCache = mock(DirectMemoryOnlyDiskCache.class);
    final var inMemoryOp = newInMemoryOp(inMemoryCache);

    long fileId = composeFileId(fileIdCounter.getAndIncrement(), STORAGE_ID);
    // filledUpTo=1 indicates a prior TX eagerly installed page 0 then rolled back.
    when(writeCache.getFilledUpTo(fileId)).thenReturn(1L);
    var pointer = stubPointer(fileId, 0L);
    when(inMemoryCache.loadOrAdd(fileId, 0L, false)).thenReturn(pointer);

    var entry = (CacheEntryChanges) inMemoryOp.allocatePageForWrite(fileId, 0L);

    assertThat(entry.getDelegate().getCachePointer()).isSameAs(pointer);
    assertThat(entry.isNew).isTrue();
    verify(inMemoryCache, times(1)).loadOrAdd(fileId, 0L, false);
    verify(pointer, times(1)).decrementReadersReferrer();
  }

  /**
   * Fresh-booked files on the in-memory engine take the eager-install branch (same as
   * non-fresh files). {@link AtomicOperationBinaryTracking#addFile} eagerly calls
   * {@code readCache.addFile} when the engine is {@link DirectMemoryOnlyDiskCache}, so
   * the underlying {@code MemoryFile} is already registered before
   * {@code allocatePageForWrite} fires and {@code readCache.loadOrAdd} succeeds. The
   * {@code commitChanges} replay loop later skips its own {@code readCache.addFile} call
   * (via the {@code eagerlyInstalledInCache} flag on {@code FileChanges}) so the
   * duplicate-registration error never fires.
   *
   * <p>This was a deliberate behavior change: prior iterations of this method tried
   * to dispatch fresh files to the stub branch on the in-memory engine, but the commit-
   * time replay then NPE'd because the in-memory {@code loadOrAddForWrite} returns null
   * on miss and the page was never installed. Eager registration in {@code addFile}
   * closes that gap. The disk engine still takes the stub branch on fresh files —
   * {@code readCache.addFile} on the disk engine creates physical state (WAL records,
   * AsyncFile open) that has not yet run at allocation time.
   */
  @Test
  public void freshBookedFileTakesEagerInstallBranchOnInMemoryEngine() throws IOException {
    final var inMemoryCache = mock(DirectMemoryOnlyDiskCache.class);
    final var inMemoryOp = newInMemoryOp(inMemoryCache);

    long fileId = composeFileId(fileIdCounter.getAndIncrement(), STORAGE_ID);
    when(writeCache.bookFileId("fresh.dat")).thenReturn(fileId);
    var pointer = stubPointer(fileId, 0L);
    when(inMemoryCache.loadOrAdd(fileId, 0L, false)).thenReturn(pointer);

    inMemoryOp.addFile("fresh.dat");

    var entry = (CacheEntryChanges) inMemoryOp.allocatePageForWrite(fileId, 0);

    // Eager-install branch: the overlay wraps the cache-installed pointer (no null-
    // buffer stub on this branch).
    assertThat(entry.getDelegate().getCachePointer()).isSameAs(pointer);
    assertThat(entry.isNew).isTrue();
    // addFile eagerly registered the file with the in-memory cache so that
    // allocatePageForWrite could take the eager-install branch.
    verify(inMemoryCache, times(1))
        .addFile(eq("fresh.dat"), eq(fileId), eq(writeCache), anyBoolean());
    // Eager install fired exactly once.
    verify(inMemoryCache, times(1)).loadOrAdd(fileId, 0L, false);
    // Referrer balance: bump-once + decrement-once = net zero, same as the non-fresh
    // path.
    verify(pointer, times(1)).decrementReadersReferrer();
    // Bookkeeping is unchanged.
    assertThat(inMemoryOp.hasChangesForPage(fileId, 0L)).isTrue();
    assertThat(inMemoryOp.filledUpTo(fileId)).isEqualTo(1L);
  }

  /**
   * Idempotency on the in-memory eager-install branch: a second call for the same
   * {@code (fileId, pageIndex)} must short-circuit through the existing overlay
   * (registered in {@code pageChangesMap}) and must not call {@code loadOrAdd} a
   * second time. Without this property, the SLBB.splitRootBucket two-page recipe
   * could double-allocate and double-decrement the referrer on a regression that
   * moves the dispatch above the early-return.
   */
  @Test
  public void inMemoryEagerInstallIsIdempotentForSamePageIndex() throws IOException {
    final var inMemoryCache = mock(DirectMemoryOnlyDiskCache.class);
    final var inMemoryOp = newInMemoryOp(inMemoryCache);

    long fileId = composeFileId(fileIdCounter.getAndIncrement(), STORAGE_ID);
    long pageIndex = 10;
    when(writeCache.getFilledUpTo(fileId)).thenReturn(10L);
    var pointer = stubPointer(fileId, pageIndex);
    when(inMemoryCache.loadOrAdd(fileId, pageIndex, false)).thenReturn(pointer);

    var first = inMemoryOp.allocatePageForWrite(fileId, pageIndex);
    var second = inMemoryOp.allocatePageForWrite(fileId, pageIndex);

    assertThat(second).isSameAs(first);
    // Only one loadOrAdd dispatch — the second call returned through the
    // pageChangesMap early-return.
    verify(inMemoryCache, times(1)).loadOrAdd(fileId, pageIndex, false);
    // Referrer balanced exactly once across both calls.
    verify(pointer, times(1)).decrementReadersReferrer();
  }

  /**
   * SLBB.splitRootBucket recipe on the in-memory engine: two consecutive allocations
   * for distinct pageIndices must produce two distinct overlays, each wrapping the
   * per-pageIndex pointer the cache handed out. Pins the contract that the same-
   * pageIndex idempotency does NOT bleed across consecutive distinct pageIndices —
   * a regression that keyed the early-return on a per-file counter (rather than
   * pageIndex) would silently merge leftBucketEntry and rightBucketEntry into one
   * overlay.
   */
  @Test
  public void twoConsecutiveAllocationsProduceDistinctOverlaysOnInMemoryEngine()
      throws IOException {
    final var inMemoryCache = mock(DirectMemoryOnlyDiskCache.class);
    final var inMemoryOp = newInMemoryOp(inMemoryCache);

    long fileId = composeFileId(fileIdCounter.getAndIncrement(), STORAGE_ID);
    when(writeCache.getFilledUpTo(fileId)).thenReturn(5L);
    var leftPointer = stubPointer(fileId, 5L);
    when(inMemoryCache.loadOrAdd(fileId, 5L, false)).thenReturn(leftPointer);
    var rightPointer = stubPointer(fileId, 6L);
    when(inMemoryCache.loadOrAdd(fileId, 6L, false)).thenReturn(rightPointer);

    var leftEntry = (CacheEntryChanges) inMemoryOp.allocatePageForWrite(fileId, 5L);
    var rightEntry = (CacheEntryChanges) inMemoryOp.allocatePageForWrite(fileId, 6L);

    assertThat(rightEntry).isNotSameAs(leftEntry);
    assertThat(leftEntry.getDelegate().getCachePointer()).isSameAs(leftPointer);
    assertThat(rightEntry.getDelegate().getCachePointer()).isSameAs(rightPointer);
    verify(leftPointer, times(1)).decrementReadersReferrer();
    verify(rightPointer, times(1)).decrementReadersReferrer();
    assertThat(inMemoryOp.filledUpTo(fileId)).isEqualTo(7L);
  }

  /**
   * Dispatch pin for the disk-engine + non-fresh-file path: the stub-shape branch must
   * fire and the in-memory cache primitive must NOT be touched. Complements the in-
   * memory dispatch tests by explicitly verifying the negative space — a regression
   * that flipped the {@code instanceof DirectMemoryOnlyDiskCache} guard would
   * otherwise silently route disk-engine traffic through the in-memory branch.
   *
   * <p>The null-buffer delegate is the structural marker for the stub branch: the in-
   * memory eager-install branch installs a real {@link CachePointer} with a non-null
   * buffer, so a regression that misroutes to that branch would surface here as a
   * non-null buffer on the returned delegate. The companion
   * {@code verifyNoInteractions(readCache)} confirms the dispatch never enters any
   * cache primitive on this path — the stub-shape entry is materialized purely from
   * the in-progress {@code pageChangesMap} state.
   */
  @Test
  public void diskEngineWithNonFreshFileTakesStubBranch() throws IOException {
    long fileId = composeFileId(fileIdCounter.getAndIncrement(), STORAGE_ID);
    when(writeCache.getFilledUpTo(fileId)).thenReturn(10L);

    // The default fixture's readCache is a plain ReadCache mock (NOT a
    // DirectMemoryOnlyDiskCache), so dispatch must take the stub branch.
    var entry = (CacheEntryChanges) op.allocatePageForWrite(fileId, 10L);

    assertThat(entry.getDelegate().getCachePointer().getBuffer()).isNull();
    // The disk-engine stub branch must materialize the entry without entering any
    // cache primitive — no loadOrAdd, no addFile, no release. verifyNoInteractions
    // would also flag any unexpected readCache touch by a future regression.
    verifyNoInteractions(readCache);
  }

  // ---------------------------------------------------------------------------
  // Defensive guards: the method must reject negative pageIndex and refuse to
  // operate on a deleted file.
  // ---------------------------------------------------------------------------

  /**
   * A negative {@code pageIndex} is a caller bug; the method asserts on it. With
   * assertions disabled, the negative pageIndex would still surface via the
   * downstream {@code WriteCache.loadOrAdd}'s own validation, but the assertion
   * fail-fasts the error closer to the caller for easier diagnosis. The message
   * must name the offending pageIndex so a regression that fires the assertion
   * on an unrelated invariant (e.g. a downstream null-check) is distinguishable
   * from this caller-bug guard.
   */
  @Test
  public void negativePageIndexFailsFast() {
    long fileId = composeFileId(fileIdCounter.getAndIncrement(), STORAGE_ID);

    assertThatThrownBy(() -> op.allocatePageForWrite(fileId, -1L))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("pageIndex out of range")
        .hasMessageContaining("-1");
  }

  /**
   * Calling {@code allocatePageForWrite} on a file that this operation has marked
   * for deletion must raise {@link StorageException} — same contract as
   * {@code loadPageForWrite} and {@code loadPageForRead}.
   */
  @Test
  public void deletedFileRaisesStorageException() throws IOException {
    long fileId = composeFileId(fileIdCounter.getAndIncrement(), STORAGE_ID);
    when(writeCache.fileNameById(fileId)).thenReturn("doomed.dat");

    op.deleteFile(fileId);

    assertThatThrownBy(() -> op.allocatePageForWrite(fileId, 0L))
        .isInstanceOf(StorageException.class)
        .hasMessageContaining("is deleted");
  }

  /**
   * Multi-thread regression pin for the rollback-orphan reuse branch: two AOBT
   * instances on two threads, both calling {@code allocatePageForWrite(fileId, 0L)}
   * against a {@code DirectMemoryOnlyDiskCache} where pageIndex 0 is already a
   * leftover orphan from a prior aborted "TX". Both threads must succeed (no
   * {@code IllegalStateException}) and the page must remain installed with a
   * balanced referrer count.
   *
   * <p>The episode that triggered the rollback-orphan-tolerant branch (the 4-thread
   * CME race in {@code EntityPartialDeserializationLinkBagTest.testOppositeLinkBag
   * SurvivesConcurrentModification}) repeatedly produced rollback orphans that hit
   * this branch under contention. A regression that re-introduced the strict
   * allocator-only check on the in-memory engine would surface here as an
   * {@code IllegalStateException} from at least one of the threads.
   */
  @Test
  public void inMemoryEagerInstallToleratesConcurrentOrphanReuse() throws Exception {
    final var realCache = newRealInMemoryCache();
    try {
      // Pre-stage the orphan: pretend a prior TX installed page 0 in MemoryFile then
      // rolled back. From the next TX's perspective, mapEntryPoint.fileSize would still
      // read 0 (logical horizon) but realCache.getFilledUpTo would return 1 (physical
      // horizon). Both threads ask for pageIndex 0 — the legal "first allocation"
      // from the logical TX's view.
      long fileId = realCache.addFile("orphan.dat", writeCache);
      var orphanPointer = realCache.loadOrAdd(fileId, 0L, false);
      orphanPointer.decrementReadersReferrer();
      assertThat(realCache.getFilledUpTo(fileId)).isEqualTo(1L);

      // Two AOBT instances coordinated via CountDownLatch to maximize contention on
      // the orphan-reuse path. Each instance owns its own pageChangesMap, so the
      // idempotency early-return does NOT collapse the two calls into one.
      final var op1 = newInMemoryOp(realCache);
      final var op2 = newInMemoryOp(realCache);
      final var startGate = new CountDownLatch(1);
      final var finishGate = new CountDownLatch(2);
      final var error1 = new AtomicReference<Throwable>();
      final var error2 = new AtomicReference<Throwable>();

      final ExecutorService pool = Executors.newFixedThreadPool(2);
      try {
        pool.submit(
            () -> {
              try {
                startGate.await();
                op1.allocatePageForWrite(fileId, 0L);
              } catch (Throwable t) {
                error1.set(t);
              } finally {
                finishGate.countDown();
              }
            });
        pool.submit(
            () -> {
              try {
                startGate.await();
                op2.allocatePageForWrite(fileId, 0L);
              } catch (Throwable t) {
                error2.set(t);
              } finally {
                finishGate.countDown();
              }
            });

        startGate.countDown();
        assertThat(finishGate.await(30, TimeUnit.SECONDS)).isTrue();
      } finally {
        pool.shutdownNow();
        // Ensure submitted tasks have fully terminated before the outer finally
        // runs realCache.delete() — without this, a still-in-flight task could
        // race with cache teardown. Restore the interrupt flag if interrupted.
        try {
          pool.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
        }
      }

      assertThat(error1.get())
          .as("thread 1 must not raise IllegalStateException on rollback-orphan reuse")
          .isNull();
      assertThat(error2.get())
          .as("thread 2 must not raise IllegalStateException on rollback-orphan reuse")
          .isNull();
      // Page 0 must still be resident — both threads decremented exactly once each
      // and the in-cache referrer (held by installEmptyPage) keeps it pinned.
      assertThat(realCache.getFilledUpTo(fileId)).isEqualTo(1L);

      // Probe the orphan pointer's referrer count directly. Both threads incremented
      // the readers-referrer once via MemoryFile.loadOrAddPage and the in-memory
      // eager-install branch decremented it once each, so the post-concurrency state
      // must be referrersCount=1 (the in-cache reference held by installEmptyPage).
      // A regression that skipped the eager-install-branch decrement on one or both
      // threads would surface here as referrersCount>1 (the count would never fall
      // back to 1 because the matching decrement never fired). A regression that
      // double-decremented would already have thrown IllegalStateException inside
      // decrementReadersReferrer and been captured by error1/error2 above.
      // Reflection is the cheapest probe — the field is package-private with no
      // public accessor, and adding one purely for this test would be over-fitting.
      var orphanReload = realCache.loadOrAdd(fileId, 0L, false);
      // loadOrAdd bumps the referrer by 1 for the caller; release it immediately so
      // the field read measures the steady-state count rather than a transient bump.
      orphanReload.decrementReadersReferrer();
      assertThat(orphanReload)
          .as("page 0 must still be the same orphan pointer — a recycled frame would "
              + "break the readers-referrer accounting under the eager-install branch")
          .isSameAs(orphanPointer);
      var referrersField = CachePointer.class.getDeclaredField("referrersCount");
      referrersField.setAccessible(true);
      assertThat(referrersField.getInt(orphanPointer))
          .as("orphan readers-referrer must be balanced: bump-once / decrement-once per "
              + "thread leaves the in-cache referrer at 1")
          .isEqualTo(1);
    } finally {
      realCache.delete();
    }
  }

  // ---------------------------------------------------------------------------
  // filledUpTo: direct coverage of the three-arm body inlined into the public
  // override. The deleted-file arm raises, the truncated-file arm returns 0,
  // and the committed-file fall-through is exercised indirectly by the
  // allocator tests above (op.filledUpTo == maxNewPageIndex + 1 on the isNew /
  // overlay path).
  // ---------------------------------------------------------------------------

  /**
   * The first arm of {@code filledUpTo}: a file marked for deletion in this TX must
   * raise {@link StorageException} naming the offending fileId. Pins that the
   * deleted-files guard fires before the placeholder-registration branch runs, so
   * callers cannot silently observe the committed extent of a doomed file mid-TX.
   */
  @Test
  public void filledUpToOnDeletedFileRaisesStorageException() {
    long fileId = composeFileId(fileIdCounter.getAndIncrement(), STORAGE_ID);
    when(writeCache.fileNameById(fileId)).thenReturn("doomed.dat");

    op.deleteFile(fileId);

    assertThatThrownBy(() -> op.filledUpTo(fileId))
        .isInstanceOf(StorageException.class)
        .hasMessageContaining(Long.toString(fileId))
        .hasMessageContaining("deleted");
  }

  /**
   * After {@code truncateFile} runs on a pre-existing (non-fresh) file,
   * {@code filledUpTo} must return 0 regardless of the committed
   * {@code writeCache.getFilledUpTo} value. The test name reflects the actual
   * arm exercised: {@code truncateFile} pre-sets {@code maxNewPageIndex = -1},
   * so the second arm of the inlined three-arm body ({@code maxNewPageIndex > -2})
   * fires first and returns {@code maxNewPageIndex + 1 = 0}. The third
   * ({@code changesContainer.truncate}) arm is structurally unreachable under
   * current call shapes; a future refactor that drops the {@code maxNewPageIndex = -1}
   * pre-set in {@code truncateFile} would activate the third arm, at which point
   * a dedicated test would be added — this setup would still fall through arm 2.
   */
  @Test
  public void filledUpToAfterTruncateExercisesMaxNewPageIndexArm() {
    long fileId = composeFileId(fileIdCounter.getAndIncrement(), STORAGE_ID);
    // Pre-existing file with five committed pages on disk; truncateFile must override
    // this with the in-TX truncate flag.
    when(writeCache.getFilledUpTo(fileId)).thenReturn(5L);

    op.truncateFile(fileId);

    assertThat(op.filledUpTo(fileId)).isEqualTo(0L);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Builds a real {@link DirectMemoryOnlyDiskCache} via reflection — its constructor is
   * package-private and lives in the {@code memory} package, while this test class lives
   * in the AOBT package. The reflection hop is the cheapest way to exercise the genuine
   * cache in a multi-thread regression test without restructuring production visibility.
   */
  private static DirectMemoryOnlyDiskCache newRealInMemoryCache() throws Exception {
    final Constructor<DirectMemoryOnlyDiskCache> ctor =
        DirectMemoryOnlyDiskCache.class.getDeclaredConstructor(int.class, int.class, String.class);
    ctor.setAccessible(true);
    return ctor.newInstance(1024, STORAGE_ID, "allocatePageForWriteRealCache");
  }

  /**
   * Builds a {@link CachePointer} mock that satisfies the production-side asserts on the
   * in-memory eager-install branch: a non-null {@code getBuffer()} (so the
   * {@code releasePageFromWrite} gate fires and the
   * {@code "delegate must carry a non-null buffer"} assertion passes), the matching
   * {@code getFileId()} (the in-memory engine's cache builds pointers keyed by the
   * extracted int file id, not the composed fileId), and the matching
   * {@code getPageIndex()}.
   */
  private static CachePointer stubPointer(long fileId, long pageIndex) {
    var pointer = mock(CachePointer.class);
    when(pointer.getFileId()).thenReturn((long) AbstractWriteCache.extractFileId(fileId));
    when(pointer.getPageIndex()).thenReturn((int) pageIndex);
    when(pointer.getBuffer()).thenReturn(ByteBuffer.allocate(8));
    return pointer;
  }

  /**
   * Builds an {@link AtomicOperationBinaryTracking} bound to the in-memory cache mock.
   * Mirrors the {@link #setUp()} body but accepts an explicit {@link DirectMemoryOnlyDiskCache}
   * so each eager-install test gets a fresh, isolated cache without duplicating the 11-arg
   * constructor at every test site.
   */
  private AtomicOperationBinaryTracking newInMemoryOp(DirectMemoryOnlyDiskCache inMemoryCache) {
    return new AtomicOperationBinaryTracking(
        inMemoryCache,
        writeCache,
        wal,
        STORAGE_ID,
        new AtomicOperationsSnapshot(0, 100, new LongOpenHashSet(), 100),
        new ConcurrentSkipListMap<>(),
        new ConcurrentSkipListMap<>(),
        new AtomicLong(),
        new ConcurrentSkipListMap<>(),
        new ConcurrentSkipListMap<>(),
        new AtomicLong());
  }

  private CacheEntry mockCacheEntry(long fileId, int pageIndex) {
    var pointer = mock(CachePointer.class);
    when(pointer.getBuffer()).thenReturn(null);
    var entry = mock(CacheEntry.class);
    when(entry.getFileId()).thenReturn(fileId);
    when(entry.getPageIndex()).thenReturn(pageIndex);
    when(entry.getCachePointer()).thenReturn(pointer);
    return entry;
  }

  private static long composeFileId(long fileId, int storageId) {
    return (((long) storageId) << 32) | fileId;
  }
}
