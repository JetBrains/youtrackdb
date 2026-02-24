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

import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;

/**
 * Page 0 of a {@link PaginatedCollectionV2}'s data file ({@code .pcl}), storing collection-level
 * metadata. This page is always the first page in the data file and is never used for record
 * storage.
 *
 * <h2>Page Layout</h2>
 *
 * <pre>{@code
 *  Byte offset (relative to NEXT_FREE_POSITION):
 *  +-------------------+-------------------+-------------------+
 *  | RECORDS_SIZE (4B) |    SIZE (4B)      |  FILE_SIZE (4B)   |
 *  |   (reserved)      |   (reserved)      | = data page count |
 *  +-------------------+-------------------+-------------------+
 *
 *  FILE_SIZE tracks how many data pages (pages 1..N) have been allocated in the data file.
 *  Page 0 itself is not counted. This counter is used by
 *  PaginatedCollectionV2#allocateNewPage() to determine whether to append a new page or
 *  reuse an existing one.
 * }</pre>
 *
 * <p>The {@code RECORDS_SIZE} and {@code SIZE} fields are reserved for future use and are
 * currently unused by V2 collections.
 *
 * @see PaginatedCollectionV2
 */
public final class PaginatedCollectionStateV2 extends DurablePage {

  /** Byte offset for the records-size field (reserved, currently unused). */
  private static final int RECORDS_SIZE_OFFSET = NEXT_FREE_POSITION;
  /** Byte offset for the size field (reserved, currently unused). */
  private static final int SIZE_OFFSET = RECORDS_SIZE_OFFSET + IntegerSerializer.INT_SIZE;
  /** Byte offset for the file-size field (number of allocated data pages). */
  private static final int FILE_SIZE_OFFSET = SIZE_OFFSET + IntegerSerializer.INT_SIZE;
  private static final int APPROXIMATE_RECORDS_COUNT_OFFSET =
      FILE_SIZE_OFFSET + IntegerSerializer.INT_SIZE;

  public PaginatedCollectionStateV2(CacheEntry cacheEntry) {
    super(cacheEntry);
  }

  /**
   * Sets the number of data pages currently allocated in the collection's data file.
   * Does not count page 0 (this state page itself).
   */
  public void setFileSize(int size) {
    setIntValue(FILE_SIZE_OFFSET, size);
  }

  /**
   * Returns the number of data pages currently allocated in the collection's data file.
   * Does not count page 0 (this state page itself).
   */
  public int getFileSize() {
    return getIntValue(FILE_SIZE_OFFSET);
  }

  public void setApproximateRecordsCount(long count) {
    setLongValue(APPROXIMATE_RECORDS_COUNT_OFFSET, count);
  }

  public long getApproximateRecordsCount() {
    return getLongValue(APPROXIMATE_RECORDS_COUNT_OFFSET);
  }
}
