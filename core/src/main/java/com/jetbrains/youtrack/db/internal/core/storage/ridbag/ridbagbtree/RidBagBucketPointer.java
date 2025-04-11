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

package com.jetbrains.youtrack.db.internal.core.storage.ridbag.ridbagbtree;

import com.jetbrains.youtrack.db.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrack.db.internal.common.serialization.types.LongSerializer;

/**
 * A pointer to bucket in disk page. Defines the page and the offset in page where the bucket is
 * placed. Is immutable.
 */
public class RidBagBucketPointer {

  public static final int SIZE = LongSerializer.LONG_SIZE + IntegerSerializer.INT_SIZE;
  public static final RidBagBucketPointer NULL = new RidBagBucketPointer(-1, -1);

  private final long pageIndex;
  private final int pageOffset;

  public RidBagBucketPointer(long pageIndex, int pageOffset) {
    this.pageIndex = pageIndex;
    this.pageOffset = pageOffset;
  }

  public long getPageIndex() {
    return pageIndex;
  }

  public int getPageOffset() {
    return pageOffset;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    var that = (RidBagBucketPointer) o;

    if (pageIndex != that.pageIndex) {
      return false;
    }
    return pageOffset == that.pageOffset;
  }

  @Override
  public int hashCode() {
    var result = (int) (pageIndex ^ (pageIndex >>> 32));
    result = 31 * result + pageOffset;
    return result;
  }

  public boolean isValid() {
    return pageIndex >= 0;
  }
}
