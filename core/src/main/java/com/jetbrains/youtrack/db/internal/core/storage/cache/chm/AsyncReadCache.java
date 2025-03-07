package com.jetbrains.youtrack.db.internal.core.storage.cache.chm;

import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.internal.common.concur.lock.ThreadInterruptedException;
import com.jetbrains.youtrack.db.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrack.db.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrack.db.internal.common.directmemory.Pointer;
import com.jetbrains.youtrack.db.internal.common.profiler.metrics.CoreMetrics;
import com.jetbrains.youtrack.db.internal.common.profiler.metrics.Ratio;
import com.jetbrains.youtrack.db.internal.common.types.ModifiableBoolean;
import com.jetbrains.youtrack.db.internal.common.util.RawPairLongInteger;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrack.db.internal.core.exception.StorageException;
import com.jetbrains.youtrack.db.internal.core.storage.cache.AbstractWriteCache;
import com.jetbrains.youtrack.db.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrack.db.internal.core.storage.cache.CacheEntryImpl;
import com.jetbrains.youtrack.db.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrack.db.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrack.db.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrack.db.internal.core.storage.cache.chm.readbuffer.BoundedBuffer;
import com.jetbrains.youtrack.db.internal.core.storage.cache.chm.readbuffer.Buffer;
import com.jetbrains.youtrack.db.internal.core.storage.cache.chm.writequeue.MPSCLinkedQueue;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Disk cache based on ConcurrentHashMap and eviction policy which is asynchronously processed by
 * handling set of events logged in lock free event buffer. This feature first was introduced in
 * Caffeine framework https://github.com/ben-manes/caffeine and in ConcurrentLinkedHashMap library
 * https://github.com/ben-manes/concurrentlinkedhashmap . The difference is that if consumption of
 * memory in cache is bigger than 1% disk cache is switched from asynchronous processing of stream
 * of events to synchronous processing. But that is true only for threads which cause loading of
 * additional pages from write cache to disk cache. Window TinyLFU policy is used as cache eviction
 * policy because it prevents usage of ghost entries and as result considerably decrease usage of
 * heap memory.
 */
public final class AsyncReadCache implements ReadCache {

  private static final int N_CPU = Runtime.getRuntime().availableProcessors();
  private static final int WRITE_BUFFER_MAX_BATCH = 128 * ceilingPowerOfTwo(N_CPU);

  private final ConcurrentHashMap<PageKey, CacheEntry> data;
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

  private final int pageSize;

  private final ByteBufferPool bufferPool;

  private final Ratio cacheHitRatio;

