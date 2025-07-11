package com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.atomicoperations;

import com.jetbrains.youtrack.db.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrack.db.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrack.db.internal.core.storage.cache.chm.LRUList;
import com.jetbrains.youtrack.db.internal.core.storage.cache.chm.PageKey;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.WALChanges;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.WALPageChangesPortion;
import java.io.IOException;

public class CacheEntryChanges implements CacheEntry {

  protected CacheEntry delegate;
  protected final WALChanges changes = new WALPageChangesPortion();
  private LogSequenceNumber initialLSN;
  private final AtomicOperation atomicOp;

  protected boolean isNew;

  private LogSequenceNumber changeLSN;

  protected boolean verifyCheckSum;

  @SuppressWarnings("WeakerAccess")
  public CacheEntryChanges(final boolean verifyCheckSum, AtomicOperation atomicOp) {
    this.verifyCheckSum = verifyCheckSum;
    this.atomicOp = atomicOp;
  }

  @Override
  public CachePointer getCachePointer() {
    return delegate.getCachePointer();
  }

  @Override
  public void clearCachePointer() {
    delegate.clearCachePointer();
  }

  @Override
  public long getFileId() {
    return delegate.getFileId();
  }

  @Override
  public int getPageIndex() {
    return delegate.getPageIndex();
  }

  @Override
  public void acquireExclusiveLock() {
    delegate.acquireExclusiveLock();
  }

  @Override
  public void releaseExclusiveLock() {
    delegate.releaseExclusiveLock();
  }

  @Override
  public void acquireSharedLock() {
    delegate.acquireSharedLock();
  }

  @Override
  public void releaseSharedLock() {
    delegate.releaseSharedLock();
  }

  @Override
  public int getUsagesCount() {
    return delegate.getUsagesCount();
  }

  @Override
  public void incrementUsages() {
    delegate.incrementUsages();
  }

  @Override
  public boolean isLockAcquiredByCurrentThread() {
    return delegate.isLockAcquiredByCurrentThread();
  }

  @Override
  public void decrementUsages() {
    delegate.decrementUsages();
  }

  @Override
  public WALChanges getChanges() {
    return changes;
  }

  public void setDelegate(final CacheEntry delegate) {
    this.delegate = delegate;
  }

  public CacheEntry getDelegate() {
    return delegate;
  }

  @Override
  public LogSequenceNumber getEndLSN() {
    return delegate.getEndLSN();
  }

  @Override
  public void setEndLSN(final LogSequenceNumber endLSN) {
    delegate.setEndLSN(endLSN);
  }

  @Override
  public boolean acquireEntry() {
    return delegate.acquireEntry();
  }

  @Override
  public void releaseEntry() {
    delegate.releaseEntry();
  }

  @Override
  public boolean isReleased() {
    return delegate.isReleased();
  }

  @Override
  public boolean isAlive() {
    return delegate.isAlive();
  }

  @Override
  public boolean freeze() {
    return delegate.freeze();
  }

  @Override
  public boolean isFrozen() {
    return delegate.isFrozen();
  }

  @Override
  public void makeDead() {
    delegate.makeDead();
  }

  @Override
  public boolean isDead() {
    return delegate.isDead();
  }

  @Override
  public boolean isNewlyAllocatedPage() {
    return delegate.isNewlyAllocatedPage();
  }

  @Override
  public void markAllocated() {
    delegate.markAllocated();
  }

  @Override
  public void clearAllocationFlag() {
    delegate.clearAllocationFlag();
  }

  @Override
  public boolean insideCache() {
    return delegate.insideCache();
  }

  @Override
  public CacheEntry getNext() {
    throw new UnsupportedOperationException();
  }

  @Override
  public CacheEntry getPrev() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setPrev(final CacheEntry prev) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setNext(final CacheEntry next) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setContainer(final LRUList lruList) {
    throw new UnsupportedOperationException();
  }

  @Override
  public LRUList getContainer() {
    throw new UnsupportedOperationException();
  }

  LogSequenceNumber getChangeLSN() {
    return changeLSN;
  }

  void setChangeLSN(final LogSequenceNumber lsn) {
    this.changeLSN = lsn;
  }

  @Override
  public LogSequenceNumber getInitialLSN() {
    return initialLSN;
  }

  @Override
  public void setInitialLSN(LogSequenceNumber lsn) {
    this.initialLSN = lsn;
  }

  @Override
  public PageKey getPageKey() {
    return delegate.getPageKey();
  }

  @Override
  public void close() throws IOException {
    atomicOp.releasePageFromWrite(this);
  }
}
