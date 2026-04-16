package com.jetbrains.youtrackdb.internal.core.storage.cache;

import com.jetbrains.youtrackdb.internal.core.storage.cache.chm.LRUList;
import com.jetbrains.youtrackdb.internal.core.storage.cache.chm.PageKey;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALChanges;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

public class CacheEntryImpl implements CacheEntry {

  private static final AtomicIntegerFieldUpdater<CacheEntryImpl> USAGES_COUNT_UPDATER;
  private static final AtomicIntegerFieldUpdater<CacheEntryImpl> STATE_UPDATER;

  static {
    USAGES_COUNT_UPDATER =
        AtomicIntegerFieldUpdater.newUpdater(CacheEntryImpl.class, "usagesCount");
    STATE_UPDATER = AtomicIntegerFieldUpdater.newUpdater(CacheEntryImpl.class, "state");
  }

  private static final int FROZEN = -1;
  private static final int DEAD = -2;

  private CachePointer dataPointer;
  private volatile int usagesCount;
  @SuppressWarnings("UnusedVariable")
  private volatile int state;

  // LRU list pointers — guarded by evictionLock in LockFreeReadCache.
  // All reads and writes happen inside LRUList methods, which are called
  // exclusively from WTinyLFUPolicy under the eviction lock. No volatile
  // needed because the lock provides visibility and ordering guarantees.
  //
  // @GuardedBy cannot resolve cross-class instance locks, so we suppress the
  // checker and keep the annotation as a documentation safeguard.
  @SuppressWarnings("GuardedBy")
  @GuardedBy("LockFreeReadCache.evictionLock") private CacheEntry next;

  @SuppressWarnings("GuardedBy")
  @GuardedBy("LockFreeReadCache.evictionLock") private CacheEntry prev;

  @SuppressWarnings("GuardedBy")
  @GuardedBy("LockFreeReadCache.evictionLock") private LRUList container;

  /**
   * Protected by page lock inside disk cache
   */
  private boolean allocatedPage;

  private final boolean insideCache;
  private final ReadCache readCache;
  private final PageKey pageKey;

  public CacheEntryImpl(
      final long fileId,
      final int pageIndex,
      final CachePointer dataPointer,
      final boolean insideCache,
      ReadCache readCache) {
    this(new PageKey(fileId, pageIndex), dataPointer, insideCache, readCache);
  }

  public CacheEntryImpl(
      final PageKey pageKey,
      final CachePointer dataPointer,
      final boolean insideCache,
      ReadCache readCache) {
    if (pageKey.fileId() < 0) {
      throw new IllegalStateException("File id has invalid value " + pageKey.fileId());
    }

    if (pageKey.pageIndex() < 0) {
      throw new IllegalStateException("Page index has invalid value " + pageKey.pageIndex());
    }

    this.dataPointer = dataPointer;
    this.insideCache = insideCache;
    this.readCache = readCache;
    this.pageKey = pageKey;
  }

  @Override
  public boolean isNewlyAllocatedPage() {
    return allocatedPage;
  }

  @Override
  public void markAllocated() {
    allocatedPage = true;
  }

  @Override
  public void clearAllocationFlag() {
    allocatedPage = false;
  }

  @Override
  public CachePointer getCachePointer() {
    return dataPointer;
  }

  @Override
  public void clearCachePointer() {
    dataPointer = null;
  }

  @Override
  public long getFileId() {
    return pageKey.fileId();
  }

  @Override
  public int getPageIndex() {
    return pageKey.pageIndex();
  }

  @Override
  public void acquireExclusiveLock() {
    dataPointer.acquireExclusiveLock();
  }

  @Override
  public void releaseExclusiveLock() {
    dataPointer.releaseExclusiveLock();
  }

  @Override
  public void acquireSharedLock() {
    dataPointer.acquireSharedLock();
  }

  @Override
  public void releaseSharedLock() {
    dataPointer.releaseSharedLock();
  }

  @Override
  public int getUsagesCount() {
    return USAGES_COUNT_UPDATER.get(this);
  }

  @Override
  public void incrementUsages() {
    USAGES_COUNT_UPDATER.incrementAndGet(this);
  }

