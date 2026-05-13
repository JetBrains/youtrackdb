package com.jetbrains.youtrackdb.internal.core.storage.cache.chm;

import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.common.types.ModifiableBoolean;
import com.jetbrains.youtrackdb.internal.core.command.CommandOutputListener;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.PageDataVerificationError;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.BackgroundExceptionListener;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.PageIsBrokenListener;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for file-lifecycle methods of {@link LockFreeReadCache}:
 * {@code truncateFile()}, {@code closeFile()}, {@code deleteFile()},
 * {@code changeMaximumAmountOfMemory()}, and {@code silentLoadForRead()}.
 *
 * <p>These methods delegate to {@code clearFile()} internally and route to the WriteCache
 * for the final file-system operation. The tests verify:
 * <ul>
 *   <li>Cache entries for the target file are evicted from the read cache.</li>
 *   <li>Entries for other files remain untouched.</li>
 *   <li>The WriteCache receives the correct file-system call.</li>
 *   <li>{@code changeMaximumAmountOfMemory()} updates the eviction policy's max-size.</li>
 *   <li>{@code silentLoadForRead()} returns an entry on miss (loads from WriteCache) and
 *       null when the WriteCache returns null for the page.</li>
 * </ul>
 */
public class LockFreeReadCacheFileOpsTest {

  private static final int PAGE_SIZE = 4 * 1024;

  private DirectMemoryAllocator allocator;
  private ByteBufferPool bufferPool;
  private LockFreeReadCache readCache;
  private TrackingWriteCache writeCache;

  @Before
  public void setUp() {
    allocator = new DirectMemoryAllocator();
    bufferPool = new ByteBufferPool(PAGE_SIZE, allocator, 256);
    readCache = new LockFreeReadCache(bufferPool, 1024L * PAGE_SIZE, PAGE_SIZE);
    writeCache = new TrackingWriteCache(bufferPool);
  }

  @After
  public void tearDown() {
    try {
      readCache.clear();
    } catch (StorageException ignored) {
      // Tolerate pinned-entry failures in tearDown to keep tests self-isolating.
    }
  }

  // ---- changeMaximumAmountOfMemory ----

  /**
   * {@code changeMaximumAmountOfMemory(newMax)} must update the eviction policy's maximum size
   * so subsequent loads are evicted once the new threshold is crossed.
   *
   * <p>{@link WTinyLFUPolicy#setMaxSize(int)} throws {@link IllegalStateException} when the
   * requested limit is smaller than the number of entries already present. Therefore the test
   * reduces the limit only after ensuring the current entry count does not exceed the new limit:
   * load 2 pages, reduce the maximum to 2 pages (2 ≤ 2 — allowed), then load 20 additional
   * distinct pages to drive eviction and confirm that used memory stays at or below the new limit.
   */
  @Test
  public void testChangeMaximumAmountOfMemoryReducesLimit() {
    // Pre-load 2 pages — exactly at the new limit, so setMaxSize(2) is permitted.
    for (int i = 0; i < 2; i++) {
      final var entry = readCache.loadForRead(0, i, writeCache, false);
      readCache.releaseFromRead(entry);
    }
    Assert.assertEquals("sanity: 2 pages must be in cache before resize",
        2L * PAGE_SIZE, readCache.getUsedMemory());

    // Reduce the maximum to 2 pages (current count == new limit — just within the allowed range).
    readCache.changeMaximumAmountOfMemory(2L * PAGE_SIZE);

    // Load 20 more distinct pages to drive eviction well past the new limit.
    for (int i = 2; i < 22; i++) {
      final var entry = readCache.loadForRead(0, i, writeCache, false);
      readCache.releaseFromRead(entry);
    }

    // The used memory must not exceed 2 pages now that the policy limit has been applied.
    Assert.assertTrue(
        "After changeMaximumAmountOfMemory(2 pages) + 20 additional loads, "
            + "used memory must be ≤ 2 pages; actual=" + readCache.getUsedMemory(),
        readCache.getUsedMemory() <= 2L * PAGE_SIZE);

    readCache.assertSize();
    readCache.assertConsistency();
  }

