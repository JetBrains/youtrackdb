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

import com.jetbrains.youtrackdb.internal.common.concur.collection.CASObjectArray;
import com.jetbrains.youtrackdb.internal.common.types.ModifiableBoolean;
import com.jetbrains.youtrackdb.internal.common.util.CommonConst;
import com.jetbrains.youtrackdb.internal.core.command.CommandOutputListener;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.storage.cache.AbstractWriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.FileHandler;
import com.jetbrains.youtrackdb.internal.core.storage.cache.PageDataVerificationError;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.BackgroundExceptionListener;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.PageIsBrokenListener;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import javax.annotation.Nullable;

/**
 * @since 6/24/14
 */
public final class DirectMemoryOnlyDiskCache extends AbstractWriteCache
    implements ReadCache, WriteCache {

  private final Lock metadataLock = new ReentrantLock();

  private final ConcurrentMap<String, FileHandler> fileNameHandlerMap = new ConcurrentHashMap<>();
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
  }

  @Override
  public String getStorageName() {
    return storageName;
  }

  /**
   * {@inheritDoc}
   */
  @Nullable
  @Override
  public Path getRootDirectory() {
    return null;
  }

  @Override
  public FileHandler addFile(final String fileName, final WriteCache writeCache) {
    metadataLock.lock();
    try {
      var fileHandler = fileNameHandlerMap.get(fileName);

      if (fileHandler == null) {
        fileHandler = new FileHandler(id, new CASObjectArray<>());
        counter++;
        final var id = counter;

        files.put(id, new MemoryFile(this.id, id));
        fileNameHandlerMap.put(fileName, fileHandler);
        fileIdNameMap.put(id, fileName);
      } else {
        throw new StorageException(storageName, fileName + " already exists.");
      }

      return fileHandler;
    } finally {
      metadataLock.unlock();
    }
  }

  @Override
  public FileHandler fileHandlerByName(final String fileName) {
    metadataLock.lock();
    try {
      return fileNameHandlerMap.get(fileName);
    } finally {
      metadataLock.unlock();
    }
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
  public FileHandler addFile(final String fileName, final long fileId,
      final WriteCache writeCache) {
    final var intId = extractFileId(fileId);

    metadataLock.lock();
    try {
      if (files.containsKey(intId)) {
        throw new StorageException(storageName, "File with id " + intId + " already exists.");
      }

      if (fileNameHandlerMap.containsKey(fileName)) {
        throw new StorageException(storageName, fileName + " already exists.");
      }

      files.put(intId, new MemoryFile(id, intId));
      var fileHandler = new FileHandler(intId, new CASObjectArray<>());
      fileNameHandlerMap.put(fileName, fileHandler);
      fileIdNameMap.put(intId, fileName);

      return fileHandler;
    } finally {
      metadataLock.unlock();
    }
  }

  @Nullable
  @Override
  public CacheEntry loadForWrite(
      final FileHandler fileHandler,
      final long pageIndex,
      final WriteCache writeCache,
      final boolean verifyChecksums,
      final LogSequenceNumber startLSN) {
    assert fileHandler.fileId() >= 0;
    final var cacheEntry = doLoad(fileHandler, pageIndex);

    if (cacheEntry == null) {
      return null;
    }

    cacheEntry.acquireExclusiveLock();
    return cacheEntry;
  }

  @Nullable
  @Override
  public CacheEntry loadForRead(
      final FileHandler fileHandler,
      final long pageIndex,
      final WriteCache writeCache,
      final boolean verifyChecksums) {

    final var cacheEntry = doLoad(fileHandler, pageIndex);

    if (cacheEntry == null) {
      return null;
    }

    cacheEntry.acquireSharedLock();

    return cacheEntry;
  }

  @Override
  public CacheEntry silentLoadForRead(
      FileHandler fileHandler, int pageIndex, WriteCache writeCache, boolean verifyChecksums) {
    return loadForRead(fileHandler, pageIndex, writeCache, verifyChecksums);
  }

  @Nullable
  private CacheEntry doLoad(final FileHandler fileHandler, final long pageIndex) {
    final var intId = extractFileId(fileHandler.fileId());

    final var memoryFile = getFile(intId);
    final var cacheEntry = memoryFile.loadPage(pageIndex);
    if (cacheEntry == null) {
      return null;
    }

    // todo remove
    synchronized (cacheEntry) {
      cacheEntry.incrementUsages();
    }

    return cacheEntry;
  }

  @Override
  public CacheEntry allocateNewPage(
      final FileHandler fileHandler, final WriteCache writeCache,
      final LogSequenceNumber startLSN) {
    // todo do we need the id
    final var intId = extractFileId(fileHandler.fileId());

    final var memoryFile = getFile(intId);
    final var cacheEntry = memoryFile.addNewPage(this);

    synchronized (cacheEntry) {
      cacheEntry.incrementUsages();
    }
    cacheEntry.acquireExclusiveLock();
    return cacheEntry;
  }

  @Override
  public int allocateNewPage(final long fileId) {
    throw new UnsupportedOperationException();
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
    cacheEntry.releaseSharedLock();

    doRelease(cacheEntry);
  }

  private static void doRelease(final CacheEntry cacheEntry) {
    //noinspection SynchronizationOnLocalVariableOrMethodParameter
    synchronized (cacheEntry) {
      cacheEntry.decrementUsages();
      assert cacheEntry.getUsagesCount() > 0
          || cacheEntry.getCachePointer().getBuffer() == null
          || !cacheEntry.isLockAcquiredByCurrentThread();
    }
  }

  @Override
  public long getFilledUpTo(final FileHandler fileHandler) {
    final var intId = extractFileId(fileHandler.fileId());
    final var memoryFile = getFile(intId);
    return memoryFile.size();
  }

  @Override
  public void flush(final long fileId) {
  }

  @Override
  public void close(final FileHandler fileHandler, final boolean flush) {
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

      fileNameHandlerMap.remove(fileName);
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

      fileNameHandlerMap.remove(fileName);

      fileIdNameMap.put(intId, newFileName);
      fileNameHandlerMap.put(newFileName, new FileHandler(intId, new CASObjectArray<>()));
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
      fileNameHandlerMap.clear();
    } finally {
      metadataLock.unlock();
    }

    return new long[0];
  }

  @Override
  public void replaceFileId(long fileId, long newFileId) throws IOException {
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
  public PageDataVerificationError[] checkStoredPages(
      final CommandOutputListener commandOutputListener) {
    return CommonConst.EMPTY_PAGE_DATA_VERIFICATION_ARRAY;
  }

  @Override
  public boolean exists(final String name) {
    metadataLock.lock();
    try {
      final var fileHandler = fileNameHandlerMap.get(name);
      // do we need to check id is > -1 still?
      if (fileHandler == null) {
        return false;
      }

      final var memoryFile = files.get(fileHandler.fileId());
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
  public FileHandler loadFile(final String fileName) {
    // I suspect we need thid lock because previous collection was not atomic
    metadataLock.lock();
    try {
      final var fileHandler = fileNameHandlerMap.get(fileName);

      if (fileHandler == null) {
        throw new StorageException(storageName, "File " + fileName + " does not exist.");
      }

      return fileHandler;
    } finally {
      metadataLock.unlock();
    }
  }

  @Override
  public FileHandler addFile(final String fileName) {
    return addFile(fileName, null);
  }

  @Override
  public FileHandler addFile(final String fileName, final long fileId) {
    return addFile(fileName, fileId, null);
  }

  @Override
  public void store(final long fileId, final long pageIndex, final CachePointer dataPointer) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void syncDataFiles(final long segmentId, byte[] lastMetadata) {
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

  @Override
  public long getExclusiveWriteCachePagesSize() {
    return 0;
  }

  @Override
  public void truncateFile(long fileId, WriteCache writeCache) throws IOException {
    truncateFile(fileId);
  }

  @Override
  public int getId() {
    return id;
  }

  @Override
  public Map<String, FileHandler> files() {
    final var result = new ConcurrentHashMap<String, FileHandler>(1024);

    metadataLock.lock();
    try {
      for (final var entry : fileNameHandlerMap.entrySet()) {
        // why it's > 0 and not -1? is 0 a special id?
        if (entry.getValue().fileId() > 0) {
          result.put(entry.getKey(), entry.getValue());
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

  @Nullable
  @Override
  public String restoreFileById(final long fileId) {
    return null;
  }

  @Override
  public void closeFile(final FileHandler fileHandler, final boolean flush,
      final WriteCache writeCache) {
    close(fileHandler, flush);
  }

  @Override
  public void deleteFile(final long fileId, final WriteCache writeCache) {
    deleteFile(fileId);
  }
}
