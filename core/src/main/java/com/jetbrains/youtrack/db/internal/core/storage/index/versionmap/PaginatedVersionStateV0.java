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

package com.jetbrains.youtrack.db.internal.core.storage.index.versionmap;

import com.jetbrains.youtrack.db.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrack.db.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.base.DurablePage;

public final class PaginatedVersionStateV0 extends DurablePage {

  private static final int RECORDS_SIZE_OFFSET = NEXT_FREE_POSITION;
  private static final int SIZE_OFFSET = RECORDS_SIZE_OFFSET + IntegerSerializer.INT_SIZE;
  private static final int FILE_SIZE_OFFSET = SIZE_OFFSET + IntegerSerializer.INT_SIZE;
  private static final int FREE_LIST_OFFSET = FILE_SIZE_OFFSET + IntegerSerializer.INT_SIZE;

  public PaginatedVersionStateV0(CacheEntry cacheEntry) {
    super(cacheEntry);
  }

  public void setSize(int size) {
    setIntValue(SIZE_OFFSET, size);
  }

  public int getSize() {
    return getIntValue(SIZE_OFFSET);
  }

  public void setRecordsSize(int recordsSize) {
    setIntValue(RECORDS_SIZE_OFFSET, recordsSize);
  }

  public int getRecordsSize() {
    return getIntValue(RECORDS_SIZE_OFFSET);
  }

  public void setFileSize(int size) {
    setIntValue(FILE_SIZE_OFFSET, size);
  }

  public int getFileSize() {
    return getIntValue(FILE_SIZE_OFFSET);
  }

  public void setFreeListPage(int index, int pageIndex) {
    final var pageOffset = FREE_LIST_OFFSET + index * IntegerSerializer.INT_SIZE;
    setIntValue(pageOffset, pageIndex);
  }

  public int getFreeListPage(int index) {
    return getIntValue(FREE_LIST_OFFSET + index * IntegerSerializer.INT_SIZE);
  }
}
