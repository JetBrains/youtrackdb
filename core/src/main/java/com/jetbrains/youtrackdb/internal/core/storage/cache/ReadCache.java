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

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
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

  /**
   * Loads the page at {@code pageIndex} in {@code fileId} for write access, allocating it on
   * disk if it does not yet exist. Unlike a pure load, this primitive is total: it returns a
   * usable {@link CacheEntry} for any open, non-deleted file regardless of whether
   * {@code pageIndex} is already on disk. When the page is freshly extended (or sits in a
   * just-filled gap), the returned entry is flagged as newly allocated so that
   * {@link #releaseFromWrite} publishes it on the write cache's dirty-page list.
   *
   * <p>The caller receives the entry already write-pinned and with its dirty-pages-table
   * record installed against {@code startLSN}.
   *
   * @see WriteCache#loadOrAdd
   */
  CacheEntry loadOrAddForWrite(
      long fileId,
      long pageIndex,
      WriteCache writeCache,
      boolean verifyChecksums,
      LogSequenceNumber startLSN)
      throws IOException;

  CacheEntry loadForRead(
      long fileId, long pageIndex, WriteCache writeCache, boolean verifyChecksums)
      throws IOException;

  /** Whether shared pinned reads participate in optimistic attempt validation. */
  default boolean supportsOptimisticReads() {
    return false;
  }

  /** A shared load for an attempt. Disk implementations must not extend the file. */
  default CacheEntry loadForRead(
      long fileId, long pageIndex, WriteCache writeCache, boolean verifyChecksums,
      OptimisticReadScope scope) throws IOException {
    return loadForRead(fileId, pageIndex, writeCache, verifyChecksums);
  }

  CacheEntry silentLoadForRead(
      final long extFileId,
      final int pageIndex,
      final WriteCache writeCache,
      final boolean verifyChecksums);

  void releaseFromRead(CacheEntry cacheEntry);

  void releaseFromWrite(CacheEntry cacheEntry, WriteCache writeCache, boolean changed);

  /**
   * Reserves commit pins before any page load. Null selects the caller's fallback protocol.
   * Implementations without a disk page cache do not support reservations.
   */
  @Nullable default WriteReservation tryReserveWritePins(int pages) {
    return null;
  }

  /** Per-commit page limit captured when the shared cache is created. */
  default int commitPageLimit() {
    return (Integer) GlobalConfiguration.DISK_CACHE_COMMIT_PAGE_LIMIT.getDefValue();
  }

  /** Cache-wide commit pin capacity. Zero disables page-set application. */
  default int writePinBudget() {
    return 0;
  }

  /**
   * Thread-confined resources for page-set application. Slots are consecutive from zero,
   * in strictly increasing canonical file/page order. Pin returns the entry for that slot.
   * All pins in one reservation must use the same {@link WriteCache}.
   * Pin rejects a different write cache with {@link IllegalArgumentException} before changing the slot.
   * Load every pin before locking any page. Prepare successfully before touching page content.
   * Close unlocks every page before releasing pins. A failed publish keeps its pin and budget.
   */
  interface WriteReservation extends AutoCloseable {
    CacheEntry pin(int slot, long fileId, long pageIndex, WriteCache writeCache,
        boolean verifyChecksums);

    /** Returns the entry of an already pinned slot while this reservation is open. */
    CacheEntry entry(int slot);

    void lock(int slot);

    void prepare(int slot, LogSequenceNumber startLSN, LogSequenceNumber endLSN);

    void publishAndUnlock(int slot);

    @Override
    void close();
  }

  long getUsedMemory();

  void clear();

  /**
   * Layered shrink primitive: reduces the physical size of {@code fileId} to
   * {@code targetBytes} by first delegating to {@link WriteCache#shrinkFile(long, long)}
   * (which settles the write-back layer and truncates the underlying file) and then
   * dropping any read-cache entries that sit at or above the target. This primitive
   * carries an explicit target and never grows the file. A {@code targetBytes} greater than
   * or equal to the current physical size is a clean no-op (the underlying WriteCache impl applies
   * the pre-flight check and the read-cache range-purge is itself a no-op when no entry
   * exists at or past the target).
   *
   * <p>Used by the recovery-time orphan-truncation pass to repair the
   * {@code logical &lt;= physical} invariant on the four entry-point-equipped storage
   * components (BTree, SharedLinkBagBTree, CollectionPositionMapV2,
   * PaginatedCollectionV2) after a partial-flush crash leaves physical pages past the
   * EP-stored logical counter. The write cache shrinks first, then the read cache drops
   * entries for the removed pages.
   *
   * <p>The in-memory implementation ({@code DirectMemoryOnlyDiskCache}) delegates to its
   * own {@code shrinkFile} no-op; it does not maintain a separate read-cache layer and
   * cannot produce on-disk orphans.
   *
   * @param fileId external file id of the target file
   * @param targetBytes new physical size in bytes; must be {@code >= 0} and should be a
   *     multiple of the page size
   * @param writeCache the write cache backing this read cache
   * @throws IOException if the underlying disk I/O fails during the truncate
   */
  void shrinkFile(long fileId, long targetBytes, WriteCache writeCache) throws IOException;

  void closeFile(long fileId, boolean flush, WriteCache writeCache);

  void deleteFile(long fileId, WriteCache writeCache) throws IOException;

  void deleteStorage(WriteCache writeCache) throws IOException;

  /**
   * Closes all files inside of write cache and flushes all associated data.
   *
   * @param writeCache Write cache to close.
   */
  void closeStorage(WriteCache writeCache) throws IOException;

  /**
   * Returns an unpinned identity token for final optimistic validation, or null on miss.
   * The caller records this entry with its frame and stamp. Final validation checks
   * that the recorded entry is alive and still holds that frame, without another lookup.
   * Implementations without optimistic reads return null.
   */
  @Nullable default CacheEntry getCacheEntryOptimistic(long fileId, long pageIndex) {
    return null;
  }

  /**
   * Records a successful optimistic read access for the given page, updating the
   * frequency sketch used by the eviction policy. Call this after cached-frame identity,
   * stamps, and the apply-phase epoch have passed validation.
   *
   * <p>If the entry has been evicted between validation and this call, the access
   * is silently skipped (acceptable — one missed frequency bump is harmless).
   */
  void recordOptimisticAccess(long fileId, long pageIndex);
}
