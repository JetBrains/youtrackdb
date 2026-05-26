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
   * to short-circuit a second persist on the same atomic operation. Defensive
   * belt against any future re-entry into persist within the same atomic
   * operation, for example a nested or mistakenly-replayed lifecycle pass. The
   * latch would also prevent a double-write to the BTree entry-point page if
   * any path tried to invoke persist twice in a row.
   */
  // Thread-confinement note: plain boolean because the holder lives on a
  // single AtomicOperation, driven by exactly one thread between
  // startAtomicOperation and endAtomicOperation. If a future path persists
  // from a different thread, this field must become volatile or move behind
  // a synchronizer.
  private boolean persisted = false;

  /**
   * Idempotency latch set by {@link
   * com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage#applyIndexCountDeltas}
   * after a successful pass. Read by the apply hook in {@link
   * com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager#endAtomicOperation}
   * to short-circuit a second apply on the same atomic operation. Defensive
   * belt against any future re-entry into apply within the same atomic
   * operation, for example a nested or mistakenly-replayed lifecycle pass.
   * The latch would also prevent a double-increment of the engine's in-memory
   * {@code AtomicLong} counters if any path tried to invoke apply twice in a
   * row. See the thread-confinement note on {@link #persisted} above: the
   * latch is a plain boolean because the holder is single-threaded between
   * {@code startAtomicOperation} and {@code endAtomicOperation}.
   */
  private boolean applied = false;

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

  /**
   * Marks this holder as already applied to the engines' in-memory
   * {@code AtomicLong} counters. Idempotent: calling twice in a row is
   * harmless.
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
