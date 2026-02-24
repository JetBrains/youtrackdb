package com.jetbrains.youtrackdb.internal.core.storage.collection.v2;

import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;

/**
 * Page 0 of a {@link CollectionPositionMapV2}'s file ({@code .cpm}), serving as the entry point
 * that tracks how many bucket pages are currently in use.
 *
 * <h2>Page Layout</h2>
 *
 * <pre>{@code
 *  Byte offset (relative to NEXT_FREE_POSITION):
 *  +-------------------+
 *  | FILE_SIZE (4B)    |
 *  | = bucket page cnt |
 *  +-------------------+
 *
 *  FILE_SIZE stores the number of CollectionPositionMapBucket pages (pages 1..N) that
 *  are currently in use. Page 0 (this entry point) is not counted. A value of 0 means
 *  no positions have been allocated yet.
 * }</pre>
 *
 * @see CollectionPositionMapV2
 */
final class MapEntryPoint extends DurablePage {

  /** Byte offset for the file-size field (number of bucket pages in use). */
  private static final int FILE_SIZE_OFFSET = NEXT_FREE_POSITION;

  MapEntryPoint(CacheEntry cacheEntry) {
    super(cacheEntry);
  }

  /** Returns the number of bucket pages currently in use (pages 1..N). */
  int getFileSize() {
    return getIntValue(FILE_SIZE_OFFSET);
  }

  /** Sets the number of bucket pages currently in use. */
  void setFileSize(int size) {
    setIntValue(FILE_SIZE_OFFSET, size);
  }
}
