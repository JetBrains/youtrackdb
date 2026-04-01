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
package com.jetbrains.youtrackdb.internal.core.storage.cache;

import com.jetbrains.youtrackdb.internal.common.directmemory.PageFrame;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import java.io.IOException;
import javax.annotation.Nullable;

/**
 * This class is heart of YouTrackDB storage model it presents disk backed data cache which works
 * with direct memory.
 *
 * <p>Model of this cache is based on page model. All direct memory area is mapped to disk files
 * and each file is split on pages. Page is smallest unit of work. The amount of RAM which can be
 * used for data manipulation is limited so only a subset of data will be really loaded into RAM on
 * demand, if there is not enough RAM to store all data, part of them will by flushed to the disk.
 * If disk cache is closed all changes will be flushed to the disk.
 *
 * @since 14.03.13
 */
public interface ReadCache {

  /**
   * Minimum size of memory which may be allocated by cache (in pages). This parameter is used only
   * if related flag is set in constrictor of cache.
   */
  int MIN_CACHE_SIZE = 256;

  long addFile(String fileName, WriteCache writeCache) throws IOException;

  long addFile(String fileName, long fileId, WriteCache writeCache) throws IOException;

  /**
   * Registers a file with an explicit non-durability flag. The default implementation
   * delegates to the 3-arg overload (ignores nonDurable), preserving backward
   * compatibility for implementations that do not support non-durable files.
   */
  default long addFile(String fileName, long fileId, WriteCache writeCache, boolean nonDurable)
      throws IOException {
    return addFile(fileName, fileId, writeCache);
  }

  CacheEntry loadForWrite(
      long fileId,
      long pageIndex,
      WriteCache writeCache,
      boolean verifyChecksums,
      LogSequenceNumber startLSN)
      throws IOException;

  CacheEntry loadForRead(
      long fileId, long pageIndex, WriteCache writeCache, boolean verifyChecksums)
      throws IOException;

  CacheEntry silentLoadForRead(
      final long extFileId,
      final int pageIndex,
      final WriteCache writeCache,
      final boolean verifyChecksums);

  void releaseFromRead(CacheEntry cacheEntry);

  void releaseFromWrite(CacheEntry cacheEntry, WriteCache writeCache, boolean changed);

  CacheEntry allocateNewPage(long fileId, WriteCache writeCache, LogSequenceNumber startLSN)
      throws IOException;

  long getUsedMemory();

  void clear();

  void truncateFile(long fileId, WriteCache writeCache) throws IOException;

  void closeFile(long fileId, boolean flush, WriteCache writeCache);

  void deleteFile(long fileId, WriteCache writeCache) throws IOException;

  void deleteStorage(WriteCache writeCache) throws IOException;

  /**
   * Closes all files inside of write cache and flushes all associated data.
   *
   * @param writeCache Write cache to close.
   */
  void closeStorage(WriteCache writeCache) throws IOException;

  void changeMaximumAmountOfMemory(long calculateReadCacheMaxMemory);

  /**
   * Optimistic cache lookup: returns the PageFrame for the given page without CAS-based
   * pinning. Returns null on cache miss, evicted entry, or null CachePointer.
   *
   * <p>The caller must take an optimistic stamp from the returned PageFrame and validate
   * it after reading data to ensure the page was not evicted or modified.
   */
  @Nullable PageFrame getPageFrameOptimistic(long fileId, long pageIndex);

  /**
   * Records a successful optimistic read access for the given page, updating the
   * frequency sketch used by the eviction policy. Call this after stamp validation
   * succeeds, to keep eviction decisions accurate.
   *
   * <p>If the entry has been evicted between validation and this call, the access
   * is silently skipped (acceptable — one missed frequency bump is harmless).
   */
  void recordOptimisticAccess(long fileId, long pageIndex);
}
