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

import java.util.HashMap;
import java.util.Map;

/**
 * Holds histogram deltas for all engines affected by a single transaction.
 * Stored directly on the {@link
 * com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated
 * .atomicoperations.AtomicOperation AtomicOperation} as a first-class
 * field (not WAL metadata). On commit, deltas are applied to the in-memory
 * CHM cache; on rollback, the holder is discarded with the operation.
 *
 * <p>The key is the engine ID (stable integer identifier assigned by
 * DiskStorage); the value is the accumulated delta for that engine.
 */
public final class HistogramDeltaHolder {

  private final Map<Integer, HistogramDelta> deltas = new HashMap<>();

  /**
   * Returns the delta for the given engine, creating it if absent.
   */
  public HistogramDelta getOrCreate(int engineId) {
    return deltas.computeIfAbsent(engineId, k -> new HistogramDelta());
  }

  /**
   * Returns the full delta map (for iteration during commit).
   */
  public Map<Integer, HistogramDelta> getDeltas() {
    return deltas;
  }
}
