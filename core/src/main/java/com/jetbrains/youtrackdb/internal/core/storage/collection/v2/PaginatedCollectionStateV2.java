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

public final class PaginatedCollectionStateV2 extends DurablePage {

  private static final int RECORDS_SIZE_OFFSET = NEXT_FREE_POSITION;
  private static final int SIZE_OFFSET = RECORDS_SIZE_OFFSET + IntegerSerializer.INT_SIZE;
  private static final int FILE_SIZE_OFFSET = SIZE_OFFSET + IntegerSerializer.INT_SIZE;

  public PaginatedCollectionStateV2(CacheEntry cacheEntry) {
    super(cacheEntry);
  }

  public void setFileSize(int size) {
    setIntValue(FILE_SIZE_OFFSET, size);
  }

  public int getFileSize() {
    return getIntValue(FILE_SIZE_OFFSET);
  }
}
