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

package com.jetbrains.youtrackdb.internal.core.storage.collection.v2;

import com.jetbrains.youtrackdb.internal.common.util.CommonConst;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.CollectionPositionMapException;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.collection.CollectionPositionMap;
import com.jetbrains.youtrackdb.internal.core.storage.collection.CollectionPositionMapBucket;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import java.io.IOException;
import java.util.Arrays;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.commons.lang3.ArrayUtils;

/**
 * Maps logical <em>collection positions</em> (monotonically increasing {@code long} values) to
 * physical record locations ({@link CollectionPositionMapBucket.PositionEntry}) on the data pages
 * of a {@link PaginatedCollectionV2}.
 *
 * <h2>File Layout</h2>
 *
 * <p>The position map is stored in a single file with extension {@value
 * CollectionPositionMap#DEF_EXTENSION}. The file is organized as a sequence of fixed-size pages
 * managed by the disk cache. Page 0 is a {@link MapEntryPoint} that holds the count of bucket
 * pages currently in use. Pages 1..N are {@link CollectionPositionMapBucket} pages, each holding
 * up to {@link CollectionPositionMapBucket#MAX_ENTRIES} position entries.
 *
 * <pre>{@code
 *  .cpm file layout:
 *  +-------------------------------+
 *  | Page 0: MapEntryPoint         |
 *  |   fileSize (int) = N          |  <-- number of bucket pages in use
 *  +-------------------------------+
 *  | Page 1: CollectionPositionMapBucket  |
 *  |   entry[0] .. entry[MAX-1]    |
 *  +-------------------------------+
 *  | Page 2: CollectionPositionMapBucket  |
 *  |   entry[0] .. entry[MAX-1]    |
 *  +-------------------------------+
 *  |           ...                 |
 *  +-------------------------------+
 *  | Page N: CollectionPositionMapBucket  |
 *  |   entry[0] .. entry[K]        |  <-- last page may be partially filled
 *  +-------------------------------+
 * }</pre>
 *
 * <h2>Position-to-Page Mapping</h2>
 *
 * <p>A collection position {@code P} maps to:
 * <ul>
 *   <li><b>Bucket page index</b>: {@code P / MAX_ENTRIES + 1} (the {@code +1} accounts for
 *       the entry-point page at index 0)</li>
 *   <li><b>Entry index within the bucket</b>: {@code P % MAX_ENTRIES}</li>
 * </ul>
 *
 * <pre>{@code
 *  Collection position space:
 *
 *  Position:  0  1  2 ... MAX-1  MAX  MAX+1 ... 2*MAX-1  2*MAX ...
 *             |------Page 1------|  |-------Page 2------|  |--Page 3--...
 *
 *  Example (MAX_ENTRIES = 378):
 *    position 0   -> page 1, index 0
 *    position 377 -> page 1, index 377
 *    position 378 -> page 2, index 0
 *    position 756 -> page 3, index 0
 * }</pre>
 *
 * <h2>Entry Lifecycle</h2>
 *
 * <p>Each entry in a bucket has one of four statuses:
 * <ul>
 *   <li>{@link CollectionPositionMapBucket#NOT_EXISTENT} - never allocated (implicit, beyond
 *       current size)</li>
 *   <li>{@link CollectionPositionMapBucket#ALLOCATED} - slot reserved but no data written yet</li>
 *   <li>{@link CollectionPositionMapBucket#FILLED} - contains a valid position entry pointing to
 *       a data page</li>
 *   <li>{@link CollectionPositionMapBucket#REMOVED} - tombstone (deletion version stored for MVCC
 *       visibility checks)</li>
 * </ul>
 *
 * @see PaginatedCollectionV2
 * @see CollectionPositionMapBucket
 * @see MapEntryPoint
 */
public final class CollectionPositionMapV2 extends CollectionPositionMap {

  /** Internal file ID assigned by the disk cache when the .cpm file is opened/created. */
  private long fileId;