  /**
   * {@code changeMaximumAmountOfMemory()} with a value larger than the current cache size must
   * be accepted without error and must not evict any existing entries.
   */
  @Test
  public void testChangeMaximumAmountOfMemoryIncreaseIsAccepted() {
    // Load 10 pages.
    for (int i = 0; i < 10; i++) {
      final var entry = readCache.loadForRead(0, i, writeCache, false);
      readCache.releaseFromRead(entry);
    }
    final long usedBefore = readCache.getUsedMemory();
    Assert.assertEquals("sanity: 10 pages loaded", 10L * PAGE_SIZE, usedBefore);

    // Increase the limit — must not throw and must not evict.
    readCache.changeMaximumAmountOfMemory(2048L * PAGE_SIZE);
    Assert.assertEquals(
        "changeMaximumAmountOfMemory(larger) must not evict existing entries",
        usedBefore, readCache.getUsedMemory());

    readCache.assertSize();
    readCache.assertConsistency();
  }

  // ---- truncateFile ----

  /**
   * {@code truncateFile(fileId, writeCache)} must:
   * <ol>
   *   <li>Remove all cached pages for {@code fileId} from the read cache.</li>
   *   <li>Leave cached pages for other file IDs untouched.</li>
   *   <li>Invoke {@code writeCache.truncateFile(fileId)} exactly once.</li>
   * </ol>
   */
  @Test
  public void testTruncateFileEvictsCacheEntriesForTargetFile() throws IOException {
    // Load 3 pages for file 0 and 2 pages for file 1.
    for (int i = 0; i < 3; i++) {
      readCache.releaseFromRead(readCache.loadForRead(0, i, writeCache, false));
    }
    for (int i = 0; i < 2; i++) {
      readCache.releaseFromRead(readCache.loadForRead(1, i, writeCache, false));
    }
    Assert.assertEquals("sanity: 5 pages in cache", 5L * PAGE_SIZE, readCache.getUsedMemory());
    Assert.assertEquals("sanity: truncateFile not called yet", 0, writeCache.truncateCount.get());

    // Truncate file 0.
    readCache.truncateFile(0, writeCache);

    // File 0's 3 pages must be evicted; file 1's 2 pages must remain.
    Assert.assertEquals(
        "After truncateFile(0), only file 1's 2 pages must remain in cache",
        2L * PAGE_SIZE, readCache.getUsedMemory());
    Assert.assertEquals(
        "truncateFile must invoke writeCache.truncateFile exactly once",
        1, writeCache.truncateCount.get());

    readCache.assertSize();
    readCache.assertConsistency();
  }

  // ---- closeFile ----

  /**
   * {@code closeFile(fileId, flush, writeCache)} must:
   * <ol>
   *   <li>Remove all cached pages for {@code fileId}.</li>
   *   <li>Invoke {@code writeCache.close(fileId, flush)} exactly once.</li>
   *   <li>Leave other files' cached pages untouched.</li>
   * </ol>
   */
  @Test
  public void testCloseFileEvictsCacheEntriesAndDelegatesClose() {
    // Load 2 pages for file 10 and 3 pages for file 11.
    for (int i = 0; i < 2; i++) {
      readCache.releaseFromRead(readCache.loadForRead(10, i, writeCache, false));
    }
    for (int i = 0; i < 3; i++) {
      readCache.releaseFromRead(readCache.loadForRead(11, i, writeCache, false));
    }
    Assert.assertEquals("sanity: 5 pages in cache", 5L * PAGE_SIZE, readCache.getUsedMemory());

    // Close file 10 (flush=true is passed through).
    readCache.closeFile(10, true, writeCache);

    // File 10's 2 pages must be evicted; file 11's 3 pages must remain.
    Assert.assertEquals(
        "After closeFile(10), only file 11's 3 pages must remain",
        3L * PAGE_SIZE, readCache.getUsedMemory());
    Assert.assertEquals("writeCache.close(fileId, flush) must be called once",
        1, writeCache.closeFileCount.get());
    // Pin the exact fileId — a regression that passed any non-negative ID (e.g., always 0)
    // would have slipped through the previous weak >= 0 check.
    Assert.assertEquals("closeFile must pass the fileId that was used (10)",
        10L, writeCache.lastClosedFileId);

    readCache.assertSize();
    readCache.assertConsistency();
  }

  // ---- deleteFile ----