  public AsyncReadCache(
      final ByteBufferPool bufferPool,
      final long maxCacheSizeInBytes,
      final int pageSize) {
    evictionLock.lock();
    try {
      this.pageSize = pageSize;
      this.bufferPool = bufferPool;

      this.maxCacheSize = (int) (maxCacheSizeInBytes / pageSize);
      this.data = new ConcurrentHashMap<>(this.maxCacheSize, 0.5f, N_CPU * 2);
      policy = new WTinyLFUPolicy(data, new FrequencySketch(), cacheSize);
      policy.setMaxSize(this.maxCacheSize);
    } finally {
      evictionLock.unlock();
    }

    this.cacheHitRatio = YouTrackDBEnginesManager.instance()
        .getMetricsRegistry()
        .globalMetric(CoreMetrics.CACHE_HIT_RATIO);
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
  public CacheEntry loadForWrite(
      final long fileId,
      final long pageIndex,
      final WriteCache writeCache,
      final boolean verifyChecksums,
      final LogSequenceNumber startLSN) {
    final CacheEntry cacheEntry = doLoad(fileId, (int) pageIndex, writeCache, verifyChecksums);

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

  @Override
  public CacheEntry silentLoadForRead(
      final long extFileId,
      final int pageIndex,
      final WriteCache writeCache,
      final boolean verifyChecksums) {
    final long fileId = AbstractWriteCache.checkFileIdCompatibility(writeCache.getId(), extFileId);
    final PageKey pageKey = new PageKey(fileId, pageIndex);

    for (; ; ) {
      CacheEntry cacheEntry = data.get(pageKey);

      if (cacheEntry == null) {
        final CacheEntry[] updatedEntry = new CacheEntry[1];

        cacheEntry =
            data.compute(
                pageKey,
                (page, entry) -> {
                  if (entry == null) {
                    try {
                      final CachePointer pointer =
                          writeCache.load(
                              fileId, pageIndex, new ModifiableBoolean(), verifyChecksums);
                      if (pointer == null) {
                        return null;
                      }

                      updatedEntry[0] =
                          new CacheEntryImpl(
                              page.getFileId(), page.getPageIndex(), pointer, false, this);
                      return null;
                    } catch (final IOException e) {
                      throw BaseException.wrapException(
                          new StorageException(
                              "Error during loading of page " + pageIndex + " for file " + fileId),
                          e);
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

  private CacheEntry doLoad(
      final long extFileId,
      final int pageIndex,
      final WriteCache writeCache,
      final boolean verifyChecksums) {
    final long fileId = AbstractWriteCache.checkFileIdCompatibility(writeCache.getId(), extFileId);
    final PageKey pageKey = new PageKey(fileId, pageIndex);

    boolean success = false;

    try {
      while (true) {
        checkWriteBuffer();

        CacheEntry cacheEntry;

        cacheEntry = data.get(pageKey);

        if (cacheEntry != null) {
          if (cacheEntry.acquireEntry()) {
            afterRead(cacheEntry);
            success = true;

            return cacheEntry;
          }
        } else {
          final boolean[] read = new boolean[1];

          cacheEntry =
              data.compute(
                  pageKey,
                  (page, entry) -> {
                    if (entry == null) {
                      try {
                        final CachePointer pointer =
                            writeCache.load(
                                fileId, pageIndex, new ModifiableBoolean(), verifyChecksums);
                        if (pointer == null) {
                          return null;
                        }

                        cacheSize.incrementAndGet();
                        return new CacheEntryImpl(
                            page.getFileId(), page.getPageIndex(), pointer, true, this);
                      } catch (final IOException e) {
                        throw BaseException.wrapException(
                            new StorageException(
                                "Error during loading of page " + pageIndex + " for file "
                                    + fileId),
                            e);
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
                    e);
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

    final Pointer pointer = bufferPool.acquireDirect(true, Intention.ADD_NEW_PAGE_IN_DISK_CACHE);
    final CachePointer cachePointer = new CachePointer(pointer, bufferPool, fileId, pageIndex);
    cachePointer.incrementReadersReferrer();
    DurablePage.setLogSequenceNumberForPage(
        pointer.getNativeByteBuffer(), new LogSequenceNumber(-1, -1));

    final CacheEntry cacheEntry = new CacheEntryImpl(fileId, pageIndex, cachePointer, true, this);
    cacheEntry.acquireEntry();

    final CacheEntry oldCacheEntry = data.putIfAbsent(cacheEntry.getPageKey(), cacheEntry);
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
  public void releaseFromRead(final CacheEntry cacheEntry) {
    cacheEntry.releaseEntry();

    if (!cacheEntry.insideCache()) {
      cacheEntry.getCachePointer().decrementReadersReferrer();
    }
  }

  @Override
  public void releaseFromWrite(
      final CacheEntry cacheEntry, final WriteCache writeCache, final boolean changed) {
    final CachePointer cachePointer = cacheEntry.getCachePointer();
    assert cachePointer != null;

    if (cacheEntry.isNewlyAllocatedPage() || changed) {
      if (cacheEntry.isNewlyAllocatedPage()) {
        cacheEntry.clearAllocationFlag();
      }

      data.compute(
          cacheEntry.getPageKey(),
          (page, entry) -> {
            writeCache.store(
                cacheEntry.getFileId(), cacheEntry.getPageIndex(), cacheEntry.getCachePointer());
            return entry; // may be absent if page in pinned pages, in such case we use map as
            // virtual lock
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
    cachePointer.releaseExclusiveLock();
    cacheEntry.releaseEntry();
  }

  @Override
  public CacheEntry allocateNewPage(
      long fileId, final WriteCache writeCache, final LogSequenceNumber startLSN)
      throws IOException {
    fileId = AbstractWriteCache.checkFileIdCompatibility(writeCache.getId(), fileId);
    final int newPageIndex = writeCache.allocateNewPage(fileId);
    final CacheEntry cacheEntry = addNewPagePointerToTheCache(fileId, newPageIndex);

    cacheEntry.acquireExclusiveLock();
    cacheEntry.markAllocated();
    writeCache.updateDirtyPagesTable(cacheEntry.getCachePointer(), startLSN);
    return cacheEntry;
  }

  private void afterRead(final CacheEntry entry) {
    final boolean bufferOverflow = readBuffer.offer(entry) == Buffer.FULL;

    if (drainStatus.get().shouldBeDrained(bufferOverflow)) {
      tryToDrainBuffers();
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
    for (int i = 0; i < WRITE_BUFFER_MAX_BATCH; i++) {
      final CacheEntry entry = writeBuffer.poll();

      if (entry == null) {
        break;
      }

      this.policy.onAdd(entry);
    }
  }

  private void emptyWriteBuffer() {
    while (true) {
      final CacheEntry entry = writeBuffer.poll();

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
    evictionLock.lock();
    try {
      emptyBuffers();

      for (final CacheEntry entry : data.values()) {
        if (entry.freeze()) {
          policy.onRemove(entry);
        } else {
          throw new StorageException(
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

    final int filledUpTo = (int) writeCache.getFilledUpTo(fileId);
    writeCache.truncateFile(fileId);

    clearFile(fileId, filledUpTo, writeCache);
  }

  @Override
  public void closeFile(long fileId, final boolean flush, final WriteCache writeCache) {
    fileId = AbstractWriteCache.checkFileIdCompatibility(writeCache.getId(), fileId);
    final int filledUpTo = (int) writeCache.getFilledUpTo(fileId);

    clearFile(fileId, filledUpTo, writeCache);
    writeCache.close(fileId, flush);
  }

  public void deleteFile(long fileId, final WriteCache writeCache) throws IOException {
    fileId = AbstractWriteCache.checkFileIdCompatibility(writeCache.getId(), fileId);
    final int filledUpTo = (int) writeCache.getFilledUpTo(fileId);

    clearFile(fileId, filledUpTo, writeCache);
    writeCache.deleteFile(fileId);
  }

  @Override
  public void deleteStorage(final WriteCache writeCache) throws IOException {
    final Collection<Long> files = writeCache.files().values();
    final List<RawPairLongInteger> filledUpTo = new ArrayList<>(1024);
    for (final long fileId : files) {
      filledUpTo.add(new RawPairLongInteger(fileId, (int) writeCache.getFilledUpTo(fileId)));
    }

    for (final RawPairLongInteger entry : filledUpTo) {
      clearFile(entry.first, entry.second, writeCache);
    }

    writeCache.delete();
  }

  @Override
  public void closeStorage(final WriteCache writeCache) throws IOException {
    final Collection<Long> files = writeCache.files().values();
    final List<RawPairLongInteger> filledUpTo = new ArrayList<>(1024);
    for (final long fileId : files) {
      filledUpTo.add(new RawPairLongInteger(fileId, (int) writeCache.getFilledUpTo(fileId)));
    }

    for (final RawPairLongInteger entry : filledUpTo) {
      clearFile(entry.first, entry.second, writeCache);
    }

    writeCache.close();
  }

  private void clearFile(final long fileId, final int filledUpTo, final WriteCache writeCache) {
    evictionLock.lock();
    try {
      emptyBuffers();

      for (int pageIndex = 0; pageIndex < filledUpTo; pageIndex++) {
        final PageKey pageKey = new PageKey(fileId, pageIndex);
        final CacheEntry cacheEntry = data.remove(pageKey);
        if (cacheEntry != null) {
          if (cacheEntry.freeze()) {
            policy.onRemove(cacheEntry);
            cacheSize.decrementAndGet();

            try {
              writeCache.checkCacheOverflow();
            } catch (final java.lang.InterruptedException e) {
              throw BaseException.wrapException(
                  new ThreadInterruptedException("Check of write cache overflow was interrupted"),
                  e);
            }
          } else {
            throw new StorageException(
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
    evictionLock.lock();
    try {
      emptyBuffers();
      policy.assertSize();
    } finally {
      evictionLock.unlock();
    }
  }

  void assertConsistency() {
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

  @SuppressWarnings("SameParameterValue")
  private static int ceilingPowerOfTwo(final int x) {
    // From Hacker's Delight, Chapter 3, Harry S. Warren Jr.
    return 1 << -Integer.numberOfLeadingZeros(x - 1);
  }
}
