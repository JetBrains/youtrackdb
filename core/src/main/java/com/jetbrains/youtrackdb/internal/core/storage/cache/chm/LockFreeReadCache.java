package com.jetbrains.youtrackdb.internal.core.storage.cache.chm;

import com.jetbrains.youtrackdb.internal.common.concur.collection.CASObjectArray;
import com.jetbrains.youtrackdb.internal.common.concur.lock.LockManager;
import com.jetbrains.youtrackdb.internal.common.concur.lock.PartitionedLockManager;
import com.jetbrains.youtrackdb.internal.common.concur.lock.ThreadInterruptedException;
import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
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
 * if consumption of memory in cache is bigger than 1% disk cache is switched from asynchronous
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
   * Batch size for accumulating read-buffer entries before flushing to the striped buffer.
   * This amortises the per-entry overhead of striped-buffer probe lookup, volatile table
   * read, CAS contention, and drain-status check — all of which are paid once per batch
   * instead of once per cache hit.
   */
  private static final int READ_BATCH_SIZE = 16;

  static final CacheEntry LOCK_FREE_READ_CACHE_CACHE_ENTRY_PLACEHOLDER =
      new CacheEntryPlaceholder();

  /**
   * Per-file storage map: file id → {@link FileHandler} whose {@code casArray()} is a
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
   * Thread-local batch for accumulating cache entries before offering them to the striped read
   * buffer. Access is single-threaded (thread-local), so no synchronisation is needed on the
   * batch or its index.
   */
  private final ThreadLocal<ReadBatch> readBatch =
      ThreadLocal.withInitial(() -> new ReadBatch(READ_BATCH_SIZE));

  private final int pageSize;

  private final ByteBufferPool bufferPool;

  private final Ratio cacheHitRatio;

  public LockFreeReadCache(
      final ByteBufferPool bufferPool,
      final long maxCacheSizeInBytes,
      final int pageSize) {
    evictionLock.lock();
    try {
      this.pageSize = pageSize;
      this.bufferPool = bufferPool;

      this.maxCacheSize = (int) (maxCacheSizeInBytes / pageSize);
      this.data = new ConcurrentHashMap<>(this.maxCacheSize, 0.5f, N_CPU << 1);
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
   * not yet been initialised. Package-private for testability.
   */
  static Ratio resolveCacheHitRatio(@Nullable final MetricsRegistry registry) {
    return registry != null
        ? registry.globalMetric(CoreMetrics.CACHE_HIT_RATIO)
        : Ratio.NOOP;
  }

  // -------------------------------------------------------------------------
  // ReadCache — file registration
  // -------------------------------------------------------------------------

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

  // -------------------------------------------------------------------------
  // ReadCache — page loading
  // -------------------------------------------------------------------------

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

  // -------------------------------------------------------------------------
  // Core load logic — CASObjectArray per-file lookup
  // -------------------------------------------------------------------------

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
            success = true;
            afterRead(cacheEntry);
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

  // -------------------------------------------------------------------------
  // Page allocation
  // -------------------------------------------------------------------------

  private CacheEntry addNewPagePointerToTheCache(final FileHandler fileHandler,
      final int pageIndex) {
    final var pointer = bufferPool.acquireDirect(true, Intention.ADD_NEW_PAGE_IN_DISK_CACHE);
    final var cachePointer = new CachePointer(pointer, bufferPool, fileHandler.fileId(), pageIndex);
    cachePointer.incrementReadersReferrer();
    DurablePage.setLogSequenceNumberForPage(
        pointer.getNativeByteBuffer(), new LogSequenceNumber(-1, -1));

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

    afterAdd(cacheEntry);

    return cacheEntry;
  }

  @Override
  public CacheEntry allocateNewPage(
      final FileHandler fileHandler, final WriteCache writeCache, final LogSequenceNumber startLSN)
      throws IOException {
    final var fileId = AbstractWriteCache.checkFileIdCompatibility(writeCache.getId(),
        fileHandler.fileId());
    // Ensure the handler is registered so WTinyLFUPolicy can find it.
    data.computeIfAbsent(fileId, id -> fileHandler);

    final var newPageIndex = writeCache.allocateNewPage(fileId);
    final var cacheEntry = addNewPagePointerToTheCache(fileHandler, newPageIndex);

    cacheEntry.acquireExclusiveLock();
    cacheEntry.markAllocated();
    writeCache.updateDirtyPagesTable(cacheEntry.getCachePointer(), startLSN);
    return cacheEntry;
  }

  // -------------------------------------------------------------------------
  // Cache entry release
  // -------------------------------------------------------------------------

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

      // Use the per-file entry in `data` as a virtual mutex for the store operation,
      // matching the pattern from the original YTDB-165 implementation.
      data.compute(
          cacheEntry.getFileId(),
          (fId, entry) -> {
            writeCache.store(
                cacheEntry.getFileId(), cacheEntry.getPageIndex(), cacheEntry.getCachePointer());
            return entry; // entry may be absent if page is pinned; map used as virtual lock
          });
    }

    // Release the exclusive lock only after the write cache has been updated, to prevent
    // a fuzzy-checkpoint race that can cause data loss on recovery. See the full explanation
    // in the original source.
    cachePointer.releaseExclusiveLock();
    cacheEntry.releaseEntry();
  }

  // -------------------------------------------------------------------------
  // Memory management
  // -------------------------------------------------------------------------

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
  public long getUsedMemory() {
    return ((long) cacheSize.get()) * pageSize;
  }

  // -------------------------------------------------------------------------
  // Cache / file lifecycle
  // -------------------------------------------------------------------------

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
    // Capture filledUpTo *before* truncating so we know which page slots to evict.
    final var filledUpTo = (int) writeCache.getFilledUpTo(fileId);
    writeCache.truncateFile(fileId);
    clearFile(fileId, filledUpTo, writeCache);
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
    // Snapshot filledUpTo for every file *before* any deletion touches the write cache,
    // to avoid TOCTOU races where getFilledUpTo() returns 0 after truncation has started.
    final var files = writeCache.files();
    final var snapshots = new long[files.size() * 2];
    var idx = 0;
    for (final var handler : files.values()) {
      final long fid = handler.fileId();
      snapshots[idx++] = fid;
      snapshots[idx++] = writeCache.getFilledUpTo(fid);
    }

    for (var i = 0; i < snapshots.length; i += 2) {
      clearFile(snapshots[i], (int) snapshots[i + 1], writeCache);
    }

    writeCache.delete();
  }

  @Override
  public void closeStorage(final WriteCache writeCache) throws IOException {
    final var files = writeCache.files();
    final var snapshots = new long[files.size() * 2];
    var idx = 0;
    for (final var handler : files.values()) {
      final long fid = handler.fileId();
      snapshots[idx++] = fid;
      snapshots[idx++] = writeCache.getFilledUpTo(fid);
    }

    for (var i = 0; i < snapshots.length; i += 2) {
      clearFile(snapshots[i], (int) snapshots[i + 1], writeCache);
    }

    writeCache.close();
  }

  /**
   * Removes all cached pages for {@code fileId} up to (exclusive) {@code filledUpTo}.
   * Must be called while the file is still visible in the write cache so that
   * {@code checkCacheOverflow()} has valid context.
   */
  private void clearFile(final long fileId, final int filledUpTo, final WriteCache writeCache) {
    flushCurrentThreadReadBatch();
    evictionLock.lock();
    try {
      emptyBuffers();

      final var fileHandler = data.remove(fileId);
      if (fileHandler == null) {
        // File was never loaded into the read cache; nothing to evict.
        return;
      }

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

  // -------------------------------------------------------------------------
  // Read/write buffer management
  // -------------------------------------------------------------------------

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
      if (readBuffer.offer(batch.entries[i]) == Buffer.FULL) {
        bufferOverflow = true;
      }
    }
    Arrays.fill(batch.entries, 0, batch.size, null); // release object references
    batch.size = 0;

    if (drainStatus.get().shouldBeDrained(bufferOverflow)) {
      tryToDrainBuffers();
    }
  }

  /**
   * Flushes the current thread's pending read-batch to the striped buffer. Call this before any
   * operation that requires the eviction policy to reflect all recent accesses (e.g., clear,
   * assertSize, clearFile).
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
      drainStatus.lazySet(DrainStatus.IN_PROGRESS);
      emptyBuffers();
    } finally {
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
        drainStatus.lazySet(DrainStatus.IN_PROGRESS);
        drainBuffers();
      } finally {
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
      policy.onAdd(entry);
    }
  }

  private void emptyWriteBuffer() {
    while (true) {
      final var entry = writeBuffer.poll();
      if (entry == null) {
        break;
      }
      policy.onAdd(entry);
    }
  }

  // -------------------------------------------------------------------------
  // Testing / assertion helpers
  // -------------------------------------------------------------------------

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

  // -------------------------------------------------------------------------
  // Internal types
  // -------------------------------------------------------------------------

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

  // -------------------------------------------------------------------------
  // CacheEntryPlaceholder — sentinel used in CASObjectArray slots
  // -------------------------------------------------------------------------

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
    public void acquireExclusiveLock() {
      throw new UnsupportedOperationException("This object is a placeholder");
    }

    @Override
    public void releaseExclusiveLock() {
      throw new UnsupportedOperationException("This object is a placeholder");
    }

    @Override
    public void acquireSharedLock() {
      throw new UnsupportedOperationException("This object is a placeholder");
    }

    @Override
    public void releaseSharedLock() {
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
    public boolean isLockAcquiredByCurrentThread() {
      return false;
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
    public PageKey getPageKey() {
      return new PageKey(fileId, pageIndex);
    }

    @Override
    public void close() throws IOException {
    }
  }
}
