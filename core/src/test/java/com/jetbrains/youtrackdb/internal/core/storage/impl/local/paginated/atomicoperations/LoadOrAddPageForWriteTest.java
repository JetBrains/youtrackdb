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
 * {@link AtomicOperationBinaryTracking}: dual-engine dispatch, bookkeeping, and idempotency.
 *
 * <p>The method is the write-side allocator that replaces today's {@code addPage(long)} —
 * callers state the target {@code pageIndex} up front (typically derived from
 * {@code entryPoint.pagesSize + 1}) instead of letting the cache pick the index. The
 * tests below exercise both engine paths via the {@link ReadCache} mock seam:
 * <ul>
 *   <li>Disk engine ({@code LockFreeReadCache}) — {@code loadOrAddForWrite} returns a
 *       non-null write-locked entry; the new method must release the exclusive lock so
 *       the delegate matches {@code loadPageForWrite}'s overlay lifecycle.</li>
 *   <li>In-memory engine ({@code DirectMemoryOnlyDiskCache}) — {@code loadOrAddForWrite}
 *       returns {@code null}; the new method falls back to {@code WriteCache.loadOrAdd}
 *       which is total on the in-memory engine and installs the page in {@code MemoryFile}
 *       so the commit-time {@code loadOrAddForWrite} finds it.</li>
 * </ul>
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
  // Disk-engine path: loadOrAddForWrite is total and returns a write-locked
  // entry; loadOrAddPageForWrite must release the exclusive lock and wrap.
  // ---------------------------------------------------------------------------

  /**
   * On the disk engine, {@code loadOrAddForWrite} returns a non-null write-locked entry.
   * The new method must release the exclusive lock on the delegate (so subsequent
   * AOBT lifecycle code, which expects a usages-incremented overlay reference rather
   * than a write-locked one, can run without double-release surprises) and wrap the
   * delegate in a {@link CacheEntryChanges}.
   */
  @Test
  public void diskEngineDelegatesToLoadOrAddForWriteAndReleasesExclusiveLock()
      throws IOException {
    long fileId = composeFileId(fileIdCounter.getAndIncrement(), STORAGE_ID);
    long pageIndex = 5;

    // Disk-engine semantics: existing file, getFilledUpTo returns the
    // committed logical size; loadOrAddForWrite returns a real entry.
    when(writeCache.getFilledUpTo(fileId)).thenReturn(10L);
    var delegate = mockCacheEntry(fileId, (int) pageIndex);
    when(readCache.loadOrAddForWrite(
        eq(fileId), eq(pageIndex), eq(writeCache), eq(false), any()))
        .thenReturn(delegate);

    var entry = op.loadOrAddPageForWrite(fileId, pageIndex);

    assertThat(entry).isInstanceOf(CacheEntryChanges.class);
    var changes = (CacheEntryChanges) entry;
    assertThat(changes.getDelegate()).isSameAs(delegate);
    // isNew=false because pageIndex (5) is below committed filledUpTo (10).
    assertThat(changes.isNew).isFalse();
    // Exclusive lock acquired by loadOrAddForWrite must be released so the
    // delegate's lifecycle matches loadPageForWrite (overlay reference, no
    // caller-visible write lock).
    verify(delegate).releaseExclusiveLock();
    // The in-memory fallback (writeCache.loadOrAdd) must NOT fire on the
    // disk-engine path.
    verify(writeCache, never()).loadOrAdd(anyLong(), anyLong(), anyBoolean());
  }

  /**
   * On the disk engine, allocating a page beyond the committed file size sets the
   * {@code isNew} flag on the returned overlay so commitChanges can classify the
   * page as freshly allocated (and the read-cache layer flags it for the dirty-pages
   * publication).
   */
  @Test
  public void diskEngineMarksIsNewWhenPageIndexExtendsBeyondFilledUpTo()
      throws IOException {
    long fileId = composeFileId(fileIdCounter.getAndIncrement(), STORAGE_ID);
    long pageIndex = 10; // == filledUpTo, i.e. the one-page-extend target

    when(writeCache.getFilledUpTo(fileId)).thenReturn(10L);
    var delegate = mockCacheEntry(fileId, (int) pageIndex);
    when(readCache.loadOrAddForWrite(
        eq(fileId), eq(pageIndex), eq(writeCache), eq(false), any()))
        .thenReturn(delegate);

    var entry = (CacheEntryChanges) op.loadOrAddPageForWrite(fileId, pageIndex);

    assertThat(entry.isNew).isTrue();
  }

  // ---------------------------------------------------------------------------
  // In-memory-engine path: loadOrAddForWrite is non-total (null on miss); the
  // new method falls back to writeCache.loadOrAdd and wraps the CachePointer.
  // ---------------------------------------------------------------------------

  /**
   * On the in-memory engine, {@code ReadCache.loadOrAddForWrite} returns {@code null}
   * on miss (the read-cache wrapper is deliberately non-total there). The new method
   * must fall back to {@code WriteCache.loadOrAdd} (total on the in-memory engine),
   * wrap the returned {@link CachePointer} in a fresh {@link CacheEntry}, and return
   * the {@link CacheEntryChanges} overlay. The fallback installs the page in
   * MemoryFile so the commit-time {@code loadOrAddForWrite} finds it via
   * {@code MemoryFile.loadPage}.
   */
  @Test
  public void inMemoryEngineFallsBackToWriteCacheLoadOrAddOnNullReturn()
      throws IOException {
    long fileId = composeFileId(fileIdCounter.getAndIncrement(), STORAGE_ID);
    long pageIndex = 0;

    // In-memory semantics: a fresh file (no committed pages); the read-cache
    // wrapper returns null because the page is not in MemoryFile yet.
    when(writeCache.getFilledUpTo(fileId)).thenReturn(0L);
    when(readCache.loadOrAddForWrite(
        eq(fileId), eq(pageIndex), eq(writeCache), eq(false), any()))
        .thenReturn(null);
    var pointer = mock(CachePointer.class);
    when(writeCache.loadOrAdd(fileId, pageIndex, false)).thenReturn(pointer);

    var entry = (CacheEntryChanges) op.loadOrAddPageForWrite(fileId, pageIndex);

    // Wrap delegate exists and exposes the installed CachePointer.
    assertThat(entry.getDelegate().getCachePointer()).isSameAs(pointer);
    // isNew=true because pageIndex (0) >= committed filledUpTo (0).
    assertThat(entry.isNew).isTrue();
    // Fallback fired exactly once.
    verify(writeCache, times(1)).loadOrAdd(fileId, pageIndex, false);
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
    when(writeCache.bookFileId("fresh.dat")).thenReturn(fileId);
    op.addFile("fresh.dat");

    // Mock the in-memory path so we exercise allocation without a real cache.
    when(readCache.loadOrAddForWrite(
        anyLong(), anyLong(), any(), anyBoolean(), any()))
        .thenReturn(null);
    var pointer = mock(CachePointer.class);
    when(writeCache.loadOrAdd(eq(fileId), anyLong(), eq(false)))
        .thenReturn(pointer);

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
  // Idempotency: a second call for the same (fileId, pageIndex) inside the same
  // TX must return the same overlay so WAL change accumulation stays single-rooted.
  // ---------------------------------------------------------------------------

  /**
   * A second call to {@code loadOrAddPageForWrite} for the same {@code (fileId,
   * pageIndex)} inside the same TX must return the same {@link CacheEntryChanges}
   * overlay so WAL change accumulation in {@code changes} stays single-rooted —
   * if two overlays existed, half of the writes would go to one and half to the
   * other, and only one would be applied at commit time.
   */
  @Test
  public void secondCallReturnsExistingOverlay() throws IOException {
    long fileId = composeFileId(fileIdCounter.getAndIncrement(), STORAGE_ID);
    long pageIndex = 3;

    when(writeCache.getFilledUpTo(fileId)).thenReturn(10L);
    var delegate = mockCacheEntry(fileId, (int) pageIndex);
    when(readCache.loadOrAddForWrite(
        eq(fileId), eq(pageIndex), eq(writeCache), eq(false), any()))
        .thenReturn(delegate);

    var first = op.loadOrAddPageForWrite(fileId, pageIndex);
    var second = op.loadOrAddPageForWrite(fileId, pageIndex);

    assertThat(second).isSameAs(first);
    // Cache primitive consulted only on the first call; the second hits the
    // pageChangesMap idempotency check.
    verify(readCache, times(1)).loadOrAddForWrite(
        eq(fileId), eq(pageIndex), eq(writeCache), eq(false), any());
  }

  // ---------------------------------------------------------------------------
  // Defensive guards: the method must reject negative pageIndex and refuse to
  // operate on a deleted file.
  // ---------------------------------------------------------------------------

  /**
   * A negative {@code pageIndex} is a caller bug; the method asserts on it. With
   * assertions disabled, the negative pageIndex would still surface via the
   * downstream {@code WriteCache.loadOrAdd}'s own validation, but the assertion
   * fail-fasts the error closer to the caller for easier diagnosis.
   */
  @Test
  public void negativePageIndexFailsFast() {
    long fileId = composeFileId(fileIdCounter.getAndIncrement(), STORAGE_ID);

    assertThatThrownBy(() -> op.loadOrAddPageForWrite(fileId, -1L))
        .isInstanceOf(AssertionError.class);
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