  /** Package-private constructor; instances are created by {@link PaginatedCollectionV2}. */
  CollectionPositionMapV2(
      final AbstractStorage storage,
      final String name,
      final String lockName,
      final String extension) {
    super(storage, name, extension, lockName);
  }

  /** Opens an existing position-map file by name through the disk cache. */
  public void open(final AtomicOperation atomicOperation) throws IOException {
    fileId = openFile(atomicOperation, getFullName());
  }

  /**
   * Creates a new position-map file and initializes its entry point (page 0) with a file size
   * of zero. If the physical file already has pages (e.g., leftover from a partial previous
   * creation), the existing page 0 is reused and reset.
   */
  public void create(final AtomicOperation atomicOperation) throws IOException {
    fileId = addFile(atomicOperation, getFullName());

    if (getFilledUpTo(atomicOperation, fileId) == 0) {
      // Fresh file -- append the entry-point page.
      try (final var cacheEntry = addPage(atomicOperation, fileId)) {
        final var mapEntryPoint = new MapEntryPoint(cacheEntry);
        mapEntryPoint.setFileSize(0);
      }
    } else {
      // File already has pages -- reset the entry point.
      try (final var cacheEntry = loadPageForWrite(atomicOperation, fileId, 0, false)) {
        final var mapEntryPoint = new MapEntryPoint(cacheEntry);
        mapEntryPoint.setFileSize(0);
      }
    }
  }

  public void flush() {
    writeCache.flush(fileId);
  }

  public void close(final boolean flush) {
    readCache.closeFile(fileId, flush, writeCache);
  }

  public void truncate(final AtomicOperation atomicOperation) throws IOException {
    try (final var cacheEntry = loadPageForWrite(atomicOperation, fileId, 0, true)) {
      final var mapEntryPoint = new MapEntryPoint(cacheEntry);
      mapEntryPoint.setFileSize(0);
    }
  }

  public void delete(final AtomicOperation atomicOperation) throws IOException {
    deleteFile(atomicOperation, fileId);
  }

  void rename(final String newName) throws IOException {
    writeCache.renameFile(fileId, newName + getExtension());
    setName(newName);
  }

  /**
   * Reads the logical page count (number of bucket pages in use) from the entry-point page.
   * This value does <em>not</em> include the entry-point page itself.
   */
  private long getLastPage(final AtomicOperation atomicOperation) throws IOException {
    long lastPage;
    try (final var entryPointEntry = loadPageForRead(atomicOperation, fileId, 0)) {
      final var mapEntryPoint = new MapEntryPoint(entryPointEntry);
      lastPage = mapEntryPoint.getFileSize();
    }
    return lastPage;
  }

