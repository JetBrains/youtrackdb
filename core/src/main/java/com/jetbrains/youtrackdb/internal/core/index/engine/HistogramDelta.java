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

import javax.annotation.Nullable;

/**
 * Transaction-local accumulator for histogram changes. Carried by the
 * AtomicOperation and applied to the CHM cache only on successful commit.
 * On rollback, simply discarded (write-nothing-on-error).
 *
 * <p>One {@code HistogramDelta} is created per (engine, transaction) pair.
 * All fields are modified only by the owning transaction thread within the
 * component lock, so no synchronization is needed.
 */
public final class HistogramDelta {

  long totalCountDelta;
  long nullCountDelta;

  /**
   * Per-bucket frequency deltas. Null if no histogram existed when the first
   * non-null key operation occurred (i.e., the snapshot had no histogram).
   * int[] suffices — bounded by mutations in a single transaction, well
   * within Integer.MAX_VALUE.
   */
  @Nullable int[] frequencyDeltas;

  /**
   * Version of the HistogramSnapshot that was current when this delta's
   * frequencyDeltas were computed (i.e., the boundaries used for
   * findBucket). Captured once on the first onPut/onRemove that allocates
   * frequencyDeltas[]. On commit, if the snapshot's version has changed
   * (rebalance occurred), frequencyDeltas are discarded because the bucket
   * layout no longer matches. Only meaningful when frequencyDeltas != null.
   */
  long snapshotVersion;

  /** Number of put/remove ops in this transaction (both inserts and updates). */
  long mutationCount;

  /**
   * Per-transaction HLL sketch for multi-value indexes. Null for single-value
   * indexes. ~1 KB fixed size regardless of transaction size. On commit,
   * merged into the snapshot's persisted HLL sketch and distinctCount updated
   * from hll.estimate().
   */
  @Nullable HyperLogLogSketch hllSketch;

  /**
   * Initializes frequency deltas for the given bucket count and snapshot
   * version. Called once on the first non-null key operation when a histogram
   * exists.
   */
  void initFrequencyDeltas(int bucketCount, long version) {
    if (frequencyDeltas == null) {
      frequencyDeltas = new int[bucketCount];
      snapshotVersion = version;
    }
  }

  /**
   * Returns or creates the per-transaction HLL sketch. Lazily allocated on
   * first use (multi-value only).
   */
  HyperLogLogSketch getOrCreateHll() {
    if (hllSketch == null) {
      hllSketch = new HyperLogLogSketch();
    }
    return hllSketch;
  }
}
