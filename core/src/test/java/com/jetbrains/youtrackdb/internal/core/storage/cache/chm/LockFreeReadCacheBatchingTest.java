package com.jetbrains.youtrackdb.internal.core.storage.cache.chm;

import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.Ratio;
import com.jetbrains.youtrackdb.internal.common.types.ModifiableBoolean;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrackdb.internal.core.command.CommandOutputListener;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.FileHandler;
import com.jetbrains.youtrackdb.internal.core.storage.cache.PageDataVerificationError;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.BackgroundExceptionListener;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.PageIsBrokenListener;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the read batch accumulation mechanism in LockFreeReadCache.
 *
 * <p>LockFreeReadCache accumulates afterRead() events in a thread-local ReadBatch array before
 * flushing them to the striped read buffer. The batch is flushed when it reaches
 * {@code READ_BATCH_SIZE} (16) entries, or when an operation that needs the eviction policy
 * to be fully up-to-date is called (clear, assertSize, assertConsistency, clearFile).
 *
 * <p>These tests verify that entries are correctly accumulated across doLoad calls, flushed at
 * the batch-size boundary, and that cache consistency and eviction correctness are maintained.
 */
public class LockFreeReadCacheBatchingTest {

  private static final int PAGE_SIZE = 4 * 1024;

  private DirectMemoryAllocator allocator;
  private ByteBufferPool bufferPool;
  private LockFreeReadCache readCache;
  private WriteCache writeCache;
  private FileHandler fileHandler;

  @Before
  public void setUp() {
    allocator = new DirectMemoryAllocator();
    bufferPool = new ByteBufferPool(PAGE_SIZE, allocator, 256);
    // 1024 pages = 4 MB cache
    long maxMemory = 1024L * PAGE_SIZE;
    readCache = new LockFreeReadCache(bufferPool, maxMemory, PAGE_SIZE);
    writeCache = new MockedWriteCache(bufferPool);
    fileHandler = new FileHandler(0);
  }

  @After
  public void tearDown() {
    readCache.clear();
  }

  /**
   * A cache hit via afterRead() should accumulate the entry in the thread-local ReadBatch
   * instead of immediately offering it to the read buffer. After a single cache hit the batch
   * should hold exactly one entry.
   */
  @Test
  public void testCacheHitAccumulatesInBatch() throws Exception {
    // Load page 0 for the first time (cache miss, goes through afterAdd)
    var entry = readCache.loadForRead(fileHandler, 0, writeCache, false);
    readCache.releaseFromRead(entry);

    // Re-load page 0 (cache hit, goes through afterRead)
    entry = readCache.loadForRead(fileHandler, 0, writeCache, false);
    readCache.releaseFromRead(entry);

    // The afterRead entry should still be in the thread-local batch
    var batchSize = getThreadLocalBatchSize();
    Assert.assertEquals(
        "After a single cache hit, one entry should be accumulated in the batch",
        1, batchSize);
  }

  /**
   * Multiple cache hits should accumulate in the batch across doLoad calls. The batch is NOT
   * flushed by checkWriteBuffer, so consecutive cache hits grow the batch until it reaches
   * READ_BATCH_SIZE.
   */
  @Test
  public void testBatchAccumulatesAcrossMultipleDoLoadCalls() throws Exception {
    // Load several distinct pages (cache misses)
    var pageCount = 8;
    for (int i = 0; i < pageCount; i++) {
      var entry = readCache.loadForRead(fileHandler, i, writeCache, false);
      readCache.releaseFromRead(entry);
    }

    // Re-read all pages (cache hits) — each afterRead adds to batch
    for (int i = 0; i < pageCount; i++) {
      var entry = readCache.loadForRead(fileHandler, i, writeCache, false);
      readCache.releaseFromRead(entry);
    }

    // Batch should have accumulated all 8 cache-hit entries
    var batchSize = getThreadLocalBatchSize();
    Assert.assertEquals(
        "Batch should accumulate entries across doLoad calls",
        pageCount, batchSize);
  }