  /**
   * Allocates a new collection position and returns its global index. The allocation proceeds
   * by finding the last bucket page that has room (or creating a new one if the current last
   * bucket is full), then calling {@link CollectionPositionMapBucket#allocate()} to reserve a
   * slot with status {@link CollectionPositionMapBucket#ALLOCATED}.
   *
   * <p>The returned global collection position is computed as:
   * {@code localIndex + (bucketPageIndex - 1) * MAX_ENTRIES}.
   *
   * @param atomicOperation the current atomic operation context
   * @return the newly allocated global collection position
   */
  public long allocate(final AtomicOperation atomicOperation) throws IOException {
    CacheEntry cacheEntry;
    var clear = false;

    try (var entryPointEntry = loadPageForWrite(atomicOperation, fileId, 0, true)) {
      final var mapEntryPoint = new MapEntryPoint(entryPointEntry);
      final var lastPage = mapEntryPoint.getFileSize();

      var filledUpTo = getFilledUpTo(atomicOperation, fileId);

      assert lastPage <= filledUpTo - 1;

      if (lastPage == 0) {
        // No bucket pages exist yet -- create the first one.
        if (lastPage == filledUpTo - 1) {
          // Physical file has no pages beyond the entry point -- append a new one.
          cacheEntry = addPage(atomicOperation, fileId);
          filledUpTo++;
        } else {
          // Reuse an existing-but-unclaimed page (recovery scenario).
          cacheEntry = loadPageForWrite(atomicOperation, fileId, lastPage + 1, false);
        }
        mapEntryPoint.setFileSize(lastPage + 1);

        clear = true;
      } else {
        // Load the current last bucket page to check if it has room.
        cacheEntry = loadPageForWrite(atomicOperation, fileId, lastPage, true);
      }

      try {
        var bucket = new CollectionPositionMapBucket(cacheEntry);
        if (clear) {
          bucket.init();
        }

        if (bucket.isFull()) {
          // Current last bucket page is full -- allocate a new bucket page.
          cacheEntry.close();

          assert lastPage <= filledUpTo - 1;

          if (lastPage == filledUpTo - 1) {
            cacheEntry = addPage(atomicOperation, fileId);
          } else {
            cacheEntry = loadPageForWrite(atomicOperation, fileId, lastPage + 1, false);
          }

          mapEntryPoint.setFileSize(lastPage + 1);

          bucket = new CollectionPositionMapBucket(cacheEntry);
          bucket.init();
        }
        // Reserve a slot in the bucket and convert to a global collection position.
        final long index = bucket.allocate();
        return index
            + (long) (cacheEntry.getPageIndex() - 1) * CollectionPositionMapBucket.MAX_ENTRIES;
      } finally {
        cacheEntry.close();
      }
    }
  }

  /**
   * Updates the position entry for the given collection position. Transitions the entry from
   * ALLOCATED to FILLED if it was previously allocated, or replaces the current entry if already
   * FILLED.
   *
   * @param collectionPosition the global collection position to update
   * @param entry              the new position entry (page index, record offset, version)
   * @param atomicOperation    the current atomic operation context
   */
  public void update(
      final long collectionPosition,
      final CollectionPositionMapBucket.PositionEntry entry,
      final AtomicOperation atomicOperation)
      throws IOException {

    final var index = (int) (collectionPosition % CollectionPositionMapBucket.MAX_ENTRIES);

    try (final var cacheEntry =
        loadBucketPageForWrite(collectionPosition, atomicOperation)) {
      final var bucket = new CollectionPositionMapBucket(cacheEntry);
      bucket.set(index, entry);
    }
  }

  /**
   * Updates only the record version of the entry at the given collection position, leaving
   * the page index and record offset unchanged. Used when the record content has not moved
   * but the version stamp needs to be updated (e.g., during
   * {@link PaginatedCollectionV2#updateRecordVersion}).
   */
  public void updateVersion(
      final long collectionPosition, final long recordVersion,
      final AtomicOperation atomicOperation)
      throws IOException {

    final var index = (int) (collectionPosition % CollectionPositionMapBucket.MAX_ENTRIES);

    try (final var cacheEntry =
        loadBucketPageForWrite(collectionPosition, atomicOperation)) {
      final var bucket = new CollectionPositionMapBucket(cacheEntry);
      bucket.updateVersion(index, recordVersion);
    }
  }

  /**
   * Loads the bucket page that contains the entry for the given collection position, for
   * writing. Throws if the position is beyond the current range of allocated bucket pages.
   */
  private CacheEntry loadBucketPageForWrite(
      final long collectionPosition,
      final AtomicOperation atomicOperation) throws IOException {

    // +1 because page 0 is the entry-point page, bucket pages start at index 1.
    final var pageIndex = collectionPosition / CollectionPositionMapBucket.MAX_ENTRIES + 1;

    final var lastPage = getLastPage(atomicOperation);
    if (pageIndex > lastPage) {
      throw new CollectionPositionMapException(storage.getName(),
          "Passed in collection position "
              + collectionPosition
              + " is outside of range of collection-position map", this);
    }

    return loadPageForWrite(atomicOperation, fileId, pageIndex, true);
  }

