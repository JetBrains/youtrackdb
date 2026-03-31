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

package com.jetbrains.youtrackdb.internal.core.index.engine;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Holds index entry count deltas for all engines affected by a single
 * transaction. Stored directly on the {@link
 * com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated
 * .atomicoperations.AtomicOperation AtomicOperation} as a first-class
 * field. On commit, deltas are applied to the engines' in-memory
 * {@code AtomicLong} counters; on rollback, the holder is discarded
 * with the operation.
 *
 * <p>The key is the engine ID (stable integer identifier assigned by
 * DiskStorage); the value is the accumulated delta for that engine.
 */
public final class IndexCountDeltaHolder {

  private final Map<Integer, IndexCountDelta> deltas = new HashMap<>();

  /**
   * Returns the delta for the given engine, creating it if absent.
   */
  public IndexCountDelta getOrCreate(int engineId) {
    return deltas.computeIfAbsent(engineId, k -> new IndexCountDelta());
  }

  /**
   * Returns an unmodifiable view of the delta map (for iteration during
   * commit).
   */
  public Map<Integer, IndexCountDelta> getDeltas() {
    return Collections.unmodifiableMap(deltas);
  }
}
