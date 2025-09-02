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
package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v1;

import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.common.serialization.types.ShortSerializer;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import javax.annotation.Nullable;

/**
 * Bucket which is intended to save values stored in sbtree under <code>null</code> key. Bucket has
 * following layout:
 *
 * <ol>
 *   <li>First byte is flag which indicates presence of value in bucket
 *   <li>Second byte indicates whether value is presented by link to the "bucket list" where actual
 *       value is stored or real value passed be user.
 *   <li>The rest is serialized value whether link or passed in value.
 * </ol>
 *
 * @since 4/15/14
 */
public final class CellBTreeNullBucketSingleValueV1 extends DurablePage {

  public CellBTreeNullBucketSingleValueV1(final CacheEntry cacheEntry) {
    super(cacheEntry);
  }

  public void init() {
    setByteValue(NEXT_FREE_POSITION, (byte) 0);
  }

  public void setValue(final RID value) {
    setByteValue(NEXT_FREE_POSITION, (byte) 1);

    setShortValue(NEXT_FREE_POSITION + 1, (short) value.getCollectionId());
    setLongValue(NEXT_FREE_POSITION + 1 + ShortSerializer.SHORT_SIZE, value.getCollectionPosition());
  }

  @Nullable
  public RID getValue() {
    if (getByteValue(NEXT_FREE_POSITION) == 0) {
      return null;
    }

    final int collectionId = getShortValue(NEXT_FREE_POSITION + 1);
    final var collectionPosition = getLongValue(NEXT_FREE_POSITION + 1 + ShortSerializer.SHORT_SIZE);
    return new RecordId(collectionId, collectionPosition);
  }

  public void removeValue() {
    setByteValue(NEXT_FREE_POSITION, (byte) 0);
  }
}