  /**
   * Retrieves the position entry for the given collection position, or {@code null} if the
   * position does not exist or is not in FILLED status.
   *
   * @param collectionPosition the global collection position to look up
   * @param atomicOperation    the current atomic operation context
   * @return the position entry, or {@code null} if not found / not filled
   */
  @Nullable
  public CollectionPositionMapBucket.PositionEntry get(
      final long collectionPosition, final AtomicOperation atomicOperation) throws IOException {
    final var pageIndex = collectionPosition / CollectionPositionMapBucket.MAX_ENTRIES + 1;
    final var index = (int) (collectionPosition % CollectionPositionMapBucket.MAX_ENTRIES);

    final var lastPage = getLastPage(atomicOperation);

    if (pageIndex > lastPage) {
      return null;
    }

    try (final var cacheEntry = loadPageForRead(atomicOperation, fileId, pageIndex)) {
      final var bucket = new CollectionPositionMapBucket(cacheEntry);
      return bucket.get(index);
    }
  }

  /**
   * Retrieves the position entry together with its status byte for the given collection position.
   * Unlike {@link #get}, this method returns entries in any status (FILLED, REMOVED, ALLOCATED),
   * which is needed by the MVCC read path to detect tombstones and invisible versions.
   *
   * @param collectionPosition the global collection position to look up
   * @param atomicOperation    the current atomic operation context
   * @return an {@link CollectionPositionMapBucket.EntryWithStatus} containing the status and
   *         (for FILLED/REMOVED) the position entry; never {@code null}
   */
  @Nonnull
  public CollectionPositionMapBucket.EntryWithStatus getWithStatus(
      final long collectionPosition, final AtomicOperation atomicOperation) throws IOException {
    final var pageIndex = collectionPosition / CollectionPositionMapBucket.MAX_ENTRIES + 1;
    final var index = (int) (collectionPosition % CollectionPositionMapBucket.MAX_ENTRIES);

    final var lastPage = getLastPage(atomicOperation);

    if (pageIndex > lastPage) {
      return new CollectionPositionMapBucket.EntryWithStatus(
          CollectionPositionMapBucket.NOT_EXISTENT, null);
    }

    try (final var cacheEntry = loadPageForRead(atomicOperation, fileId, pageIndex)) {
      final var bucket = new CollectionPositionMapBucket(cacheEntry);
      return bucket.getEntryWithStatus(index);
    }
  }

  /**
   * Marks the entry at the given collection position as REMOVED (tombstone) and stores the
   * {@code deletionVersion} so MVCC readers can determine deletion visibility.
   *
   * @param collectionPosition the global collection position to mark as removed
   * @param deletionVersion    the commitTs of the deleting transaction
   * @param atomicOperation    the current atomic operation context
   */
  public void remove(final long collectionPosition, final long deletionVersion,
      final AtomicOperation atomicOperation)
      throws IOException {
    final var pageIndex = collectionPosition / CollectionPositionMapBucket.MAX_ENTRIES + 1;
    final var index = (int) (collectionPosition % CollectionPositionMapBucket.MAX_ENTRIES);

    try (final var cacheEntry =
        loadPageForWrite(atomicOperation, fileId, pageIndex, true)) {
      final var bucket = new CollectionPositionMapBucket(cacheEntry);

      bucket.remove(index, deletionVersion);
    }
  }

  /**
   * Returns up to {@code limit} FILLED positions that are {@code >= collectionPosition},
   * scanning forward through bucket pages.
   */
  long[] ceilingPositions(
      long collectionPosition,
      AtomicOperation atomicOperation,
      int limit
  ) throws IOException {
    return ceilingPositionsImpl(collectionPosition, limit, atomicOperation,
        LONG_ARRAY_RESULT_BUILDER);
  }

  /**
   * Returns up to {@code limit} FILLED positions that are strictly {@code > collectionPosition},
   * scanning forward through bucket pages.
   */
  long[] higherPositions(
      long collectionPosition,
      AtomicOperation atomicOperation,
      int limit
  ) throws IOException {
    if (collectionPosition == Long.MAX_VALUE) {
      return CommonConst.EMPTY_LONG_ARRAY;
    }

    return ceilingPositionsImpl(
        collectionPosition + 1, limit, atomicOperation,
        LONG_ARRAY_RESULT_BUILDER);
  }

