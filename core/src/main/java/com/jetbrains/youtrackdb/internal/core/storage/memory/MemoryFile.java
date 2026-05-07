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

  /**
   * Magic-stamp LSN written into every freshly-installed in-memory page before publication so
   * callers reading the LSN immediately after {@code loadOrAddPage} see {@code (-1,-1)} on both
   * engines uniformly. Hoisted to a class-level constant to avoid per-install allocation: gap-fill
   * stamps every page in the gap with this value, and replaying a long WAL gap can install many
   * pages in one call.
   */
  private static final LogSequenceNumber MAGIC_EMPTY_LSN = new LogSequenceNumber(-1, -1);

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
   * <p>The atomicity contract from the caller's point of view: after this method returns,
   * every index in {@code [currentSize, pageIndex]} has an installed entry; the loop installs
   * the gap pages {@code [currentSize, pageIndex - 1]} and the target page is installed after
   * the loop. The returned entry's {@link CachePointer} has its readers-referrer count
   * incremented exactly once before publication, so the caller owns a single readers
   * reference. The primitive is the in-memory parallel of the disk engine's
   * {@code WOWCache.loadOrAdd}: it covers the load / one-page extend / multi-page gap-fill
   * branches uniformly.
   *
   * <p><b>Atomicity mechanism.</b> {@link ConcurrentSkipListMap#computeIfAbsent} is <i>not</i>
   * guaranteed to apply its mapping function exactly once even when the value is absent
   * (multiple threads can both observe a miss, both materialise a candidate, and only one
   * publishes via the underlying {@code putIfAbsent}). Each gap page (and the target page) is
   * therefore published with an explicit {@code putIfAbsent}-with-release-on-loss pattern: the
   * candidate {@link CachePointer} is constructed eagerly outside the publish step, the
   * publish itself is the single atomic point, and the loser of any concurrent install race
   * releases its candidate's freshly-acquired {@code pageFrame} via {@code decrementReferrer}
   * before returning the winner's entry. {@code clearLock}'s read-side serialises every
   * installer against {@link #clear()} (the clear writer drops every page in one critical
   * section); read locks do not exclude installers from each other, so the publish-or-release
   * dance above is the actual install-then-publish atomicity contract on the same key.
   *
   * <p><b>Gap-fill semantics.</b> When {@code pageIndex >= currentSize}, the helper iterates
   * indices {@code [currentSize, pageIndex - 1]} and installs an empty page at each; the
   * target page at {@code pageIndex} is installed after the loop. The iteration is in
   * ascending order so {@link #size()} advances monotonically as observed by other threads;
   * no-one ever sees a non-contiguous tail. Intermediate gap pages are never returned to the
   * caller; their referrer count starts at 1 (the in-cache reference held by the map itself),
   * matching today's {@link #addNewPage} contract. The target page's readers-referrer is
   * bumped before the read-lock is released, before any concurrent {@link #clear()} can drop
   * the page.
   *
   * @param pageIndex zero-based target page index
   * @param readCache back-reference threaded into the freshly-installed {@link CacheEntryImpl}
   *     instances (used by the cache for release callbacks)
   * @return the existing or freshly-installed {@link CacheEntry} for {@code pageIndex} with
   *     its {@link CachePointer}'s readers-referrer count already incremented exactly once
   */
  public CacheEntry loadOrAddPage(final long pageIndex, final ReadCache readCache) {
    if (pageIndex < 0) {
      throw new IllegalArgumentException("Illegal page index value " + pageIndex);
    }
    clearLock.readLock().lock();
    try {
      // Fast path: target already installed. The map is concurrent so the get is lock-free
      // and reflects the latest committed mapping. The readers-referrer is bumped while the
      // clearLock readLock is held, which prevents a concurrent clear() / deleteFile() /
      // truncateFile() from observing readers==0 and recycling the frame between our get
      // and our increment.
      final var existing = content.get(pageIndex);
      if (existing != null) {
        existing.getCachePointer().incrementReadersReferrer();
        return existing;
      }
      // Install gap pages [currentSize, pageIndex - 1] one at a time. We snapshot size() once
      // and increment locally; concurrent installers' progress at the same indices is observed
      // via installEmptyPage's idempotence — putIfAbsent is a no-op when the key is already
      // present, returning the existing entry, which we discard for gap pages.
      // Iterate in ascending order so size() advances monotonically as observed by other
      // threads — no-one ever sees a non-contiguous tail.
      var currentSize = size();
      while (currentSize < pageIndex) {
        installEmptyPage(currentSize, readCache);
        currentSize++;
      }
      // Install the target index. installEmptyPage publishes the candidate with putIfAbsent;
      // a concurrent caller targeting the same index observes whichever entry wins the race
      // and the loser's pageFrame is released back to the pool before return.
      final var target = installEmptyPage(pageIndex, readCache);
      // Bump the readers-referrer for the target before releasing clearLock, so a concurrent
      // clear() / deleteFile() / truncateFile() cannot drop the page out from under us.
      target.getCachePointer().incrementReadersReferrer();
      return target;
    } finally {
      clearLock.readLock().unlock();
    }
  }

  /**
   * Installs a freshly-created {@link CacheEntry} for {@code index} if no entry exists yet,
   * returning the installed (or pre-existing) entry. The candidate is built eagerly: a
   * pageFrame is acquired from the pool, stamped with {@link #MAGIC_EMPTY_LSN}, wrapped in a
   * {@link CachePointer} with referrer count 1, and bound into a {@link CacheEntryImpl}. The
   * publish step is the single atomic point — {@link ConcurrentSkipListMap#putIfAbsent} —
   * and the loser of any concurrent install race releases its candidate's pageFrame via
   * {@link CachePointer#decrementReferrer()} before returning the winner's entry. This
   * matches the disk engine's "magic-stamped empty buffer" contract on the extend / gap-fill
   * branches and never leaks a frame even under contention on the same key.
   */
  private CacheEntry installEmptyPage(final long index, final ReadCache readCache) {
    final var framePool = ByteBufferPool.instance(null).pageFramePool();
    final var pageFrame = framePool.acquire(true, Intention.ADD_NEW_PAGE_IN_MEMORY_STORAGE);
    // Stamp the empty buffer with LSN(-1,-1) so callers that read the LSN before the first
    // mutation see the magic-stamp value the disk engine writes on extend/gap-fill.
    DurablePage.setLogSequenceNumberForPage(pageFrame.getBuffer(), MAGIC_EMPTY_LSN);
    final var cachePointer = new CachePointer(pageFrame, framePool, id, (int) index);
    cachePointer.incrementReferrer();
    final var freshEntry =
        new CacheEntryImpl(
            DirectMemoryOnlyDiskCache.composeFileId(storageId, id),
            (int) index,
            cachePointer,
            true,
            readCache);
    final var existing = content.putIfAbsent(index, freshEntry);
    if (existing != null) {
      // Lost the publish race: another installer beat us to this key. Release the
      // freshly-acquired pageFrame back to the pool and return the winner's entry.
      cachePointer.decrementReferrer();
      return existing;
    }
    return freshEntry;
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
