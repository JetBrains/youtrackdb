package com.jetbrains.youtrackdb.internal.core.storage.cache.chm;

import com.jetbrains.youtrackdb.api.exception.BaseException;
import com.jetbrains.youtrackdb.internal.common.concur.collection.CASObjectArray;
import com.jetbrains.youtrackdb.internal.common.concur.lock.LockManager;
import com.jetbrains.youtrackdb.internal.common.concur.lock.PartitionedLockManager;
import com.jetbrains.youtrackdb.internal.common.concur.lock.ThreadInterruptedException;
import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.CoreMetrics;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.Ratio;
import com.jetbrains.youtrackdb.internal.common.types.ModifiableBoolean;
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
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALChanges;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALPageChangesPortion;
import java.io.IOException;
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

  private final CacheEntry LOCK_FREE_READ_CACHE_CACHE_ENTRY_PLACEHOLDER =
      new CacheEntryPlaceholder();

  private final ConcurrentHashMap<Long/*fileId*/, FileHandler> data; // todo replace with primitive concurrent hash map
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

    final var updatedEntry = new CacheEntry[1];
    for (; ; ) {
      @SuppressWarnings("unechecked") final var casArray = (CASObjectArray<CacheEntry>) fileHandler.casArray();
      var cacheEntry = casArray.get(pageIndex);
      if (cacheEntry == null) {

        try {
          final var pointer = writeCache.load(fileHandler.fileId(), pageIndex,
              new ModifiableBoolean(), verifyChecksums);
          if (pointer != null) {
            updatedEntry[0] = new CacheEntryImpl(fileHandler.fileId(), pageIndex, pointer, false,
                this);
          }

        } catch (final IOException e) {
          throw BaseException.wrapException(
              new StorageException(writeCache.getStorageName(),
                  "Error during loading of page " + pageIndex + " for file " + fileId),
              e, writeCache.getStorageName());
        }

        cacheEntry = casArray.get(pageIndex);
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

  @Nullable
  private CacheEntry doLoad(
      final FileHandler fileHandler,
      final int pageIndex,
      final WriteCache writeCache,
      final boolean verifyChecksums) {
    var success = false;
    try {
      while (true) {
        checkWriteBuffer();

        CacheEntry cacheEntry;

        @SuppressWarnings("unchecked")
        var casArray = (CASObjectArray<CacheEntry>) fileHandler.casArray();
        //search for page in an array
        cacheEntry = casArray.get(pageIndex);
        var read = true;
        if (cacheEntry != null) {
          if (cacheEntry.acquireEntry()) {
            afterRead(cacheEntry);
            return cacheEntry;
          }
        } else {
          try {
            var pageKey = new PageKey(fileHandler.fileId(), pageIndex);
            var pageLock = lockManager.acquireExclusiveLock(pageKey);
            try {
              final var pointer = writeCache.load(
                  fileHandler.fileId(), pageIndex, new ModifiableBoolean(), verifyChecksums);
              if (pointer == null) {
                return null;
              }
              cacheEntry = casArray.get(pageIndex);
              if (cacheEntry != null) {
                read = true;
              } else {
                cacheSize.incrementAndGet();
                var newCacheEntry = new CacheEntryImpl(pageKey, pointer, true, this);
                casArray.set(pageIndex, newCacheEntry,
                    LOCK_FREE_READ_CACHE_CACHE_ENTRY_PLACEHOLDER);
                cacheEntry = newCacheEntry;
                read = false;
              }
            } finally {
              pageLock.unlock();
            }
          } catch (final IOException e) {
            throw BaseException.wrapException(
                new StorageException(writeCache.getStorageName(),
                    "Error during loading of page " + pageIndex + " for file "
                        + fileHandler.fileId()),
                e, writeCache.getStorageName());
          }
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
    final var pointer = bufferPool.acquireDirect(true, Intention.ADD_NEW_PAGE_IN_DISK_CACHE);
    final var cachePointer = new CachePointer(pointer, bufferPool, fileHandler.fileId(),
        pageIndex);
    cachePointer.incrementReadersReferrer();
    DurablePage.setLogSequenceNumberForPage(
        pointer.getNativeByteBuffer(), new LogSequenceNumber(-1, -1));

    final CacheEntry cacheEntry = new CacheEntryImpl(fileHandler.fileId(), pageIndex,
        cachePointer,
        true, this);
    cacheEntry.acquireEntry();

    @SuppressWarnings("unchecked")
    var casArray = (CASObjectArray<CacheEntry>) fileHandler.casArray();
    //if cas does not work, it means old entry is present
    final var oldCacheEntryPresent = !casArray.compareAndSet(pageIndex, null, cacheEntry);
    if (oldCacheEntryPresent) {
      throw new IllegalStateException(
          "Page  " + fileHandler.fileId() + ":" + pageIndex + " was allocated in other thread");
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
          // todo this logic I don't get yet, need to ponder on it
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
      FileHandler fileHandler, final WriteCache writeCache, final LogSequenceNumber startLSN)
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
  public void truncateFile(long fileId, final WriteCache writeCache)
      throws IOException {
    writeCache.truncateFile(fileId);

    clearFile(fileId, writeCache);
  }

  @Override
  public void closeFile(FileHandler fileHandler, final boolean flush, final WriteCache writeCache) {
    clearFile(fileHandler.fileId(), writeCache);
    writeCache.close(fileHandler, flush);
  }

  @Override
  public void deleteFile(long fileId, final WriteCache writeCache) throws IOException {
    fileId = AbstractWriteCache.checkFileIdCompatibility(writeCache.getId(), fileId);

    clearFile(fileId, writeCache);
    writeCache.deleteFile(fileId);
  }

  @Override
  public void deleteStorage(final WriteCache writeCache) throws IOException {
    final var files = writeCache.files().values();
    for (final var fileHandlers : files) {
      clearFile(fileHandlers.fileId(), writeCache);
    }

    writeCache.delete();
  }

  @Override
  public void closeStorage(final WriteCache writeCache) throws IOException {
    final var files = writeCache.files().values();
    for (final var fileHandler : files) {
      clearFile(fileHandler.fileId(), writeCache);
    }

    writeCache.close();
  }

  private void clearFile(final long fileId, final WriteCache writeCache) {
    evictionLock.lock();
    try {
      emptyBuffers();

      final var fileHandler = data.remove(fileId);
      @SuppressWarnings("unchecked") final var pageEntries = (CASObjectArray<CacheEntry>) fileHandler.casArray();
      for (var pageIndex = 0; pageIndex < pageEntries.size(); pageIndex++) {
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

  private class CacheEntryPlaceholder implements CacheEntry {

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

    }

    @Override
    public void releaseExclusiveLock() {

    }

    @Override
    public void acquireSharedLock() {

    }

    @Override
    public void releaseSharedLock() {

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
    public void setInitialLSN(LogSequenceNumber lsn) {

    }

    @Override
    public void setEndLSN(LogSequenceNumber endLSN) {

    }

    @Override
    public boolean acquireEntry() {
      return false;
    }

    @Override
    public void releaseEntry() {

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
    public void setPrev(CacheEntry prev) {

    }

    @Override
    public void setNext(CacheEntry next) {

    }

    @Override
    public void setContainer(LRUList lruList) {

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