  /**
   * Returns all FILLED position entries strictly greater than {@code collectionPosition},
   * including their physical page locations and record versions. Used by
   * {@link PaginatedCollectionV2#nextPage} for page-based browsing.
   */
  CollectionPositionEntry[] higherPositionsEntries(
      long collectionPosition,
      AtomicOperation atomicOperation
  ) throws IOException {
    if (collectionPosition == Long.MAX_VALUE) {
      return new CollectionPositionEntry[]{};
    }

    return ceilingPositionsImpl(
        collectionPosition + 1, -1, atomicOperation,
        COL_POS_ENTRY_ARRAY_RESULT_BUILDER);
  }

  /// General logic for finding ceiling positions for the given position.
  private <T> T ceilingPositionsImpl(
      long collectionPosition,
      int limit,
      final AtomicOperation atomicOperation,
      final PositionResultBuilder<T> resultBuilder
  ) throws IOException {

    if (collectionPosition < 0) {
      collectionPosition = 0;
    }
    if (limit <= 0) {
      limit = Integer.MAX_VALUE;
    }

    var pageIndex = collectionPosition / CollectionPositionMapBucket.MAX_ENTRIES + 1;
    var index = (int) (collectionPosition % CollectionPositionMapBucket.MAX_ENTRIES);

    final var lastPage = getLastPage(atomicOperation);

    if (pageIndex > lastPage) {
      return resultBuilder.emptyResult();
    }

    T result = null;
    do {
      try (final var cacheEntry = loadPageForRead(atomicOperation, fileId, pageIndex)) {

        final var bucket = new CollectionPositionMapBucket(cacheEntry);
        final var resultSize = bucket.getSize() - index;

        if (resultSize <= 0) {
          pageIndex++;
          index = 0;
        } else {
          var entriesCount = 0;
          final var startIndex =
              (long) (cacheEntry.getPageIndex() - 1) * CollectionPositionMapBucket.MAX_ENTRIES
                  + index;

          result = resultBuilder.newResultOfSize(resultSize);
          var currentLimit = Math.min(limit, resultSize);
          for (var i = 0; i < resultSize && entriesCount < currentLimit; i++) {
            if (bucket.exists(i + index)) {
              resultBuilder.setElement(result, entriesCount, startIndex + i, bucket, i + index);
              entriesCount++;
            }
          }

          if (entriesCount == 0) {
            result = null;
            pageIndex++;
            index = 0;
          } else {
            result = resultBuilder.copyResult(result, entriesCount);
          }
        }
      }
    } while (result == null && pageIndex <= lastPage);

    if (result == null) {
      result = resultBuilder.emptyResult();
    }

    return result;
  }

  /**
   * Returns up to {@code limit} FILLED positions strictly less than {@code collectionPosition},
   * scanning backward through bucket pages. Results are in ascending order.
   */
  long[] lowerPositions(
      long collectionPosition,
      AtomicOperation atomicOperation,
      int limit
  ) throws IOException {
    if (collectionPosition == 0) {
      return CommonConst.EMPTY_LONG_ARRAY;
    }

    return floorPositionsImpl(
        collectionPosition - 1, limit, atomicOperation,
        LONG_ARRAY_RESULT_BUILDER, false);
  }

  /**
   * Returns all FILLED position entries strictly less than {@code collectionPosition},
   * in <em>descending</em> order (highest position first). Used for backward page browsing.
   */
  CollectionPositionEntry[] lowerPositionsEntriesReversed(
      long collectionPosition,
      AtomicOperation atomicOperation
  ) throws IOException {
    if (collectionPosition == 0) {
      return new CollectionPositionEntry[]{};
    }

    return floorPositionsImpl(
        collectionPosition - 1, -1, atomicOperation,
        COL_POS_ENTRY_ARRAY_RESULT_BUILDER, true);
  }

