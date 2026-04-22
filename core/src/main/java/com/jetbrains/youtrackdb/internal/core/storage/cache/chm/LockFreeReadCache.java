package com.jetbrains.youtrackdb.internal.core.storage.cache.chm;

import com.jetbrains.youtrackdb.internal.common.concur.collection.CASObjectArray;
import com.jetbrains.youtrackdb.internal.common.concur.lock.LockManager;
import com.jetbrains.youtrackdb.internal.common.concur.lock.PartitionedLockManager;
import com.jetbrains.youtrackdb.internal.common.concur.lock.ThreadInterruptedException;
import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.common.directmemory.PageFrame;
import com.jetbrains.youtrackdb.internal.common.directmemory.PageFramePool;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.CoreMetrics;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.MetricsRegistry;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.Ratio;
import com.jetbrains.youtrackdb.internal.common.types.ModifiableBoolean;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.storage.cache.AbstractWriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntryImpl;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.FileHandler;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.chm.readbuffer.BoundedBuffer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.chm.readbuffer.Buffer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.chm.writequeue.MPSCLinkedQueue;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALChanges;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALPageChangesPortion;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import javax.annotation.Nullable;

/**
 * Disk cache based on ConcurrentHashMap and eviction policy which is asynchronously processed by
 * handling set of events logged in lock free event buffer. This feature first was introduced in
 * Caffeine framework <a href="https://github.com/ben-manes/caffeine">...</a> and in
 * ConcurrentLinkedHashMap library
 * <a href="https://github.com/ben-manes/concurrentlinkedhashmap">...</a>. The difference is that
 * if consumption of
 * memory in cache is bigger than 1% disk cache is switched from asynchronous
 * processing of stream of events to synchronous processing. But that is true only for threads which
 * cause loading of additional pages from write cache to disk cache. Window TinyLFU policy is used
 * as cache eviction policy because it prevents usage of ghost entries and as result considerably
 * decrease usage of heap memory.
 *
 * <p>Internal storage uses a per-file {@link CASObjectArray}{@code <CacheEntry>} (indexed by page
 * index) instead of a flat {@code ConcurrentHashMap<PageKey, CacheEntry>}. This reduces hash
 * collisions and improves cache-line locality for sequential page access patterns. The public API
 * uses {@link FileHandler} objects (which carry both the file id and the backing array) in method
 * signatures, in line with the current {@link ReadCache} interface contract.
 */
public final class LockFreeReadCache implements ReadCache {

  private static final int N_CPU = Runtime.getRuntime().availableProcessors();
  private static final int WRITE_BUFFER_MAX_BATCH = 128 * ceilingPowerOfTwo(N_CPU);

  /**
   * Batch size for accumulating read buffer entries before flushing to the striped buffer.
   * This amortizes the per-entry overhead of striped buffer probe lookup, volatile table
   * read, CAS contention, and drain-status check — all of which are paid once per batch
   * instead of once per cache hit.
   */
  private static final int READ_BATCH_SIZE = 16;

  static final CacheEntry LOCK_FREE_READ_CACHE_CACHE_ENTRY_PLACEHOLDER =
      new CacheEntryPlaceholder();

  /**
   * Per-file storage map: file id to {@link FileHandler} whose {@code casArray()} is a
   * {@link CASObjectArray}{@code <CacheEntry>} indexed by page index.
   * This is the same map passed to {@link WTinyLFUPolicy} so that the policy can locate and
   * evict individual cache entries without needing a separate flat map.
   */
  private final ConcurrentHashMap<Long /*fileId*/, FileHandler> data;
  private final Lock evictionLock = new ReentrantLock();

  private final WTinyLFUPolicy policy;

  private final Buffer readBuffer = new BoundedBuffer();
  private final MPSCLinkedQueue<CacheEntry> writeBuffer = new MPSCLinkedQueue<>();
  private final LockManager<PageKey> lockManager = new PartitionedLockManager<PageKey>();
  private final AtomicInteger cacheSize = new AtomicInteger();
  private final int maxCacheSize;

  /**
   * Status which indicates whether flush of buffers should be performed or may be delayed.
   */
  private final AtomicReference<DrainStatus> drainStatus = new AtomicReference<>(DrainStatus.IDLE);

  /**
   * Thread-local batch for accumulating cache entries before offering them to the
   * striped read buffer. Access is single-threaded (thread-local), so no synchronization
   * is needed on the batch or its index.
   */
  private final ThreadLocal<ReadBatch> readBatch =
      ThreadLocal.withInitial(() -> new ReadBatch(READ_BATCH_SIZE));

  private final int pageSize;

  private final PageFramePool pageFramePool;

  private final Ratio cacheHitRatio;

