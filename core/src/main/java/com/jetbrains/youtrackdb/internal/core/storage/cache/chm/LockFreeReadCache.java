package com.jetbrains.youtrackdb.internal.core.storage.cache.chm;

import com.jetbrains.youtrackdb.api.exception.BaseException;
import com.jetbrains.youtrackdb.internal.common.concur.collection.CASObjectArray;
import com.jetbrains.youtrackdb.internal.common.concur.lock.ThreadInterruptedException;
import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.CoreMetrics;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.Ratio;
import com.jetbrains.youtrackdb.internal.common.types.ModifiableBoolean;
import com.jetbrains.youtrackdb.internal.common.util.RawPairLongInteger;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
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
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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
 */
public final class LockFreeReadCache implements ReadCache {

  private static final int N_CPU = Runtime.getRuntime().availableProcessors();
  private static final int WRITE_BUFFER_MAX_BATCH = 128 * ceilingPowerOfTwo(N_CPU);

  private final ConcurrentHashMap<Long/*fileId*/, FileHandler> data; // todo replace with primitive concurrent hash map
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
  public FileHandler loadFileHandler(long fileId) {
    return data.get(fileId);
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

  @Nullable
  @Override
  public CacheEntry silentLoadForRead(
      final FileHandler fileHandler,
      final int pageIndex,
      final WriteCache writeCache,
      final boolean verifyChecksums) {
    final var fileId = AbstractWriteCache.checkFileIdCompatibility(writeCache.getId(),
        fileHandler.fileId());
    assert fileId == fileHandler.fileId() :
        "File id in handler is different. New FileHandler has to be constructed";

    for (; ; ) {
      // todo lookup in existing file handler
      var existingFileHandler = data.get(fileId);

      if (existingFileHandler == null) {
        final var updatedEntry = new CacheEntry[1];

        final var newFileHandler =
            data.compute(
                fileId,
                (fId, oldHandler) -> {
                  if (oldHandler == null) {
                    final var newCasArray = new CASObjectArray<CacheEntry>();
                    try {
                      final var pointer =
                          writeCache.load(
                              fId, pageIndex, new ModifiableBoolean(), verifyChecksums);
                      if (pointer == null) {
                        return new FileHandler(fId, newCasArray);
                      }

                      updatedEntry[0] =
                          new CacheEntryImpl(fId, pageIndex, pointer, false, this);
                      newCasArray.add(updatedEntry[0]);
                      return new FileHandler(fId, newCasArray);
                    } catch (final IOException e) {
                      throw BaseException.wrapException(
                          new StorageException(writeCache.getStorageName(),
                              "Error during loading of page " + pageIndex + " for file " + fileId),
                          e, writeCache.getStorageName());
                    }

                  } else {
                    return oldHandler;
                  }
                });

        // todo look up in new file handler
//        if (cacheEntry == null) {
//          cacheEntry = updatedEntry[0];
//        }

//        if (cacheEntry == null) {
//          return null;
//        }
      }
      CacheEntry cacheEntry = null;
      if (cacheEntry.acquireEntry()) {
        return cacheEntry;
      }
    }
  }

  @Nullable
  private CacheEntry doLoad(
      final FileHandler fileHandler,
      final int pageIndex,
      final WriteCache writeCache,
      final boolean verifyChecksums) {
    final var fileId = AbstractWriteCache.checkFileIdCompatibility(writeCache.getId(),
        fileHandler.fileId());
    final var pageKey = new PageKey(fileId, pageIndex);

    var success = false;
    try {
      while (true) {
        checkWriteBuffer();

        CacheEntry cacheEntry;

        final var storedFileHandler = data.get(fileHandler.fileId());
        @SuppressWarnings("unchecked") final var casArray = (CASObjectArray<CacheEntry>) storedFileHandler.casArray();
        //search for page in an array
        cacheEntry = null; // entry would be found in an array

        if (cacheEntry != null) {
          if (cacheEntry.acquireEntry()) {
            afterRead(cacheEntry);
            success = true;

            return cacheEntry;
          }
        } else {
          final var read = new boolean[1];

          final var computedFileHandler =
              data.compute(
                  fileId,
                  (fId, oldFileHandler) -> {
                    if (casArray == null) {
                      try {
                        final var pointer =
                            writeCache.load(
                                fileId, pageIndex, new ModifiableBoolean(), verifyChecksums);
                        if (pointer == null) {
                          return null;
                        }

                        cacheSize.incrementAndGet();
                        return null; // todo
//                        return new CacheEntryImpl(
//                            page.fileId(), page.pageIndex(), pointer, true, this);
                      } catch (final IOException e) {
                        throw BaseException.wrapException(
                            new StorageException(writeCache.getStorageName(),
                                "Error during loading of page " + pageIndex + " for file "
                                    + fileId),
                            e, writeCache.getStorageName());
                      }
                    } else {
                      read[0] = true;
                      // todo
                      return null;
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
    final var pointer = bufferPool.acquireDirect(true, Intention.ADD_NEW_PAGE_IN_DISK_CACHE);
    final var cachePointer = new CachePointer(pointer, bufferPool, fileId, pageIndex);
    cachePointer.incrementReadersReferrer();
    DurablePage.setLogSequenceNumberForPage(
        pointer.getNativeByteBuffer(), new LogSequenceNumber(-1, -1));

    final CacheEntry cacheEntry = new CacheEntryImpl(fileId, pageIndex, cachePointer, true, this);
    cacheEntry.acquireEntry();

    // todo
    final CacheEntry oldCacheEntry = null;//data.putIfAbsent(cacheEntry.getPageKey(), cacheEntry);
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
    final var cachePointer = cacheEntry.getCachePointer();
    assert cachePointer != null;

    if (cacheEntry.isNewlyAllocatedPage() || changed) {
      if (cacheEntry.isNewlyAllocatedPage()) {
        cacheEntry.clearAllocationFlag();
      }

      data.compute(
          cacheEntry.getFileId(),
          (fId, entry) -> {
            writeCache.store(
                cacheEntry.getFileId(), cacheEntry.getPageIndex(), cacheEntry.getCachePointer());
            return entry; // may be absent if page is in the pinned pages,
            // in such case we use map as virtual lock
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
    final var newPageIndex = writeCache.allocateNewPage(fileId);
    final var cacheEntry = addNewPagePointerToTheCache(fileId, newPageIndex);

    cacheEntry.acquireExclusiveLock();
    cacheEntry.markAllocated();
    writeCache.updateDirtyPagesTable(cacheEntry.getCachePointer(), startLSN);
    return cacheEntry;
  }

  private void afterRead(final CacheEntry entry) {
    final var bufferOverflow = readBuffer.offer(entry) == Buffer.FULL;

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
    evictionLock.lock();
    try {
      emptyBuffers();

      for (final var fileHandler : data.values()) {
        // we don't expose this array as part of the public API
        // however here we have to use it in our internal algorithm
        @SuppressWarnings("unchecked")
        var cacheEntries = (CASObjectArray<CacheEntry>) fileHandler.casArray();
        for (var i = 0; i < cacheEntries.size(); i++) {
          var entry = cacheEntries.get(i);
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

  // todo, ask Andrii if we even need this filledUpTo limit anymore
  private void clearFile(final long fileId, final int filledUpTo, final WriteCache writeCache) {
    evictionLock.lock();
    try {
      emptyBuffers();

      final var fileHandler = data.remove(fileId);
      @SuppressWarnings("unchecked") final var pageEntries = (CASObjectArray<CacheEntry>) fileHandler.casArray();
      for (var pageIndex = 0; pageIndex < pageEntries.size()
          // this additional check will make sure we are not removing more pages than before
          // for me, it seems logical we should remove the entire file, but who knows which mysteries are hidden
          // in the calling code :)
          // if on the review stage we are sure removing the entire file is correct,
          // this check and parameter should be removed
          && pageIndex < filledUpTo;
          pageIndex++) {
        final var cacheEntry = pageEntries.get(pageIndex);
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
