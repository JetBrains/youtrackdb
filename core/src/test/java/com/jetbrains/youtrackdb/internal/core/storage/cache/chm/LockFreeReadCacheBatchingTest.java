package com.jetbrains.youtrackdb.internal.core.storage.cache.chm;

import com.jetbrains.youtrackdb.internal.common.collection.ConcurrentLongIntHashMap;
import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.Ratio;
import com.jetbrains.youtrackdb.internal.common.types.ModifiableBoolean;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrackdb.internal.core.command.CommandOutputListener;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
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
import java.util.concurrent.atomic.AtomicInteger;
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
  private MockedWriteCache writeCache;

  @Before
  public void setUp() {
    allocator = new DirectMemoryAllocator();
    bufferPool = new ByteBufferPool(PAGE_SIZE, allocator, 256);
    // 1024 pages = 4 MB cache
    long maxMemory = 1024L * PAGE_SIZE;
    readCache = new LockFreeReadCache(bufferPool, maxMemory, PAGE_SIZE);
    writeCache = new MockedWriteCache(bufferPool);
  }

  @After
  public void tearDown() {
    // Tolerant of a cache that was already drained by closeStorage/deleteStorage, or left in
    // an un-clearable state by a freeze()-failure test (pinned/frozen entries). The direct
    // memory is reclaimed on GC or on process exit; individual tests are self-isolating.
    try {
      readCache.clear();
    } catch (Exception ignored) {
      // Expected when drainAllEntries aborted mid-way or the cache was already closed.
    }
  }

  /**
   * A cache hit via afterRead() should accumulate the entry in the thread-local ReadBatch
   * instead of immediately offering it to the read buffer. After a single cache hit the batch
   * should hold exactly one entry.
   */
  @Test
  public void testCacheHitAccumulatesInBatch() throws Exception {
    // Load page 0 for the first time (cache miss, goes through afterAdd)
    var entry = readCache.loadForRead(0, 0, writeCache, false);
    readCache.releaseFromRead(entry);

    // Re-load page 0 (cache hit, goes through afterRead)
    entry = readCache.loadForRead(0, 0, writeCache, false);
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
      var entry = readCache.loadForRead(0, i, writeCache, false);
      readCache.releaseFromRead(entry);
    }

    // Re-read all pages (cache hits) — each afterRead adds to batch
    for (int i = 0; i < pageCount; i++) {
      var entry = readCache.loadForRead(0, i, writeCache, false);
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
      var entry = readCache.loadForRead(0, i, writeCache, false);
      readCache.releaseFromRead(entry);
    }

    // Re-read all 16 pages (cache hits). The 16th afterRead triggers flushReadBatch.
    for (int i = 0; i < batchCapacity; i++) {
      var entry = readCache.loadForRead(0, i, writeCache, false);
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
      var entry = readCache.loadForRead(0, i, writeCache, false);
      readCache.releaseFromRead(entry);
    }

    // Re-read 20 pages: first 16 hits fill and flush the batch,
    // next 4 hits start accumulating in the fresh batch
    for (int i = 0; i < 20; i++) {
      var entry = readCache.loadForRead(0, i, writeCache, false);
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
      var entry = readCache.loadForRead(0, i, writeCache, false);
      readCache.releaseFromRead(entry);
    }

    // Re-read each page 4 times (all cache hits, go through afterRead → batch)
    for (int round = 0; round < 4; round++) {
      for (int i = 0; i < pageCount; i++) {
        var entry = readCache.loadForRead(0, i, writeCache, false);
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
      var entry = readCache.loadForRead(0, i, writeCache, false);
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
      var entry = readCache.loadForRead(0, i, writeCache, false);
      readCache.releaseFromRead(entry);
    }

    // Cache hits → afterRead accumulates 5 entries in batch
    for (int i = 0; i < 5; i++) {
      var entry = readCache.loadForRead(0, i, writeCache, false);
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

    // Cache should still work after clear
    var entry = readCache.loadForRead(0, 0, writeCache, false);
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
      var entry = readCache.loadForRead(0, i, writeCache, false);
      readCache.releaseFromRead(entry);
    }

    // Re-read to generate afterRead events
    for (int round = 0; round < 3; round++) {
      for (int i = 0; i < pageCount; i++) {
        var entry = readCache.loadForRead(0, i, writeCache, false);
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
      var entry = readCache.loadForRead(0, i, writeCache, false);
      readCache.releaseFromRead(entry);
    }

    var executor = Executors.newFixedThreadPool(threadCount);
    List<Future<Void>> futures = new ArrayList<>();

    for (int t = 0; t < threadCount; t++) {
      futures.add(executor.submit(() -> {
        var rng = ThreadLocalRandom.current();
        for (int i = 0; i < readsPerThread; i++) {
          var pageIndex = rng.nextInt(pageCount);
          var entry = readCache.loadForRead(0, pageIndex, writeCache, false);
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
    var entry = readCache.loadForRead(0, 0, writeCache, false);
    readCache.releaseFromRead(entry);

    // Write-load the same page (cache hit, goes through afterRead)
    entry = readCache.loadForWrite(0, 0, writeCache, false, null);
    readCache.releaseFromWrite(entry, writeCache, false);

    // Read-load again (cache hit)
    entry = readCache.loadForRead(0, 0, writeCache, false);
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
      var entry = readCache.loadForRead(0, i, writeCache, false);
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
      var entry = readCache.loadForRead(0, i, writeCache, false);
      readCache.releaseFromRead(entry);
    }

    // Interleave: re-read existing page (hit, afterRead), then load new page (miss, afterAdd)
    for (int i = 10; i < 100; i++) {
      // Cache hit on an existing page
      var hitEntry = readCache.loadForRead(0, i % 10, writeCache, false);
      readCache.releaseFromRead(hitEntry);

      // Cache miss on a new page
      var missEntry = readCache.loadForRead(0, i, writeCache, false);
      readCache.releaseFromRead(missEntry);
    }

    readCache.assertSize();
    readCache.assertConsistency();
  }

  // --- allocateNewPage cacheSize tracking tests ---

  /**
   * allocateNewPage() must increment cacheSize so that getUsedMemory() reflects the newly
   * allocated page. This is the root-cause fix for cacheSize counter drift — without the
   * increment in addNewPagePointerToTheCache(), eviction decrements for allocated pages
   * would drive cacheSize negative, eventually crashing clear() with
   * IllegalArgumentException from ArrayList(negativeCapacity).
   */
  @Test
  public void testAllocateNewPageIncrementsCacheSize() throws Exception {
    long initialMemory = readCache.getUsedMemory();

    var entry = readCache.allocateNewPage(0, writeCache, new LogSequenceNumber(-1, -1));
    readCache.releaseFromWrite(entry, writeCache, false);

    // cacheSize must have been incremented: memory should increase by exactly one page.
    Assert.assertEquals(
        "allocateNewPage must increment cacheSize",
        initialMemory + PAGE_SIZE, readCache.getUsedMemory());

    readCache.assertSize();
    readCache.assertConsistency();
  }

  /**
   * Multiple allocateNewPage() calls across different file IDs must each increment
   * cacheSize correctly, keeping memory tracking accurate. Uses different fileIds
   * because MockedWriteCache.allocateNewPage() always returns pageIndex 0.
   */
  @Test
  public void testMultipleAllocateNewPagesTrackMemoryCorrectly() throws Exception {
    int allocCount = 5;
    var entries = new ArrayList<CacheEntry>();
    for (int fileId = 0; fileId < allocCount; fileId++) {
      var entry = readCache.allocateNewPage(fileId, writeCache,
          new LogSequenceNumber(-1, -1));
      entries.add(entry);
    }

    Assert.assertEquals(
        "Each allocateNewPage must increment cacheSize",
        (long) allocCount * PAGE_SIZE, readCache.getUsedMemory());

    for (var entry : entries) {
      readCache.releaseFromWrite(entry, writeCache, false);
    }

    readCache.assertSize();
    readCache.assertConsistency();
  }

  // --- clear() with negative cacheSize defense-in-depth test ---

  /**
   * clear() must not throw when cacheSize has drifted to a negative value.
   * The Math.max(0, cacheSize) guard in clear() prevents IllegalArgumentException
   * from ArrayList(negativeCapacity). After clear(), cacheSize must be reset to 0.
   */
  @Test
  public void testClearWithNegativeCacheSizeDoesNotThrow() throws Exception {
    // Force cacheSize to a negative value via reflection, simulating the
    // counter drift that could occur from a future increment/decrement bug.
    var cacheSizeField = LockFreeReadCache.class.getDeclaredField("cacheSize");
    cacheSizeField.setAccessible(true);
    var cacheSize = (AtomicInteger) cacheSizeField.get(readCache);
    cacheSize.set(-5);

    // clear() must not throw IllegalArgumentException.
    readCache.clear();

    // After clear(), cacheSize must be reset to 0 (not left at -5).
    Assert.assertEquals("cacheSize must be 0 after clear()", 0, cacheSize.get());
    Assert.assertEquals("Used memory must be 0 after clear()", 0, readCache.getUsedMemory());
  }

  // --- drainAllEntries / closeStorage / deleteStorage tests ---

  /**
   * Happy-path: {@code closeStorage} populates the read cache, then drains every entry in one
   * pass, resets {@code cacheSize} to zero, and closes the underlying write cache exactly once.
   * This covers the sequencing {@code flushCurrentThreadReadBatch → evictionLock → emptyBuffers
   * → forEachValue → freeze → policy.onRemove → drainAll → cacheSize.set(0) → writeCache.close()}
   * that motivates the whole branch.
   */
  @Test
  public void testCloseStorageDrainsAllEntriesAndClosesWriteCacheExactlyOnce() throws Exception {
    int fileCount = 3;
    int pagesPerFile = 10;
    for (int fileId = 0; fileId < fileCount; fileId++) {
      for (int pageIdx = 0; pageIdx < pagesPerFile; pageIdx++) {
        var entry = readCache.loadForRead(fileId, pageIdx, writeCache, false);
        readCache.releaseFromRead(entry);
      }
    }

    Assert.assertEquals(
        "sanity: cache must be populated before closeStorage",
        (long) fileCount * pagesPerFile * PAGE_SIZE, readCache.getUsedMemory());
    Assert.assertEquals(
        "sanity: writeCache must not be closed before closeStorage",
        0, writeCache.closeCount.get());

    readCache.closeStorage(writeCache);

    Assert.assertEquals(
        "closeStorage must reset used memory", 0, readCache.getUsedMemory());
    Assert.assertEquals(
        "closeStorage must reset the internal cacheSize counter",
        0, getCacheSizeCounter());
    Assert.assertEquals(
        "closeStorage must drain the internal map", 0L, getDataMap().size());
    Assert.assertEquals(
        "closeStorage must close writeCache exactly once",
        1, writeCache.closeCount.get());
    Assert.assertEquals(
        "closeStorage must not invoke writeCache.delete()",
        0, writeCache.deleteCount.get());
  }

  /**
   * Happy-path: {@code deleteStorage} follows the same drain sequencing as closeStorage but
   * invokes {@code writeCache.delete()} rather than {@code close()}.
   */
  @Test
  public void testDeleteStorageDrainsAllEntriesAndDeletesWriteCacheExactlyOnce() throws Exception {
    for (int i = 0; i < 16; i++) {
      var entry = readCache.loadForRead(0, i, writeCache, false);
      readCache.releaseFromRead(entry);
    }

    Assert.assertEquals(
        "sanity: cache populated", 16L * PAGE_SIZE, readCache.getUsedMemory());

    readCache.deleteStorage(writeCache);

    Assert.assertEquals(
        "deleteStorage must reset used memory", 0, readCache.getUsedMemory());
    Assert.assertEquals(
        "deleteStorage must reset cacheSize", 0, getCacheSizeCounter());
    Assert.assertEquals(
        "deleteStorage must drain the map", 0L, getDataMap().size());
    Assert.assertEquals(
        "deleteStorage must call writeCache.delete() exactly once",
        1, writeCache.deleteCount.get());
    Assert.assertEquals(
        "deleteStorage must not invoke writeCache.close()",
        0, writeCache.closeCount.get());
  }

  /**
   * The third drainAllEntries phase ({@code data.drainAll}) shrinks each section back to its
   * constructor-time capacity. After loading many more pages than the initial per-section
   * capacity, the map total capacity grows; closeStorage must then shrink it back, freeing the
   * retained Entry[] arrays. Without this phase, a long-lived storage that is closed and reopened
   * would leak the large arrays accumulated during normal operation.
   */
  @Test
  public void testCloseStorageShrinksMapCapacityAfterDrain() throws Exception {
    // Load enough distinct pages to force sections to grow beyond their initial capacity.
    int pageCount = 2000;
    for (int i = 0; i < pageCount; i++) {
      var entry = readCache.loadForRead(0, i, writeCache, false);
      readCache.releaseFromRead(entry);
    }

    long preCloseCapacity = getDataMap().capacity();
    Assert.assertTrue(
        "sanity: sections must have grown beyond initial capacity (pre=" + preCloseCapacity + ")",
        preCloseCapacity > 0);

    readCache.closeStorage(writeCache);

    long postCloseCapacity = getDataMap().capacity();
    Assert.assertTrue(
        "closeStorage must shrink map capacity (pre=" + preCloseCapacity
            + ", post=" + postCloseCapacity + ")",
        postCloseCapacity < preCloseCapacity);
    Assert.assertEquals(
        "map must be empty after closeStorage", 0L, getDataMap().size());
  }

  /**
   * Freeze-failure contract: when {@code freeze()} fails on a pinned entry during
   * drainAllEntries, the method throws {@link StorageException} <em>before</em> the map is
   * mutated, so the caller can retry close on a fresh attempt. Verified end-to-end by:
   * <ol>
   *   <li>Loading a single entry and holding its read lock (pin).</li>
   *   <li>Asserting {@code closeStorage} throws StorageException.</li>
   *   <li>Asserting the map still contains the entry, {@code cacheSize} is unchanged, and
   *       {@code writeCache.close()} was not invoked.</li>
   *   <li>Releasing the pin and re-invoking {@code closeStorage} — the retry must succeed and
   *       must close the write cache exactly once (not twice across both attempts).</li>
   * </ol>
   * Using a single entry guarantees the only iterated entry is the pinned one, so no other entry
   * is frozen before the abort — the retry therefore reaches a clean state.
   */
  @Test
  public void testCloseStorageAbortsOnFreezeFailureAndIsRetryable() throws Exception {
    var pinnedEntry = readCache.loadForRead(0, 0, writeCache, false);
    // Do NOT release — the entry's use count stays at 1, so freeze() returns false.

    long preCacheSize = getCacheSizeCounter();
    long preMapSize = getDataMap().size();
    Assert.assertEquals("sanity: exactly one entry in the map", 1L, preMapSize);

    try {
      readCache.closeStorage(writeCache);
      Assert.fail("closeStorage must throw StorageException when an entry cannot be frozen");
    } catch (StorageException expected) {
      // The exception message must name the entry that blocked the close so operators can
      // identify the leaking caller.
      Assert.assertTrue(
          "exception message must reference the blocking page: " + expected.getMessage(),
          expected.getMessage() != null
              && expected.getMessage().contains("Page with index"));
    }

    Assert.assertEquals(
        "cacheSize must be unchanged after aborted close",
        preCacheSize, getCacheSizeCounter());
    Assert.assertEquals(
        "map must still contain the entry after aborted close",
        preMapSize, getDataMap().size());
    Assert.assertEquals(
        "writeCache.close() must NOT be called when drainAllEntries aborts",
        0, writeCache.closeCount.get());
    Assert.assertFalse(
        "pinned entry must NOT be frozen when drainAllEntries aborts on it",
        pinnedEntry.isFrozen());

    // Retry: once the pin is released, closeStorage must succeed on a fresh attempt.
    readCache.releaseFromRead(pinnedEntry);
    readCache.closeStorage(writeCache);

    Assert.assertEquals(
        "retry must reset cacheSize", 0, getCacheSizeCounter());
    Assert.assertEquals(
        "retry must drain the map", 0L, getDataMap().size());
    Assert.assertEquals(
        "retry must close writeCache exactly once across both attempts",
        1, writeCache.closeCount.get());
  }

  /**
   * Concurrency contract: the drainAllEntries precondition says "no concurrent doLoad during
   * close" — but the method must still handle the state left behind by concurrent reads that
   * <em>have already quiesced</em>. Multiple reader threads accumulate per-thread ReadBatch
   * state and outstanding write-buffer entries; once they finish, {@code closeStorage} from a
   * different thread must flush the batches of all threads (via emptyBuffers on the write buffer
   * + drainReadBuffers on the striped read buffer), drain every entry, and close the write cache
   * exactly once. Without the {@code flushCurrentThreadReadBatch → emptyBuffers} sequencing,
   * pending read events would reference cache entries after drainAll freed them.
   */
  @Test
  public void testCloseStorageAfterConcurrentReadsDrainsEverything() throws Exception {
    int fileCount = 4;
    int pageLimit = 200;
    int threadCount = 4;
    int readsPerThread = 5_000;

    // Pre-load pages single-threaded so readers see cache hits.
    for (int f = 0; f < fileCount; f++) {
      for (int p = 0; p < pageLimit; p++) {
        var entry = readCache.loadForRead(f, p, writeCache, false);
        readCache.releaseFromRead(entry);
      }
    }

    var executor = Executors.newFixedThreadPool(threadCount);
    List<Future<Void>> futures = new ArrayList<>();
    try {
      for (int t = 0; t < threadCount; t++) {
        futures.add(executor.submit(() -> {
          var rng = ThreadLocalRandom.current();
          for (int i = 0; i < readsPerThread; i++) {
            int fileId = rng.nextInt(fileCount);
            int pageIndex = rng.nextInt(pageLimit);
            var entry = readCache.loadForRead(fileId, pageIndex, writeCache, false);
            readCache.releaseFromRead(entry);
          }
          return null;
        }));
      }
      for (var future : futures) {
        future.get();
      }
    } finally {
      executor.shutdown();
    }

    // Pre-close sanity: cache has entries, writeCache has not been closed.
    Assert.assertTrue(
        "sanity: cache must be populated after concurrent reads",
        readCache.getUsedMemory() > 0);
    Assert.assertEquals(
        "sanity: writeCache.close() must not have run yet",
        0, writeCache.closeCount.get());

    // closeStorage from the main thread — a different thread than the readers. It must flush the
    // entries accumulated in every thread's ReadBatch and every pending write-buffer entry.
    readCache.closeStorage(writeCache);

    Assert.assertEquals(
        "closeStorage must reset used memory to zero after concurrent reads",
        0, readCache.getUsedMemory());
    Assert.assertEquals(
        "closeStorage must reset cacheSize after concurrent reads",
        0, getCacheSizeCounter());
    Assert.assertEquals(
        "closeStorage must drain the map after concurrent reads",
        0L, getDataMap().size());
    Assert.assertEquals(
        "closeStorage must invoke writeCache.close() exactly once",
        1, writeCache.closeCount.get());
  }

  /**
   * Reflection helper: returns the current value of {@code LockFreeReadCache.cacheSize}, used
   * to assert that drainAllEntries resets the counter (and, on abort, does not).
   */
  private int getCacheSizeCounter() throws Exception {
    var cacheSizeField = LockFreeReadCache.class.getDeclaredField("cacheSize");
    cacheSizeField.setAccessible(true);
    return ((AtomicInteger) cacheSizeField.get(readCache)).get();
  }

  /**
   * Reflection helper: returns the internal {@link ConcurrentLongIntHashMap} backing the cache,
   * so tests can observe map size and capacity directly (both public methods on the map).
   */
  @SuppressWarnings("unchecked")
  private ConcurrentLongIntHashMap<CacheEntry> getDataMap() throws Exception {
    var dataField = LockFreeReadCache.class.getDeclaredField("data");
    dataField.setAccessible(true);
    return (ConcurrentLongIntHashMap<CacheEntry>) dataField.get(readCache);
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
  //
  // Non-record class so the `close()` / `delete()` / `closeFile()` call counts are visible to
  // tests that verify drainAllEntries sequencing (closeStorage/deleteStorage must only close the
  // write cache after the read-cache drain succeeds).

  private static final class MockedWriteCache implements WriteCache {

    private final ByteBufferPool byteBufferPool;
    final AtomicInteger closeCount = new AtomicInteger();
    final AtomicInteger deleteCount = new AtomicInteger();

    MockedWriteCache(final ByteBufferPool byteBufferPool) {
      this.byteBufferPool = byteBufferPool;
    }

    ByteBufferPool byteBufferPool() {
      return byteBufferPool;
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
      closeCount.incrementAndGet();
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
      deleteCount.incrementAndGet();
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