  public LockFreeReadCache(
      final ByteBufferPool bufferPool,
      final long maxCacheSizeInBytes,
      final int pageSize) {
    evictionLock.lock();
    try {
      this.pageSize = pageSize;
      this.pageFramePool = bufferPool.pageFramePool();

      this.maxCacheSize = (int) (maxCacheSizeInBytes / pageSize);
      // Section count rounded up to power of two from N_CPU << 1 (matching
      // ConcurrentHashMap's concurrency level). ConcurrentLongIntHashMap requires
      // power-of-two section count for bit-mask section selection.
      this.data =
          new ConcurrentLongIntHashMap<>(this.maxCacheSize, ceilingPowerOfTwo(N_CPU << 1));
      policy = new WTinyLFUPolicy(data, new FrequencySketch(), cacheSize);
      policy.setMaxSize(this.maxCacheSize);
    } finally {
      evictionLock.unlock();
    }

    this.cacheHitRatio = resolveCacheHitRatio(
        YouTrackDBEnginesManager.instance().getMetricsRegistry());
  }

  /**
   * Resolves the cache-hit ratio metric from the given registry. Returns {@link Ratio#NOOP} when
   * the registry is {@code null}, which happens during early engine startup when the profiler has
   * not yet been initialized. Package-private for testability.
   */
  static Ratio resolveCacheHitRatio(@Nullable final MetricsRegistry registry) {
    return registry != null
        ? registry.globalMetric(CoreMetrics.CACHE_HIT_RATIO)
        : Ratio.NOOP;
  }

  @Override
  public FileHandler addFile(final String fileName, final WriteCache writeCache)
      throws IOException {
    return writeCache.addFile(fileName);
  }

  @Override
  public FileHandler addFile(final String fileName, long fileId, final WriteCache writeCache)
      throws IOException {
    assert fileId >= 0;
    fileId = AbstractWriteCache.checkFileIdCompatibility(writeCache.getId(), fileId);
    return writeCache.addFile(fileName, fileId);
  }

  @Override
  public long addFile(
      final String fileName, long fileId, final WriteCache writeCache, final boolean nonDurable)
      throws IOException {
    assert fileId >= 0;
    fileId = AbstractWriteCache.checkFileIdCompatibility(writeCache.getId(), fileId);
    return writeCache.addFile(fileName, fileId, nonDurable);
  }

  @Override
  public CacheEntry loadForWrite(
      final FileHandler fileHandler,
      final long pageIndex,
      final WriteCache writeCache,
      final boolean verifyChecksums,
      final LogSequenceNumber startLSN) {
    final var cacheEntry = doLoad(fileHandler, (int) pageIndex, writeCache, verifyChecksums);

    if (cacheEntry != null) {
      cacheEntry.acquireExclusiveLock();
      writeCache.updateDirtyPagesTable(cacheEntry.getCachePointer(), startLSN);
    }

    return cacheEntry;
  }

  @Override
  public CacheEntry loadForRead(
      final FileHandler fileHandler,
      final long pageIndex,
      final WriteCache writeCache,
      final boolean verifyChecksums) {
    return doLoad(fileHandler, (int) pageIndex, writeCache, verifyChecksums);
  }

  @Nullable @Override
  public CacheEntry silentLoadForRead(
      final FileHandler fileHandler,
      final int pageIndex,
      final WriteCache writeCache,
      final boolean verifyChecksums) {
    final var fileId = AbstractWriteCache.checkFileIdCompatibility(writeCache.getId(),
        fileHandler.fileId());
    assert fileId == fileHandler.fileId()
        : "File id in handler is different. New FileHandler has to be constructed";

    // Ensure the file is registered in the data map so that the policy and
    // clearFile can reach its CASObjectArray.
    final var handler = data.computeIfAbsent(fileId, id -> fileHandler);

    @SuppressWarnings("unchecked")
    final var casArray = (CASObjectArray<CacheEntry>) handler.casArray();

    for (;;) {
      var cacheEntry = casArray.getOrNull(pageIndex);

      if (cacheEntry == null || cacheEntry == LOCK_FREE_READ_CACHE_CACHE_ENTRY_PLACEHOLDER) {
        final CacheEntry[] updatedEntry = new CacheEntry[1];
        try {
          final var pointer = writeCache.load(fileId, pageIndex,
              new ModifiableBoolean(), verifyChecksums);
          if (pointer != null) {
            updatedEntry[0] = new CacheEntryImpl(fileId, pageIndex, pointer, false, this);
          }
        } catch (final IOException e) {
          throw BaseException.wrapException(
              new StorageException(writeCache.getStorageName(),
                  "Error during loading of page " + pageIndex + " for file " + fileId),
              e, writeCache.getStorageName());
        }

        // Re-read after the I/O; another thread may have raced us.
        cacheEntry = casArray.getOrNull(pageIndex);
        if (cacheEntry == null || cacheEntry == LOCK_FREE_READ_CACHE_CACHE_ENTRY_PLACEHOLDER) {
          cacheEntry = updatedEntry[0];
        }

        if (cacheEntry == null) {
          return null;
        }
      }

      if (cacheEntry.acquireEntry()) {
        return cacheEntry;
      }
    }
  }