  /**
   * {@code deleteFile(fileId, writeCache)} must:
   * <ol>
   *   <li>Remove all cached pages for {@code fileId}.</li>
   *   <li>Invoke {@code writeCache.deleteFile(fileId)} exactly once.</li>
   *   <li>Leave other files' cached pages untouched.</li>
   * </ol>
   */
  @Test
  public void testDeleteFileEvictsCacheEntriesAndDelegatesDelete() throws IOException {
    // Load 4 pages for file 20, 1 page for file 21.
    for (int i = 0; i < 4; i++) {
      readCache.releaseFromRead(readCache.loadForRead(20, i, writeCache, false));
    }
    readCache.releaseFromRead(readCache.loadForRead(21, 0, writeCache, false));
    Assert.assertEquals("sanity: 5 pages in cache", 5L * PAGE_SIZE, readCache.getUsedMemory());

    readCache.deleteFile(20, writeCache);

    Assert.assertEquals(
        "After deleteFile(20), only file 21's 1 page must remain",
        1L * PAGE_SIZE, readCache.getUsedMemory());
    Assert.assertEquals("writeCache.deleteFile() must be called exactly once",
        1, writeCache.deleteFileCount.get());

    readCache.assertSize();
    readCache.assertConsistency();
  }

  /**
   * {@code deleteFile()} on a file with no cached pages must still invoke
   * {@code writeCache.deleteFile()} exactly once (the file-system operation must always run).
   */
  @Test
  public void testDeleteFileOnUncachedFileStillDelegates() throws IOException {
    Assert.assertEquals("sanity: cache is empty", 0, readCache.getUsedMemory());

    readCache.deleteFile(99, writeCache);

    Assert.assertEquals(
        "deleteFile on an uncached file must still invoke writeCache.deleteFile()",
        1, writeCache.deleteFileCount.get());
    Assert.assertEquals("cache must remain empty", 0, readCache.getUsedMemory());
  }

  // ---- silentLoadForRead ----

  /**
   * {@code silentLoadForRead()} must return a valid cache entry for a page that exists in the
   * WriteCache. The returned entry must be acquirable (its use-count is incremented by the call),
   * and the fileId / pageIndex getters must match the requested coordinates.
   *
   * <p>Unlike {@code loadForRead()}, {@code silentLoadForRead()} does NOT add the entry to the
   * eviction policy — it stores the new entry in a separate array and returns it directly.
   * The test just verifies the entry is non-null and has the correct coordinates.
   */
  @Test
  public void testSilentLoadForReadReturnsCacheEntryOnMiss() {
    // silentLoadForRead takes (extFileId, pageIndex, writeCache, verifyChecksums).
    // The extFileId must be the external file ID (composed with writeCache.getId() = 0).
    final var entry = readCache.silentLoadForRead(0, 5, writeCache, false);

    Assert.assertNotNull("silentLoadForRead must return a non-null entry for a valid page", entry);
    Assert.assertEquals("returned entry must have the requested page index",
        5, entry.getPageIndex());

    // Release the entry to prevent pinned-entry tearDown failures.
    readCache.releaseFromRead(entry);
  }

  /**
   * {@code silentLoadForRead()} on a page for which the WriteCache returns null (the page does
   * not exist in the storage) must return null without throwing.
   */
  @Test
  public void testSilentLoadForReadReturnsNullWhenWriteCacheReturnsNull() {
    // Use a negative pageIndex as a sentinel — TrackingWriteCache.load() returns null for it.
    final var entry = readCache.silentLoadForRead(0, TrackingWriteCache.NULL_PAGE_INDEX,
        writeCache, false);
    Assert.assertNull(
        "silentLoadForRead must return null when WriteCache.load() returns null", entry);
  }

  // ---- Minimal WriteCache stub ----

  /**
   * A WriteCache stub that tracks which file-lifecycle methods were called and allows
   * {@code load()} to return a page frame for valid page indices or null for the sentinel
   * {@link #NULL_PAGE_INDEX}.
   */
  private static final class TrackingWriteCache implements WriteCache {

    /** Page index sentinel that causes {@code load()} to return {@code null}. */
    static final int NULL_PAGE_INDEX = Integer.MIN_VALUE;

    private final ByteBufferPool byteBufferPool;

    final AtomicInteger truncateCount = new AtomicInteger();
    final AtomicInteger closeFileCount = new AtomicInteger();
    final AtomicInteger deleteFileCount = new AtomicInteger();
    volatile long lastClosedFileId = -1;

