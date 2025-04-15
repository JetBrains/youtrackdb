package com.jetbrains.youtrack.db.internal.core.storage.memory;

import com.jetbrains.youtrack.db.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrack.db.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrack.db.internal.common.directmemory.Pointer;
import com.jetbrains.youtrack.db.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrack.db.internal.core.storage.cache.CacheEntryImpl;
import com.jetbrains.youtrack.db.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrack.db.internal.core.storage.cache.ReadCache;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class MemoryFile {

  private final int id;
  private final int storageId;

  private final ReadWriteLock clearLock = new ReentrantReadWriteLock();

  private final ConcurrentSkipListMap<Long, CacheEntry> content = new ConcurrentSkipListMap<>();

  public MemoryFile(final int storageId, final int id) {
    this.storageId = storageId;
    this.id = id;
  }

  public CacheEntry loadPage(final long index) {
    clearLock.readLock().lock();
    try {
      return content.get(index);
    } finally {
      clearLock.readLock().unlock();
    }
  }

  public CacheEntry addNewPage(ReadCache readCache) {
    clearLock.readLock().lock();
    try {
      CacheEntry cacheEntry;

      long index;
      do {
        if (content.isEmpty()) {
          index = 0;
        } else {
          final long lastIndex = content.lastKey();
          index = lastIndex + 1;
        }

        final var bufferPool = ByteBufferPool.instance(null);
        final var pointer =
            bufferPool.acquireDirect(true, Intention.ADD_NEW_PAGE_IN_MEMORY_STORAGE);

        final var cachePointer = new CachePointer(pointer, bufferPool, id, (int) index);
        cachePointer.incrementReferrer();

        cacheEntry =
            new CacheEntryImpl(
                DirectMemoryOnlyDiskCache.composeFileId(storageId, id),
                (int) index,
                cachePointer,
                true,
                readCache);

        final var oldCacheEntry = content.putIfAbsent(index, cacheEntry);

        if (oldCacheEntry != null) {
          cachePointer.decrementReferrer();
          index = -1;
        }
      } while (index < 0);

      return cacheEntry;
    } finally {
      clearLock.readLock().unlock();
    }
  }

  public long size() {
    clearLock.readLock().lock();
    try {
      if (content.isEmpty()) {
        return 0;
      }

      try {
        return content.lastKey() + 1;
      } catch (final NoSuchElementException ignore) {
        return 0;
      }

    } finally {
      clearLock.readLock().unlock();
    }
  }

  public long getUsedMemory() {
    return content.size();
  }

  public void clear() {
    var thereAreNotReleased = false;

    clearLock.writeLock().lock();
    try {
      for (final var entry : content.values()) {
        //noinspection SynchronizationOnLocalVariableOrMethodParameter
        synchronized (entry) {
          thereAreNotReleased |= entry.getUsagesCount() > 0;
          entry.getCachePointer().decrementReferrer();
        }
      }

      content.clear();
    } finally {
      clearLock.writeLock().unlock();
    }

    if (thereAreNotReleased) {
      throw new IllegalStateException(
          "Some cache entries were not released. Storage may be in invalid state.");
    }
  }
}