  /**
   * Returns up to {@code limit} FILLED positions that are {@code <= collectionPosition},
   * scanning backward through bucket pages. Results are in ascending order.
   */
  long[] floorPositions(
      long collectionPosition,
      AtomicOperation atomicOperation,
      int limit
  ) throws IOException {
    return floorPositionsImpl(
        collectionPosition, limit, atomicOperation,
        LONG_ARRAY_RESULT_BUILDER, false
    );
  }

  /// General logic for finding floor positions for the given position.
  private <T> T floorPositionsImpl(
      final long collectionPosition,
      int limit,
      final AtomicOperation atomicOperation,
      final PositionResultBuilder<T> resultBuilder,
      final boolean reverseOrder
  ) throws IOException {
    if (collectionPosition < 0) {
      return resultBuilder.emptyResult();
    }
    if (limit <= 0) {
      limit = Integer.MAX_VALUE;
    }

    var pageIndex = collectionPosition / CollectionPositionMapBucket.MAX_ENTRIES + 1;
    var index = (int) (collectionPosition % CollectionPositionMapBucket.MAX_ENTRIES);

    final var lastPage = getLastPage(atomicOperation);

    if (pageIndex > lastPage) {
      pageIndex = lastPage;
      index = Integer.MIN_VALUE;
    }

    if (pageIndex < 0) {
      return resultBuilder.emptyResult();
    }

    T result;
    do {
      try (final var cacheEntry = loadPageForRead(atomicOperation, fileId, pageIndex)) {
        final var bucket = new CollectionPositionMapBucket(cacheEntry);
        var bucketSize = bucket.getSize();
        if (index == Integer.MIN_VALUE) {
          index = bucketSize - 1;
        }

        var resultSize = index + 1;
        var entriesCount = 0;

        final var startPosition =
            (long) (cacheEntry.getPageIndex() - 1) * CollectionPositionMapBucket.MAX_ENTRIES;
        var currentLimit = Math.min(resultSize, limit);
        result = resultBuilder.newResultOfSize(resultSize);

        for (var i = resultSize - 1; i >= 0 && entriesCount < currentLimit; i--) {
          if (bucket.exists(i)) {
            resultBuilder.setElement(result, entriesCount, startPosition + i, bucket, i);
            entriesCount++;
          }
        }

        if (entriesCount == 0) {
          result = null;
          pageIndex--;
          index = Integer.MIN_VALUE;
        } else {
          result = resultBuilder.copyResult(result, entriesCount);
        }
      }
    } while (result == null && pageIndex >= 0);

    if (result == null) {
      result = resultBuilder.emptyResult();
    }

    if (!reverseOrder) {
      resultBuilder.reverseResult(result);
    }
    return result;
  }

  /**
   * Scans forward through all bucket pages to find the first FILLED position, or returns
   * {@link RID#COLLECTION_POS_INVALID} if the map is empty.
   */
  long getFirstPosition(final AtomicOperation atomicOperation) throws IOException {
    final var lastPage = getLastPage(atomicOperation);

    for (long pageIndex = 1; pageIndex <= lastPage; pageIndex++) {
      try (final var cacheEntry = loadPageForRead(atomicOperation, fileId, pageIndex)) {
        final var bucket = new CollectionPositionMapBucket(cacheEntry);
        final var bucketSize = bucket.getSize();

        for (var index = 0; index < bucketSize; index++) {
          if (bucket.exists(index)) {
            return pageIndex * CollectionPositionMapBucket.MAX_ENTRIES
                + index
                - CollectionPositionMapBucket.MAX_ENTRIES;
          }
        }
      }
    }

    return RID.COLLECTION_POS_INVALID;
  }