  @Nullable private CacheEntry doLoad(
      final FileHandler fileHandler,
      final int pageIndex,
      final WriteCache writeCache,
      final boolean verifyChecksums) {
    final var fileId = AbstractWriteCache.checkFileIdCompatibility(writeCache.getId(),
        fileHandler.fileId());

    // Register the handler so that WTinyLFUPolicy.remove() can reach it.
    final var handler = data.computeIfAbsent(fileId, id -> fileHandler);

    @SuppressWarnings("unchecked")
    final var casArray = (CASObjectArray<CacheEntry>) handler.casArray();

    var success = false;
    try {
      while (true) {
        checkWriteBuffer();

        CacheEntry cacheEntry = casArray.getOrNull(pageIndex);

        var read = true;
        if (cacheEntry != null && cacheEntry != LOCK_FREE_READ_CACHE_CACHE_ENTRY_PLACEHOLDER) {
          if (cacheEntry.acquireEntry()) {
            afterRead(cacheEntry);
            success = true;
            return cacheEntry;
          }
          // Entry is being evicted — spin and retry.
          continue;
        }

        // Page not in cache: acquire a per-page lock and load from the write cache.
        try {
          final var pageKey = new PageKey(fileId, pageIndex);
          final var pageLock = lockManager.acquireExclusiveLock(pageKey);
          try {
            // Re-check under lock: another thread may have populated the slot.
            cacheEntry = casArray.getOrNull(pageIndex);
            if (cacheEntry != null
                && cacheEntry != LOCK_FREE_READ_CACHE_CACHE_ENTRY_PLACEHOLDER) {
              read = true;
            } else {
              final var pointer = writeCache.load(
                  fileId, pageIndex, new ModifiableBoolean(), verifyChecksums);
              if (pointer == null) {
                return null;
              }
              cacheSize.incrementAndGet();
              final var newEntry = new CacheEntryImpl(pageKey, pointer, true, this);
              casArray.set(pageIndex, newEntry, LOCK_FREE_READ_CACHE_CACHE_ENTRY_PLACEHOLDER);
              cacheEntry = newEntry;
              read = false;
            }
          } finally {
            pageLock.unlock();
          }
        } catch (final IOException e) {
          throw BaseException.wrapException(
              new StorageException(writeCache.getStorageName(),
                  "Error during loading of page " + pageIndex + " for file " + fileId),
              e, writeCache.getStorageName());
        }

        if (cacheEntry.acquireEntry()) {
          if (read) {
            success = true;
            afterRead(cacheEntry);
          } else {
            afterAdd(cacheEntry);

            try {
              writeCache.checkCacheOverflow();
            } catch (final java.lang.InterruptedException e) {
              throw BaseException.wrapException(
                  new ThreadInterruptedException("Check of write cache overflow was interrupted"),
                  e, writeCache.getStorageName());
            }
          }

          return cacheEntry;
        }
      }
    } finally {
      cacheHitRatio.record(success);
    }
  }

  private CacheEntry addNewPagePointerToTheCache(final FileHandler fileHandler,
      final int pageIndex) {
    final var pageFrame = pageFramePool.acquire(true, Intention.ADD_NEW_PAGE_IN_DISK_CACHE);
    final var cachePointer = new CachePointer(pageFrame, pageFramePool,
        fileHandler.fileId(), pageIndex);
    cachePointer.incrementReadersReferrer();
    DurablePage.setLogSequenceNumberForPage(
        pageFrame.getBuffer(), new LogSequenceNumber(-1, -1));

    final CacheEntry cacheEntry = new CacheEntryImpl(fileHandler.fileId(), pageIndex,
        cachePointer, true, this);
    cacheEntry.acquireEntry();

    @SuppressWarnings("unchecked")
    final var casArray = (CASObjectArray<CacheEntry>) fileHandler.casArray();

    if (casArray.size() < pageIndex + 1) {
      // Grow the array to at least pageIndex; intermediate slots are filled with placeholder
      // atomically by CASObjectArray itself.
      casArray.set(pageIndex, LOCK_FREE_READ_CACHE_CACHE_ENTRY_PLACEHOLDER,
          LOCK_FREE_READ_CACHE_CACHE_ENTRY_PLACEHOLDER);
    }

    // If the CAS fails, another thread already stored something — that is a bug.
    final var oldPresent = !casArray.compareAndSet(pageIndex,
        LOCK_FREE_READ_CACHE_CACHE_ENTRY_PLACEHOLDER, cacheEntry);
    if (oldPresent) {
      throw new IllegalStateException(
          "Page " + fileHandler.fileId() + ":" + pageIndex + " was allocated in another thread");
    }

    cacheSize.incrementAndGet();
    afterAdd(cacheEntry);

    return cacheEntry;
  }

