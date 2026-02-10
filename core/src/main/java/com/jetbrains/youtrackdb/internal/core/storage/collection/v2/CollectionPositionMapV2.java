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
import javax.annotation.Nullable;
import org.apache.commons.lang3.ArrayUtils;

public final class CollectionPositionMapV2 extends CollectionPositionMap {

  private long fileId;

  CollectionPositionMapV2(
      final AbstractStorage storage,
      final String name,
      final String lockName,
      final String extension) {
    super(storage, name, extension, lockName);
  }

  public void open(final AtomicOperation atomicOperation) throws IOException {
    fileId = openFile(atomicOperation, getFullName());
  }

  public void create(final AtomicOperation atomicOperation) throws IOException {
    fileId = addFile(atomicOperation, getFullName());

    if (getFilledUpTo(atomicOperation, fileId) == 0) {
      try (final var cacheEntry = addPage(atomicOperation, fileId)) {
        final var mapEntryPoint = new MapEntryPoint(cacheEntry);
        mapEntryPoint.setFileSize(0);
      }
    } else {
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

  public long add(
      final long pageIndex, final int recordPosition, final long recordVersion,
      final AtomicOperation atomicOperation)
      throws IOException {
    CacheEntry cacheEntry;
    var clear = false;

    try (final var entryPointEntry = loadPageForWrite(atomicOperation, fileId, 0, true)) {
      final var mapEntryPoint = new MapEntryPoint(entryPointEntry);
      final var lastPage = mapEntryPoint.getFileSize();
      var filledUpTo = getFilledUpTo(atomicOperation, fileId);

      assert lastPage <= filledUpTo - 1;

      if (lastPage == 0) {
        if (lastPage == filledUpTo - 1) {
          cacheEntry = addPage(atomicOperation, fileId);
          filledUpTo++;
        } else {
          cacheEntry = loadPageForWrite(atomicOperation, fileId, lastPage + 1, false);
        }
        mapEntryPoint.setFileSize(lastPage + 1);
        clear = true;
      } else {
        cacheEntry = loadPageForWrite(atomicOperation, fileId, lastPage, true);
      }

      try {
        var bucket = new CollectionPositionMapBucket(cacheEntry);
        if (clear) {
          bucket.init();
        }
        if (bucket.isFull()) {
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

        final long index = bucket.add(pageIndex, recordPosition, recordVersion);
        return index
            + (long) (cacheEntry.getPageIndex() - 1) * CollectionPositionMapBucket.MAX_ENTRIES;
      } finally {
        cacheEntry.close();
      }
    }
  }

  private long getLastPage(final AtomicOperation atomicOperation) throws IOException {
    long lastPage;
    try (final var entryPointEntry = loadPageForRead(atomicOperation, fileId, 0)) {
      final var mapEntryPoint = new MapEntryPoint(entryPointEntry);
      lastPage = mapEntryPoint.getFileSize();
    }
    return lastPage;
  }

  public long allocate(final AtomicOperation atomicOperation) throws IOException {
    CacheEntry cacheEntry;
    var clear = false;

    try (var entryPointEntry = loadPageForWrite(atomicOperation, fileId, 0, true)) {
      final var mapEntryPoint = new MapEntryPoint(entryPointEntry);
      final var lastPage = mapEntryPoint.getFileSize();

      var filledUpTo = getFilledUpTo(atomicOperation, fileId);

      assert lastPage <= filledUpTo - 1;

      if (lastPage == 0) {
        if (lastPage == filledUpTo - 1) {
          cacheEntry = addPage(atomicOperation, fileId);
          filledUpTo++;
        } else {
          cacheEntry = loadPageForWrite(atomicOperation, fileId, lastPage + 1, false);
        }
        mapEntryPoint.setFileSize(lastPage + 1);

        clear = true;
      } else {
        cacheEntry = loadPageForWrite(atomicOperation, fileId, lastPage, true);
      }

      try {
        var bucket = new CollectionPositionMapBucket(cacheEntry);
        if (clear) {
          bucket.init();
        }

        if (bucket.isFull()) {
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
        final long index = bucket.allocate();
        return index
            + (long) (cacheEntry.getPageIndex() - 1) * CollectionPositionMapBucket.MAX_ENTRIES;
      } finally {
        cacheEntry.close();
      }
    }
  }

  public void update(
      final long collectionPosition,
      final CollectionPositionMapBucket.PositionEntry entry,
      final AtomicOperation atomicOperation)
      throws IOException {

    final var pageIndex = collectionPosition / CollectionPositionMapBucket.MAX_ENTRIES + 1;
    final var index = (int) (collectionPosition % CollectionPositionMapBucket.MAX_ENTRIES);

    final var lastPage = getLastPage(atomicOperation);
    if (pageIndex > lastPage) {
      throw new CollectionPositionMapException(storage.getName(),
          "Passed in collection position "
              + collectionPosition
              + " is outside of range of collection-position map", this);
    }

    try (final var cacheEntry =
        loadPageForWrite(atomicOperation, fileId, pageIndex, true)) {
      final var bucket = new CollectionPositionMapBucket(cacheEntry);
      bucket.set(index, entry);
    }
  }

  public void updateVersion(
      final long collectionPosition, final long recordVersion,
      final AtomicOperation atomicOperation)
      throws IOException {

    final var pageIndex = collectionPosition / CollectionPositionMapBucket.MAX_ENTRIES + 1;
    final var index = (int) (collectionPosition % CollectionPositionMapBucket.MAX_ENTRIES);

    final var lastPage = getLastPage(atomicOperation);
    if (pageIndex > lastPage) {
      throw new CollectionPositionMapException(storage.getName(),
          "Passed in collection position "
              + collectionPosition
              + " is outside of range of collection-position map", this);
    }

    try (final var cacheEntry =
        loadPageForWrite(atomicOperation, fileId, pageIndex, true)) {
      final var bucket = new CollectionPositionMapBucket(cacheEntry);
      bucket.updateVersion(index, recordVersion);
    }
  }

  public long getVersion(
      final long collectionPosition, final AtomicOperation atomicOperation)
      throws IOException {

    final var pageIndex = collectionPosition / CollectionPositionMapBucket.MAX_ENTRIES + 1;
    final var index = (int) (collectionPosition % CollectionPositionMapBucket.MAX_ENTRIES);

    final var lastPage = getLastPage(atomicOperation);
    if (pageIndex > lastPage) {
      return -1;
    }

    try (final var cacheEntry = loadPageForRead(atomicOperation, fileId, pageIndex)) {
      final var bucket = new CollectionPositionMapBucket(cacheEntry);
      return bucket.getVersion(index);
    }
  }

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

  public void remove(final long collectionPosition, final AtomicOperation atomicOperation)
      throws IOException {
    final var pageIndex = collectionPosition / CollectionPositionMapBucket.MAX_ENTRIES + 1;
    final var index = (int) (collectionPosition % CollectionPositionMapBucket.MAX_ENTRIES);

    try (final var cacheEntry =
        loadPageForWrite(atomicOperation, fileId, pageIndex, true)) {
      final var bucket = new CollectionPositionMapBucket(cacheEntry);

      bucket.remove(index);
    }
  }

  long[] ceilingPositions(
      long collectionPosition,
      AtomicOperation atomicOperation,
      int limit
  ) throws IOException {
    return ceilingPositionsImpl(collectionPosition, limit, atomicOperation,
        LONG_ARRAY_RESULT_BUILDER);
  }

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

  /**
   * Returns the next position available.
   */
  long getNextPosition(final AtomicOperation atomicOperation) throws IOException {
    final var pageIndex = getLastPage(atomicOperation);

    try (final var cacheEntry = loadPageForRead(atomicOperation, fileId, pageIndex)) {
      final var bucket = new CollectionPositionMapBucket(cacheEntry);
      final var bucketSize = bucket.getSize();
      return pageIndex * CollectionPositionMapBucket.MAX_ENTRIES + bucketSize;
    }
  }

  public long getFileId() {
    return fileId;
  }

  /* void replaceFileId(final long newFileId) {
    this.fileId = newFileId;
  }*/

  public static final class CollectionPositionEntry {

    private final long position;
    private final long page;
    private final int offset;
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