  /**
   * Returns the raw status byte ({@link CollectionPositionMapBucket#NOT_EXISTENT},
   * {@link CollectionPositionMapBucket#ALLOCATED}, {@link CollectionPositionMapBucket#FILLED},
   * or {@link CollectionPositionMapBucket#REMOVED}) for the given collection position.
   */
  public byte getStatus(final long collectionPosition, final AtomicOperation atomicOperation)
      throws IOException {
    final var pageIndex =
        (collectionPosition + CollectionPositionMapBucket.MAX_ENTRIES)
            / CollectionPositionMapBucket.MAX_ENTRIES;
    final var index = (int) (collectionPosition % CollectionPositionMapBucket.MAX_ENTRIES);

    final var lastPage = getLastPage(atomicOperation);
    if (pageIndex > lastPage) {
      return CollectionPositionMapBucket.NOT_EXISTENT;
    }

    try (final var cacheEntry = loadPageForRead(atomicOperation, fileId, pageIndex)) {
      final var bucket = new CollectionPositionMapBucket(cacheEntry);

      return bucket.getStatus(index);
    }
  }

  /**
   * Scans backward through all bucket pages to find the last FILLED position, or returns
   * {@link RID#COLLECTION_POS_INVALID} if the map is empty.
   */
  public long getLastPosition(final AtomicOperation atomicOperation) throws IOException {
    final var lastPage = getLastPage(atomicOperation);

    for (var pageIndex = lastPage; pageIndex >= 1; pageIndex--) {

      try (final var cacheEntry = loadPageForRead(atomicOperation, fileId, pageIndex)) {
        final var bucket = new CollectionPositionMapBucket(cacheEntry);
        final var bucketSize = bucket.getSize();

        for (var index = bucketSize - 1; index >= 0; index--) {
          if (bucket.exists(index)) {
            return pageIndex * CollectionPositionMapBucket.MAX_ENTRIES
                + index
                - CollectionPositionMapBucket.MAX_ENTRIES;
          }
        }
      }
    }

    return RID.COLLECTION_POS_INVALID;
  }

  public long getFileId() {
    return fileId;
  }

  /* void replaceFileId(final long newFileId) {
    this.fileId = newFileId;
  }*/

  /// Visitor callback for iterating over all position map entries, including
  /// both {@link CollectionPositionMapBucket#FILLED} and
  /// {@link CollectionPositionMapBucket#REMOVED} entries.
  interface EntryVisitor {

    void visit(long position, byte status, long recordVersion);
  }

  /// Iterates over all entries in the position map, including removed (tombstone) entries.
  /// For each entry with status {@link CollectionPositionMapBucket#FILLED} or
  /// {@link CollectionPositionMapBucket#REMOVED}, the visitor is called with the entry's
  /// collection position, status, and record version.
  /// {@link CollectionPositionMapBucket#ALLOCATED} entries are skipped.
  void forEachEntry(AtomicOperation atomicOperation, EntryVisitor visitor)
      throws IOException {
    final var lastPage = getLastPage(atomicOperation);
    for (long pageIndex = 1; pageIndex <= lastPage; pageIndex++) {
      try (final var cacheEntry =
          loadPageForRead(atomicOperation, fileId, pageIndex)) {
        final var bucket = new CollectionPositionMapBucket(cacheEntry);
        final var bucketSize = bucket.getSize();
        final var startPosition =
            (pageIndex - 1) * CollectionPositionMapBucket.MAX_ENTRIES;

        for (var i = 0; i < bucketSize; i++) {
          final var status = bucket.getStatus(i);
          if (status == CollectionPositionMapBucket.FILLED
              || status == CollectionPositionMapBucket.REMOVED) {
            visitor.visit(startPosition + i, status, bucket.getRecordVersionAt(i));
          }
        }
      }
    }
  }

  /**
   * A snapshot of a position map entry enriched with the global collection position. Used as a
   * transfer object when iterating position entries for page-based browsing (see
   * {@link #higherPositionsEntries} and {@link #lowerPositionsEntriesReversed}).
   *
   * <p>Unlike {@link CollectionPositionMapBucket.PositionEntry} (which stores the page index and
   * record offset), this class additionally carries the global {@link #position} so that
   * callers don't need to recompute it from the bucket page index and local entry index.
   */
  public static final class CollectionPositionEntry {

