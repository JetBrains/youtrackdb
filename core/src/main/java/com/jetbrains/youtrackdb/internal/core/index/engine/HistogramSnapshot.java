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
 * Immutable point-in-time snapshot of index statistics and histogram.
 *
 * <p>Represents committed state only. Replaced atomically on transaction commit
 * (delta application) and on histogram rebalance. Stored in a shared
 * {@code ConcurrentHashMap<Integer, HistogramSnapshot>} keyed by engine ID.
 *
 * @param stats                    current index statistics (never null)
 * @param histogram                equi-depth histogram; null before first build
 *                                 or for indexes below HISTOGRAM_MIN_SIZE
 * @param mutationsSinceRebalance  number of put/remove operations since last
 *                                 histogram build/rebalance; used to trigger
 *                                 automatic rebalance
 * @param totalCountAtLastBuild    totalCount at the time of the last histogram
 *                                 build; used to compute the rebalance threshold
 * @param version                  incremented on each rebalance; used by delta
 *                                 application to detect stale frequencyDeltas.
 *                                 NOT persisted — resets to 0 on restart (safe
 *                                 because in-flight transactions are rolled back
 *                                 on crash recovery)
 * @param hasDriftedBuckets        true if any bucket frequency was clamped from
 *                                 negative to 0 during delta application; causes
 *                                 the rebalance threshold to be halved. NOT
 *                                 persisted — resets to false on restart and
 *                                 rebalance
 * @param hllSketch                multi-value NDV tracking sketch; null for
 *                                 single-value indexes and for multi-value
 *                                 indexes below HISTOGRAM_MIN_SIZE (lazy init);
 *                                 persisted to .ixs page
 */
public record HistogramSnapshot(
    IndexStatistics stats,
    @Nullable EquiDepthHistogram histogram,
    long mutationsSinceRebalance,
    long totalCountAtLastBuild,
    long version,
    boolean hasDriftedBuckets,
    @Nullable HyperLogLogSketch hllSketch
) {
}
