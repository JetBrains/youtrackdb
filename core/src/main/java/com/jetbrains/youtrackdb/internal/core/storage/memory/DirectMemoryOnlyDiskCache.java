/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */

package com.jetbrains.youtrackdb.internal.core.storage.memory;

import com.jetbrains.youtrackdb.internal.common.directmemory.PageFrame;
import com.jetbrains.youtrackdb.internal.common.types.ModifiableBoolean;
import com.jetbrains.youtrackdb.internal.common.util.CommonConst;
import com.jetbrains.youtrackdb.internal.core.command.CommandOutputListener;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.storage.cache.AbstractWriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.PageDataVerificationError;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.BackgroundExceptionListener;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.PageIsBrokenListener;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import javax.annotation.Nullable;

/**
 * Read and write cache implementation that stores all pages in direct memory only.
 *
 * @since 6/24/14
 */
public final class DirectMemoryOnlyDiskCache extends AbstractWriteCache
    implements ReadCache, WriteCache {

  private final Lock metadataLock = new ReentrantLock();

  private final Object2IntOpenHashMap<String> fileNameIdMap = new Object2IntOpenHashMap<>();
  private final Int2ObjectOpenHashMap<String> fileIdNameMap = new Int2ObjectOpenHashMap<>();

  private final ConcurrentMap<Integer, MemoryFile> files = new ConcurrentHashMap<>();

  private int counter;

  private final int pageSize;
  private final int id;
  private final String storageName;

  DirectMemoryOnlyDiskCache(final int pageSize, final int id, String storageName) {
    this.pageSize = pageSize;
    this.id = id;
    this.storageName = storageName;
    fileNameIdMap.defaultReturnValue(-1);
  }

  @Override
  public String getStorageName() {
    return storageName;
  }

  /**
   * {@inheritDoc}
   */
  @Nullable @Override
  public Path getRootDirectory() {
    return null;
  }

  @Override
  public long addFile(final String fileName, final WriteCache writeCache) {
    metadataLock.lock();
    try {
      var fileId = fileNameIdMap.getInt(fileName);

      if (fileId == -1) {
        counter++;
        final var id = counter;

        files.put(id, new MemoryFile(this.id, id));
        fileNameIdMap.put(fileName, id);

        fileId = id;

        fileIdNameMap.put(fileId, fileName);
      } else {
        throw new StorageException(storageName, fileName + " already exists.");
      }

      return composeFileId(id, fileId);
    } finally {
      metadataLock.unlock();
    }
  }

  @Override
  public long fileIdByName(final String fileName) {
    metadataLock.lock();
    try {
      final var fileId = fileNameIdMap.getInt(fileName);
      if (fileId > -1) {
        return fileId;
      }
    } finally {
      metadataLock.unlock();
    }

    return -1;
  }

  @Override
  public int internalFileId(final long fileId) {
    return extractFileId(fileId);
  }

  @Override
  public long externalFileId(final int fileId) {
    return composeFileId(id, fileId);
  }

  @Override
  public long bookFileId(final String fileName) {
    metadataLock.lock();
    try {
      counter++;
      return composeFileId(id, counter);
    } finally {
      metadataLock.unlock();
    }
  }

  @Override
  public void addBackgroundExceptionListener(final BackgroundExceptionListener listener) {
  }

  @Override
  public void removeBackgroundExceptionListener(final BackgroundExceptionListener listener) {
  }

  @Override
  public void checkCacheOverflow() {
  }

  @Override
  public long addFile(final String fileName, final long fileId, final WriteCache writeCache) {
    final var intId = extractFileId(fileId);

    metadataLock.lock();
    try {
      if (files.containsKey(intId)) {
        throw new StorageException(storageName, "File with id " + intId + " already exists.");
      }

      if (fileNameIdMap.containsKey(fileName)) {
        throw new StorageException(storageName, fileName + " already exists.");
      }

      files.put(intId, new MemoryFile(id, intId));
      fileNameIdMap.put(fileName, intId);
      fileIdNameMap.put(intId, fileName);

      return composeFileId(id, intId);
    } finally {
      metadataLock.unlock();
    }
  }

  /**
   * Read-cache "load for write" entry point on the in-memory engine.
   *
   * <p><b>Divergence from the disk engine.</b> On the disk engine the read-cache wrappers
   * route through {@code LockFreeReadCache.data.compute} and bottom out on the total
   * {@code WriteCache.loadOrAdd} primitive (so the wrappers are total too). The in-memory
   * engine bypasses {@code LockFreeReadCache} entirely &mdash; this method is a direct map
   * probe via {@link #doLoad}, and it deliberately keeps {@code null}-on-miss semantics. The
   * total contract is offered only on {@link #loadOrAdd} (write-cache primitive); read-cache
   * misses against an unallocated page index continue to surface as {@code null} so existing
   * callers that distinguish "page does not exist" from "page exists but is empty" keep
   * working unchanged.
   */
  @Nullable @Override
  public CacheEntry loadOrAddForWrite(
      final long fileId,
      final long pageIndex,
      final WriteCache writeCache,
      final boolean verifyChecksums,
      final LogSequenceNumber startLSN) {
    assert fileId >= 0;
    final var cacheEntry = doLoad(fileId, pageIndex);

    if (cacheEntry == null) {
      return null;
    }

    cacheEntry.acquireExclusiveLock();
    return cacheEntry;
  }

  /**
   * Read-cache "load for read" entry point on the in-memory engine.
   *
   * <p><b>Divergence from the disk engine.</b> See {@link #loadOrAddForWrite} for the rationale:
   * the in-memory read-cache wrappers stay {@code null}-on-miss, while the write-cache
   * primitive {@link #loadOrAdd} is total.
   */
  @Nullable @Override
  public CacheEntry loadForRead(
      final long fileId,
      final long pageIndex,
      final WriteCache writeCache,
      final boolean verifyChecksums) {

    final var cacheEntry = doLoad(fileId, pageIndex);

    if (cacheEntry == null) {
      return null;
    }

    return cacheEntry;
  }

  @Override
  public CacheEntry silentLoadForRead(
      long extFileId, int pageIndex, WriteCache writeCache, boolean verifyChecksums) {
    return loadForRead(extFileId, pageIndex, writeCache, verifyChecksums);
  }

  @Nullable private CacheEntry doLoad(final long fileId, final long pageIndex) {
    final var intId = extractFileId(fileId);

    final var memoryFile = getFile(intId);
    final var cacheEntry = memoryFile.loadPage(pageIndex);
    if (cacheEntry == null) {
      return null;
    }

    synchronized (cacheEntry) {
      cacheEntry.incrementUsages();
    }

    return cacheEntry;
  }

  @Override
  public CacheEntry allocateNewPage(
      final long fileId, final WriteCache writeCache, final LogSequenceNumber startLSN) {
    final var intId = extractFileId(fileId);

    final var memoryFile = getFile(intId);
    final var cacheEntry = memoryFile.addNewPage(this);

    synchronized (cacheEntry) {
      cacheEntry.incrementUsages();
    }
    cacheEntry.acquireExclusiveLock();
    return cacheEntry;
  }

  /**
   * @deprecated Use {@link #loadOrAdd(long, long, boolean)} instead. The in-memory engine never
   *     had a working implementation (this always throws {@link UnsupportedOperationException});
   *     callers should migrate to {@link #loadOrAdd} which is total on this engine. Final
   *     deletion lands in the write-side API collapse.
   */
  @Deprecated
  @Override
  public int allocateNewPage(final long fileId) {
    throw new UnsupportedOperationException();
  }

  /**
   * In-memory parallel of the disk engine's {@code WOWCache.loadOrAdd}: a total page-access
   * primitive that returns a usable {@link CachePointer} for the given
   * {@code (fileId, pageIndex)} regardless of whether the page already exists in the
   * per-file map. The method never returns {@code null} for any open, non-deleted file.
   *
   * <p><b>Branch collapse.</b> The disk engine's three branches (load existing /
   * one-page extend / multi-page gap-fill) collapse to a single conceptual operation here:
   * the in-memory engine has no on-disk representation, so "load" and "extend / gap-fill"
   * differ only in whether the per-file map already has an entry for the target page index.
   * The atomic install-or-fetch is delegated to {@link MemoryFile#loadOrAddPage}, which
   * builds each candidate eagerly and publishes via
   * {@link java.util.concurrent.ConcurrentSkipListMap#putIfAbsent}; the loser of any
   * concurrent install race releases its candidate's freshly-acquired {@code pageFrame} back
   * to the pool before returning the winner's entry so no frame leaks under contention.
   *
   * <p><b>Gap-fill.</b> After this call returns, every index in
   * {@code [currentSize, pageIndex]} has an installed entry; the gap loop installs pages
   * {@code [currentSize, pageIndex - 1]} and the target page is installed afterwards. Only
   * the target page's pointer is returned to the caller. This matches the disk engine's
   * gap-fill contract (recovery-only path under normal callers) so the WAL replay loop can
   * target arbitrary pageIndex values without divergence between engines.
   *
   * <p><b>Magic stamp.</b> Each freshly-installed page frame is zero-filled by
   * {@code pageFramePool.acquire} and stamped with {@link LogSequenceNumber}{@code (-1,-1)}
   * before publication, mirroring the magic-stamped empty-buffer contract on the disk
   * engine's extend / gap-fill branches. Callers that read the LSN immediately after
   * {@code loadOrAdd} on a fresh page see {@code (-1,-1)} on both engines uniformly.
   *
   * <p><b>Read-cache divergence.</b> Only this method (the {@code WriteCache} primitive)
   * is total on the in-memory engine. The {@link ReadCache} entry points
   * {@link #loadForRead} and {@link #loadOrAddForWrite} keep their {@code null}-on-miss
   * semantics because the in-memory engine bypasses {@code LockFreeReadCache.data.compute}
   * and so cannot fold the totality contract into the read-cache wrappers without
   * rewriting unrelated callers. The disk engine's read-cache wrappers (which DO go through
   * {@code data.compute}) inherit totality from {@link #loadOrAdd} on that engine; the
   * in-memory engine does not.
   *
   * <p><b>Referrer accounting.</b> {@link MemoryFile#loadOrAddPage} bumps the returned
   * {@link CachePointer}'s readers-referrer count exactly once before publication and
   * before releasing the per-file {@code clearLock} read lock; no concurrent {@code clear()}
   * / {@code deleteFile()} / {@code truncateFile()} can recycle the frame between
   * publication and the increment. Callers must call
   * {@link CachePointer#decrementReadersReferrer()} when they are done with the pointer.
   *
   * @param fileId external file id of the target page; must be open and registered
   * @param pageIndex zero-based page index inside that file; must be non-negative
   * @param verifyChecksums ignored on the in-memory engine (no on-disk image to verify)
   * @return a non-null {@link CachePointer} positioned at the target page
   * @throws IllegalArgumentException if {@code pageIndex < 0} or the file does not exist
   */
  @Override
  public CachePointer loadOrAdd(
      final long fileId, final long pageIndex, final boolean verifyChecksums) {
    if (pageIndex < 0) {
      throw new IllegalArgumentException("Illegal page index value " + pageIndex);
    }
    final var intId = extractFileId(fileId);
    // Fail fast on a deleted/never-registered fileId. The contract for loadOrAdd matches
    // WOWCache: an unknown fileId surfaces an IllegalArgumentException raw to the caller as
    // a caller-bug signal; the totality contract holds only for open, non-deleted files.
    // Probe the per-storage map directly rather than going through getFile() so the engine-
    // specific StorageException does not leak into a contract that promises the disk-engine
    // IllegalArgumentException shape.
    final var memoryFile = files.get(intId);
    if (memoryFile == null) {
      throw new IllegalArgumentException(
          "File with id " + intId + " not found in DirectMemoryOnlyDiskCache");
    }
    // The MemoryFile primitive returns the entry with its CachePointer's readers-referrer
    // already incremented exactly once (and the increment ran under the per-file clearLock
    // readLock so a concurrent clear()/deleteFile()/truncateFile() cannot recycle the frame
    // between publication and the increment). The caller owns a single readers reference
    // and must call decrementReadersReferrer() to release.
    final var cacheEntry = memoryFile.loadOrAddPage(pageIndex, this);
    return cacheEntry.getCachePointer();
  }

  private MemoryFile getFile(final int fileId) {
    final var memoryFile = files.get(fileId);

    if (memoryFile == null) {
      throw new StorageException(storageName, "File with id " + fileId + " does not exist");
    }

    return memoryFile;
  }

  @Override
  public void releaseFromWrite(
      final CacheEntry cacheEntry, final WriteCache writeCache, boolean changed) {
    cacheEntry.releaseExclusiveLock();

    doRelease(cacheEntry);
  }

  @Override
  public void releaseFromRead(final CacheEntry cacheEntry) {
    doRelease(cacheEntry);
  }

  private static void doRelease(final CacheEntry cacheEntry) {
    //noinspection SynchronizationOnLocalVariableOrMethodParameter
    synchronized (cacheEntry) {
      cacheEntry.decrementUsages();
    }
  }

  @Override
  public long getFilledUpTo(final long fileId) {
    final var intId = extractFileId(fileId);
    final var memoryFile = getFile(intId);
    return memoryFile.size();
  }

  @Override
  public void flush(final long fileId) {
  }

  @Override
  public void close(final long fileId, final boolean flush) {
  }

  @Override
  public void deleteFile(final long fileId) {
    final var intId = extractFileId(fileId);
    metadataLock.lock();
    try {
      final var fileName = fileIdNameMap.remove(intId);
      if (fileName == null) {
        return;
      }

      fileNameIdMap.removeInt(fileName);
      final var file = files.remove(intId);
      if (file != null) {
        file.clear();
      }
    } finally {
      metadataLock.unlock();
    }
  }

  @Override
  public void renameFile(final long fileId, final String newFileName) {
    final var intId = extractFileId(fileId);

    metadataLock.lock();
    try {
      final var fileName = fileIdNameMap.get(intId);
      if (fileName == null) {
        return;
      }

      fileNameIdMap.removeInt(fileName);

      fileIdNameMap.put(intId, newFileName);
      fileNameIdMap.put(newFileName, intId);
    } finally {
      metadataLock.unlock();
    }
  }

  @Override
  public void truncateFile(final long fileId) {
    final var intId = extractFileId(fileId);

    final var file = getFile(intId);
    file.clear();
  }

  @Override
  public void flush() {
  }

  @Override
  public long[] close() {
    return new long[0];
  }

  @Override
  public void clear() {
    delete();
  }

  @Override
  public long[] delete() {
    metadataLock.lock();
    try {
      for (final var file : files.values()) {
        file.clear();
      }

      files.clear();
      fileIdNameMap.clear();
      fileNameIdMap.clear();
    } finally {
      metadataLock.unlock();
    }

    return new long[0];
  }

  @Override
  public void replaceFileId(long fileId, long newFileId) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void deleteStorage(final WriteCache writeCache) {
    delete();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void closeStorage(final WriteCache writeCache) {
    //noinspection ResultOfMethodCallIgnored
    close();
  }

  @Override
  public void changeMaximumAmountOfMemory(final long calculateReadCacheMaxMemory) {
  }

  @Override
  @Nullable public PageFrame getPageFrameOptimistic(final long fileId, final long pageIndex) {
    // In-memory cache does not support optimistic reads — always returns null
    // so the caller falls back to the CAS-pinned path.
    return null;
  }

  @Override
  public void recordOptimisticAccess(final long fileId, final long pageIndex) {
    // No-op: in-memory cache has no eviction policy to update.
  }

  @Override
  public PageDataVerificationError[] checkStoredPages(
      final CommandOutputListener commandOutputListener) {
    return CommonConst.EMPTY_PAGE_DATA_VERIFICATION_ARRAY;
  }

  @Override
  public boolean exists(final String name) {
    metadataLock.lock();
    try {
      final var fileId = fileNameIdMap.getInt(name);
      if (fileId == -1) {
        return false;
      }

      final var memoryFile = files.get(fileId);
      return memoryFile != null;
    } finally {
      metadataLock.unlock();
    }
  }

  @Override
  public boolean exists(final long fileId) {
    final var intId = extractFileId(fileId);

    metadataLock.lock();
    try {
      final var memoryFile = files.get(intId);
      return memoryFile != null;
    } finally {
      metadataLock.unlock();
    }
  }

  @Override
  public void restoreModeOn() {
  }

  @Override
  public void restoreModeOff() {
  }

  @Override
  public String fileNameById(final long fileId) {
    final var intId = extractFileId(fileId);

    metadataLock.lock();
    try {
      return fileIdNameMap.get(intId);
    } finally {
      metadataLock.unlock();
    }
  }

  @Override
  public String nativeFileNameById(final long fileId) {
    return fileNameById(fileId);
  }

  @Override
  public long getUsedMemory() {
    long totalPages = 0;
    for (final var file : files.values()) {
      totalPages += file.getUsedMemory();
    }

    return totalPages * pageSize;
  }

  @Override
  public boolean checkLowDiskSpace() {
    return true;
  }

  /**
   * Not implemented because has no sense
   */
  @Override
  public void addPageIsBrokenListener(final PageIsBrokenListener listener) {
  }

  /**
   * Not implemented because has no sense
   */
  @Override
  public void removePageIsBrokenListener(final PageIsBrokenListener listener) {
  }

  @Override
  public long loadFile(final String fileName) {
    metadataLock.lock();
    try {
      final var fileId = fileNameIdMap.getInt(fileName);

      if (fileId == -1) {
        throw new StorageException(storageName, "File " + fileName + " does not exist.");
      }

      return composeFileId(id, fileId);
    } finally {
      metadataLock.unlock();
    }
  }

  @Override
  public long addFile(final String fileName) {
    return addFile(fileName, null);
  }

  @Override
  public long addFile(final String fileName, final long fileId) {
    return addFile(fileName, fileId, null);
  }

  @Override
  public void store(final long fileId, final long pageIndex, final CachePointer dataPointer) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void syncDataFiles(final long segmentId) {
  }

  @Override
  public void flushTillSegment(final long segmentId) {
  }

  @Override
  public Long getMinimalNotFlushedSegment() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void updateDirtyPagesTable(
      final CachePointer pointer, final LogSequenceNumber startLSN) {
  }

  @Override
  public void create() {
  }

  @Override
  public void open() {
  }

  @Override
  public CachePointer load(
      final long fileId,
      final long startPageIndex,
      final ModifiableBoolean cacheHit,
      final boolean verifyChecksums) {
    throw new UnsupportedOperationException();
  }

  /**
   * Non-extending probe primitive on the in-memory engine.
   *
   * <p>The in-memory engine's silent-read path (see
   * {@link #silentLoadForRead}) bypasses {@code WriteCache.load} / {@code loadIfPresent}
   * entirely: it goes through the {@code ReadCache} surface ({@link #loadForRead}) which
   * probes the {@link MemoryFile} map directly. This implementation therefore mirrors the
   * existing {@link #load} contract by throwing {@link UnsupportedOperationException} so a
   * future caller that wires the in-memory engine into a code path expecting the
   * {@code WriteCache} silent-probe primitive surfaces the unwired call site immediately
   * rather than silently returning {@code null} and corrupting the diagnostic.
   */
  @Override
  public CachePointer loadIfPresent(
      final long fileId, final long pageIndex, final boolean verifyChecksums) {
    throw new UnsupportedOperationException();
  }

  @Override
  public long getExclusiveWriteCachePagesSize() {
    return 0;
  }

  @Override
  public void truncateFile(final long fileId, final WriteCache writeCache) {
    truncateFile(fileId);
  }

  @Override
  public int getId() {
    return id;
  }

  @Override
  public Map<String, Long> files() {
    final var result = new Object2LongOpenHashMap<String>(1024);

    metadataLock.lock();
    try {
      for (final var entry : fileNameIdMap.object2IntEntrySet()) {
        if (entry.getIntValue() > 0) {
          result.put(entry.getKey(), composeFileId(id, entry.getIntValue()));
        }
      }
    } finally {
      metadataLock.unlock();
    }

    return result;
  }

  /**
   * @inheritDoc
   */
  @Override
  public int pageSize() {
    return pageSize;
  }

  /**
   * @inheritDoc
   */
  @Override
  public boolean fileIdsAreEqual(final long firsId, final long secondId) {
    final var firstIntId = extractFileId(firsId);
    final var secondIntId = extractFileId(secondId);

    return firstIntId == secondIntId;
  }

  @Nullable @Override
  public String restoreFileById(final long fileId) {
    return null;
  }

  @Override
  public void closeFile(final long fileId, final boolean flush, final WriteCache writeCache) {
    close(fileId, flush);
  }

  @Override
  public void deleteFile(final long fileId, final WriteCache writeCache) {
    deleteFile(fileId);
  }
}