    /** Global collection position (the logical record ID within this collection). */
    private final long position;
    /** Data-file page index where the record's entry-point chunk is stored. */
    private final long page;
    /** Slot offset within the data page. */
    private final int offset;
    /** Record version (commitTs of the writing transaction). */
    private final long recordVersion;

    CollectionPositionEntry(
        final long position, final long page, final int offset,
        final long recordVersion) {
      this.position = position;
      this.page = page;
      this.offset = offset;
      this.recordVersion = recordVersion;
    }

    public long getPosition() {
      return position;
    }

    public long getPage() {
      return page;
    }

    public int getOffset() {
      return offset;
    }

    public long getRecordVersion() {
      return recordVersion;
    }
  }

  /// Response builder for `lower*`, `higher*`, `floor*` and `ceiling*` positions methods. This
  /// interface abstracts away the actual result type, making it possible to use the same lookup
  /// logic for both primitive results (`long[]`) and object results (`CollectionPositionEntry[]`)
  /// without unnecessary allocations or boxing.
  ///
  /// @param <T> Result type. Although there are no restrictions on what types could be used here,
  ///            it probably will make sense to use some container types, as arrays or collections.
  interface PositionResultBuilder<T> {

    /// Return empty result.
    T emptyResult();

    /// Construct a new result of size `size`.
    T newResultOfSize(int size);

    /// Reverse the order of the given result.
    void reverseResult(T result);

    /// Set the value of the `index`-th element in `result`.
    ///
    /// @param result      Result container
    /// @param index       Index of the element to set in the result.
    /// @param position    Collection position that will be stored on the `index`-th position of the
    ///                    result.
    /// @param bucket      Collection position bucket that holds the entry for `position`.
    /// @param bucketIndex Index in the `bucket` of the entry for `position`.
    void setElement(
        T result, int index,
        long position, CollectionPositionMapBucket bucket, int bucketIndex
    );

    /// Create a copy of the given result of the given size. This method has the same semantics as
    /// [Arrays#copyOf]
    T copyResult(T result, int size);
  }

  private static final PositionResultBuilder<long[]> LONG_ARRAY_RESULT_BUILDER =
      new PositionResultBuilder<>() {
        @Override
        public long[] emptyResult() {
          return CommonConst.EMPTY_LONG_ARRAY;
        }

        @Override
        public long[] newResultOfSize(int size) {
          return new long[size];
        }

        @Override
        public void reverseResult(long[] result) {
          ArrayUtils.reverse(result);
        }

        @Override
        public void setElement(
            long[] result, int index, long position,
            CollectionPositionMapBucket bucket, int bucketIndex) {
          result[index] = position;
        }

        @Override
        public long[] copyResult(long[] result, int newLength) {
          return Arrays.copyOf(result, newLength);
        }
      };

  private static final PositionResultBuilder<CollectionPositionEntry[]> COL_POS_ENTRY_ARRAY_RESULT_BUILDER =
      new PositionResultBuilder<>() {
        @Override
        public CollectionPositionEntry[] emptyResult() {
          return new CollectionPositionEntry[0];
        }

        @Override
        public CollectionPositionEntry[] newResultOfSize(int size) {
          return new CollectionPositionEntry[size];
        }

        @Override
        public void reverseResult(CollectionPositionEntry[] result) {
          ArrayUtils.reverse(result);
        }

        @Override
        public void setElement(
            CollectionPositionEntry[] result, int index, long position,
            CollectionPositionMapBucket bucket, int bucketIndex) {

          final var entry = bucket.get(bucketIndex);
          assert entry != null;

          result[index] = new CollectionPositionEntry(
              position,
              entry.getPageIndex(),
              entry.getRecordPosition(),
              entry.getRecordVersion());
        }

        @Override
        public CollectionPositionEntry[] copyResult(CollectionPositionEntry[] result,
            int newLength) {
          return Arrays.copyOf(result, newLength);
        }
      };
}
