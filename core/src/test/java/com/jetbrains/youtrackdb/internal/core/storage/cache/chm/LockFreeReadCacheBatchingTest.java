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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
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
    readCache = new LockFreeReadCache(maxMemory, PAGE_SIZE);
    writeCache = new MockedWriteCache(bufferPool);
  }

  @After
  public void tearDown() {
    // readCache.clear() iterates the whole shared map regardless of storage id, so
    // cross-storage tests that fail mid-way still reclaim all unpinned direct memory.
    // A StorageException from clear() only surfaces when an entry is still pinned — in that
    // case, the test harness's direct-memory tracker will flag any leaked allocations at JVM
    // exit, but the individual test is self-isolating.
    try {
      readCache.clear();
    } catch (StorageException ignored) {
      // A freeze()-failure test may leave a pinned entry that blocks clear; tolerate and
      // move on. Any non-StorageException here indicates a regression and must surface.
    }
  }

  /**
   * The {@code MockedWriteCache} inner class is not exercised by the recovery-time
   * orphan-truncation pass, so its {@code shrinkFile} override throws UOE. This assertion pins
   * the contract — a regression to a silent no-op or a real truncate would hide a future test
   * accidentally routing through this mock instead of the production WOWCache.
   */
  @Test
  public void testShrinkFileMockThrowsUnsupportedOperation() {
    Assert.assertThrows(
        UnsupportedOperationException.class, () -> writeCache.shrinkFile(0L, 0L));
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
   * loadOrAddForWrite also calls doLoad internally, so cache hits through loadOrAddForWrite
   * should correctly interact with the batching mechanism (afterRead is called on hits).
   */
  @Test
  public void testLoadOrAddForWriteWithBatching() {
    // Load page (miss)
    var entry = readCache.loadForRead(0, 0, writeCache, false);
    readCache.releaseFromRead(entry);

    // Write-load the same page (cache hit, goes through afterRead)
    entry = readCache.loadOrAddForWrite(0, 0, writeCache, false, null);
    readCache.releaseFromWrite(entry, writeCache, false);

    // Read-load again (cache hit)
    entry = readCache.loadForRead(0, 0, writeCache, false);
    readCache.releaseFromRead(entry);

    readCache.assertSize();
    readCache.assertConsistency();
  }

  // --- markAllocated branch contract on the write-load path ---
  //
  // The write-load primitive must flag the resulting CacheEntry as newly allocated whenever
  // the requested pageIndex sits at-or-beyond the file's filledUpTo (an extend or gap-fill),
  // and must NOT set the flag when the page was already on disk (existing page). The flag is
  // load-bearing: releaseFromWrite uses isNewlyAllocatedPage() to decide whether to publish a
  // dirty-page record even when the caller passes changed=false. Without these tests, every
  // existing test ran with filledUpTo == 0 (the mock default), so the markAllocated line
  // fired unconditionally and a deletion of the line would have passed the entire test suite.

  /**
   * On the write-load path, when {@code pageIndex >= filledUpTo} (the extend branch), the
   * returned CacheEntry must be flagged as newly allocated. With the flag set, a subsequent
   * {@code releaseFromWrite} call with {@code changed=false} must still publish the page on
   * the dirty-page list — verified here by counting {@link
   * MockedWriteCache#storeCount}.
   */
  @Test
  public void testWriteLoadFlagsExtendedPageAsNewlyAllocated() {
    // filledUpTo defaults to 0 — every pageIndex is in the extend branch.
    var entry = readCache.loadOrAddForWrite(0, 5, writeCache, false, null);
    Assert.assertTrue(
        "extend-branch entry must be flagged newly allocated before releaseFromWrite",
        entry.isNewlyAllocatedPage());

    int storesBefore = writeCache.storeCount.get();
    readCache.releaseFromWrite(entry, writeCache, /*changed=*/false);
    Assert.assertEquals(
        "releaseFromWrite must publish the dirty page exactly once when the entry is flagged"
            + " newly allocated, even with changed=false",
        storesBefore + 1, writeCache.storeCount.get());
  }

  /**
   * On the write-load path, when {@code pageIndex < filledUpTo} (existing page on disk), the
   * returned CacheEntry must NOT be flagged as newly allocated. This is the test that
   * discriminates the markAllocated conditional — a regression that always set the flag would
   * fire here. The {@code storeCount} no-op assertion is the symmetric companion of
   * {@link #testWriteLoadFlagsExtendedPageAsNewlyAllocated}: when the flag is unset and
   * {@code changed=false}, {@code releaseFromWrite} must skip the {@code store} call.
   */
  @Test
  public void testWriteLoadDoesNotFlagExistingPageAsNewlyAllocated() {
    writeCache.setFilledUpTo(0L, 10L);

    var entry = readCache.loadOrAddForWrite(0, 3, writeCache, false, null);
    Assert.assertFalse(
        "existing-page entry must NOT be flagged newly allocated",
        entry.isNewlyAllocatedPage());

    int storesBefore = writeCache.storeCount.get();
    readCache.releaseFromWrite(entry, writeCache, /*changed=*/false);
    Assert.assertEquals(
        "releaseFromWrite must NOT publish when entry is not flagged newly allocated"
            + " and changed=false",
        storesBefore, writeCache.storeCount.get());
  }

  /**
   * Boundary case: when {@code pageIndex == filledUpTo} (one-page extend), the returned entry
   * must still be flagged as newly allocated. This pins the {@code >=} (not {@code >})
   * inequality in the markAllocated guard — a regression that swapped the operator would
   * mis-classify the boundary page as existing and lose its dirty-publish.
   */
  @Test
  public void testWriteLoadFlagsBoundaryPageAsNewlyAllocated() {
    writeCache.setFilledUpTo(0L, 5L);

    var entry = readCache.loadOrAddForWrite(0, 5, writeCache, false, null);
    Assert.assertTrue(
        "boundary-page entry (pageIndex == filledUpTo) must be flagged newly allocated",
        entry.isNewlyAllocatedPage());

    readCache.releaseFromWrite(entry, writeCache, /*changed=*/false);
  }

  /**
   * Read-path contract: the read-load primitive must never flag the resulting CacheEntry as
   * newly allocated, even on extend-branch parameters where the corresponding write-load
   * companion DOES flag the entry. The markAllocated logic lives solely on the write-load
   * primitive; pinning this here with {@code filledUpTo=0} and {@code pageIndex=5} forces
   * the production guard {@code pageIndex >= filledUpTo} to be TRUE, so a regression that
   * conditionally bled {@code markAllocated} to the read path under the same guard would
   * fire here. (A {@code pageIndex < filledUpTo} configuration would pass vacuously
   * because the guard is FALSE for both read and write paths.)
   */
  @Test
  public void testReadLoadDoesNotFlagPageAsNewlyAllocatedOnExtendBranchParameters() {
    // filledUpTo=0 + pageIndex=5 → pageIndex >= filledUpTo is TRUE.
    // The read path must ignore this condition; only the write path may flag.
    writeCache.setFilledUpTo(0L, 0L);

    var entry = readCache.loadForRead(0, 5, writeCache, false);
    Assert.assertFalse(
        "read-load must never flag the entry as newly allocated, "
            + "even when pageIndex >= filledUpTo (write-only contract)",
        entry.isNewlyAllocatedPage());

    readCache.releaseFromRead(entry);
  }

  // --- WriteCache totality contract: fail-fast on null loadOrAdd ---

  /**
   * Totality-contract regression test: {@code LockFreeReadCache.doLoad}'s segment-lock
   * lambda must throw {@link IllegalStateException} when {@code WriteCache.loadOrAdd}
   * violates its totality contract by returning {@code null}. The throw is the production
   * fail-fast wired into {@code doLoad} so a regressing {@code WriteCache} implementation
   * cannot install a {@link CacheEntry} around a null {@link CachePointer} and surface
   * the breakage as an opaque NPE several frames downstream.
   *
   * <p>The mock's {@link MockedWriteCache#setLoadOrAddReturnsNull} toggle drives the
   * primitive into the contract-violating state in isolation; a regression that
   * re-introduced the legacy {@code addNewPagePointerToTheCache} fallback (or otherwise
   * silently masked the null) would let {@code loadOrAddForWrite} return a non-null
   * {@code CacheEntry}, failing this test.
   *
   * <p>The exception message must name both {@code fileId} and {@code pageIndex} so an
   * operator can identify the offending key when the throw surfaces in production logs;
   * a loose contains-only check would pass a buggy format such as
   * {@code "... for fileId=null ..."}.
   */
  @Test
  public void testLoadOrAddForWriteThrowsWhenLoadOrAddReturnsNull() {
    writeCache.setLoadOrAddReturnsNull(true);
    try {
      readCache.loadOrAddForWrite(0L, 7L, writeCache, false, null);
      Assert.fail(
          "loadOrAddForWrite must throw IllegalStateException when WriteCache.loadOrAdd"
              + " returns null (totality-contract violation)");
    } catch (IllegalStateException expected) {
      Assert.assertNotNull(
          "exception message must not be null", expected.getMessage());
      Assert.assertTrue(
          "exception must name the offending fileId and pageIndex: " + expected.getMessage(),
          expected.getMessage().contains("fileId=0")
              && expected.getMessage().contains("pageIndex=7"));
    } finally {
      writeCache.setLoadOrAddReturnsNull(false);
    }

    // Defensive: after the throw the cache must have skipped the markAllocated step and
    // cacheSize must remain at zero — if a regression mis-ordered the increment ahead of
    // the null check, the counter would drift and a subsequent clear() could be wedged.
    Assert.assertEquals(
        "cacheSize must remain zero after a failed loadOrAddForWrite",
        0L, readCache.getUsedMemory());
  }

  // --- silentLoadForRead non-extending-probe migration tests ---

  /**
   * Migration contract: {@code silentLoadForRead} must route through the non-extending
   * probe {@code WriteCache.loadIfPresent}, NOT the legacy {@code load} primitive. The
   * mock flips {@code loadIfPresent} into "miss" mode so it returns {@code null} without
   * delegating to {@code load}; under that configuration, the silent read must drive
   * {@code loadIfPresentCount} up by one and leave {@code loadCount} untouched. A future
   * refactor that accidentally restored the legacy {@code load} call would produce a
   * non-null entry (because mock {@code load} always allocates) and bump {@code loadCount}
   * — both assertions below catch that regression. The production {@code WOWCache.load}
   * silently extends a freshly-allocated empty page, breaking the silent-read diagnostic
   * contract on which backup and restore-mode probes depend.
   */
  @Test
  public void testSilentLoadForReadRoutesThroughLoadIfPresent() {
    final var initialLoadCount = writeCache.loadCount.get();
    final var initialLoadIfPresentCount = writeCache.loadIfPresentCount.get();
    writeCache.setLoadIfPresentReturnsNull(true);
    try {
      final var entry = readCache.silentLoadForRead(0L, 0, writeCache, false);
      Assert.assertNull(
          "loadIfPresent in miss-mode returns null; silentLoadForRead must surface that null",
          entry);
    } finally {
      writeCache.setLoadIfPresentReturnsNull(false);
    }

    Assert.assertEquals(
        "silentLoadForRead must invoke loadIfPresent exactly once",
        initialLoadIfPresentCount + 1,
        writeCache.loadIfPresentCount.get());
    Assert.assertEquals(
        "silentLoadForRead must NOT invoke the legacy extending load primitive",
        initialLoadCount,
        writeCache.loadCount.get());
  }

  /**
   * Non-extending semantics: when {@code WriteCache.loadIfPresent} reports "page not
   * present" (returns {@code null}), {@code silentLoadForRead} must surface that
   * {@code null} to the caller without installing a {@code CacheEntry}. This pins the
   * core property the silent-read path needs from the migration: a probe that faithfully
   * answers "no such page" instead of magic-stamping a fresh empty buffer.
   */
  @Test
  public void testSilentLoadForReadReturnsNullWhenLoadIfPresentMisses() {
    writeCache.setLoadIfPresentReturnsNull(true);
    try {
      final var entry = readCache.silentLoadForRead(0L, 0, writeCache, false);
      Assert.assertNull(
          "silentLoadForRead must propagate the loadIfPresent null on miss",
          entry);
    } finally {
      writeCache.setLoadIfPresentReturnsNull(false);
    }
  }

  /**
   * Cached-hit fast path on {@code silentLoadForRead}: a page already present in the
   * read-cache map must be returned without re-invoking {@code loadIfPresent} (or any
   * other write-cache load primitive). Pins the existing {@code data.get} fast-path
   * inside the silent-read loop — a regression that bypasses the cache and always
   * routes to the write-cache layer would inflate {@code loadIfPresentCount} on the
   * second call.
   */
  @Test
  public void testSilentLoadForReadHonoursCachedHitFastPath() {
    // Prime the cache via the read-side primitive; this installs an entry under (0, 0).
    final var primer = readCache.loadForRead(0, 0, writeCache, false);
    readCache.releaseFromRead(primer);
    final var loadIfPresentBefore = writeCache.loadIfPresentCount.get();

    final var entry = readCache.silentLoadForRead(0L, 0, writeCache, false);
    Assert.assertNotNull(entry);
    readCache.releaseFromRead(entry);

    Assert.assertEquals(
        "cached hit must not invoke loadIfPresent",
        loadIfPresentBefore,
        writeCache.loadIfPresentCount.get());
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

  // --- LockFreeReadCache wrapper functional contract tests ---
  //
  // The tests in this section pin wrapper-level invariants that the read-cache primitive
  // contract requires: cache hit / miss routing through the write-cache layer, the
  // markAllocated short-circuit on a write-load cache hit, eviction of clean vs. dirty
  // entries, pin retention under eviction pressure, and the WTinyLFU two-tier transitions
  // (probation -> protection on access; protection -> probation on overflow). They
  // complement the batching tests above (which exercise the read-batch state machine) and
  // the markAllocated branch tests above (which exercise the dispatcher's miss-side
  // flagging logic).

  /**
   * Cache-miss routing: {@code loadForRead} on an absent page must drive
   * {@code WriteCache.loadOrAdd} exactly once (the data.compute lambda routes through this
   * primitive in production) and must NOT invoke the legacy {@code load} or
   * non-extending probe {@code loadIfPresent}. A regression that re-introduced the legacy
   * {@code load} call (or routed through {@code loadIfPresent} for an extending read) would
   * fail the per-primitive counter assertions below. The cache must hold exactly one entry
   * after the call.
   */
  @Test
  public void testLoadForReadCacheMissRoutesThroughLoadOrAddExactlyOnce() {
    final var loadBefore = writeCache.loadCount.get();
    final var loadOrAddBefore = writeCache.loadOrAddCount.get();
    final var loadIfPresentBefore = writeCache.loadIfPresentCount.get();

    final var entry = readCache.loadForRead(0, 0, writeCache, false);
    try {
      Assert.assertNotNull("loadForRead on a fresh page must return a non-null entry", entry);
      Assert.assertEquals(
          "cache-miss read must invoke writeCache.loadOrAdd exactly once",
          loadOrAddBefore + 1, writeCache.loadOrAddCount.get());
      Assert.assertEquals(
          "cache-miss read must NOT invoke the legacy extending load primitive",
          loadBefore, writeCache.loadCount.get());
      Assert.assertEquals(
          "cache-miss read must NOT invoke the non-extending loadIfPresent probe",
          loadIfPresentBefore, writeCache.loadIfPresentCount.get());
      Assert.assertEquals(
          "cache-miss read must install exactly one entry (one page of memory)",
          PAGE_SIZE, readCache.getUsedMemory());
    } finally {
      readCache.releaseFromRead(entry);
    }
  }

  /**
   * Cache-hit fast path: {@code loadForRead} on a page already in the cache must NOT invoke
   * any write-cache load primitive (the data.get fast path short-circuits before the
   * data.compute lambda). The hit is still recorded via {@code afterRead} so the
   * thread-local read batch grows; this pins both halves of the hit contract — no
   * write-cache I/O AND policy updates still happen.
   */
  @Test
  public void testLoadForReadCacheHitDoesNotInvokeWriteCacheButRecordsAccess() throws Exception {
    // Prime the cache.
    final var primer = readCache.loadForRead(0, 0, writeCache, false);
    readCache.releaseFromRead(primer);

    final var loadBefore = writeCache.loadCount.get();
    final var loadOrAddBefore = writeCache.loadOrAddCount.get();
    final var loadIfPresentBefore = writeCache.loadIfPresentCount.get();
    final var batchBefore = getThreadLocalBatchSize();

    final var entry = readCache.loadForRead(0, 0, writeCache, false);
    try {
      Assert.assertNotNull("cache hit must return the cached entry", entry);
      Assert.assertEquals(
          "cache hit must NOT invoke writeCache.loadOrAdd",
          loadOrAddBefore, writeCache.loadOrAddCount.get());
      Assert.assertEquals(
          "cache hit must NOT invoke writeCache.load",
          loadBefore, writeCache.loadCount.get());
      Assert.assertEquals(
          "cache hit must NOT invoke writeCache.loadIfPresent",
          loadIfPresentBefore, writeCache.loadIfPresentCount.get());
      Assert.assertEquals(
          "cache hit must record one afterRead event in the thread-local batch",
          batchBefore + 1, getThreadLocalBatchSize());
    } finally {
      readCache.releaseFromRead(entry);
    }
  }

  /**
   * Cache-hit fast path on the write-load primitive: {@code loadOrAddForWrite} on an
   * already-cached page must NOT invoke any write-cache load primitive, exactly mirroring
   * the read-side fast path. This is the symmetric companion of the read-side test above —
   * a regression that bled the write-load primitive into the cache-miss compute lambda on a
   * hit would fail the {@code loadOrAddCount} assertion below.
   */
  @Test
  public void testLoadOrAddForWriteCacheHitDoesNotInvokeWriteCache() {
    // Prime the cache via the read primitive so the entry is installed without a
    // markAllocated flag.
    final var primer = readCache.loadForRead(0, 0, writeCache, false);
    readCache.releaseFromRead(primer);

    final var loadOrAddBefore = writeCache.loadOrAddCount.get();
    final var loadBefore = writeCache.loadCount.get();

    final var entry = readCache.loadOrAddForWrite(0, 0, writeCache, false, null);
    try {
      Assert.assertNotNull("cache hit on write-load must return the cached entry", entry);
      Assert.assertEquals(
          "cache hit on write-load must NOT invoke writeCache.loadOrAdd",
          loadOrAddBefore, writeCache.loadOrAddCount.get());
      Assert.assertEquals(
          "cache hit on write-load must NOT invoke the legacy writeCache.load",
          loadBefore, writeCache.loadCount.get());
    } finally {
      readCache.releaseFromWrite(entry, writeCache, /*changed=*/false);
    }
  }

  /**
   * Cache-hit short-circuit on the markAllocated flag: when {@code loadOrAddForWrite} hits
   * the cache, the entry's existing {@code isNewlyAllocatedPage} state must be preserved as
   * the hit branch does not re-evaluate {@code pageIndex >= filledUpTo}. Here we prime the
   * cache via the read primitive (so the entry is NOT flagged), then call
   * {@code loadOrAddForWrite} with extend-branch parameters ({@code pageIndex >= filledUpTo})
   * — the hit must keep the flag false. A regression that bled the markAllocated logic into
   * the hit path would set the flag and cause a spurious dirty-page publish on
   * {@code releaseFromWrite(_, _, false)}.
   */
  @Test
  public void testLoadOrAddForWriteCacheHitDoesNotFlagAsNewlyAllocated() {
    // filledUpTo defaults to 0 — pageIndex=5 is in the extend branch for a fresh miss.
    // Prime via loadForRead so the entry is installed WITHOUT the markAllocated flag.
    final var primer = readCache.loadForRead(0, 5, writeCache, false);
    readCache.releaseFromRead(primer);
    Assert.assertFalse(
        "sanity: primer entry must not be flagged newly allocated",
        primer.isNewlyAllocatedPage());

    final var storesBefore = writeCache.storeCount.get();
    final var entry = readCache.loadOrAddForWrite(0, 5, writeCache, false, null);
    try {
      Assert.assertFalse(
          "cache hit on write-load must preserve the entry's existing markAllocated state"
              + " (not flagged) even when pageIndex >= filledUpTo",
          entry.isNewlyAllocatedPage());
    } finally {
      readCache.releaseFromWrite(entry, writeCache, /*changed=*/false);
    }
    Assert.assertEquals(
        "releaseFromWrite on an unflagged cache-hit entry must NOT invoke store",
        storesBefore, writeCache.storeCount.get());
  }

  /**
   * Cache-hit identity: the {@code CacheEntry} returned by {@code loadOrAddForWrite} on a
   * page previously installed via {@code loadForRead} must be the same instance — the cache
   * does not allocate a fresh {@code CacheEntryImpl} for a hit. This pins the data.get fast
   * path's identity contract.
   */
  @Test
  public void testLoadOrAddForWriteOnExistingCachedPageReturnsSameInstance() {
    final var primer = readCache.loadForRead(0, 3, writeCache, false);
    readCache.releaseFromRead(primer);

    final var entry = readCache.loadOrAddForWrite(0, 3, writeCache, false, null);
    try {
      Assert.assertSame(
          "write-load cache hit must return the same CacheEntry instance installed by the prior"
              + " read-load",
          primer, entry);
    } finally {
      readCache.releaseFromWrite(entry, writeCache, /*changed=*/false);
    }
  }

  /**
   * Eviction of clean entries: loading more distinct pages than the cache capacity forces
   * WTinyLFU eviction. None of the read-only loads ever invoked
   * {@code releaseFromWrite(_, _, true)} or a markAllocated write-load, so
   * {@code writeCache.store} must NEVER be called — eviction itself does not flush. A
   * regression that introduced a synchronous flush-on-evict path would fail this assertion.
   */
  @Test
  public void testEvictionOfCleanEntriesDoesNotInvokeStore() {
    // Cache holds 1024 pages; load 2000 distinct read-only pages to force eviction.
    for (int i = 0; i < 2000; i++) {
      final var entry = readCache.loadForRead(0, i, writeCache, false);
      readCache.releaseFromRead(entry);
    }

    readCache.assertSize();
    readCache.assertConsistency();

    Assert.assertEquals(
        "eviction of clean read-only entries must never invoke writeCache.store",
        0, writeCache.storeCount.get());
    Assert.assertTrue(
        "used memory must not exceed the cache budget after eviction",
        readCache.getUsedMemory() <= 1024L * PAGE_SIZE);
  }

  /**
   * Eviction of dirty entries: stores happen at {@code releaseFromWrite} time (when the
   * caller flips {@code changed=true} or the entry was markAllocated on miss), NOT at
   * eviction time. Once a dirty entry has been released, its content is already persisted
   * via {@code writeCache.store} and a subsequent eviction is a silent removal. This pins
   * the "no data lost on eviction" invariant: even if every dirty entry installed below is
   * later evicted, the final {@code storeCount} equals the number of dirty releases — no
   * fewer (no lost write), no more (no duplicate flush-on-evict).
   */
  @Test
  public void testEvictionOfDirtyEntriesPreservesEveryStoreExactlyOnce() {
    final int dirtyPages = 100;

    // Make every page existing on disk so markAllocated stays unset; the only way store()
    // can run is via changed=true on releaseFromWrite. This isolates the test from the
    // markAllocated branch's store path, which is already covered by Step 1's regression.
    writeCache.setFilledUpTo(0L, Long.MAX_VALUE);

    for (int i = 0; i < dirtyPages; i++) {
      final var entry = readCache.loadOrAddForWrite(0, i, writeCache, false, null);
      readCache.releaseFromWrite(entry, writeCache, /*changed=*/true);
    }

    Assert.assertEquals(
        "each dirty release must invoke store exactly once",
        dirtyPages, writeCache.storeCount.get());

    // Force eviction by loading enough additional read-only pages to overflow the 1024-page
    // cache. The original dirty pages are now eviction candidates (they have been released).
    for (int i = dirtyPages; i < dirtyPages + 2000; i++) {
      final var entry = readCache.loadForRead(0, i, writeCache, false);
      readCache.releaseFromRead(entry);
    }

    readCache.assertSize();
    readCache.assertConsistency();

    Assert.assertEquals(
        "eviction must not invoke store again — every dirty page was already persisted at"
            + " releaseFromWrite time",
        dirtyPages, writeCache.storeCount.get());
    Assert.assertTrue(
        "used memory must respect the cache budget post-eviction",
        readCache.getUsedMemory() <= 1024L * PAGE_SIZE);
  }

  /**
   * Pin retention under eviction pressure: an entry whose {@code acquireEntry} reference
   * has not been released ({@code usagesCount > 0}) cannot be evicted, because
   * {@code WTinyLFUPolicy.purgeEden}'s eviction path requires {@code freeze()} which only
   * succeeds when state == 0. Verified by pinning one page (loadForRead without releasing),
   * generating eviction pressure that would otherwise drop random entries, and asserting the
   * pinned entry is still loadable as a cache hit afterwards. This pins the "pinned pages
   * survive eviction" invariant — the foundation that lets read locks be held across long
   * operations without losing the underlying page.
   */
  @Test
  public void testPinnedEntryIsRetainedUnderEvictionPressure() {
    final var pinned = readCache.loadForRead(0, 0, writeCache, false);
    // Do NOT release — usagesCount stays at 1; freeze() will return false on this entry.

    final var loadOrAddBeforePressure = writeCache.loadOrAddCount.get();
    try {
      // Load 3000 distinct read-only pages on a DIFFERENT page-index range so the pinned
      // (0, 0) entry is a candidate for eviction (it is the LRU-oldest entry by the end).
      for (int i = 1; i < 3001; i++) {
        final var entry = readCache.loadForRead(0, i, writeCache, false);
        readCache.releaseFromRead(entry);
      }

      readCache.assertSize();
      readCache.assertConsistency();

      // Reload (0, 0). If the pinned entry was evicted, this would be a cache miss and
      // loadOrAddCount would increment again — the assertion below catches that. If the
      // pinned entry survived, this is a cache hit and the count is unchanged.
      final var hit = readCache.loadForRead(0, 0, writeCache, false);
      try {
        Assert.assertSame(
            "pinned entry must still be the live cache entry — eviction must not have"
                + " dropped it under churn",
            pinned, hit);
      } finally {
        readCache.releaseFromRead(hit);
      }
      Assert.assertEquals(
          "reloading the pinned entry after eviction pressure must be a cache hit"
              + " (no new writeCache.loadOrAdd invocation)",
          loadOrAddBeforePressure + 3000, writeCache.loadOrAddCount.get());
    } finally {
      // Release the original pin so tearDown's clear() does not throw on a stuck entry.
      readCache.releaseFromRead(pinned);
    }
  }

  /**
   * Two-tier transition — promotion: an access on an entry sitting in WTinyLFU's
   * probation tier must promote it to the protection tier on the next
   * {@code policy.onAccess} drain. The transition is the core mechanism that lets
   * frequently-touched pages outlast a cache churn — without it, a probation entry that
   * keeps getting accessed would still be evicted at the head of probation. Verified by
   * pre-filling the cache (eden=204, probation=820 entries with maxEdenSize=204 and
   * maxSecondLevelSize=820), forcing a buffer drain, re-accessing a probation entry, and
   * checking the tier membership before vs. after.
   */
  @Test
  public void testProbationHitPromotesToProtection() throws Exception {
    // Pre-fill: load 1024 distinct pages. Eden overflows past maxEdenSize and the surplus
    // is admitted to probation (the policy admits to probation while
    // probation.size + protection.size < maxSecondLevelSize).
    final int totalPages = 1024;
    for (int i = 0; i < totalPages; i++) {
      final var entry = readCache.loadForRead(0, i, writeCache, false);
      readCache.releaseFromRead(entry);
    }
    // assertConsistency drains both buffers so the policy state is observable.
    readCache.assertConsistency();

    // Prime the striped read buffer: the BoundedBuffer's table is null until the first
    // offer arrives, and that first offer is consumed by table init (it lands in slot 0 of
    // a fresh RingBuffer without bumping writeCounter, so the subsequent drainTo skips it).
    // A throwaway access on the last-loaded page (currently in eden — its onAccess would be
    // a within-tier move that does not affect the probation entries we care about) absorbs
    // this initialisation cost so the page (0,0) access below routes through the normal
    // offer path and is actually delivered to {@code policy.onAccess}.
    final var primer = readCache.loadForRead(0, totalPages - 1, writeCache, false);
    readCache.releaseFromRead(primer);
    readCache.assertConsistency();

    // Pick a page that should be in probation (an early-loaded page — page 0 is the head
    // of probation, having been the first to overflow eden).
    final long fileId = 0;
    final int pageIndex = 0;
    final var policy = getPolicy();
    final var probationBefore = collect(policy.probation());
    final var protectionBefore = collect(policy.protection());

    final var targetEntry = findEntry(fileId, pageIndex);
    Assert.assertNotNull("sanity: page (0,0) must still be in the cache", targetEntry);
    Assert.assertTrue(
        "sanity: page (0,0) must start in the probation tier",
        containsByIdentity(probationBefore, targetEntry));
    Assert.assertFalse(
        "sanity: page (0,0) must NOT start in the protection tier",
        containsByIdentity(protectionBefore, targetEntry));

    // Re-read page (0,0); this records an afterRead event. assertConsistency forces the
    // batch + striped buffer to drain into the policy so onAccess runs.
    final var hit = readCache.loadForRead(fileId, pageIndex, writeCache, false);
    readCache.releaseFromRead(hit);
    readCache.assertConsistency();

    final var probationAfter = collect(policy.probation());
    final var protectionAfter = collect(policy.protection());
    Assert.assertTrue(
        "page (0,0) must have been promoted to the protection tier after the cache hit",
        containsByIdentity(protectionAfter, targetEntry));
    Assert.assertFalse(
        "page (0,0) must have been removed from the probation tier",
        containsByIdentity(probationAfter, targetEntry));
  }

  /**
   * Two-tier transition — demotion under protection overflow: when the protection tier
   * grows past {@code maxProtectedSize}, the head (oldest) entry demotes back to probation.
   * For the 1024-page cache configuration here, {@code maxProtectedSize == 656}. Promoting
   * 657 distinct probation entries must trigger exactly one head-demotion of the first
   * promoted entry on the 657th promotion. Verified by pre-filling, promoting 657 distinct
   * pages one at a time (with a drain between each so the policy observes each access in
   * order), and asserting the first-promoted entry is back in probation while the last
   * batch of promoted entries sits in protection. This pins the bidirectional tier
   * discipline — promotion and demotion both work without requiring an explicit eviction.
   */
  @Test
  public void testProtectionOverflowDemotesOldestEntryToProbation() throws Exception {
    final int totalPages = 1024;
    for (int i = 0; i < totalPages; i++) {
      final var entry = readCache.loadForRead(0, i, writeCache, false);
      readCache.releaseFromRead(entry);
    }
    readCache.assertConsistency();

    // Prime the striped read buffer: see testProbationHitPromotesToProtection for the
    // rationale — the very first offer to a fresh BoundedBuffer is consumed by table init
    // and dropped before drainTo can deliver it to policy.onAccess. Without this prime,
    // the first promotion below would silently no-op and we would only see 656 promotions
    // instead of 657, missing the overflow that this test exists to verify.
    final var primer = readCache.loadForRead(0, totalPages - 1, writeCache, false);
    readCache.releaseFromRead(primer);
    readCache.assertConsistency();

    // maxProtectedSize for 1024 cache = 1024 - 204 - (1024 - 204) * 20 / 100 = 656.
    // Promote 657 distinct probation entries; the 657th promotion overflows protection and
    // the head (the first promoted, page 0) must demote back to probation.
    final int promotions = 657;
    for (int i = 0; i < promotions; i++) {
      final var entry = readCache.loadForRead(0, i, writeCache, false);
      readCache.releaseFromRead(entry);
      // Drain after every access so the policy sees them in promotion order — this
      // guarantees page 0 sits at the head of protection when the overflow fires.
      readCache.assertConsistency();
    }

    final var policy = getPolicy();
    final var probationAfter = collect(policy.probation());
    final var protectionAfter = collect(policy.protection());

    final var firstPromoted = findEntry(0L, 0);
    final var lastPromoted = findEntry(0L, promotions - 1);
    Assert.assertNotNull("sanity: page (0,0) must still be in the cache", firstPromoted);
    Assert.assertNotNull(
        "sanity: page (0," + (promotions - 1) + ") must still be in the cache", lastPromoted);

    Assert.assertTrue(
        "the first promoted entry must have been demoted to probation when protection"
            + " overflowed its " + 656 + "-entry budget",
        containsByIdentity(probationAfter, firstPromoted));
    Assert.assertFalse(
        "the first promoted entry must no longer be in protection after the overflow demotion",
        containsByIdentity(protectionAfter, firstPromoted));
    Assert.assertTrue(
        "the last promoted entry must remain in protection (it was the most recent"
            + " admission and is at the tail)",
        containsByIdentity(protectionAfter, lastPromoted));
  }

  // --- helpers for WTinyLFUPolicy tier inspection ---

  /**
   * Returns the {@link WTinyLFUPolicy} backing this read-cache via reflection so tier-
   * inspection helpers can call its package-private {@code eden()}, {@code probation()},
   * {@code protection()} iterators directly. The policy field is otherwise inaccessible
   * from outside the {@code LockFreeReadCache} class.
   */
  private WTinyLFUPolicy getPolicy() throws Exception {
    final var policyField = LockFreeReadCache.class.getDeclaredField("policy");
    policyField.setAccessible(true);
    return (WTinyLFUPolicy) policyField.get(readCache);
  }

  /**
   * Collects the entries an iterator yields into an {@link ArrayList}, preserving the
   * iteration order. Used to snapshot a WTinyLFU tier before re-accessing entries that
   * change tier membership.
   */
  private static List<CacheEntry> collect(final Iterator<CacheEntry> it) {
    final var list = new ArrayList<CacheEntry>();
    while (it.hasNext()) {
      list.add(it.next());
    }
    return list;
  }

  /**
   * Membership check by reference identity (not {@link Object#equals}) — the tier
   * assertions need to confirm the SAME {@link CacheEntry} instance is in a given tier,
   * not just an instance with the same {@code fileId} / {@code pageIndex}. A regression
   * that re-installed a fresh entry under the same key would pass an equals-based check
   * and incorrectly look like the original was promoted/demoted in place.
   */
  private static boolean containsByIdentity(
      final List<CacheEntry> list, final CacheEntry target) {
    for (final var e : list) {
      if (e == target) {
        return true;
      }
    }
    return false;
  }

  /**
   * Looks up the live {@link CacheEntry} for {@code (fileId, pageIndex)} via the data map
   * the read-cache exposes for testing through {@link #getDataMap()}. Returns {@code null}
   * if the entry is no longer in the cache (e.g., evicted by churn). Used by tier-
   * transition tests to obtain a stable identity reference for the entry whose tier
   * membership is being checked.
   */
  private CacheEntry findEntry(final long fileId, final int pageIndex) throws Exception {
    final var map = getDataMap();
    final var collected = new ArrayList<CacheEntry>();
    map.forEachValue(collected::add);
    for (final var entry : collected) {
      if (entry.getFileId() == fileId && entry.getPageIndex() == pageIndex) {
        return entry;
      }
    }
    return null;
  }

  // --- Wrapper-level MT stress: eviction-vs-load and flush-worker concurrency ---
  //
  // Scenarios 4-5 from the cache test coverage plan. Scenario 4 covers wrapper-level
  // eviction-vs-load behaviour: pin counts respected (a pinned entry is not evicted while
  // held), dirty pages flushed via writeCache.store at releaseFromWrite (not at eviction
  // time), and no lost writes (every dirty release must produce exactly one store call,
  // even under concurrent eviction pressure that races with new loads). Scenario 5
  // covers flush-worker concurrency: a reader thread calling loadForRead on the same
  // (fileId, pageIndex) where a store is in flight inside a releaseFromWrite call must
  // observe a consistent state and must not deadlock — the hit path is lock-free
  // (data.get) and does not block on the segment write lock that the in-flight store
  // holds. Both scenarios use deterministic CountDownLatch / CyclicBarrier coordination
  // (no Thread.sleep) and are bounded by @Test(timeout = 60_000) so a coordination bug
  // fails fast instead of hanging Surefire.

  /**
   * Eviction-vs-load under concurrency (scenario 4). Eight worker threads execute a mixed
   * read/write workload while the cache is sized at 1024 pages; we deliberately load 2048
   * distinct pages per worker to force WTinyLFU eviction throughout the run. One thread
   * additionally pins page (0, 0) for the duration of the run by calling
   * {@code loadForRead} without releasing the entry.
   *
   * <p>The test pins three invariants the wrapper contract requires:
   * <ul>
   *   <li><b>Pin retention under concurrent eviction pressure.</b> The pinned entry must
   *   survive — after every worker finishes, reloading (0, 0) must be a cache hit and
   *   return the same {@link CacheEntry} instance. Eviction in the policy refuses to drop
   *   an entry whose {@code usagesCount > 0} (the {@code freeze()} state-machine check),
   *   and the strengthened MT context here is the only thing that distinguishes this from
   *   the single-threaded {@code testPinnedEntryIsRetainedUnderEvictionPressure} above —
   *   a regression that introduced a non-atomic check-then-evict path would flake here.
   *   <li><b>Stores happen at releaseFromWrite, never at eviction.</b> Every worker tracks
   *   the dirty releases it performs (every Nth load is via {@code loadOrAddForWrite}
   *   with {@code changed=true}); the global expected store count is the sum of those
   *   counters. After every worker finishes, {@code MockedWriteCache.storeCount} must
   *   equal this expected sum — no more (no flush-on-eviction), no less (no lost write).
   *   <li><b>Used memory bounded post-run.</b> After all workers complete and assertSize
   *   drains the buffers, used memory must respect the {@code 1024 * PAGE_SIZE} cache
   *   budget.
   * </ul>
   *
   * <p>Pages are partitioned into a [0, 4096) range; the pinned page (0, 0) is excluded
   * from worker page-index selection so the worker's own releaseFromWrite never collides
   * with the pinned entry's read-load (a same-key collision would block the worker on the
   * entry's exclusive lock, not a wrapper-level invariant the test cares about). The
   * file's logical filledUpTo is set to {@link Long#MAX_VALUE} so {@code loadOrAddForWrite}
   * takes the existing-page branch (no markAllocated flag) — the only way {@code store}
   * runs is via {@code changed=true}, isolating this test from the markAllocated branch
   * already covered by Step 1's regression.
   */
  @Test(timeout = 60_000)
  public void testEvictionVsLoadConcurrencyRespectsPinsAndStoreCount() throws Exception {
    final int workerCount = 8;
    final int loadsPerWorker = 2048;
    // pageIndex ranges per worker are disjoint and skip page 0 (pinned), so write-load
    // releases from different workers never collide on the same key.
    final int pagesPerWorker = 512;

    // Make every page appear "existing" so loadOrAddForWrite never flags markAllocated;
    // the only path to store() is via changed=true on releaseFromWrite.
    writeCache.setFilledUpTo(0L, Long.MAX_VALUE);

    // Pin (0, 0) for the duration of the run; never released until after assertions.
    final var pinned = readCache.loadForRead(0, 0, writeCache, false);

    final var pool = Executors.newFixedThreadPool(workerCount);
    final var startBarrier = new CyclicBarrier(workerCount);
    final var expectedStores = new AtomicInteger(0);

    final var loadOrAddBefore = writeCache.loadOrAddCount.get();

    try {
      final List<Future<Integer>> futures = new ArrayList<>();
      for (int w = 0; w < workerCount; w++) {
        final int workerId = w;
        futures.add(pool.submit(() -> {
          startBarrier.await(30, TimeUnit.SECONDS);
          int localDirtyReleases = 0;
          for (int i = 0; i < loadsPerWorker; i++) {
            // page-index space: each worker writes into a disjoint range starting after
            // page 0 to avoid colliding with the pinned entry.
            final int pageOffset = 1 + workerId * pagesPerWorker + (i % pagesPerWorker);
            // Every 5th access is a dirty write-load; the rest are read-loads. The 1:5
            // ratio gives ~409 dirty releases per worker (loadsPerWorker / 5) — enough
            // store invocations that a flush-on-eviction regression would balloon
            // storeCount noticeably, but not so many that the test runtime grows.
            if (i % 5 == 0) {
              final var e = readCache.loadOrAddForWrite(0, pageOffset, writeCache, false, null);
              readCache.releaseFromWrite(e, writeCache, /*changed=*/ true);
              localDirtyReleases++;
            } else {
              final var e = readCache.loadForRead(0, pageOffset, writeCache, false);
              readCache.releaseFromRead(e);
            }
            // Progress log every 500 iterations keeps youtrackdb.test.inactivity.timeout
            // happy during the long run; printed only by worker 0 so the log isn't
            // 8x duplicated.
            if (workerId == 0 && i > 0 && i % 500 == 0) {
              System.out.println("eviction-vs-load worker 0 progressed to i=" + i);
            }
          }
          expectedStores.addAndGet(localDirtyReleases);
          return localDirtyReleases;
        }));
      }

      // Wait for every worker before any assertion. pool.invokeAll-equivalent: we built
      // futures manually so the Futures are in-hand before shutdown; this prevents a
      // racing future from completing after shutdownNow() and leaking a thread.
      for (final var f : futures) {
        f.get(30, TimeUnit.SECONDS);
      }

      // Drain the read batch + striped buffers so the policy state is observable.
      readCache.assertConsistency();
      readCache.assertSize();

      // Invariant 1: pinned entry survived eviction pressure. Reload (0, 0); must be a
      // cache hit (no loadOrAdd invocation increment beyond what the workers did).
      final var loadOrAddBeforeReload = writeCache.loadOrAddCount.get();
      final var hit = readCache.loadForRead(0, 0, writeCache, false);
      try {
        Assert.assertSame(
            "pinned entry (0, 0) must still be the live cache entry — concurrent eviction"
                + " pressure must not drop a pinned entry",
            pinned, hit);
        Assert.assertEquals(
            "reloading the pinned entry after concurrent eviction pressure must be a"
                + " cache hit (no new writeCache.loadOrAdd invocation)",
            loadOrAddBeforeReload, writeCache.loadOrAddCount.get());
      } finally {
        readCache.releaseFromRead(hit);
      }

      // Invariant 2: stores happen at releaseFromWrite, not eviction. Every worker
      // counted its dirty releases; the global storeCount must match exactly. A
      // flush-on-eviction regression would inflate storeCount above the expected sum;
      // a dropped write would deflate it.
      Assert.assertEquals(
          "global storeCount must equal the total dirty releases performed by workers —"
              + " concurrent eviction must NOT invoke store, and every dirty release must"
              + " produce exactly one store call",
          expectedStores.get(), writeCache.storeCount.get());

      // Invariant 3: used memory bounded post-run.
      Assert.assertTrue(
          "used memory (" + readCache.getUsedMemory() + ") must not exceed the cache"
              + " budget (" + 1024L * PAGE_SIZE + ") after concurrent eviction pressure",
          readCache.getUsedMemory() <= 1024L * PAGE_SIZE);

      // Sanity: workers issued meaningful eviction pressure — loadOrAdd was invoked at
      // least once for every distinct miss (8 workers × 512 pages/worker = 4096 distinct
      // pages, plus the read-only loads that go through the same loadOrAdd primitive on
      // first sight). The cache holds at most 1024 pages, so most loads were misses.
      Assert.assertTrue(
          "sanity: workers must have triggered substantial loadOrAdd traffic to"
              + " meaningfully stress eviction (got " + (writeCache.loadOrAddCount.get()
                  - loadOrAddBefore)
              + ")",
          writeCache.loadOrAddCount.get() - loadOrAddBefore >= 1024);
    } finally {
      // Always release the pin so tearDown's clear() does not throw on a stuck entry.
      readCache.releaseFromRead(pinned);
      pool.shutdown();
      if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
        pool.shutdownNow();
      }
    }
  }

  /**
   * Flush-worker concurrency on the same key (scenario 5). One worker thread completes a
   * {@code loadOrAddForWrite} and releases with {@code changed=true}; the
   * {@code MockedWriteCache.store(...)} call inside that {@code releaseFromWrite}
   * suspends on a {@link CountDownLatch} until the test releases it. While the store is
   * suspended (and the segment write lock for that key is held by the
   * {@code data.compute} that wraps the store call inside {@code releaseFromWrite}), a
   * second reader thread issues {@code loadForRead} on the SAME
   * {@code (fileId, pageIndex)}.
   *
   * <p><b>Wrapper contract under same-key contention.</b> The reader's
   * {@code loadForRead} reads through {@code ConcurrentLongIntHashMap.get}, which begins
   * with a {@link java.util.concurrent.locks.StampedLock#tryOptimisticRead optimistic
   * read}. When the writer's {@code data.compute} holds the segment write lock, the
   * optimistic stamp is invalidated and the reader falls back to a blocking
   * {@code readLock()} call — i.e., the reader waits for the writer's
   * {@code releaseFromWrite} to complete before observing the entry. The test releases
   * the store latch AFTER kicking off the reader, so the reader's wait is bounded: it
   * proceeds the moment the writer's {@code data.compute} returns and the segment write
   * lock is released. This timeline pins three invariants without depending on whether
   * the reader's serialisation against the segment write lock is internal to the map or
   * exposed at the wrapper level:
   * <ul>
   *   <li><b>No deadlock.</b> The reader's {@code loadForRead} eventually completes
   *   without hanging — after the latch release the writer's {@code data.compute}
   *   returns and the reader's blocked {@code readLock} acquisition proceeds. A
   *   regression that introduced a true deadlock (e.g., a reader holding a per-entry
   *   lock the writer also needs) would fail the test as a {@code @Test(timeout)}
   *   miss.
   *   <li><b>No torn read.</b> The reader's returned entry is the SAME instance as the
   *   writer's entry — no second {@code loadOrAdd} invocation, no fresh
   *   {@code CacheEntryImpl}.
   *   <li><b>No double-flush.</b> After the writer's {@code releaseFromWrite}
   *   completes, {@code storeCount} ends at exactly 1 — the reader's
   *   {@code loadForRead} must not have routed through any store path. A regression
   *   that introduced a synchronous flush-on-load fallback would fail this assertion.
   * </ul>
   *
   * <p>Timeline:
   * <pre>
   *   writer thread:                                reader thread:
   *   - loadOrAddForWrite (cache miss, install)
   *   - releaseFromWrite(changed=true)
   *     -> data.compute starts (segment write lock held)
   *     -> store() awaits on latch ----------------> loadForRead
   *                                                  -> data.get: optimistic read
   *                                                     invalidated; falls back to
   *                                                     readLock(), blocked until
   *                                                     writer releases segment lock
   *   - latch.countDown() (released by test)
   *     -> store() returns
   *     -> data.compute returns, segment lock released
   *   - releaseFromWrite returns                     - readLock acquired, get returns
   *                                                  - releaseFromRead, return
   * </pre>
   *
   * <p>The reader runs on a separate thread because the writer is blocked inside
   * {@code store()}, so a single-threaded test cannot drive both halves of the timeline.
   */
  @Test(timeout = 60_000)
  public void testFlushWorkerConcurrencyReaderObservesConsistentState() throws Exception {
    final long fileId = 0;
    final int pageIndex = 7;
    // Existing-page branch so the store path is gated on changed=true (not markAllocated).
    writeCache.setFilledUpTo(fileId, Long.MAX_VALUE);

    // storeRelease unblocks the MockedWriteCache.store() body when the test releases it.
    // setStoreBlockLatch installs it; on cleanup we null it back so any tearDown-time
    // store invocation completes synchronously.
    final var storeRelease = new CountDownLatch(1);
    writeCache.setStoreBlockLatch(storeRelease);

    final var pool = Executors.newFixedThreadPool(2);
    final var storesBefore = writeCache.storeCount.get();
    final var loadOrAddBefore = writeCache.loadOrAddCount.get();

    final var writerEntryRef = new AtomicReference<CacheEntry>();
    final var readerEntryRef = new AtomicReference<CacheEntry>();
    final var readerException = new AtomicReference<Throwable>();

    try {
      // Writer thread: load-or-add and release with changed=true; blocks inside store().
      final var writerFuture = pool.submit(() -> {
        final var e = readCache.loadOrAddForWrite(fileId, pageIndex, writeCache, false, null);
        writerEntryRef.set(e);
        // store() will await storeRelease before returning. This call blocks until the
        // test releases the latch below.
        readCache.releaseFromWrite(e, writeCache, /*changed=*/ true);
        return null;
      });

      // Wait until the writer is suspended inside store(): MockedWriteCache.store()
      // increments storeCount BEFORE awaiting the latch, so a storeCount delta confirms
      // the writer has entered the store() body and is at-or-past the segment
      // write-lock acquisition inside data.compute. Using yield-loop polling (no
      // Thread.sleep for correctness — the latch / counter is the source of truth; the
      // yield only avoids a busy-spin CPU burn).
      final long storeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
      while (System.nanoTime() < storeDeadline
          && writeCache.storeCount.get() == storesBefore) {
        Thread.yield();
      }
      Assert.assertEquals(
          "writer thread must have entered the store() body and be awaiting the latch"
              + " before the reader fires",
          storesBefore + 1, writeCache.storeCount.get());

      // Reader thread: loadForRead on the SAME (fileId, pageIndex) while the writer is
      // suspended inside store() (with the segment write lock held inside data.compute).
      // ConcurrentLongIntHashMap.get's optimistic-read stamp will be invalidated by the
      // writer's pending write, so the reader falls back to a blocking readLock — but
      // the readLock proceeds the moment we release the store latch below, so the
      // reader's wait is bounded.
      final var readerFuture = pool.submit(() -> {
        try {
          final var e = readCache.loadForRead(fileId, pageIndex, writeCache, false);
          readerEntryRef.set(e);
          readCache.releaseFromRead(e);
        } catch (Throwable t) {
          readerException.set(t);
        }
        return null;
      });

      // Give the reader a brief moment to enter its blocked readLock so we exercise the
      // serialise-on-segment-write-lock path (not the post-release fast path). This is
      // a yield-loop with a 1s deadline — we do not depend on it for correctness; the
      // assertions below pass whether the reader is mid-readLock or has not yet
      // started. We just want the test to actually exercise the contention window most
      // of the time, so a regression introducing a true deadlock is more likely to
      // surface as a hang rather than slipping through the post-release fast path.
      final long readerStartDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
      while (System.nanoTime() < readerStartDeadline && !readerFuture.isDone()) {
        Thread.yield();
      }

      // Release the store: the writer's data.compute returns, the segment write lock is
      // released, and the reader's blocked readLock proceeds (or the reader's
      // optimistic read succeeds on a re-stamped retry). Either way, the reader
      // observes the same CacheEntry instance the writer installed.
      storeRelease.countDown();

      // Both threads must complete promptly. If the hit path deadlocked (e.g., a
      // regression that introduced a per-entry lock the writer also holds), this
      // would time out.
      readerFuture.get(20, TimeUnit.SECONDS);
      writerFuture.get(20, TimeUnit.SECONDS);

      Assert.assertNull(
          "reader thread must not throw on a same-key load while a store is in flight: "
              + readerException.get(),
          readerException.get());
      Assert.assertSame(
          "reader thread must observe the same CacheEntry instance the writer installed —"
              + " no torn read, no second loadOrAdd, no fresh CacheEntryImpl",
          writerEntryRef.get(), readerEntryRef.get());
      Assert.assertEquals(
          "reader's loadForRead must not have routed through writeCache.loadOrAdd — it"
              + " must have hit the data.get fast path",
          loadOrAddBefore + 1, writeCache.loadOrAddCount.get());

      // Final invariant: exactly one store call across the full test — no double-flush
      // from the reader's path, no second store from any retry / fallback path.
      Assert.assertEquals(
          "exactly one store call must have happened across the writer + reader run —"
              + " a double-flush would indicate the reader incorrectly routed through a"
              + " store-on-load path",
          storesBefore + 1, writeCache.storeCount.get());
    } finally {
      // Always release the latch even on assertion failure, otherwise the writer thread
      // hangs and Surefire fork holds open until pool.awaitTermination times out below.
      storeRelease.countDown();
      // Clear the latch so any subsequent store call from tearDown (e.g., evictions of
      // surviving dirty pages — not expected here, but defensive) completes
      // synchronously.
      writeCache.setStoreBlockLatch(null);
      pool.shutdown();
      if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
        pool.shutdownNow();
      }
    }
  }

  // --- loadOrAddForWrite cacheSize tracking tests ---

  /**
   * The write-load primitive {@code loadOrAddForWrite} must increment cacheSize on the
   * extend branch so that getUsedMemory() reflects the newly allocated page. This is the
   * root-cause fix for cacheSize counter drift — without the increment in {@code doLoad},
   * eviction decrements for freshly allocated pages would drive cacheSize negative,
   * eventually crashing {@code clear()} with IllegalArgumentException from
   * ArrayList(negativeCapacity).
   */
  @Test
  public void testLoadOrAddForWriteIncrementsCacheSize() throws Exception {
    long initialMemory = readCache.getUsedMemory();

    var entry = readCache.loadOrAddForWrite(0, 0, writeCache, false,
        new LogSequenceNumber(-1, -1));
    readCache.releaseFromWrite(entry, writeCache, false);

    // cacheSize must have been incremented: memory should increase by exactly one page.
    Assert.assertEquals(
        "loadOrAddForWrite must increment cacheSize",
        initialMemory + PAGE_SIZE, readCache.getUsedMemory());

    readCache.assertSize();
    readCache.assertConsistency();
  }

  /**
   * Multiple {@code loadOrAddForWrite} calls across different file IDs must each increment
   * cacheSize correctly, keeping memory tracking accurate. Uses different fileIds with the
   * same pageIndex 0 to mirror the MockedWriteCache stub semantics (loadOrAdd always
   * returns a freshly-allocated pointer for the requested pageIndex).
   */
  @Test
  public void testMultipleLoadOrAddForWriteCallsTrackMemoryCorrectly() throws Exception {
    int allocCount = 5;
    var entries = new ArrayList<CacheEntry>();
    for (int fileId = 0; fileId < allocCount; fileId++) {
      var entry = readCache.loadOrAddForWrite(fileId, 0, writeCache, false,
          new LogSequenceNumber(-1, -1));
      entries.add(entry);
    }

    Assert.assertEquals(
        "Each loadOrAddForWrite must increment cacheSize",
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
   * Happy-path: {@code closeStorage} populates the read cache, then drains every entry
   * belonging to this storage in one pass, decrements {@code cacheSize} for each drained
   * entry, and closes the underlying write cache exactly once. Covers the sequencing
   * {@code flushCurrentThreadReadBatch → evictionLock → emptyBuffers →
   * data.removeByStorageId(writeCache.getId()) → (per entry: freeze → policy.onRemove →
   * cacheSize.decrementAndGet) → writeCache.close()} implemented in
   * {@code LockFreeReadCache.drainAllEntries}.
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
   * When {@code removeByStorageId} leaves a section empty, it calls {@code shrinkIfGrown} to
   * reset the section's backing array to its constructor-time capacity, releasing the large
   * arrays accumulated during normal operation. After loading many more pages than the initial
   * per-section capacity, sections grow; closeStorage must shrink them back. Without this, a
   * long-lived JVM that closes and reopens storages would retain the peak array sizes forever.
   */
  @Test
  public void testCloseStorageShrinksMapCapacityAfterDrain() throws Exception {
    // Capture capacity of a fresh, empty map to assert growth later.
    long initialCapacity = getDataMap().capacity();

    // Load enough distinct pages to force sections to grow beyond their initial capacity.
    int pageCount = 2000;
    for (int i = 0; i < pageCount; i++) {
      var entry = readCache.loadForRead(0, i, writeCache, false);
      readCache.releaseFromRead(entry);
    }

    long preCloseCapacity = getDataMap().capacity();
    Assert.assertTrue(
        "sanity: 2000 loads must have grown the map past its initial capacity "
            + "(initial=" + initialCapacity + ", grown=" + preCloseCapacity + ")",
        preCloseCapacity > initialCapacity);

    readCache.closeStorage(writeCache);

    Assert.assertEquals(
        "closeStorage must shrink map capacity back to its constructor-time value",
        initialCapacity, getDataMap().capacity());
    Assert.assertEquals(
        "map must be empty after closeStorage", 0L, getDataMap().size());
  }

  /**
   * Freeze-failure contract (remove-first-then-freeze, with re-insertion on failure):
   * {@code drainAllEntries} atomically removes this storage's entries from the map via
   * {@code removeByStorageId} <em>before</em> attempting to freeze them. When {@code freeze()}
   * fails on a pinned entry, the un-frozen entry is re-inserted into the map so that a retry
   * after the caller releases the pin can drain it; the method then throws {@link
   * StorageException}. Without the re-insertion, a single pinned page would leak every
   * already-removed cache entry of this storage (out-of-map, unfrozen, policy zombies, direct
   * memory pinned). Verified end-to-end by:
   * <ol>
   *   <li>Loading a single entry and holding its read lock (pin).</li>
   *   <li>Asserting {@code closeStorage} throws StorageException with a message that names the
   *       offending page index and fileId.</li>
   *   <li>Asserting the pinned entry is BACK in the map after the abort (re-inserted so retry
   *       can see it), the pinned entry is NOT frozen, {@code cacheSize} is unchanged, and
   *       {@code writeCache.close()} was not invoked.</li>
   *   <li>Releasing the pin and re-invoking {@code closeStorage} — the retry must drain the
   *       re-inserted entry (cacheSize back to 0, map empty) and close the write cache
   *       exactly once in the retry attempt.</li>
   * </ol>
   */
  @Test
  public void testCloseStorageAbortsOnFreezeFailureAndIsRetryable() throws Exception {
    var pinnedEntry = readCache.loadForRead(0, 0, writeCache, false);
    // Do NOT release — the entry's use count stays at 1, so freeze() returns false.

    int preCacheSize = getCacheSizeCounter();
    Assert.assertEquals("sanity: exactly one entry in the map", 1L, getDataMap().size());
    Assert.assertEquals("sanity: cacheSize accounts for the loaded entry", 1, preCacheSize);

    try {
      readCache.closeStorage(writeCache);
      Assert.fail("closeStorage must throw StorageException when an entry cannot be frozen");
    } catch (StorageException expected) {
      // The exception message must name the exact page index and file id that blocked the
      // close so operators can identify the leaking caller — loose contains("Page with index")
      // would pass a buggy format like "Page with index null...".
      Assert.assertNotNull("exception message must not be null", expected.getMessage());
      Assert.assertTrue(
          "exception message must name page index 0 and file id 0: " + expected.getMessage(),
          expected.getMessage().contains("Page with index 0")
              && expected.getMessage().contains("for file id 0")
              && expected.getMessage().contains("cannot be removed"));
    }

    // Re-insertion contract: the pinned entry is BACK in the map after the abort so that a
    // retry (after the pin is released) can drain it. If we left it out, every remaining cache
    // entry for this storage would be silently leaked.
    Assert.assertEquals(
        "pinned entry must be re-inserted after freeze failure — retry needs to see it",
        1L, getDataMap().size());
    Assert.assertFalse(
        "pinned entry must NOT be frozen when drainAllEntries aborts on it",
        pinnedEntry.isFrozen());
    Assert.assertEquals(
        "cacheSize must be unchanged after aborted close (entry was re-inserted)",
        preCacheSize, getCacheSizeCounter());
    Assert.assertEquals(
        "writeCache.close() must NOT be called when drainAllEntries aborts",
        0, writeCache.closeCount.get());

    // Retry: once the pin is released, closeStorage must drain the re-inserted entry and
    // close the write cache.
    readCache.releaseFromRead(pinnedEntry);
    readCache.closeStorage(writeCache);

    Assert.assertEquals(
        "retry must drain the re-inserted entry", 0L, getDataMap().size());
    Assert.assertEquals(
        "retry must decrement cacheSize back to zero", 0, getCacheSizeCounter());
    Assert.assertEquals(
        "retry must close writeCache exactly once in the retry attempt",
        1, writeCache.closeCount.get());
  }

  /**
   * Cross-storage isolation: {@code LockFreeReadCache} is a JVM singleton shared by every open
   * storage. Closing ONE storage must leave entries belonging to OTHER live storages untouched;
   * otherwise concurrent {@code doLoad} calls on other storages would observe a frozen entry and
   * spin forever. This was the root cause of the CI hang that preceded this test.
   *
   * <p>Populates the cache from two write caches with distinct storage ids (0 and 42), closes
   * storage 0, and asserts that storage 42's entries remain present, alive, and loadable via
   * cache-hit semantics (no re-insertion as a miss).
   */
  @Test
  public void testCloseStorageDoesNotAffectOtherStorages() throws Exception {
    var otherWriteCache = new MockedWriteCache(bufferPool, 42);

    // Populate: 5 pages in this writeCache (storageId=0), 5 pages in otherWriteCache.
    int pages = 5;
    for (int p = 0; p < pages; p++) {
      readCache.releaseFromRead(readCache.loadForRead(0, p, writeCache, false));
      readCache.releaseFromRead(readCache.loadForRead(0, p, otherWriteCache, false));
    }

    Assert.assertEquals(
        "sanity: 2 storages * 5 pages = 10 entries before close",
        10L, getDataMap().size());

    readCache.closeStorage(writeCache);

    Assert.assertEquals(
        "closeStorage must only remove its own storage's entries — the other storage's 5 pages "
            + "must still be in the map",
        5L, getDataMap().size());
    Assert.assertEquals(
        "cacheSize must reflect only the surviving entries",
        5, getCacheSizeCounter());

    // Cache-hit semantics check: loading an already-cached page on the OTHER storage must hit
    // the cache, so cacheSize does not grow. A regression that accidentally evicted other
    // storages' entries would turn this into a cache miss and bump cacheSize.
    int cacheSizeBeforeReloads = getCacheSizeCounter();
    for (int p = 0; p < pages; p++) {
      var entry = readCache.loadForRead(0, p, otherWriteCache, false);
      Assert.assertFalse(
          "entry for other storage's page " + p + " must not be frozen by the other close",
          entry.isFrozen());
      readCache.releaseFromRead(entry);
    }
    Assert.assertEquals(
        "reloading other-storage pages after close must be cache hits (cacheSize unchanged)",
        cacheSizeBeforeReloads, getCacheSizeCounter());

    // Cleanup: drain the other storage so @After doesn't leak pointers.
    readCache.closeStorage(otherWriteCache);
  }

  /**
   * Cross-storage isolation under a freeze failure: closing storage A fails because one of A's
   * pages is pinned. The exception must not touch storage B's entries — they must still be
   * present, alive, and unpinned. A subsequent retry (after releasing A's pin) must drain A
   * without touching B, and B can then close cleanly. This is the critical invariant that
   * makes the CI-hang fix safe: even when close aborts mid-way, other live storages keep
   * functioning.
   */
  @Test
  public void testCloseStorageFreezeFailureDoesNotAffectOtherStorages() throws Exception {
    var otherWriteCache = new MockedWriteCache(bufferPool, 99);

    // One pinned page in storageId=0.
    var pinnedEntry = readCache.loadForRead(0, 0, writeCache, false);
    // Three clean pages in storageId=99 (otherWriteCache).
    for (int p = 0; p < 3; p++) {
      readCache.releaseFromRead(readCache.loadForRead(0, p, otherWriteCache, false));
    }

    try {
      try {
        readCache.closeStorage(writeCache);
        Assert.fail("closeStorage must throw StorageException when a page is pinned");
      } catch (StorageException expected) {
        // Exception must name the blocking page so operators can diagnose the leak.
        Assert.assertNotNull(expected.getMessage());
        Assert.assertTrue(
            "exception must name storage 0's pinned page: " + expected.getMessage(),
            expected.getMessage().contains("Page with index 0")
                && expected.getMessage().contains("cannot be removed"));
      }

      // 3 storage-99 entries + 1 re-inserted pinned storage-0 entry = 4.
      Assert.assertEquals(
          "after freeze failure: 3 surviving storage-99 + 1 re-inserted pinned = 4",
          4L, getDataMap().size());
      Assert.assertEquals(
          "cacheSize must be unchanged by a freeze failure",
          4, getCacheSizeCounter());

      // Other storage's entries must still be loadable (cache-hit, not re-inserted).
      int cacheSizeBeforeReloads = getCacheSizeCounter();
      for (int p = 0; p < 3; p++) {
        var entry = readCache.loadForRead(0, p, otherWriteCache, false);
        Assert.assertFalse(
            "other storage's entry " + p + " must not be frozen",
            entry.isFrozen());
        readCache.releaseFromRead(entry);
      }
      Assert.assertEquals(
          "reloading other-storage pages must be cache hits",
          cacheSizeBeforeReloads, getCacheSizeCounter());
    } finally {
      // Always release the pin so tearDown doesn't fail to clear the cache.
      readCache.releaseFromRead(pinnedEntry);
    }

    // Retry closing storageId=0 after releasing the pin — must drain only storage 0's entry
    // (the re-inserted one), leaving storage 99's 3 entries intact.
    readCache.closeStorage(writeCache);
    Assert.assertEquals(
        "retry must not touch other storage's 3 entries",
        3L, getDataMap().size());
    Assert.assertEquals(
        "retry must decrement cacheSize by 1 (the formerly-pinned entry)",
        3, getCacheSizeCounter());

    readCache.closeStorage(otherWriteCache);
    Assert.assertEquals(
        "final close of storage 99 must empty the cache",
        0, getCacheSizeCounter());
  }

  /**
   * Empty-storage close: closing a storage that never loaded any pages must still invoke
   * {@code writeCache.close()} exactly once. A regression that guarded
   * {@code writeCache.close()} behind "drain loop did some work" would silently skip the
   * file-close I/O and corrupt shutdown.
   */
  @Test
  public void testCloseStorageOnEmptyStorageStillClosesWriteCacheExactlyOnce() throws Exception {
    Assert.assertEquals(
        "sanity: cache is empty before closeStorage", 0, readCache.getUsedMemory());
    Assert.assertEquals(
        "sanity: writeCache has not been closed", 0, writeCache.closeCount.get());

    readCache.closeStorage(writeCache);

    Assert.assertEquals(
        "closeStorage on an empty storage must still invoke writeCache.close() exactly once",
        1, writeCache.closeCount.get());
    Assert.assertEquals(0, getCacheSizeCounter());
    Assert.assertEquals(0L, getDataMap().size());
  }

  /**
   * Partial freeze failure across many entries: loads 5 clean pages + 1 pinned page for the
   * same storage, then calls {@code closeStorage}. The 5 frozen-successfully entries must be
   * fully processed (out of map, policy.onRemove called, cacheSize decremented) while the
   * single pinned entry is re-inserted so a retry can handle it. A regression that threw on
   * the first failure without continuing the loop would leak the other 4 clean entries of the
   * batch (un-frozen, policy-zombies, direct memory pinned until JVM exit).
   */
  @Test
  public void testCloseStoragePartialFreezeFailureProcessesAllNonPinnedEntries() throws Exception {
    int cleanPages = 5;
    for (int p = 0; p < cleanPages; p++) {
      readCache.releaseFromRead(readCache.loadForRead(0, p, writeCache, false));
    }
    // Pin the 6th page. Freeze will fail on it.
    var pinnedEntry = readCache.loadForRead(0, cleanPages, writeCache, false);

    Assert.assertEquals(
        "sanity: 6 entries in the map before close",
        cleanPages + 1L, getDataMap().size());
    Assert.assertEquals(
        "sanity: cacheSize matches map size",
        cleanPages + 1, getCacheSizeCounter());

    try {
      try {
        readCache.closeStorage(writeCache);
        Assert.fail("closeStorage must throw StorageException on the pinned entry");
      } catch (StorageException expected) {
        // expected
      }

      // Exactly 1 entry remains (the re-inserted pinned one); the 5 clean entries were fully
      // processed: frozen, onRemove'd, and decremented from cacheSize.
      Assert.assertEquals(
          "5 clean entries must be fully removed from the map; only the pinned one survives "
              + "via re-insertion",
          1L, getDataMap().size());
      Assert.assertEquals(
          "cacheSize must have been decremented for each of the 5 successfully-frozen entries",
          1, getCacheSizeCounter());
      Assert.assertFalse(
          "the re-inserted pinned entry must NOT be frozen",
          pinnedEntry.isFrozen());
      // Cross-check the invariant eden + probation + protection == cacheSize == data.size().
      // The re-inserted un-frozen entry was never removed from the policy's LRU lists, so
      // policy state must still match cacheSize / data.size(). A regression that decremented
      // cacheSize for the failed entry (or called policy.onRemove on the un-frozen re-inserted
      // one) would be caught here.
      readCache.assertSize();
      readCache.assertConsistency();
    } finally {
      readCache.releaseFromRead(pinnedEntry);
    }

    // Retry after unpinning must drain the formerly-pinned entry cleanly.
    readCache.closeStorage(writeCache);
    Assert.assertEquals("retry must empty the map", 0L, getDataMap().size());
    Assert.assertEquals(
        "retry must decrement cacheSize to zero", 0, getCacheSizeCounter());
    Assert.assertEquals(
        "writeCache.close() must be invoked exactly once across the failed attempt + retry",
        1, writeCache.closeCount.get());
  }

  /**
   * Reproducer / live-check for the CI-hang root cause: a reader thread on storage B keeps
   * calling {@code loadForRead} while storage A closes on the main thread. In the buggy
   * implementation (pre-fix, unfiltered drain), storage B's entries became frozen mid-close
   * and the reader's {@code doLoad} would spin forever in its {@code while (true)} loop.
   *
   * <p>With the fix, the reader must keep making progress during and after the close — this
   * is the only test shape that witnesses the absence of the spin at runtime. A regression
   * that froze other storages' entries (or left a frozen-in-map window) would hang the reader
   * and fail the 10s join timeout below.
   */
  @Test
  public void testCloseStorageDoesNotHangConcurrentReadsOnOtherStorages() throws Exception {
    var otherWriteCache = new MockedWriteCache(bufferPool, 42);
    int pages = 50;
    for (int p = 0; p < pages; p++) {
      readCache.releaseFromRead(readCache.loadForRead(0, p, writeCache, false));
      readCache.releaseFromRead(readCache.loadForRead(0, p, otherWriteCache, false));
    }

    var readerStarted = new CountDownLatch(1);
    var stopReader = new AtomicBoolean(false);
    var readsCompleted = new AtomicLong();
    var readerFailure = new AtomicReference<Throwable>();

    Thread reader = new Thread(() -> {
      try {
        var rng = ThreadLocalRandom.current();
        readerStarted.countDown();
        while (!stopReader.get()) {
          int p = rng.nextInt(pages);
          var entry = readCache.loadForRead(0, p, otherWriteCache, false);
          Assert.assertFalse(
              "other storage's entry " + p + " must never be frozen during unrelated close",
              entry.isFrozen());
          readCache.releaseFromRead(entry);
          readsCompleted.incrementAndGet();
        }
      } catch (Throwable t) {
        readerFailure.set(t);
      }
    }, "other-storage-reader");
    reader.start();

    Assert.assertTrue("reader thread must start within 5s",
        readerStarted.await(5, TimeUnit.SECONDS));
    long preCloseReads = readsCompleted.get();

    // Close storage 0 while reader hammers storage 42. If the fix regresses and any storage-42
    // entry becomes frozen-in-map (or otherwise unacquirable) the reader's doLoad would spin
    // forever; this line and the subsequent join would not return.
    readCache.closeStorage(writeCache);

    // Give the reader 2 s to accumulate at least 1000 additional reads post-close — a spinning
    // reader would make zero progress.
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (System.nanoTime() < deadline && readsCompleted.get() - preCloseReads < 1000) {
      Thread.yield();
    }

    stopReader.set(true);
    reader.join(TimeUnit.SECONDS.toMillis(10));
    Assert.assertFalse(
        "reader thread must terminate within 10s — a spin regression would leave it alive",
        reader.isAlive());
    Assert.assertNull(
        "reader thread must not have observed a frozen/stale storage-42 entry",
        readerFailure.get());
    Assert.assertTrue(
        "reader must make forward progress during and after close (post-close reads: "
            + (readsCompleted.get() - preCloseReads) + ")",
        readsCompleted.get() - preCloseReads >= 1000);

    readCache.closeStorage(otherWriteCache);
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
    private final int storageId;
    final AtomicInteger closeCount = new AtomicInteger();
    final AtomicInteger deleteCount = new AtomicInteger();
    /**
     * Counts {@link #store} invocations so tests can verify whether {@code releaseFromWrite}
     * actually published a dirty page. Used by the markAllocated branch tests to discriminate
     * the "newly-allocated" vs "existing" loadOrAddForWrite paths: with the flag set, a
     * {@code releaseFromWrite(entry, writeCache, false)} call must still trigger
     * {@code store}; without it, the call must be a no-op.
     */
    final AtomicInteger storeCount = new AtomicInteger();
    /**
     * Counts {@link #load} invocations so {@code silentLoadForRead} regression tests can pin
     * that the silent path no longer routes through this (extending) primitive.
     */
    final AtomicInteger loadCount = new AtomicInteger();
    /**
     * Counts {@link #loadIfPresent} invocations so {@code silentLoadForRead} regression tests
     * can pin that the silent path now routes through this non-extending probe.
     */
    final AtomicInteger loadIfPresentCount = new AtomicInteger();
    /**
     * Counts {@link #loadOrAdd} invocations so write-cache routing tests can pin which
     * primitive a given read-cache wrapper actually consumes. Tracked separately from
     * {@link #loadCount} because the default {@code loadOrAdd} stub no longer delegates to
     * {@code load} — that delegation would silently couple the two counters and hide
     * routing regressions.
     */
    final AtomicInteger loadOrAddCount = new AtomicInteger();
    /**
     * When set, {@link #loadIfPresent} returns {@code null} instead of allocating a fresh
     * pointer. Lets {@code silentLoadForRead} regression tests exercise the "page not present"
     * code path the migration newly enables, without poisoning unrelated tests that still
     * rely on the always-allocate {@link #load} default.
     */
    private volatile boolean loadIfPresentReturnsNull;
    /**
     * When set, {@link #loadOrAdd} returns {@code null} instead of allocating a fresh pointer.
     * Used to force a totality-contract violation so the fail-fast {@link IllegalStateException}
     * thrown by {@code LockFreeReadCache.doLoad} can be exercised in isolation — a future
     * regression that silently masked the null (for example, by re-introducing the legacy
     * {@code addNewPagePointerToTheCache} fallback) would surface a passing test here.
     */
    private volatile boolean loadOrAddReturnsNull;
    /**
     * Latch that {@link #store} awaits before returning. {@code null} (the default) means
     * the store call completes synchronously; a non-null latch holds the flush thread inside
     * {@code store} until a test thread invokes {@link CountDownLatch#countDown()} on it.
     * Wired here so flush-worker / eviction MT scenarios can deterministically suspend a
     * concurrent store without {@code Thread.sleep}.
     */
    private volatile CountDownLatch storeBlockLatch;
    /**
     * Per-fileId logical page count returned from {@link #getFilledUpTo}. Tests use
     * {@link #setFilledUpTo} to simulate a non-empty file so the loadOrAddForWrite
     * "existing page" branch can be exercised; without an explicit override the default of
     * 0 is returned, which makes every page index &gt;= filledUpTo and therefore
     * "newly allocated".
     */
    private final Map<Long, Long> filledUpToByFile = new ConcurrentHashMap<>();

    MockedWriteCache(final ByteBufferPool byteBufferPool) {
      this(byteBufferPool, 0);
    }

    // Tests that exercise storage isolation in drainAllEntries populate the cache from two
    // different writeCache instances and need distinct storage ids in the composed fileId
    // (high 32 bits), so a single-storage close leaves the other storage's entries intact.
    MockedWriteCache(final ByteBufferPool byteBufferPool, final int storageId) {
      this.byteBufferPool = byteBufferPool;
      this.storageId = storageId;
    }

    ByteBufferPool byteBufferPool() {
      return byteBufferPool;
    }

    /**
     * Sets the value {@link #getFilledUpTo} should return for a given fileId. Used by tests
     * that need to discriminate the loadOrAddForWrite extend-vs-existing branch — the
     * existing-page branch only fires when {@code pageIndex < filledUpTo}.
     */
    void setFilledUpTo(final long fileId, final long value) {
      filledUpToByFile.put(fileId, value);
    }

    /**
     * Switches {@link #loadIfPresent} to "miss" mode (returns {@code null}). Used by
     * {@code silentLoadForRead} regression tests to pin that the silent path now routes
     * through the non-extending probe and therefore correctly surfaces a {@code null} on
     * miss instead of magic-stamping a fresh empty buffer.
     */
    void setLoadIfPresentReturnsNull(final boolean value) {
      this.loadIfPresentReturnsNull = value;
    }

    /**
     * Switches {@link #loadOrAdd} to return {@code null} instead of an allocated
     * {@link CachePointer}. Used to drive the fail-fast regression test that pins the
     * {@link IllegalStateException} thrown by {@code LockFreeReadCache.doLoad} when a write
     * cache violates the totality contract.
     */
    void setLoadOrAddReturnsNull(final boolean value) {
      this.loadOrAddReturnsNull = value;
    }

    /**
     * Installs (or removes) a latch that {@link #store} will await before returning. Pass a
     * non-null latch to suspend store invocations until the test releases the latch via
     * {@link CountDownLatch#countDown()}; pass {@code null} to restore the default
     * non-blocking behaviour. Intended for flush-worker concurrency scenarios where a test
     * needs deterministic interleaving without {@code Thread.sleep}.
     */
    void setStoreBlockLatch(final CountDownLatch latch) {
      this.storeBlockLatch = latch;
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
      storeCount.incrementAndGet();
      final var latch = storeBlockLatch;
      if (latch != null) {
        try {
          // Bounded wait: the latch must be released within 60 seconds — matching the
          // @Test timeout used by MT scenarios — so a test bug that forgets to release
          // the latch fails as a timeout instead of hanging Surefire indefinitely.
          if (!latch.await(60, TimeUnit.SECONDS)) {
            throw new IllegalStateException(
                "MockedWriteCache.store: storeBlockLatch was not released within 60s");
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(
              "MockedWriteCache.store interrupted while awaiting storeBlockLatch", e);
        }
      }
    }

    @Override
    public void checkCacheOverflow() {
    }

    @Override
    public CachePointer load(
        final long fileId,
        final long startPageIndex,
        final ModifiableBoolean cacheHit,
        final boolean verifyChecksums) {
      loadCount.incrementAndGet();
      final var pointer = byteBufferPool.acquireDirect(true, Intention.TEST);
      final var cachePointer =
          new CachePointer(pointer, byteBufferPool, fileId, (int) startPageIndex);
      cachePointer.incrementReadersReferrer();
      return cachePointer;
    }

    /**
     * Stub for the total {@code loadOrAdd} primitive. Counts invocations independently of
     * {@link #load} so routing tests can discriminate the two primitives; the default body
     * allocates a fresh {@link CachePointer} (mirroring the always-extend behaviour of the
     * production disk engine on a miss). {@link #setLoadOrAddReturnsNull} flips it to a
     * contract-violating null-return so the fail-fast {@link IllegalStateException} site in
     * {@code LockFreeReadCache.doLoad} can be exercised in isolation.
     */
    @Override
    public CachePointer loadOrAdd(
        final long fileId, final long pageIndex, final boolean verifyChecksums) {
      loadOrAddCount.incrementAndGet();
      if (loadOrAddReturnsNull) {
        return null;
      }
      final var pointer = byteBufferPool.acquireDirect(true, Intention.TEST);
      final var cachePointer =
          new CachePointer(pointer, byteBufferPool, fileId, (int) pageIndex);
      cachePointer.incrementReadersReferrer();
      return cachePointer;
    }

    /**
     * Stub for the non-extending silent-read probe. By default delegates to {@link #load} so
     * the mock keeps its always-allocate semantics for tests that don't care about the
     * probe-vs-extend distinction. {@link #setLoadIfPresentReturnsNull} flips it to a true
     * "miss" probe (returns {@code null}) so {@code silentLoadForRead} regression tests can
     * exercise the "page not present" code path.
     */
    @Override
    public CachePointer loadIfPresent(
        final long fileId, final long pageIndex, final boolean verifyChecksums) {
      loadIfPresentCount.incrementAndGet();
      if (loadIfPresentReturnsNull) {
        return null;
      }
      return load(fileId, pageIndex, new ModifiableBoolean(), verifyChecksums);
    }

    @Override
    public void flush(final long fileId) {
    }

    @Override
    public void flush() {
    }

    @Override
    public long getFilledUpTo(final long fileId) {
      return filledUpToByFile.getOrDefault(fileId, 0L);
    }

    @Override
    public long physicalSizeForBackupSnapshot(final long fileId) {
      // Mock parallel: delegates to getFilledUpTo so tests that pre-seed
      // filledUpToByFile (via setFilledUpTo) observe the same answer via the
      // backup-snapshot helper.
      return getFilledUpTo(fileId);
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
    public boolean shrinkFile(final long fileId, final long targetBytes) {
      // Mock not exercised by the recovery-time orphan-truncation pass; surfacing UOE
      // catches an accidental call from a future test that should use the production
      // WriteCache impl instead. The throw satisfies the new boolean return type, so the
      // void->boolean migration leaves this deliberate trip-wire body intact.
      throw new UnsupportedOperationException("shrinkFile is not supported by test mock");
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
      return storageId;
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