  @Override
  public void changeMaximumAmountOfMemory(final long maxMemory) {
    evictionLock.lock();
    try {
      policy.setMaxSize((int) (maxMemory / pageSize));
    } finally {
      evictionLock.unlock();
    }
  }

  @Override
  @Nullable public PageFrame getPageFrameOptimistic(final long fileId, final long pageIndex) {
    final var handler = data.get(fileId);
    if (handler == null) {
      return null;
    }

    @SuppressWarnings("unchecked")
    final var casArray = (CASObjectArray<CacheEntry>) handler.casArray();
    final var cacheEntry = casArray.getOrNull((int) pageIndex);

    if (cacheEntry == null || cacheEntry == LOCK_FREE_READ_CACHE_CACHE_ENTRY_PLACEHOLDER
            || !cacheEntry.isAlive()) {
      return null;
    }

    final var cachePointer = cacheEntry.getCachePointer();
    if (cachePointer == null) {
      return null;
    }

    return cachePointer.getPageFrame();
  }

  @Override
  public void recordOptimisticAccess(final long fileId, final long pageIndex) {
    final var handler = data.get(fileId);
    if (handler == null) {
      return;
    }

    @SuppressWarnings("unchecked")
    final var casArray = (CASObjectArray<CacheEntry>) handler.casArray();
    final var cacheEntry = casArray.getOrNull((int) pageIndex);

    // Entry may have been evicted between stamp validation and this call.
    // One missed frequency bump is harmless — skip silently.
    if (cacheEntry != null && cacheEntry != LOCK_FREE_READ_CACHE_CACHE_ENTRY_PLACEHOLDER) {
      afterRead(cacheEntry);
    }
  }

  @Override
  public void releaseFromRead(final CacheEntry cacheEntry) {
    cacheEntry.releaseEntry();

    if (!cacheEntry.insideCache()) {
      cacheEntry.getCachePointer().decrementReadersReferrer();
    }
  }

  @Override
  public void releaseFromWrite(
      final CacheEntry cacheEntry, final WriteCache writeCache, final boolean changed) {
    final var cachePointer = cacheEntry.getCachePointer();
    assert cachePointer != null;

    if (cacheEntry.isNewlyAllocatedPage() || changed) {
      if (cacheEntry.isNewlyAllocatedPage()) {
        cacheEntry.clearAllocationFlag();
      }

      // Virtual-lock pattern: compute() holds the segment write lock while the
      // remapping function executes, even if the key is absent. This ensures
      // writeCache.store() runs atomically w.r.t. concurrent map operations on
      // the same key. If absent, remapping returns null → no-op (no insertion).
      data.compute(
          cacheEntry.getFileId(),
          ? // do we need to add here concurrent casObjectArray(value) comparison
          (fId, casObjectArray, entry) -> {
            writeCache.store(
                cacheEntry.getFileId(), cacheEntry.getPageIndex(), cacheEntry.getCachePointer());
            return entry;
          });
    }

    // We need to release exclusive lock from cache pointer after we put it into the write cache so
    // both "dirty pages" of write
    // cache and write cache itself will contain actual values simultaneously. But because cache
    // entry can be cleared after we put it back to the
    // read cache we make copy of cache pointer before head.
    //
    // Following situation can happen, if we release exclusive lock before we put entry to the write
    // cache.
    // 1. Page is loaded for write, locked and related LSN is written to the "dirty pages" table.
    // 2. Page lock is released.
    // 3. Page is chosen to be flushed on disk and its entry removed from "dirty pages" table
    // 4. Page is added to write cache as dirty
    //
    // So we have situation when page is added as dirty into the write cache but its related entry
    // in "dirty pages" table is removed
    // it is treated as flushed during fuzzy checkpoint and portion of write ahead log which
    // contains not flushed changes is removed.
    // This can lead to the data loss after restore and corruption of data structures
    cacheEntry.releaseExclusiveLock();
    cacheEntry.releaseEntry();
  }

