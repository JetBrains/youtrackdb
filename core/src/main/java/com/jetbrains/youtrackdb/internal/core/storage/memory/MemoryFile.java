package com.jetbrains.youtrackdb.internal.core.storage.memory;

import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntryImpl;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
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

  /**
   * Total install-or-fetch primitive at an explicit page index.
   *
   * <p>The atomicity contract is: from the caller's point of view, after this method returns,
   * {@code (pageIndex, returnedEntry)} is observable in {@link #content} as a single transition;
   * no other thread can ever see an "in-flight" entry whose buffer or referrer count has not yet
   * been initialised. The primitive is the in-memory parallel of the disk engine's
   * {@code WOWCache.loadOrAdd}: it covers the load / one-page extend / multi-page gap-fill
   * branches uniformly.
   *
   * <p><b>Atomicity mechanism.</b> Each gap page (and the target page) is published via the
   * single-call atomic primitive {@link ConcurrentSkipListMap#computeIfAbsent}. The mapping
   * function runs while the bin is locked: a fresh {@link CachePointer} is materialised,
   * stamped with {@code LSN(-1,-1)} (matching the magic-stamped empty-buffer contract on the
   * disk engine's extend / gap-fill branches), and its referrer count is incremented before
   * the {@link CacheEntry} is returned to the map. Only after the mapping function exits does
   * the entry become visible to other readers. A concurrent {@code computeIfAbsent} on the
   * same key observes the already-published entry and skips installation. {@code clearLock}'s
   * read-side guards against {@link #clear()} running concurrently — the clear writer drops
   * every page in one critical section and is mutually exclusive with all installers.
   *
   * <p><b>Gap-fill semantics.</b> When {@code pageIndex >= currentSize}, every index in
   * {@code [currentSize, pageIndex]} gets an empty page installed (via the same atomic
   * primitive); the helper iterates from {@code currentSize} upward so the final
   * {@link #size()} reads back as {@code pageIndex + 1}. Intermediate gap pages are never
   * returned to the caller; their referrer count starts at 1 (the in-cache reference held by
   * the map itself), matching today's {@link #addNewPage} contract. Only the target page's
   * referrer count is bumped on the way out (handled by the caller).
   *
   * @param pageIndex zero-based target page index
   * @param readCache back-reference threaded into the freshly-installed {@link CacheEntryImpl}
   *     instances (used by the cache for release callbacks)
   * @return the existing or freshly-installed {@link CacheEntry} for {@code pageIndex}
   */
  public CacheEntry loadOrAddPage(final long pageIndex, final ReadCache readCache) {
    if (pageIndex < 0) {
      throw new IllegalArgumentException("Illegal page index value " + pageIndex);
    }
    clearLock.readLock().lock();
    try {
      // Fast path: target already installed. The map is concurrent so the get is lock-free
      // and reflects the latest committed mapping.
      final var existing = content.get(pageIndex);
      if (existing != null) {
        return existing;
      }
      // Install gap pages [currentSize..pageIndex-1] one at a time using the same atomic
      // primitive as the target installation. We re-read currentSize each iteration so that
      // a concurrent installer's progress is observed (computeIfAbsent on an already-present
      // index is a no-op that returns the existing entry, which we discard for gap pages).
      // Iterate in ascending order so size() advances monotonically as observed by other
      // threads — no-one ever sees a non-contiguous tail.
      var currentSize = size();
      while (currentSize < pageIndex) {
        installEmptyPage(currentSize, readCache);
        currentSize++;
      }
      // Atomic install for the target index. computeIfAbsent runs the mapping function while
      // the bin is locked; the resulting entry is published atomically when the function
      // returns. A concurrent caller targeting the same index will observe whichever entry
      // wins the race.
      return installEmptyPage(pageIndex, readCache);
    } finally {
      clearLock.readLock().unlock();
    }
  }

  /**
   * Atomically installs a freshly-created {@link CacheEntry} for {@code index} if no entry
   * exists yet, returning the installed (or pre-existing) entry. The mapping function inside
   * {@link ConcurrentSkipListMap#computeIfAbsent} runs under the per-key bin lock: the fresh
   * {@link CachePointer} acquires its referrer count and the in-buffer LSN is stamped before
   * the entry becomes visible to other threads. This matches the disk engine's
   * "magic-stamped empty buffer" contract on the extend / gap-fill branches.
   */
  private CacheEntry installEmptyPage(final long index, final ReadCache readCache) {
    return content.computeIfAbsent(index, k -> {
      final var framePool = ByteBufferPool.instance(null).pageFramePool();
      final var pageFrame =
          framePool.acquire(true, Intention.ADD_NEW_PAGE_IN_MEMORY_STORAGE);
      // Stamp the empty buffer with LSN(-1,-1) so callers that read the LSN before the first
      // mutation see the magic-stamp value the disk engine writes on extend/gap-fill.
      DurablePage.setLogSequenceNumberForPage(
          pageFrame.getBuffer(), new LogSequenceNumber(-1, -1));
      final var cachePointer =
          new CachePointer(pageFrame, framePool, id, (int) k.longValue());
      cachePointer.incrementReferrer();
      return new CacheEntryImpl(
          DirectMemoryOnlyDiskCache.composeFileId(storageId, id),
          (int) k.longValue(),
          cachePointer,
          true,
          readCache);
    });
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

        final var framePool = ByteBufferPool.instance(null).pageFramePool();
        final var pageFrame =
            framePool.acquire(true, Intention.ADD_NEW_PAGE_IN_MEMORY_STORAGE);

        final var cachePointer = new CachePointer(pageFrame, framePool, id, (int) index);
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
      // Log a warning instead of throwing — the content has already been cleared above, so
      // the storage deletion can proceed.  Throwing here causes cascading failures: the
      // uncaught IllegalStateException prevents doShutdownOnDelete() from setting the storage
      // status to CLOSED and prevents drop() from removing the storage from internal maps,
      // which poisons the YouTrackDB instance for all subsequent operations.
      LogManager.instance()
          .warn(
              this,
              "Some cache entries were not released during storage deletion."
                  + " This may indicate a cache entry leak.");
    }
  }
}
