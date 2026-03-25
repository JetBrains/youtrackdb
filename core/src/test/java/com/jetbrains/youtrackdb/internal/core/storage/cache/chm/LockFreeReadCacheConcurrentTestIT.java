package com.jetbrains.youtrackdb.internal.core.storage.cache.chm;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.common.types.ModifiableBoolean;
import com.jetbrains.youtrackdb.internal.core.command.CommandOutputListener;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.PageDataVerificationError;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.BackgroundExceptionListener;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.PageIsBrokenListener;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Concurrent stress tests for {@link LockFreeReadCache} with the {@link
 * com.jetbrains.youtrackdb.internal.common.collection.ConcurrentLongIntHashMap} backing store.
 * Validates that the integrated cache (map + eviction policy + read/write buffers) behaves
 * correctly under concurrent load and file deletion.
 *
 * <p>Uses {@link MockedWriteCache} (no disk I/O) and {@code @Category(SequentialTest.class)} to
 * avoid CI flakiness from parallel test execution.
 */
@Category(SequentialTest.class)
public class LockFreeReadCacheConcurrentTestIT {

  private static final int PAGE_SIZE = 4 * 1024; // 4KB pages
  // 4MB cache — forces frequent eviction with ~1024 pages capacity
  private static final long MAX_MEMORY = 4L * 1024 * 1024;

  /** Creates cache + write cache fixture for tests. */
  private record CacheFixture(
      LockFreeReadCache readCache, WriteCache writeCache, ByteBufferPool byteBufferPool) {
  }

  private static CacheFixture createCacheFixture() {
    var allocator = new DirectMemoryAllocator();
    var byteBufferPool = new ByteBufferPool(PAGE_SIZE, allocator, 256);
    var readCache = new LockFreeReadCache(byteBufferPool, MAX_MEMORY, PAGE_SIZE);
    var writeCache = new MockedWriteCache(byteBufferPool);
    return new CacheFixture(readCache, writeCache, byteBufferPool);
  }

  /**
   * Concurrent reads + writes with eviction on a small cache. Multiple threads call
   * loadForRead/loadForWrite on a 4MB cache (small enough to force frequent eviction). Verifies: no
   * exceptions, no data corruption, cache internal consistency after all threads complete, and that
   * memory usage stays within bounds.
   */
  @Test
  public void concurrentReadsAndWritesWithEviction() throws Exception {
    final var fixture = createCacheFixture();
    final var readCache = fixture.readCache();
    final WriteCache writeCache = fixture.writeCache();

    int fileCount = 4;
    int pageLimit = 1000; // 4 files x 1000 pages = 4000 pages, but cache fits ~1024
    int opsPerThread = 50_000;
    int totalThreads = 8; // 4 readers + 4 writers
    var executor = Executors.newFixedThreadPool(totalThreads);
    var futures = new ArrayList<Future<Void>>();
    var startBarrier = new CyclicBarrier(totalThreads);

    try {
      // 4 reader threads
      for (int t = 0; t < 4; t++) {
        futures.add(
            executor.submit(
                () -> {
                  startBarrier.await();
                  var rng = ThreadLocalRandom.current();
                  for (int i = 0; i < opsPerThread; i++) {
                    int fileId = rng.nextInt(fileCount);
                    int pageIndex = rng.nextInt(pageLimit);
                    var cacheEntry =
                        readCache.loadForRead(fileId, pageIndex, writeCache, true);
                    assertThat(cacheEntry).isNotNull();
                    assertThat(cacheEntry.getFileId()).isEqualTo(fileId);
                    assertThat(cacheEntry.getPageIndex()).isEqualTo(pageIndex);
                    assertThat(cacheEntry.getCachePointer()).isNotNull();
                    readCache.releaseFromRead(cacheEntry);
                  }
                  return null;
                }));
      }

      // 4 writer threads
      for (int t = 0; t < 4; t++) {
        futures.add(
            executor.submit(
                () -> {
                  startBarrier.await();
                  var rng = ThreadLocalRandom.current();
                  for (int i = 0; i < opsPerThread; i++) {
                    int fileId = rng.nextInt(fileCount);
                    int pageIndex = rng.nextInt(pageLimit);
                    var cacheEntry =
                        readCache.loadForWrite(fileId, pageIndex, writeCache, true, null);
                    assertThat(cacheEntry).isNotNull();
                    assertThat(cacheEntry.getFileId()).isEqualTo(fileId);
                    assertThat(cacheEntry.getPageIndex()).isEqualTo(pageIndex);
                    assertThat(cacheEntry.getCachePointer()).isNotNull();
                    readCache.releaseFromWrite(cacheEntry, writeCache, true);
                  }
                  return null;
                }));
      }

      for (var future : futures) {
        future.get(60, TimeUnit.SECONDS);
      }
    } finally {
      executor.shutdownNow();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }

    // Post-run validation: cache internal consistency
    try {
      readCache.assertSize();
      readCache.assertConsistency();
      assertThat(readCache.getUsedMemory())
          .as("cache should have entries after concurrent operations")
          .isGreaterThan(0);
      assertThat(readCache.getUsedMemory())
          .as("used memory within cache limit")
          .isLessThanOrEqualTo(MAX_MEMORY);
    } finally {
      readCache.clear();
    }
    assertThat(readCache.getUsedMemory()).isEqualTo(0);
  }

