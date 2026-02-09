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

package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 *
 */
public interface TreeInternal<K, V> {

  boolean isEmpty(AtomicOperation atomicOperation);

  void loadEntriesMajor(
      K key, boolean inclusive, boolean ascSortOrder, RangeResultListener<K, V> listener,
      AtomicOperation atomicOperation);

  K firstKey(AtomicOperation atomicOperation);

  @Nullable
  V remove(AtomicOperation atomicOperation, K key) throws IOException;

  /**
   *
   */
  interface RangeResultListener<K, V> {

    /**
     * Callback method for result entries.
     *
     * @param entry result entry
     * @return true if continue to iterate through entries, false if no more result needed.
     */
    boolean addResult(Map.Entry<K, V> entry);
  }

  class AccumulativeListener<K, V> implements RangeResultListener<K, V> {

    private final int limit;
    private final List<Map.Entry<K, V>> entries;

    public AccumulativeListener(int limit) {
      entries = new ArrayList<Map.Entry<K, V>>(limit);
      this.limit = limit;
    }

    @Override
    public boolean addResult(Map.Entry<K, V> entry) {
      entries.add(entry);

      return limit > entries.size();
    }

    public List<Map.Entry<K, V>> getResult() {
      return entries;
    }
  }
}