  /**
   * DEBUG only !!
   *
   * @return Whether lock acquired on current entry
   */
  @Override
  public boolean isLockAcquiredByCurrentThread() {
    return dataPointer.isLockAcquiredByCurrentThread();
  }

  @Override
  public void decrementUsages() {
    USAGES_COUNT_UPDATER.decrementAndGet(this);
  }

  @Nullable @Override
  public WALChanges getChanges() {
    return null;
  }

  @Override
  public LogSequenceNumber getInitialLSN() {
    // Return a non-null sentinel so that DurablePage.<init> skips the
    // LSN-read block on the read path, avoiding a wasted allocation.
    return LogSequenceNumber.NOT_TRACKED;
  }

  @Override
  public void setInitialLSN(LogSequenceNumber lsn) {
  }

  @Override
  public LogSequenceNumber getEndLSN() {
    return dataPointer.getEndLSN();
  }

  @Override
  public void setEndLSN(final LogSequenceNumber endLSN) {
    dataPointer.setEndLSN(endLSN);
  }

  @Override
  public boolean acquireEntry() {
    var state = STATE_UPDATER.get(this);

    while (state >= 0) {
      if (STATE_UPDATER.compareAndSet(this, state, state + 1)) {
        return true;
      }

      state = STATE_UPDATER.get(this);
    }

    return false;
  }

  @Override
  public void releaseEntry() {
    var state = STATE_UPDATER.get(this);

    while (true) {
      if (state <= 0) {
        throw new IllegalStateException(
            "Cache entry " + getFileId() + ":" + getPageIndex() + " has invalid state " + state);
      }

      if (STATE_UPDATER.compareAndSet(this, state, state - 1)) {
        return;
      }

      state = STATE_UPDATER.get(this);
    }
  }

  @Override
  public boolean isReleased() {
    return STATE_UPDATER.get(this) == 0;
  }

  @Override
  public boolean isAlive() {
    return STATE_UPDATER.get(this) >= 0;
  }

  @Override
  public boolean freeze() {
    var state = STATE_UPDATER.get(this);
    while (state == 0) {
      if (STATE_UPDATER.compareAndSet(this, state, FROZEN)) {
        return true;
      }

      state = STATE_UPDATER.get(this);
    }

    return false;
  }

  @Override
  public boolean isFrozen() {
    return STATE_UPDATER.get(this) == FROZEN;
  }

  @Override
  public void makeDead() {
    var state = STATE_UPDATER.get(this);

    while (state == FROZEN) {
      if (STATE_UPDATER.compareAndSet(this, state, DEAD)) {
        return;
      }

      state = STATE_UPDATER.get(this);
    }

    throw new IllegalStateException(
        "Cache entry " + getFileId() + ":" + getPageIndex() + " has invalid state " + state);
  }

  @Override
  public boolean isDead() {
    return STATE_UPDATER.get(this) == DEAD;
  }

  @SuppressWarnings("GuardedBy")
  @Override
  public CacheEntry getNext() {
    return next;
  }

  @SuppressWarnings("GuardedBy")
  @Override
  public CacheEntry getPrev() {
    return prev;
  }

  @SuppressWarnings("GuardedBy")
  @Override
  public void setPrev(final CacheEntry prev) {
    this.prev = prev;
  }

  @SuppressWarnings("GuardedBy")
  @Override
  public void setNext(final CacheEntry next) {
    this.next = next;
  }

  @SuppressWarnings("GuardedBy")
  @Override
  public void setContainer(final LRUList lruList) {
    this.container = lruList;
  }

  @SuppressWarnings("GuardedBy")
  @Override
  public LRUList getContainer() {
    return container;
  }

  @Override
  public boolean insideCache() {
    return insideCache;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof CacheEntryImpl that)) {
      return false;
    }
    return this.pageKey.equals(that.pageKey);
  }

  @Override
  public int hashCode() {
    return pageKey.hashCode();
  }

  @Override
  public PageKey getPageKey() {
    return this.pageKey;
  }

  @Override
  public String toString() {
    return "CacheEntryImpl{"
        + "dataPointer="
        + dataPointer
        + ", fileId="
        + getFileId()
        + ", pageIndex="
        + getPageIndex()
        + ", usagesCount="
        + usagesCount
        + '}';
  }

  @Override
  public void close() throws IOException {
    this.readCache.releaseFromRead(this);
  }
}
