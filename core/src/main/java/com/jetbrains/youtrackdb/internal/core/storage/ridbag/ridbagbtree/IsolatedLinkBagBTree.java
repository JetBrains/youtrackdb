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

package com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree;

import com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.TreeInternal;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.LinkBagPointer;
import it.unimi.dsi.fastutil.objects.ObjectIntPair;
import java.io.IOException;
import java.util.Spliterator;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface IsolatedLinkBagBTree<K, V> extends TreeInternal<K, V> {

  /**
   * Gets id of file where this bonsai tree is stored.
   *
   * @return id of file in {@link ReadCache}
   */
  long getFileId();

  /**
   * Returns the pointer to the root bucket in this tree.
   *
   * @return the pointer to the root bucket in tree.
   */
  LinkBagBucketPointer getRootBucketPointer();

  /**
   * Returns the pointer to the collection associated with this B-tree.
   *
   * @return pointer to a collection.
   */
  LinkBagPointer getCollectionPointer();

  /**
   * Search for entry with specific key and return its value.
   *
   * @param key
   * @param atomicOperation
   * @return value associated with given key, NULL if no value is associated.
   */
  @Nullable
  V get(K key, AtomicOperation atomicOperation);

  boolean put(AtomicOperation atomicOperation, K key, V value) throws IOException;

  /**
   * Deletes all entries from tree.
   *
   * @param atomicOperation
   */
  void clear(AtomicOperation atomicOperation) throws IOException;

  /**
   * Deletes whole tree. After this operation tree is no longer usable.
   *
   * @param atomicOperation
   */
  void delete(AtomicOperation atomicOperation);

  boolean isEmpty(AtomicOperation atomicOperation);

  V remove(AtomicOperation atomicOperation, K key) throws IOException;

  void loadEntriesMajor(
      K key, boolean inclusive, boolean ascSortOrder, RangeResultListener<K, V> listener,
      AtomicOperation atomicOperation);

  @Nonnull
  Spliterator<ObjectIntPair<K>> spliteratorEntriesBetween(
      @Nonnull K keyFrom, boolean fromInclusive,@Nonnull K keyTo, boolean toInclusive, boolean ascSortOrder,
      AtomicOperation atomicOperation);

  @Nullable
  K firstKey(AtomicOperation atomicOperation);

  @Nullable
  K lastKey(AtomicOperation atomicOperation);

  /**
   * Hardcoded method for Bag to avoid creation of extra layer.
   *
   * <p>Don't make any changes to tree.
   *
   * @return real bag size
   */
  int getRealBagSize(AtomicOperation atomicOperation);

  BinarySerializer<K> getKeySerializer();

  BinarySerializer<V> getValueSerializer();
}