  @Override
  public CacheEntry allocateNewPage(
          final FileHandler fileHandler, final WriteCache writeCache, final LogSequenceNumber startLSN)
          throws IOException {
    final var fileId = AbstractWriteCache.checkFileIdCompatibility(writeCache.getId(),
            fileHandler.fileId());

    final var newPageIndex = writeCache.allocateNewPage(fileId);
    final var cacheEntry = addNewPagePointerToTheCache(fileHandler, newPageIndex);

    cacheEntry.acquireExclusiveLock();
    cacheEntry.markAllocated();
    writeCache.updateDirtyPagesTable(cacheEntry.getCachePointer(), startLSN);
    return cacheEntry;
  }

  private void afterRead(final CacheEntry entry) {
    final var batch = readBatch.get();
    batch.entries[batch.size++] = entry;

    if (batch.size >= READ_BATCH_SIZE) {
      flushReadBatch(batch);
    }
  }

  private void flushReadBatch(final ReadBatch batch) {
    var bufferOverflow = false;
    for (int i = 0; i < batch.size; i++) {
      var result = readBuffer.offer(batch.entries[i]);
      if (result == Buffer.FULL) {
        bufferOverflow = true;
      }
    }
    Arrays.fill(batch.entries, 0, batch.size, null); // release references
    batch.size = 0;

    if (drainStatus.get().shouldBeDrained(bufferOverflow)) {
      tryToDrainBuffers();
    }
  }

  /**
   * Flushes the current thread's read batch to the striped buffer. Call this before any
   * operation that needs the eviction policy to be fully up-to-date (clear, assertSize, etc.).
   */
  private void flushCurrentThreadReadBatch() {
    final var batch = readBatch.get();
    if (batch.size > 0) {
      flushReadBatch(batch);
    }
  }

  private void afterAdd(final CacheEntry entry) {
    afterWrite(entry);
  }

  private void afterWrite(final CacheEntry command) {
    writeBuffer.offer(command);

    drainStatus.lazySet(DrainStatus.REQUIRED);
    if (cacheSize.get() > 1.07 * maxCacheSize) {
      forceDrainBuffers();
    } else {
      tryToDrainBuffers();
    }
  }

  private void forceDrainBuffers() {
    evictionLock.lock();
    try {
      // optimization to avoid to call tryLock if it is not needed
      drainStatus.lazySet(DrainStatus.IN_PROGRESS);
      emptyBuffers();
    } finally {
      // cas operation because we do not want to overwrite REQUIRED status and to avoid false
      // optimization of
      // drain buffer by IN_PROGRESS status
      try {
        drainStatus.compareAndSet(DrainStatus.IN_PROGRESS, DrainStatus.IDLE);
      } finally {
        evictionLock.unlock();
      }
    }
  }

  private void checkWriteBuffer() {
    if (!writeBuffer.isEmpty()) {
      drainStatus.lazySet(DrainStatus.REQUIRED);
      tryToDrainBuffers();
    }
  }

  private void tryToDrainBuffers() {
    if (drainStatus.get() == DrainStatus.IN_PROGRESS) {
      return;
    }

    if (evictionLock.tryLock()) {
      try {
        // optimization to avoid to call tryLock if it is not needed
        drainStatus.lazySet(DrainStatus.IN_PROGRESS);
        drainBuffers();
      } finally {
        // cas operation because we do not want to overwrite REQUIRED status and to avoid false
        // optimization of
        // drain buffer by IN_PROGRESS status
        drainStatus.compareAndSet(DrainStatus.IN_PROGRESS, DrainStatus.IDLE);
        evictionLock.unlock();
      }
    }
  }

  private void drainBuffers() {
    drainWriteBuffer();
    drainReadBuffers();
  }

  private void emptyBuffers() {
    emptyWriteBuffer();
    drainReadBuffers();
  }

  private void drainReadBuffers() {
    readBuffer.drainTo(policy);
  }

  private void drainWriteBuffer() {
    for (var i = 0; i < WRITE_BUFFER_MAX_BATCH; i++) {
      final var entry = writeBuffer.poll();

      if (entry == null) {
        break;
      }

      this.policy.onAdd(entry);
    }
  }

  private void emptyWriteBuffer() {
    while (true) {
      final var entry = writeBuffer.poll();

      if (entry == null) {
        break;
      }

      this.policy.onAdd(entry);
    }
  }

  @Override
  public long getUsedMemory() {
    return ((long) cacheSize.get()) * pageSize;
  }

