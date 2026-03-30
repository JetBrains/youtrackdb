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

/**
 * Per-index summary statistics for cost-based query optimization.
 *
 * <p>Managed by {@code IndexHistogramManager} (a StorageComponent separate from
 * the B-tree). Incrementally updated on every put/remove via engine callbacks.
 *
 * <p>Invariant (approximate): when a histogram exists,
 * {@code totalCount ≈ histogram.nonNullCount() + stats.nullCount()}.
 * Holds exactly at build/rebalance time. Between rebalances, per-bucket
 * frequency clamping can cause the histogram's non-null count (= sum of
 * clamped frequencies) to diverge slightly from
 * {@code totalCount() - nullCount()}. Rebalance restores exact equality.
 *
 * @param totalCount    total number of entries in the index (including nulls)
 * @param distinctCount number of distinct keys (NDV).
 *                      For single-value indexes: always equals {@code totalCount}
 *                      (derived in applyDelta, no separate delta field).
 *                      For multi-value indexes: updated incrementally via HLL
 *                      estimate on each commit (~3.25% error); exact value
 *                      recomputed during rebalance.
 * @param nullCount     number of entries with null key
 */
public record IndexStatistics(
    long totalCount,
    long distinctCount,
    long nullCount) {
}
