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

package com.jetbrains.youtrack.db.internal.core.storage.collection.v2;

import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.common.util.CommonConst;
import com.jetbrains.youtrack.db.internal.core.exception.CollectionPositionMapException;
import com.jetbrains.youtrack.db.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrack.db.internal.core.storage.collection.CollectionPositionMap;
import com.jetbrains.youtrack.db.internal.core.storage.collection.CollectionPositionMapBucket;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import java.io.IOException;
import java.util.Arrays;
import javax.annotation.Nullable;

/**
 * @since 10/7/13
 */
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
      final long pageIndex, final int recordPosition, final AtomicOperation atomicOperation)
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

        final long index = bucket.add(pageIndex, recordPosition);
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

  long[] higherPositions(final long collectionPosition, final AtomicOperation atomicOperation,
      int limit)
      throws IOException {
    if (collectionPosition == Long.MAX_VALUE) {
      return CommonConst.EMPTY_LONG_ARRAY;
    }

    return ceilingPositions(collectionPosition + 1, atomicOperation, limit);
  }

  CollectionPositionEntry[] higherPositionsEntries(
      final long collectionPosition, final AtomicOperation atomicOperation) throws IOException {
    if (collectionPosition == Long.MAX_VALUE) {
      return new CollectionPositionEntry[]{};
    }

    final long realPosition;
    if (collectionPosition < 0) {
      realPosition = 0;
    } else {
      realPosition = collectionPosition + 1;
    }

    var pageIndex = realPosition / CollectionPositionMapBucket.MAX_ENTRIES + 1;
    var index = (int) (realPosition % CollectionPositionMapBucket.MAX_ENTRIES);

    final var lastPage = getLastPage(atomicOperation);

    if (pageIndex > lastPage) {
      return new CollectionPositionEntry[]{};
    }

    CollectionPositionEntry[] result = null;
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
          result = new CollectionPositionEntry[resultSize];
          for (var i = 0; i < resultSize; i++) {
            if (bucket.exists(i + index)) {
              final var val = bucket.get(i + index);
              assert val != null;
              result[entriesCount] =
                  new CollectionPositionEntry(
                      startIndex + i, val.getPageIndex(), val.getRecordPosition());
              entriesCount++;
            }
          }

          if (entriesCount == 0) {
            result = null;
            pageIndex++;
            index = 0;
          } else {
            result = Arrays.copyOf(result, entriesCount);
          }
        }
      }
    } while (result == null && pageIndex <= lastPage);

    if (result == null) {
      result = new CollectionPositionEntry[]{};
    }

    return result;
  }

  long[] ceilingPositions(long collectionPosition, final AtomicOperation atomicOperation, int limit)
      throws IOException {
    if (collectionPosition < 0) {
      collectionPosition = 0;
    }

    var pageIndex = collectionPosition / CollectionPositionMapBucket.MAX_ENTRIES + 1;
    var index = (int) (collectionPosition % CollectionPositionMapBucket.MAX_ENTRIES);

    final var lastPage = getLastPage(atomicOperation);

    if (pageIndex > lastPage) {
      return CommonConst.EMPTY_LONG_ARRAY;
    }

    if (limit <= 0) {
      limit = Integer.MAX_VALUE;
    }

    long[] result = null;
    do {
      try (final var cacheEntry = loadPageForRead(atomicOperation, fileId, pageIndex)) {
        final var bucket = new CollectionPositionMapBucket(cacheEntry);
        var resultSize = bucket.getSize() - index;

        if (resultSize <= 0) {
          pageIndex++;
          index = 0;
        } else {
          var entriesCount = 0;
          final var startIndex =
              (long) cacheEntry.getPageIndex() * CollectionPositionMapBucket.MAX_ENTRIES + index;

          result = new long[resultSize];

          var currentLimit = Math.min(limit, resultSize);
          for (var i = 0; i < resultSize && entriesCount < currentLimit; i++) {
            if (bucket.exists(i + index)) {
              result[entriesCount] = startIndex + i - CollectionPositionMapBucket.MAX_ENTRIES;
              entriesCount++;
            }
          }

          if (entriesCount == 0) {
            result = null;
            pageIndex++;
            index = 0;
          } else {
            result = Arrays.copyOf(result, entriesCount);
          }
        }
      }
    } while (result == null && pageIndex <= lastPage);

    if (result == null) {
      result = CommonConst.EMPTY_LONG_ARRAY;
    }

    return result;
  }

  long[] lowerPositions(final long collectionPosition, final AtomicOperation atomicOperation,
      int limit)
      throws IOException {
    if (collectionPosition == 0) {
      return CommonConst.EMPTY_LONG_ARRAY;
    }

    return floorPositions(collectionPosition - 1, atomicOperation, limit);
  }

  long[] floorPositions(final long collectionPosition, final AtomicOperation atomicOperation,
      int limit)
      throws IOException {
    if (collectionPosition < 0) {
      return CommonConst.EMPTY_LONG_ARRAY;
    }

    var pageIndex = collectionPosition / CollectionPositionMapBucket.MAX_ENTRIES + 1;
    var index = (int) (collectionPosition % CollectionPositionMapBucket.MAX_ENTRIES);

    final var lastPage = getLastPage(atomicOperation);
    long[] result;

    if (pageIndex > lastPage) {
      pageIndex = lastPage;
      index = Integer.MIN_VALUE;
    }

    if (pageIndex < 0) {
      return CommonConst.EMPTY_LONG_ARRAY;
    }

    if (limit <= 0) {
      limit = Integer.MAX_VALUE;
    }

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
            (long) cacheEntry.getPageIndex() * CollectionPositionMapBucket.MAX_ENTRIES;
        var currentLimit = Math.min(resultSize, limit);
        result = new long[resultSize];

        for (var i = resultSize - 1; i >= 0 && entriesCount < currentLimit; i--) {
          if (bucket.exists(i)) {
            result[entriesCount] = startPosition + i - CollectionPositionMapBucket.MAX_ENTRIES;
            entriesCount++;
          }
        }

        if (entriesCount == 0) {
          result = null;
          pageIndex--;
          index = Integer.MIN_VALUE;
        } else {
          result = Arrays.copyOf(result, entriesCount);
        }
      }
    } while (result == null && pageIndex >= 0);

    if (result == null) {
      result = CommonConst.EMPTY_LONG_ARRAY;
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

    CollectionPositionEntry(final long position, final long page, final int offset) {
      this.position = position;
      this.page = page;
      this.offset = offset;
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
  }
}
