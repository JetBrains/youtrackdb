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
package com.jetbrains.youtrack.db.internal.core.storage.index.sbtree.local.v2;

import com.jetbrains.youtrack.db.internal.common.serialization.types.BinarySerializer;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrack.db.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.base.DurablePage;
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
public final class SBTreeNullBucketV2<V> extends DurablePage {

  public SBTreeNullBucketV2(CacheEntry cacheEntry) {
    super(cacheEntry);
  }

  public void init() {
    setByteValue(NEXT_FREE_POSITION, (byte) 0);
  }

  public void setValue(final byte[] value, final BinarySerializer<V> valueSerializer) {
    setByteValue(NEXT_FREE_POSITION, (byte) 1);

    setByteValue(NEXT_FREE_POSITION + 1, (byte) 1);
    setBinaryValue(NEXT_FREE_POSITION + 2, value);
  }

  @Nullable
  public SBTreeValue<V> getValue(final BinarySerializer<V> valueSerializer,
      BinarySerializerFactory serializerFactory) {
    if (getByteValue(NEXT_FREE_POSITION) == 0) {
      return null;
    }

    final var isLink = getByteValue(NEXT_FREE_POSITION + 1) == 0;
    if (isLink) {
      return new SBTreeValue<>(true, getLongValue(NEXT_FREE_POSITION + 2), null);
    }

    return new SBTreeValue<>(
        false, -1, deserializeFromDirectMemory(valueSerializer, serializerFactory,
        NEXT_FREE_POSITION + 2));
  }

  @Nullable
  public byte[] getRawValue(final BinarySerializer<V> valueSerializer,
      BinarySerializerFactory serializerFactory) {
    if (getByteValue(NEXT_FREE_POSITION) == 0) {
      return null;
    }

    final var isLink = getByteValue(NEXT_FREE_POSITION + 1) == 0;
    assert !isLink;

    return getBinaryValue(
        NEXT_FREE_POSITION + 2,
        getObjectSizeInDirectMemory(valueSerializer, serializerFactory, NEXT_FREE_POSITION + 2));
  }

  public void removeValue(final BinarySerializer<V> valueSerializer) {
    setByteValue(NEXT_FREE_POSITION, (byte) 0);
  }
}