  /**
   * When the batch reaches READ_BATCH_SIZE (16), it is flushed to the striped buffer. After
   * the flush, all 16 slots should be nulled out to release stale references, and the batch
   * size should reset to 0.
   */
  @Test
  public void testBatchFlushesAtCapacityAndNullsSlots() throws Exception {
    // READ_BATCH_SIZE = 16. Load 16 distinct pages (misses).
    var batchCapacity = 16;
    for (int i = 0; i < batchCapacity; i++) {
      var entry = readCache.loadForRead(fileHandler, i, writeCache, false);
      readCache.releaseFromRead(entry);
    }

    // Re-read all 16 pages (cache hits). The 16th afterRead triggers flushReadBatch.
    for (int i = 0; i < batchCapacity; i++) {
      var entry = readCache.loadForRead(fileHandler, i, writeCache, false);
      readCache.releaseFromRead(entry);
    }

    // After exactly READ_BATCH_SIZE hits, the batch should have been flushed
    var batchSize = getThreadLocalBatchSize();
    Assert.assertEquals("Batch should be empty after flushing at capacity", 0, batchSize);

    // All slots should be null after flush
    var batchEntries = getThreadLocalBatchEntries();
    for (int i = 0; i < batchEntries.length; i++) {
      Assert.assertNull("Batch slot " + i + " should be null after flush", batchEntries[i]);
    }
  }

  /**
   * After a flush at capacity, subsequent cache hits should start accumulating in the batch
   * again from index 0. This tests the full flush-then-reuse cycle.
   */
  @Test
  public void testBatchReusedAfterFlush() throws Exception {
    var batchCapacity = 16;
    // Load 20 distinct pages (misses)
    for (int i = 0; i < 20; i++) {
      var entry = readCache.loadForRead(fileHandler, i, writeCache, false);
      readCache.releaseFromRead(entry);
    }

    // Re-read 20 pages: first 16 hits fill and flush the batch,
    // next 4 hits start accumulating in the fresh batch
    for (int i = 0; i < 20; i++) {
      var entry = readCache.loadForRead(fileHandler, i, writeCache, false);
      readCache.releaseFromRead(entry);
    }

    var batchSize = getThreadLocalBatchSize();
    Assert.assertEquals(
        "After 20 hits (16 flushed + 4 new), batch should have 4 entries",
        20 - batchCapacity, batchSize);

    // Slots 0..3 should be non-null, slots 4..15 should be null
    var batchEntries = getThreadLocalBatchEntries();
    for (int i = 0; i < batchSize; i++) {
      Assert.assertNotNull("Active batch slot " + i + " should not be null", batchEntries[i]);
    }
    for (int i = batchSize; i < batchEntries.length; i++) {
      Assert.assertNull("Inactive batch slot " + i + " should be null", batchEntries[i]);
    }
  }

  /**
   * Repeated cache hits over many pages must maintain cache policy consistency. All batched
   * afterRead events are eventually delivered to the eviction policy so assertSize and
   * assertConsistency pass.
   */
  @Test
  public void testRepeatedCacheHitsMaintainConsistency() {
    var pageCount = 64;

    // Load pages (all cache misses)
    for (int i = 0; i < pageCount; i++) {
      var entry = readCache.loadForRead(fileHandler, i, writeCache, false);
      readCache.releaseFromRead(entry);
    }

    // Re-read each page 4 times (all cache hits, go through afterRead → batch)
    for (int round = 0; round < 4; round++) {
      for (int i = 0; i < pageCount; i++) {
        var entry = readCache.loadForRead(fileHandler, i, writeCache, false);
        readCache.releaseFromRead(entry);
      }
    }

    readCache.assertSize();
    readCache.assertConsistency();
  }

  /**
   * Eviction must work correctly when afterRead events are batched. Loading more distinct pages
   * than the cache capacity triggers eviction; the policy should still receive access events
   * and keep the cache within its memory bounds.
   */
  @Test
  public void testEvictionWorksCorrectlyWithBatching() {
    // Cache holds 1024 pages; load 1100 to trigger eviction.
    var pageCount = 1100;

    for (int i = 0; i < pageCount; i++) {
      var entry = readCache.loadForRead(fileHandler, i, writeCache, false);
      readCache.releaseFromRead(entry);
    }

    readCache.assertSize();
    readCache.assertConsistency();

    long maxMemory = 1024L * PAGE_SIZE;
    Assert.assertTrue(
        "Used memory (" + readCache.getUsedMemory()
            + ") should not exceed max memory (" + maxMemory + ")",
        readCache.getUsedMemory() <= maxMemory);
  }