  @Override
  public void clear() {
    flushCurrentThreadReadBatch();
    evictionLock.lock();
    try {
      emptyBuffers();

      for (final var fileHandler : data.values()) {
        @SuppressWarnings("unchecked")
        final var cacheEntries = (CASObjectArray<CacheEntry>) fileHandler.casArray();
        for (var i = 0; i < cacheEntries.size(); i++) {
          final var entry = cacheEntries.get(i);
          if (entry == null || entry == LOCK_FREE_READ_CACHE_CACHE_ENTRY_PLACEHOLDER) {
            continue;
          }
          if (entry.freeze()) {
            policy.onRemove(entry);
          } else {
            throw new StorageException(null,
                "Page with index "
                    + entry.getPageIndex()
                    + " for file id "
                    + entry.getFileId()
                    + " is used and cannot be removed");
          }
        }
      }

      data.clear();
      cacheSize.set(0);
    } finally {
      evictionLock.unlock();
    }
  }

  @Override
  public void truncateFile(long fileId, final WriteCache writeCache) throws IOException {
    fileId = AbstractWriteCache.checkFileIdCompatibility(writeCache.getId(), fileId);

    writeCache.truncateFile(fileId);
    clearFile(fileId, writeCache);
  }

  @Override
  public void closeFile(final FileHandler fileHandler, final boolean flush,
      final WriteCache writeCache) {
    final var fileId = AbstractWriteCache.checkFileIdCompatibility(writeCache.getId(),
        fileHandler.fileId());
    final var filledUpTo = (int) writeCache.getFilledUpTo(fileId);
    clearFile(fileId, filledUpTo, writeCache);
    writeCache.close(fileHandler, flush);
  }

  @Override
  public void deleteFile(long fileId, final WriteCache writeCache) throws IOException {
    fileId = AbstractWriteCache.checkFileIdCompatibility(writeCache.getId(), fileId);
    final var filledUpTo = (int) writeCache.getFilledUpTo(fileId);
    clearFile(fileId, filledUpTo, writeCache);
    writeCache.deleteFile(fileId);
  }

  @Override
  public void deleteStorage(final WriteCache writeCache) throws IOException {
    drainAllEntries(writeCache);
    writeCache.delete();
  }

  @Override
  public void closeStorage(final WriteCache writeCache) throws IOException {
    drainAllEntries(writeCache);
    writeCache.close();
  }

