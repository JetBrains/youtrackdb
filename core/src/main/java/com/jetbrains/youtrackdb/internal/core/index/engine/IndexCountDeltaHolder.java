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

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

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

  private final Int2ObjectOpenHashMap<IndexCountDelta> deltas =
      new Int2ObjectOpenHashMap<>();

  /**
   * Idempotency latch set by {@link
   * com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage#persistIndexCountDeltas}
   * after a successful pass. Read by the persist hook in {@link
   * com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager#endAtomicOperation}
   * to short-circuit a second persist on the same atomic operation. Closes the
   * window where both the inline persist call inside the storage commit path
   * and the lifecycle hook in {@code endAtomicOperation} would otherwise both
   * fire and double-write the BTree entry-point page within a single
   * transaction. The latch is also a defensive belt against any future path
   * that re-enters persist within the same atomic operation.
   */
  private boolean persisted = false;

  /**
   * Returns the delta for the given engine, creating it if absent.
   */
  public IndexCountDelta getOrCreate(int engineId) {
    return deltas.computeIfAbsent(engineId, k -> new IndexCountDelta());
  }

  /**
   * Returns the delta map for iteration during commit.
   */
  public Int2ObjectOpenHashMap<IndexCountDelta> getDeltas() {
    return deltas;
  }

  /**
   * Marks this holder as already persisted to the BTree entry-point pages.
   * Idempotent: calling twice in a row is harmless.
   */
  public void setPersisted() {
    this.persisted = true;
  }

  /**
   * Returns {@code true} once {@link #setPersisted()} has been called.
   */
  public boolean isPersisted() {
    return persisted;
  }
}