  /**
   * clear() must flush the current thread's pending read batch before clearing the cache.
   * This ensures LRU events are delivered to the policy and the batch does not hold stale
   * references to cleared entries.
   */
  @Test
  public void testClearFlushesPendingBatchEntries() throws Exception {
    // Load several pages (misses)
    for (int i = 0; i < 5; i++) {
      var entry = readCache.loadForRead(fileHandler, i, writeCache, false);
      readCache.releaseFromRead(entry);
    }

    // Cache hits → afterRead accumulates 5 entries in batch
    for (int i = 0; i < 5; i++) {
      var entry = readCache.loadForRead(fileHandler, i, writeCache, false);
      readCache.releaseFromRead(entry);
    }

    Assert.assertEquals("Batch should have 5 entries before clear",
        5, getThreadLocalBatchSize());

    // clear() should flush the batch first, then clear the cache
    readCache.clear();

    Assert.assertEquals("Batch should be empty after clear",
        0, getThreadLocalBatchSize());
    Assert.assertEquals("Cache should be empty after clear",
        0, readCache.getUsedMemory());

    // Cache should still work after clear — use a fresh FileHandler because the old
    // one's CASObjectArray still contains frozen entries from the cleared cache.
    var freshHandler = new FileHandler(0);
    var entry = readCache.loadForRead(freshHandler, 0, writeCache, false);
    Assert.assertNotNull("Should be able to load pages after clear", entry);
    readCache.releaseFromRead(entry);

    readCache.assertSize();
    readCache.assertConsistency();
  }

  /**
   * Memory tracking must remain correct after many batched read events. After clearing the
   * cache, all memory should be reclaimed.
   */
  @Test
  public void testMemoryTrackingWithBatching() {
    var pageCount = 128;

    // Load pages
    for (int i = 0; i < pageCount; i++) {
      var entry = readCache.loadForRead(fileHandler, i, writeCache, false);
      readCache.releaseFromRead(entry);
    }

    // Re-read to generate afterRead events
    for (int round = 0; round < 3; round++) {
      for (int i = 0; i < pageCount; i++) {
        var entry = readCache.loadForRead(fileHandler, i, writeCache, false);
        readCache.releaseFromRead(entry);
      }
    }

    Assert.assertEquals(
        "Used memory should equal pageCount * pageSize",
        (long) pageCount * PAGE_SIZE,
        readCache.getUsedMemory());

    // Allocator memory minus pool overhead should match cache memory
    Assert.assertEquals(
        allocator.getMemoryConsumption() - (long) bufferPool.getPoolSize() * PAGE_SIZE,
        readCache.getUsedMemory());

    readCache.clear();
    Assert.assertEquals(
        "Cache memory should be zero after clear", 0, readCache.getUsedMemory());
  }

  /**
   * Multiple threads loading and re-reading overlapping pages concurrently must not cause
   * inconsistencies. Each thread has its own thread-local ReadBatch so there should be no
   * cross-thread interference.
   */
  @Test
  public void testConcurrentReadsWithBatching() throws Exception {
    var pageCount = 200;
    var threadCount = 4;
    var readsPerThread = 2000;

    // Pre-load pages
    for (int i = 0; i < pageCount; i++) {
      var entry = readCache.loadForRead(fileHandler, i, writeCache, false);
      readCache.releaseFromRead(entry);
    }

    var executor = Executors.newFixedThreadPool(threadCount);
    List<Future<Void>> futures = new ArrayList<>();

    for (int t = 0; t < threadCount; t++) {
      futures.add(executor.submit(() -> {
        var rng = ThreadLocalRandom.current();
        for (int i = 0; i < readsPerThread; i++) {
          var pageIndex = rng.nextInt(pageCount);
          var entry = readCache.loadForRead(fileHandler, pageIndex, writeCache, false);
          readCache.releaseFromRead(entry);
        }
        return null;
      }));
    }

    for (var future : futures) {
      future.get();
    }

    executor.shutdown();

    readCache.assertSize();
    readCache.assertConsistency();
  }

  /**
   * loadForWrite also calls doLoad internally, so cache hits through loadForWrite should
   * correctly interact with the batching mechanism (afterRead is called on hits).
   */
  @Test
  public void testLoadForWriteWithBatching() {
    // Load page (miss)
    var entry = readCache.loadForRead(fileHandler, 0, writeCache, false);
    readCache.releaseFromRead(entry);

    // Write-load the same page (cache hit, goes through afterRead)
    entry = readCache.loadForWrite(fileHandler, 0, writeCache, false, null);
    readCache.releaseFromWrite(entry, writeCache, false);

    // Read-load again (cache hit)
    entry = readCache.loadForRead(fileHandler, 0, writeCache, false);
    readCache.releaseFromRead(entry);

    readCache.assertSize();
    readCache.assertConsistency();
  }

  /**
   * High cache churn (many more distinct pages than cache capacity) with batched read events
   * must not cause memory leaks or policy inconsistencies.
   */
  @Test
  public void testHighChurnEvictionWithBatching() {
    // Cache holds 1024 pages; load 3000 distinct pages.
    for (int i = 0; i < 3000; i++) {
      var entry = readCache.loadForRead(fileHandler, i, writeCache, false);
      readCache.releaseFromRead(entry);
    }

    readCache.assertSize();
    readCache.assertConsistency();

    long maxMemory = 1024L * PAGE_SIZE;
    Assert.assertTrue(
        "Used memory should not exceed max memory after high churn",
        readCache.getUsedMemory() <= maxMemory);

    // Allocator memory minus pool overhead should match cache memory
    Assert.assertEquals(
        allocator.getMemoryConsumption() - (long) bufferPool.getPoolSize() * PAGE_SIZE,
        readCache.getUsedMemory());
  }