  /**
   * Single-pass bulk drain used by {@code closeStorage} and {@code deleteStorage}:
   *
   * <ol>
   *   <li>{@code data.removeByStorageId(writeCache.getId())} — one write-locked sweep per
   *       section, atomically removing every entry whose fileId's high 32 bits match this
   *       storage's id. Entries from other storages sharing the same JVM cache are left intact.
   *   <li>For each removed entry: {@code freeze()} + {@code policy.onRemove()} +
   *       {@code cacheSize.decrementAndGet()}. If {@code freeze()} fails (entry still pinned),
   *       throw {@link StorageException} — remaining entries in the batch are left out of the
   *       map and not yet processed, matching the pre-existing semantics of {@link #clearFile}.
   * </ol>
   *
   * <p>This replaces the per-file {@link #clearFile} loop used by {@code closeStorage} and
   * {@code deleteStorage} before. That loop was O(files × total capacity) because every
   * {@code removeByFileId} call linearly swept all 16 sections and same-capacity rehashed any
   * section with a match — on a storage with thousands of files and a warm cache it pegged a
   * close thread at 100 % CPU inside {@code removeByFileId} for 30+ minutes. The new
   * {@code removeByStorageId} collapses that to a single O(total capacity) sweep per close.
   *
   * <p>The method acquires {@link #evictionLock} once for the whole drain rather than per entry
   * as {@link #clearFile} does; close has no progress obligation to other threads. The per-entry
   * {@code writeCache.checkCacheOverflow()} call is intentionally not made — the subsequent
   * {@code writeCache.close()}/{@code writeCache.delete()} triggers a full flush, making the
   * per-entry overflow check redundant and also avoiding its latch wait (see WOWCache#1198).
   *
   * <p><b>Critical ordering — remove from map first, freeze after:</b> freezing an entry while
   * it is still in the map would make concurrent {@code doLoad} callers (for this OR any other
   * live storage sharing the cache) spin forever in their {@code while (true)} loop, because
   * {@code data.get()} would keep returning the frozen entry and {@code acquireEntry()} would
   * keep failing. By removing entries from the map <em>before</em> freezing, concurrent loads
   * either find nothing (and re-load fresh) or find a fresh entry inserted after us — neither
   * can spin.
   *
   * <p><b>Storage-scoped filter:</b> {@link LockFreeReadCache} is a JVM singleton shared by
   * every open storage. A bulk drain that iterates ALL entries (e.g., {@link #clear()}) would
   * freeze entries belonging to other live storages and cause their in-flight {@code doLoad}
   * calls to spin. {@code removeByStorageId} restricts the sweep to entries whose fileId
   * encodes this {@code writeCache}'s id, leaving other storages untouched.
   *
   * <p><b>Freeze-failure handling — re-insert un-frozen entries:</b> freeze only fails when an
   * entry is still pinned (its refcount is {@code > 0}, i.e. some caller is holding it). If we
   * simply threw on the first failure, every already-removed entry AFTER the pinned one would
   * leak — they would be out of the map (retry can't find them), unfrozen (never released from
   * the policy), and their {@link CachePointer}s would never have their referrer counts
   * decremented (pinning the direct memory until JVM exit). To avoid this, we continue the
   * loop on failure, collect un-frozen entries, and re-insert them in the map before throwing.
   * A subsequent retry by the caller (after the pin is released) sees a fresh map containing
   * only the formerly-pinned pages and drains them correctly.
   *
   * <p><b>Durability note:</b> this drain only releases the read-cache's reference on each
   * cache entry. Dirty-page memory is still held by {@link WriteCache} via its own reference
   * count and is flushed/discarded only by {@code writeCache.close()}/{@code delete()}. Do not
   * move the {@code writeCache.close()}/{@code delete()} call ahead of this drain.
   */
  private void drainAllEntries(final WriteCache writeCache) {
    flushCurrentThreadReadBatch();
    evictionLock.lock();
    try {
      emptyBuffers();

      final var removedEntries = data.removeByStorageId(writeCache.getId());

      // First entry that freeze() rejected (still pinned). We use it to build the exception
      // message after re-inserting every un-frozen entry. Null means every entry was frozen.
      CacheEntry firstUnfrozen = null;
      for (final var cacheEntry : removedEntries) {
        if (cacheEntry.freeze()) {
          policy.onRemove(cacheEntry);
          cacheSize.decrementAndGet();
        } else {
          if (firstUnfrozen == null) {
            firstUnfrozen = cacheEntry;
          }
          // Re-insert so a retry after the caller releases the pin can drain this entry
          // through the normal path. Without this, a single pinned page would leak every
          // remaining cache entry for the whole storage (policy zombies + direct-memory
          // reference leak). Close holds the storage's exclusive lock at the caller level,
          // so no concurrent doLoad is racing us on this storage's pages here.
          data.put(cacheEntry.getFileId(), cacheEntry.getPageIndex(), cacheEntry);
        }
      }
      if (firstUnfrozen != null) {
        throw new StorageException(writeCache.getStorageName(),
            "Page with index "
                + firstUnfrozen.getPageIndex()
                + " for file id "
                + firstUnfrozen.getFileId()
                + " is used and cannot be removed");
      }
    } finally {
      evictionLock.unlock();
    }
  }

  private void clearFile(final long fileId, final WriteCache writeCache) {
    flushCurrentThreadReadBatch();
    evictionLock.lock();
    try {
      emptyBuffers();

      // Bulk removal: removeByFileId sweeps each segment linearly under its write
      // lock, collecting removed entries into a list. Entries are returned after
      // the segment lock is released, so the processing below (freeze, onRemove,
      // checkCacheOverflow) does not run under a segment write lock — avoiding
      // StampedLock reentrancy deadlock if callbacks re-enter the map.
      //
      // Precondition: clearFile assumes no concurrent doLoad for the same fileId.
      // A concurrent doLoad could re-insert an entry for a page of this file after
      // removeByFileId completes. This is a pre-existing race (the old per-page
      // loop had the same gap between remove and freeze). Callers ensure this by
      // coordinating file lifecycle at a higher level.
      final var removedEntries = data.removeByFileId(fileId);

      @SuppressWarnings("unchecked")
      final var pageEntries = (CASObjectArray<CacheEntry>) fileHandler.casArray();

      // Iterate only up to filledUpTo: pages beyond that index were never written and the
      // array may hold uninitialized / placeholder slots past that point.
      final var limit = Math.min(filledUpTo, pageEntries.size());
      for (var pageIndex = 0; pageIndex < limit; pageIndex++) {
        final var cacheEntry = pageEntries.get(pageIndex);
        if (cacheEntry == null || cacheEntry == LOCK_FREE_READ_CACHE_CACHE_ENTRY_PLACEHOLDER) {
          continue;
        }
        if (cacheEntry.freeze()) {
          policy.onRemove(cacheEntry);
          cacheSize.decrementAndGet();

          try {
            writeCache.checkCacheOverflow();
          } catch (final java.lang.InterruptedException e) {
            throw BaseException.wrapException(
                new ThreadInterruptedException("Check of write cache overflow was interrupted"),
                e, writeCache.getStorageName());
          }
        } else {
          throw new StorageException(writeCache.getStorageName(),
              "Page with index "
                  + cacheEntry.getPageIndex()
                  + " for file id "
                  + cacheEntry.getFileId()
                  + " is used and cannot be removed");
        }
      }
    } finally {
      evictionLock.unlock();
    }
  }