  /**
   * Concurrent readers on multiple files, followed by file deletion and consistency check. Runs
   * concurrent loadForRead across multiple files to stress the cache under heavy contention, then
   * stops readers and deletes all files one by one, verifying cache consistency at each stage.
   *
   * <p>deleteFile cannot be called while readers hold entries (StorageException: page is in use), so
   * readers are stopped before deletion. The test validates: (1) concurrent load/release does not
   * corrupt internal state, (2) deleteFile correctly clears each file's entries from the cache, (3)
   * final state is consistent and empty.
   */
  @Test
  public void deleteFileAfterConcurrentLoad() throws Exception {
    final var fixture = createCacheFixture();
    final var readCache = fixture.readCache();
    final WriteCache writeCache = fixture.writeCache();

    int fileCount = 4;
    int pageLimit = 500;
    int opsPerThread = 50_000;

    int readerCount = 8;
    var executor = Executors.newFixedThreadPool(readerCount);
    var futures = new ArrayList<Future<Void>>();
    var startBarrier = new CyclicBarrier(readerCount);

    try {
      // Run concurrent readers to stress cache with contention + eviction
      for (int t = 0; t < readerCount; t++) {
        futures.add(
            executor.submit(
                () -> {
                  startBarrier.await();
                  var rng = ThreadLocalRandom.current();
                  for (int i = 0; i < opsPerThread; i++) {
                    int fileId = rng.nextInt(fileCount);
                    int pageIndex = rng.nextInt(pageLimit);
                    var entry = readCache.loadForRead(fileId, pageIndex, writeCache, true);
                    assertThat(entry).isNotNull();
                    assertThat(entry.getFileId()).isEqualTo(fileId);
                    assertThat(entry.getPageIndex()).isEqualTo(pageIndex);
                    readCache.releaseFromRead(entry);
                  }
                  return null;
                }));
      }

      for (var future : futures) {
        future.get(60, TimeUnit.SECONDS);
      }
    } finally {
      executor.shutdownNow();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }

    // Cache should be consistent after concurrent load
    try {
      readCache.assertSize();
      readCache.assertConsistency();
      assertThat(readCache.getUsedMemory())
          .as("cache should have entries before deletion")
          .isGreaterThan(0);
      assertThat(readCache.getUsedMemory())
          .as("used memory within cache limit")
          .isLessThanOrEqualTo(MAX_MEMORY);

      // Delete all files one by one — exercises clearFile -> removeByFileId path.
      // Memory must not increase after each deletion.
      long previousMemory = readCache.getUsedMemory();
      for (int fId = 0; fId < fileCount; fId++) {
        readCache.deleteFile(fId, writeCache);
        long currentMemory = readCache.getUsedMemory();
        assertThat(currentMemory)
            .as("memory should not increase after deleting file %d", fId)
            .isLessThanOrEqualTo(previousMemory);
        readCache.assertConsistency();
        previousMemory = currentMemory;
      }

      // After all files deleted, cache should be empty and consistent
      assertThat(readCache.getUsedMemory())
          .as("cache should be empty after all files deleted")
          .isEqualTo(0);
      readCache.assertSize();
      readCache.assertConsistency();
    } finally {
      readCache.clear();
    }
  }

  /** Minimal WriteCache stub — allocates from ByteBufferPool, no disk I/O. */
  private record MockedWriteCache(ByteBufferPool byteBufferPool) implements WriteCache {

    @Override
    public CachePointer load(
        final long fileId,
        final long startPageIndex,
        final ModifiableBoolean cacheHit,
        final boolean verifyChecksums) {
      final var pointer = byteBufferPool.acquireDirect(true, Intention.TEST);
      final var cachePointer =
          new CachePointer(pointer, byteBufferPool, fileId, (int) startPageIndex);
      cachePointer.incrementReadersReferrer();
      return cachePointer;
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
    public void deleteFile(final long fileId) {
    }

    @Override
    public void truncateFile(final long fileId) {
    }

    @Override
    public void renameFile(final long fileId, final String newFileName) {
    }

    @Override
    public long[] close() {
      return new long[0];
    }

    @Override
    public void close(final long fileId, final boolean flush) {
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
    public int getId() {
      return 0;
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