  /**
   * A mix of cache hits and misses interleaved must maintain cache consistency. This exercises
   * the interaction between afterRead (batched) and afterAdd (immediate via writeBuffer).
   */
  @Test
  public void testInterleavedHitsAndMissesMaintainConsistency() {
    // Load 10 pages initially
    for (int i = 0; i < 10; i++) {
      var entry = readCache.loadForRead(fileHandler, i, writeCache, false);
      readCache.releaseFromRead(entry);
    }

    // Interleave: re-read existing page (hit, afterRead), then load new page (miss, afterAdd)
    for (int i = 10; i < 100; i++) {
      // Cache hit on an existing page
      var hitEntry = readCache.loadForRead(fileHandler, i % 10, writeCache, false);
      readCache.releaseFromRead(hitEntry);

      // Cache miss on a new page
      var missEntry = readCache.loadForRead(fileHandler, i, writeCache, false);
      readCache.releaseFromRead(missEntry);
    }

    readCache.assertSize();
    readCache.assertConsistency();
  }

  // --- resolveCacheHitRatio tests ---

  /**
   * When MetricsRegistry is null (early engine startup before the profiler is initialized),
   * resolveCacheHitRatio must return Ratio.NOOP so the cache can still be constructed.
   */
  @Test
  public void testResolveCacheHitRatioReturnsNoopForNullRegistry() {
    var ratio = LockFreeReadCache.resolveCacheHitRatio(null);
    Assert.assertSame(
        "Null registry should produce Ratio.NOOP", Ratio.NOOP, ratio);
  }

  /**
   * When MetricsRegistry is available (normal startup), resolveCacheHitRatio must return a real
   * Ratio instance from the registry, not NOOP.
   */
  @Test
  public void testResolveCacheHitRatioReturnsRealRatioForNonNullRegistry() {
    var registry = YouTrackDBEnginesManager.instance().getMetricsRegistry();
    // Skip (not silently pass) if the profiler is not initialized in this JVM.
    Assume.assumeNotNull(registry);

    var ratio = LockFreeReadCache.resolveCacheHitRatio(registry);
    Assert.assertNotNull("Non-null registry should produce a non-null Ratio", ratio);
    Assert.assertNotSame(
        "Non-null registry should not produce Ratio.NOOP", Ratio.NOOP, ratio);
  }

  // --- Reflection helpers for accessing the thread-local ReadBatch ---

  /**
   * Returns the current thread's ReadBatch size via reflection.
   */
  private int getThreadLocalBatchSize() throws Exception {
    var readBatchField = LockFreeReadCache.class.getDeclaredField("readBatch");
    readBatchField.setAccessible(true);
    @SuppressWarnings("unchecked")
    var threadLocal = (ThreadLocal<?>) readBatchField.get(readCache);
    var batch = threadLocal.get();

    var sizeField = batch.getClass().getDeclaredField("size");
    sizeField.setAccessible(true);
    return sizeField.getInt(batch);
  }

  /**
   * Returns the current thread's ReadBatch entries array via reflection.
   */
  private CacheEntry[] getThreadLocalBatchEntries() throws Exception {
    var readBatchField = LockFreeReadCache.class.getDeclaredField("readBatch");
    readBatchField.setAccessible(true);
    @SuppressWarnings("unchecked")
    var threadLocal = (ThreadLocal<?>) readBatchField.get(readCache);
    var batch = threadLocal.get();

    var entriesField = batch.getClass().getDeclaredField("entries");
    entriesField.setAccessible(true);
    return (CacheEntry[]) entriesField.get(batch);
  }

  // --- MockedWriteCache (same pattern as AsyncReadCacheTestIT) ---

  private record MockedWriteCache(ByteBufferPool byteBufferPool) implements WriteCache {

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
    public FileHandler loadFile(final String fileName) {
      return new FileHandler(0);
    }

    @Override
    public FileHandler addFile(final String fileName) {
      return new FileHandler(0);
    }

    @Override
    public FileHandler addFile(final String fileName, final long fileId) {
      return new FileHandler(fileId);
    }

    @Override
    public FileHandler fileHandlerByName(final String fileName) {
      return new FileHandler(0);
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
    public void close(final FileHandler fileHandler, final boolean flush) {
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
    public Map<String, FileHandler> files() {
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