  void assertSize() {
    flushCurrentThreadReadBatch();
    evictionLock.lock();
    try {
      emptyBuffers();
      policy.assertSize();
    } finally {
      evictionLock.unlock();
    }
  }

  void assertConsistency() {
    flushCurrentThreadReadBatch();
    evictionLock.lock();
    try {
      emptyBuffers();
      policy.assertConsistency();
    } finally {
      evictionLock.unlock();
    }
  }

  private enum DrainStatus {
    IDLE {
      @Override
      boolean shouldBeDrained(final boolean readBufferOverflow) {
        return readBufferOverflow;
      }
    },
    IN_PROGRESS {
      @Override
      boolean shouldBeDrained(final boolean readBufferOverflow) {
        return false;
      }
    },
    REQUIRED {
      @Override
      boolean shouldBeDrained(final boolean readBufferOverflow) {
        return true;
      }
    };

    abstract boolean shouldBeDrained(boolean readBufferOverflow);
  }

  private static final class ReadBatch {

    final CacheEntry[] entries;
    int size;

    ReadBatch(final int capacity) {
      this.entries = new CacheEntry[capacity];
    }
  }

  @SuppressWarnings("SameParameterValue")
  private static int ceilingPowerOfTwo(final int x) {
    // From Hacker's Delight, Chapter 3, Harry S. Warren Jr.
    return 1 << -Integer.numberOfLeadingZeros(x - 1);
  }

  static class CacheEntryPlaceholder implements CacheEntry {

    private final long fileId = -1;
    private final int pageIndex = -1;
    private final LogSequenceNumber lsn = new LogSequenceNumber(-1, -1);
    private CacheEntry emptyPointer;

    @Override
    public CachePointer getCachePointer() {
      throw new UnsupportedOperationException(
          "This object is a placeholder and does not support cachePointer");
    }

    @Override
    public void clearCachePointer() {
    }

    @Override
    public long getFileId() {
      return fileId;
    }

    @Override
    public int getPageIndex() {
      return pageIndex;
    }

    @Override
    public long acquireExclusiveLock() {
      throw new UnsupportedOperationException("This object is a placeholder");
    }

    @Override
    public void releaseExclusiveLock() {
      throw new UnsupportedOperationException("This object is a placeholder");
    }

    @Override
    public long acquireSharedLock() {
      throw new UnsupportedOperationException("This object is a placeholder");
    }

    @Override
    public void releaseSharedLock(long stamp) {
      throw new UnsupportedOperationException("This object is a placeholder");
    }

    @Override
    public int getUsagesCount() {
      return 0;
    }

    @Override
    public void incrementUsages() {
    }

    @Override
    public void decrementUsages() {
    }

    @Override
    public WALChanges getChanges() {
      return new WALPageChangesPortion();
    }

    @Override
    public LogSequenceNumber getEndLSN() {
      return lsn;
    }

    @Override
    public LogSequenceNumber getInitialLSN() {
      return lsn;
    }

    @Override
    public void setInitialLSN(final LogSequenceNumber lsn) {
    }

    @Override
    public void setEndLSN(final LogSequenceNumber endLSN) {
    }

    @Override
    public boolean acquireEntry() {
      throw new UnsupportedOperationException("This object is a placeholder");
    }

    @Override
    public void releaseEntry() {
      throw new UnsupportedOperationException("This object is a placeholder");
    }

    @Override
    public boolean isReleased() {
      return false;
    }

    @Override
    public boolean isAlive() {
      return false;
    }

    @Override
    public boolean freeze() {
      return false;
    }

    @Override
    public boolean isFrozen() {
      return false;
    }

    @Override
    public void makeDead() {
    }

    @Override
    public boolean isDead() {
      return false;
    }

    @Override
    public CacheEntry getNext() {
      return emptyPointer;
    }

    @Override
    public CacheEntry getPrev() {
      return emptyPointer;
    }

    @Override
    public void setPrev(final CacheEntry prev) {
    }

    @Override
    public void setNext(final CacheEntry next) {
    }

    @Override
    public void setContainer(final LRUList lruList) {
    }

    @Override
    public LRUList getContainer() {
      return new LRUList();
    }

    @Override
    public boolean isNewlyAllocatedPage() {
      return false;
    }

    @Override
    public void markAllocated() {
    }

    @Override
    public void clearAllocationFlag() {
    }

    @Override
    public boolean insideCache() {
      return false;
    }

    @Override
    public void close() throws IOException {
    }
  }
}