    TrackingWriteCache(final ByteBufferPool byteBufferPool) {
      this.byteBufferPool = byteBufferPool;
    }

    @Override
    public CachePointer load(
        final long fileId,
        final long startPageIndex,
        final ModifiableBoolean cacheHit,
        final boolean verifyChecksums) {
      if ((int) startPageIndex == NULL_PAGE_INDEX) {
        return null;
      }
      final var pointer = byteBufferPool.acquireDirect(true, Intention.TEST);
      final var cachePointer =
          new CachePointer(pointer, byteBufferPool, fileId, (int) startPageIndex);
      cachePointer.incrementReadersReferrer();
      return cachePointer;
    }

    @Override
    public void truncateFile(final long fileId) {
      truncateCount.incrementAndGet();
    }

    @Override
    public void close(final long fileId, final boolean flush) {
      closeFileCount.incrementAndGet();
      lastClosedFileId = fileId;
    }

    @Override
    public void deleteFile(final long fileId) {
      deleteFileCount.incrementAndGet();
    }

    @Override
    public int getId() {
      return 0;
    }

    @Override
    public void addPageIsBrokenListener(final PageIsBrokenListener listener) {
    }

    @Override
    public void removePageIsBrokenListener(final PageIsBrokenListener listener) {
    }

    @Override
    public long bookFileId(final String fileName) {
      return 0;
    }

    @Override
    public long loadFile(final String fileName) {
      return 0;
    }

    @Override
    public long addFile(final String fileName) {
      return 0;
    }

    @Override
    public long addFile(final String fileName, final long fileId) {
      return 0;
    }

    @Override
    public long fileIdByName(final String fileName) {
      return 0;
    }

    @Override
    public boolean checkLowDiskSpace() {
      return false;
    }

    @Override
    public void syncDataFiles(long segmentId) {
    }

    @Override
    public void flushTillSegment(final long segmentId) {
    }

    @Override
    public boolean exists(final String fileName) {
      return false;
    }

    @Override
    public boolean exists(final long fileId) {
      return false;
    }

    @Override
    public void restoreModeOn() {
    }

    @Override
    public void restoreModeOff() {
    }

    @Override
    public void store(
        final long fileId, final long pageIndex, final CachePointer dataPointer) {
    }

    @Override
    public void checkCacheOverflow() {
    }

    @Override
    public int allocateNewPage(final long fileId) {
      return 0;
    }

    @Override
    public void flush(final long fileId) {
    }

    @Override
    public void flush() {
    }

    @Override
    public long getFilledUpTo(final long fileId) {
      return 0;
    }

    @Override
    public long getExclusiveWriteCachePagesSize() {
      return 0;
    }

    @Override
    public void renameFile(final long fileId, final String newFileName) {
    }

    @Override
    public long[] close() {
      return new long[0];
    }

    @Override
    public PageDataVerificationError[] checkStoredPages(
        final CommandOutputListener commandOutputListener) {
      return new PageDataVerificationError[0];
    }

    @Override
    public long[] delete() {
      return new long[0];
    }

    @Override
    public String fileNameById(final long fileId) {
      return null;
    }

    @Override
    public String nativeFileNameById(final long fileId) {
      return null;
    }

    @Override
    public Map<String, Long> files() {
      return null;
    }

    @Override
    public int pageSize() {
      return 0;
    }

    @Override
    public String restoreFileById(final long fileId) {
      return null;
    }

    @Override
    public void addBackgroundExceptionListener(final BackgroundExceptionListener listener) {
    }

    @Override
    public void removeBackgroundExceptionListener(
        final BackgroundExceptionListener listener) {
    }

    @Override
    public Path getRootDirectory() {
      return null;
    }

    @Override
    public int internalFileId(final long fileId) {
      return 0;
    }

    @Override
    public long externalFileId(final int fileId) {
      return 0;
    }

    @Override
    public boolean fileIdsAreEqual(long firsId, long secondId) {
      return false;
    }

    @Override
    public Long getMinimalNotFlushedSegment() {
      return null;
    }

    @Override
    public void updateDirtyPagesTable(
        final CachePointer pointer, final LogSequenceNumber startLSN) {
    }

    @Override
    public void create() {
    }

    @Override
    public void open() throws IOException {
    }

    @Override
    public void replaceFileId(long fileId, long newFileId) {
    }

    @Override
    public String getStorageName() {
      return null;
    }
  }
}
