package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsTable.AtomicOperationsSnapshot;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.io.IOException;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Before;
import org.junit.Test;

/**
 * Verifies the new {@link AtomicOperation#loadOrAddPageForWrite(long, long)} method on
 * {@link AtomicOperationBinaryTracking}: allocator contract, bookkeeping, and idempotency.
 *
 * <p>The method is the write-side allocator that replaces today's {@code addPage(long)} —
 * callers state the target {@code pageIndex} up front (typically derived from
 * {@code entryPoint.pagesSize + 1}) instead of letting the cache pick the index. It is
 * allocator-only: the caller must target a genuinely new pageIndex (fresh-booked file,
 * or {@code pageIndex >= writeCache.getFilledUpTo}), and asking for an existing page
 * raises {@link IllegalStateException}. Use {@link AtomicOperation#loadPageForWrite}
 * to mutate an existing page.
 *
 * <p>The returned overlay is always stub-shaped on both engines: the delegate wraps a
 * {@link CachePointer} with a null native pointer, and the real cache slot is
 * materialized at commit time inside {@code commitChanges}'s pageChangesMap replay loop.
 */
public class LoadOrAddPageForWriteTest {

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
   * the offending pageIndex, the committed filledUpTo, and the fileId so a regression
   * (e.g., an unrelated guard firing) is distinguishable from this contract violation,
   * and so the message points the caller at the right alternative ({@code loadPageForWrite}
   * for existing pages). The guard rules out a class of bugs where a stale
   * {@code entryPoint.pagesSize} read would point at a page that is already on disk.
   */
  @Test
  public void loadOrAddPageForWriteThrowsForExistingPage() {
    long fileId = composeFileId(fileIdCounter.getAndIncrement(), STORAGE_ID);
    when(writeCache.getFilledUpTo(fileId)).thenReturn(10L); // 10 pages on disk

    assertThatThrownBy(() -> op.loadOrAddPageForWrite(fileId, 5L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("allocation-only")
        .hasMessageContaining("5")
        .hasMessageContaining("10");
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

    var entry = (CacheEntryChanges) op.loadOrAddPageForWrite(fileId, 0);

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
    assertThat(op.loadOrAddPageForWrite(fileId, 0)).isSameAs(entry);
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
   * {@link AtomicOperation#loadOrAddPageForWrite} is what makes the SLBB
   * two-page recipe safe — re-entering with the same pagesSize-derived
   * pageIndex must produce the same overlay, not a fresh one that would
   * silently merge writes from two distinct allocations.
   */
  @Test
  public void secondCallReturnsExistingOverlayOnFreshBookedFile() throws IOException {
    long fileId = composeFileId(fileIdCounter.getAndIncrement(), STORAGE_ID);
    when(writeCache.bookFileId("fresh.dat")).thenReturn(fileId);
    op.addFile("fresh.dat");

    var first = op.loadOrAddPageForWrite(fileId, 0);
    long filledAfterFirst = op.filledUpTo(fileId);
    var second = op.loadOrAddPageForWrite(fileId, 0);

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

    op.loadOrAddPageForWrite(fileId, 0);
    op.loadOrAddPageForWrite(fileId, 1);
    op.loadOrAddPageForWrite(fileId, 2);

    // hasChangesForPage queries maxNewPageIndex on the fresh-file path
    // (changesContainer.isNew == true), so it returns true for pageIndex <= 2.
    assertThat(op.hasChangesForPage(fileId, 2)).isTrue();
    // filledUpTo returns maxNewPageIndex + 1 on the isNew path.
    assertThat(op.filledUpTo(fileId)).isEqualTo(3L);
  }

  // ---------------------------------------------------------------------------
  // Cross-API idempotency: loadPageForWrite then loadOrAddPageForWrite for the
  // same (fileId, pageIndex) must collapse to a single overlay.
  // ---------------------------------------------------------------------------

  /**
   * Cross-API idempotency: a {@code loadPageForWrite} that registers a
   * {@link CacheEntryChanges} in {@code pageChangesMap} must be observed by a
   * subsequent {@code loadOrAddPageForWrite} for the same {@code (fileId,
   * pageIndex)}. The new method's early-return short-circuits engine dispatch
   * entirely, so the allocator-only contract never fires on the second call
   * even though the pageIndex is below {@code committedFilledUpTo} — the
   * existing overlay placed by {@code loadPageForWrite} takes precedence.
   * This is the most likely real-world ordering once Step 2-3 component
   * migrations land: a component first loads a known page for write, then later
   * in the same TX needs to ensure a page exists (re-entering the same overlay).
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
    var second = op.loadOrAddPageForWrite(fileId, pageIndex);

    assertThat(second).isSameAs(first);
    // The early-return short-circuits the allocator-only guard; if it did not, the
    // pageIndex (7) being below committed filledUpTo (20) would have raised
    // IllegalStateException. Cache primitive must never fire on the second call.
    verify(readCache, never()).loadOrAddForWrite(
        anyLong(), anyLong(), any(), anyBoolean(), any());
  }

  // ---------------------------------------------------------------------------
  // Bookkeeping: extending an existing file beyond committedFilledUpTo must set
  // the in-progress visibility horizon correctly and flag the page as new.
  // ---------------------------------------------------------------------------

  /**
   * Extending an existing (non-fresh) file: with {@code committedFilledUpTo=10}
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
    long pageIndex = 10; // == committedFilledUpTo, the one-page-extend target

    // Existing file (NOT created in this TX) — no addFile call, so the
    // FileChanges that loadOrAddPageForWrite lazily creates has isNew=false.
    // committedFilledUpTo from the write cache is 10 → allocating pageIndex=10
    // is the extend case.
    when(writeCache.getFilledUpTo(fileId)).thenReturn(10L);

    var entry = (CacheEntryChanges) op.loadOrAddPageForWrite(fileId, pageIndex);

    // Stub-shape delegate: null buffer (commit-time install handles the cache slot).
    assertThat(entry.getDelegate().getCachePointer().getBuffer()).isNull();
    // isNew=true because pageIndex (10) >= committedFilledUpTo (10).
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

    assertThatThrownBy(() -> op.loadOrAddPageForWrite(fileId, -1L))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("pageIndex out of range")
        .hasMessageContaining("-1");
  }

  /**
   * Calling {@code loadOrAddPageForWrite} on a file that this operation has marked
   * for deletion must raise {@link StorageException} — same contract as
   * {@code addPage}, {@code loadPageForWrite}, and {@code loadPageForRead}.
   */
  @Test
  public void deletedFileRaisesStorageException() throws IOException {
    long fileId = composeFileId(fileIdCounter.getAndIncrement(), STORAGE_ID);
    when(writeCache.fileNameById(fileId)).thenReturn("doomed.dat");

    op.deleteFile(fileId);

    assertThatThrownBy(() -> op.loadOrAddPageForWrite(fileId, 0L))
        .isInstanceOf(StorageException.class)
        .hasMessageContaining("is deleted");
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

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

  // Suppress unused-warning for the LSN type — referenced indirectly via mock signatures.
  @SuppressWarnings("unused")
  private static final LogSequenceNumber DUMMY = new LogSequenceNumber(0, 0);
}
