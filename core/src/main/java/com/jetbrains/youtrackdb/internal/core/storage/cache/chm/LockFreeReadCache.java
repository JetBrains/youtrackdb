package com.jetbrains.youtrackdb.internal.core.storage.cache.chm;

import com.jetbrains.youtrackdb.internal.common.collection.ConcurrentLongIntHashMap;
import com.jetbrains.youtrackdb.internal.common.concur.lock.ThreadInterruptedException;
import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.common.directmemory.PageFrame;
import com.jetbrains.youtrackdb.internal.common.directmemory.PageFramePool;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.CoreMetrics;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.MetricsRegistry;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.Ratio;
import com.jetbrains.youtrackdb.internal.common.types.ModifiableBoolean;
import com.jetbrains.youtrackdb.internal.common.util.RawPairLongInteger;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.storage.cache.AbstractWriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntryImpl;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.chm.readbuffer.BoundedBuffer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.chm.readbuffer.Buffer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.chm.writequeue.MPSCLinkedQueue;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
 * <a href="https://github.com/ben-manes/concurrentlinkedhashmap">...</a>. The difference is that if
 * consumption of
 * memory in cache is bigger than 1% disk cache is switched from asynchronous processing of stream
 * of events to synchronous processing. But that is true only for threads which cause loading of
 * additional pages from write cache to disk cache. Window TinyLFU policy is used as cache eviction
 * policy because it prevents usage of ghost entries and as result considerably decrease usage of
 * heap memory.
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

  private final ConcurrentLongIntHashMap<CacheEntry> data;
  private final Lock evictionLock = new ReentrantLock();

  private final WTinyLFUPolicy policy;

  private final Buffer readBuffer = new BoundedBuffer();
  private final MPSCLinkedQueue<CacheEntry> writeBuffer = new MPSCLinkedQueue<>();
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
  public long addFile(final String fileName, final WriteCache writeCache) throws IOException {
    return writeCache.addFile(fileName);
  }

  @Override
  public long addFile(final String fileName, long fileId, final WriteCache writeCache)
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
      final long fileId,
      final long pageIndex,
      final WriteCache writeCache,
      final boolean verifyChecksums,
      final LogSequenceNumber startLSN) {
    final var cacheEntry = doLoad(fileId, (int) pageIndex, writeCache, verifyChecksums);

    if (cacheEntry != null) {
      cacheEntry.acquireExclusiveLock();
      writeCache.updateDirtyPagesTable(cacheEntry.getCachePointer(), startLSN);
    }

    return cacheEntry;
  }

  @Override
  public CacheEntry loadForRead(
      final long fileId,
      final long pageIndex,
      final WriteCache writeCache,
      final boolean verifyChecksums) {
    return doLoad(fileId, (int) pageIndex, writeCache, verifyChecksums);
  }

  @Nullable @Override
  public CacheEntry silentLoadForRead(
      final long extFileId,
      final int pageIndex,
      final WriteCache writeCache,
      final boolean verifyChecksums) {
    final var fileId = AbstractWriteCache.checkFileIdCompatibility(writeCache.getId(), extFileId);

    for (;;) {
      var cacheEntry = data.get(fileId, pageIndex);

      if (cacheEntry == null) {
        final var updatedEntry = new CacheEntry[1];

        // The compute lambda receives (fId, pIdx, entry) but we use the outer-scope
        // captured fileId and pageIndex — they are guaranteed identical to the lambda
        // parameters since we pass (fileId, pageIndex) as the compute key.
        cacheEntry =
            data.compute(
                fileId,
                pageIndex,
                (fId, pIdx, entry) -> {
                  if (entry == null) {
                    try {
                      final var pointer =
                          writeCache.load(
                              fileId, pageIndex, new ModifiableBoolean(), verifyChecksums);
                      if (pointer == null) {
                        return null;
                      }

                      updatedEntry[0] =
                          new CacheEntryImpl(fileId, pageIndex, pointer, false, this);
                      return null;
                    } catch (final IOException e) {
                      throw BaseException.wrapException(
                          new StorageException(writeCache.getStorageName(),
                              "Error during loading of page " + pageIndex + " for file " + fileId),
                          e, writeCache.getStorageName());
                    }

                  } else {
                    return entry;
                  }
                });

        if (cacheEntry == null) {
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
      final long extFileId,
      final int pageIndex,
      final WriteCache writeCache,
      final boolean verifyChecksums) {
    final var fileId = AbstractWriteCache.checkFileIdCompatibility(writeCache.getId(), extFileId);

    var success = false;
    try {
      while (true) {
        checkWriteBuffer();

        CacheEntry cacheEntry;

        cacheEntry = data.get(fileId, pageIndex);

        if (cacheEntry != null) {
          if (cacheEntry.acquireEntry()) {
            afterRead(cacheEntry);
            success = true;

            return cacheEntry;
          }
        } else {
          final var read = new boolean[1];

          // The compute lambda receives (fId, pIdx, entry) but we use the outer-scope
          // captured fileId and pageIndex — they are guaranteed identical to the lambda
          // parameters since we pass (fileId, pageIndex) as the compute key.
          cacheEntry =
              data.compute(
                  fileId,
                  pageIndex,
                  (fId, pIdx, entry) -> {
                    if (entry == null) {
                      try {
                        final var pointer =
                            writeCache.load(
                                fileId, pageIndex, new ModifiableBoolean(), verifyChecksums);
                        if (pointer == null) {
                          return null;
                        }

                        cacheSize.incrementAndGet();
                        return new CacheEntryImpl(fileId, pageIndex, pointer, true, this);
                      } catch (final IOException e) {
                        throw BaseException.wrapException(
                            new StorageException(writeCache.getStorageName(),
                                "Error during loading of page " + pageIndex + " for file "
                                    + fileId),
                            e, writeCache.getStorageName());
                      }
                    } else {
                      read[0] = true;
                      return entry;
                    }
                  });

          if (cacheEntry == null) {
            return null;
          }

          if (cacheEntry.acquireEntry()) {
            if (read[0]) {
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
      }
    } finally {
      cacheHitRatio.record(success);
    }
  }

  private CacheEntry addNewPagePointerToTheCache(final long fileId, final int pageIndex) {

    final var pageFrame = pageFramePool.acquire(true, Intention.ADD_NEW_PAGE_IN_DISK_CACHE);
    final var cachePointer = new CachePointer(pageFrame, pageFramePool, fileId, pageIndex);
    cachePointer.incrementReadersReferrer();
    DurablePage.setLogSequenceNumberForPage(
        pageFrame.getBuffer(), new LogSequenceNumber(-1, -1));

    final CacheEntry cacheEntry = new CacheEntryImpl(fileId, pageIndex, cachePointer, true, this);
    cacheEntry.acquireEntry();

    final var oldCacheEntry =
        data.putIfAbsent(cacheEntry.getFileId(), cacheEntry.getPageIndex(), cacheEntry);
    if (oldCacheEntry != null) {
      throw new IllegalStateException(
          "Page  " + fileId + ":" + pageIndex + " was allocated in other thread");
    }

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
    final var pageKey = new PageKey(fileId, (int) pageIndex);
    final var cacheEntry = data.get(pageKey);

    if (cacheEntry == null || !cacheEntry.isAlive()) {
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
    final var pageKey = new PageKey(fileId, (int) pageIndex);
    final var cacheEntry = data.get(pageKey);

    // Entry may have been evicted between stamp validation and this call.
    // One missed frequency bump is harmless — skip silently.
    if (cacheEntry != null) {
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
          cacheEntry.getPageIndex(),
          (fId, pIdx, entry) -> {
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
      long fileId, final WriteCache writeCache, final LogSequenceNumber startLSN)
      throws IOException {
    fileId = AbstractWriteCache.checkFileIdCompatibility(writeCache.getId(), fileId);
    final var newPageIndex = writeCache.allocateNewPage(fileId);
    final var cacheEntry = addNewPagePointerToTheCache(fileId, newPageIndex);

    cacheEntry.acquireExclusiveLock();
    cacheEntry.markAllocated();
    writeCache.updateDirtyPagesTable(cacheEntry.getCachePointer(), startLSN);
    return cacheEntry;
  }

  private void afterRead(final CacheEntry entry) {
    var batch = readBatch.get();
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
    var batch = readBatch.get();
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

      var entries = new ArrayList<CacheEntry>();
      data.forEachValue(entries::add);

      for (final var entry : entries) {
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

      data.clear();
      cacheSize.set(0);
    } finally {
      evictionLock.unlock();
    }
  }

  @Override
  public void truncateFile(long fileId, final WriteCache writeCache) throws IOException {
    fileId = AbstractWriteCache.checkFileIdCompatibility(writeCache.getId(), fileId);

    final var filledUpTo = (int) writeCache.getFilledUpTo(fileId);
    writeCache.truncateFile(fileId);

    clearFile(fileId, filledUpTo, writeCache);
  }

  @Override
  public void closeFile(long fileId, final boolean flush, final WriteCache writeCache) {
    fileId = AbstractWriteCache.checkFileIdCompatibility(writeCache.getId(), fileId);
    final var filledUpTo = (int) writeCache.getFilledUpTo(fileId);

    clearFile(fileId, filledUpTo, writeCache);
    writeCache.close(fileId, flush);
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
    final var files = writeCache.files().values();
    final List<RawPairLongInteger> filledUpTo = new ArrayList<>(1024);
    for (final long fileId : files) {
      filledUpTo.add(new RawPairLongInteger(fileId, (int) writeCache.getFilledUpTo(fileId)));
    }

    for (final var entry : filledUpTo) {
      clearFile(entry.first, entry.second, writeCache);
    }

    writeCache.delete();
  }

  @Override
  public void closeStorage(final WriteCache writeCache) throws IOException {
    final var files = writeCache.files().values();
    final List<RawPairLongInteger> filledUpTo = new ArrayList<>(1024);
    for (final long fileId : files) {
      filledUpTo.add(new RawPairLongInteger(fileId, (int) writeCache.getFilledUpTo(fileId)));
    }

    for (final var entry : filledUpTo) {
      clearFile(entry.first, entry.second, writeCache);
    }

    writeCache.close();
  }

  private void clearFile(final long fileId, final int filledUpTo, final WriteCache writeCache) {
    flushCurrentThreadReadBatch();
    evictionLock.lock();
    try {
      emptyBuffers();

      for (var pageIndex = 0; pageIndex < filledUpTo; pageIndex++) {
        final var cacheEntry = data.remove(fileId, pageIndex);
        if (cacheEntry != null) {
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
}
