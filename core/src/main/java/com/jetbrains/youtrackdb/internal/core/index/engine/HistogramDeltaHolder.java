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
   * Idempotency latch set by {@link
   * com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage#applyHistogramDeltas}
   * after a successful pass. Read by the apply hook in {@link
   * com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager#endAtomicOperation}
   * to short-circuit a second apply on the same atomic operation. Closes the
   * window where both the inline apply call inside the storage commit path
   * and the lifecycle hook in {@code endAtomicOperation} would otherwise both
   * fire and double-apply histogram deltas to the in-memory CHM cache within
   * a single transaction. The latch is also a defensive belt against any
   * future path that re-enters apply within the same atomic operation.
   *
   * <p>Thread-confinement: plain boolean because the holder lives on a single
   * AtomicOperation, driven by exactly one thread between
   * {@code startAtomicOperation} and {@code endAtomicOperation}. If a future
   * path applies from a different thread, this field must become volatile or
   * move behind a synchronizer.
   */
  private boolean applied = false;

  /**
   * Returns the delta for the given engine, creating it if absent.
   */
  public HistogramDelta getOrCreate(int engineId) {
    return deltas.computeIfAbsent(engineId, k -> new HistogramDelta());
  }

  /**
   * Returns an unmodifiable view of the delta map (for iteration during commit).
   */
  public Map<Integer, HistogramDelta> getDeltas() {
    return Collections.unmodifiableMap(deltas);
  }

  /**
   * Marks this holder as already applied to the in-memory CHM cache.
   * Idempotent: calling twice in a row is harmless.
   */
  public void setApplied() {
    this.applied = true;
  }

  /**
   * Returns {@code true} once {@link #setApplied()} has been called.
   */
  public boolean isApplied() {
    return applied;
  }
}
