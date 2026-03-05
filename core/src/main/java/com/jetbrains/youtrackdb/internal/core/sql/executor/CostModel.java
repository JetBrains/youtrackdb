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

package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;

/**
 * Shared cost model for query plan optimization. Provides I/O-based cost
 * estimates for full class scans, index seeks, index range scans, and edge
 * traversals.
 *
 * <p>All cost constants are read from {@link GlobalConfiguration} at each
 * call so runtime tuning takes effect without restart. Costs are expressed
 * in abstract units where one sequential page read = 1.0.
 *
 * <p>Used by both the SELECT planner ({@link IndexSearchDescriptor}) and the
 * MATCH planner ({@link
 * com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchExecutionPlanner}).
 */
public final class CostModel {

  /** Default page size in bytes (8 KB). */
  private static final int PAGE_SIZE_BYTES = 8192;

  /**
   * Rough average row size in bytes used to estimate rows per page. The exact
   * value is not critical — it only affects the relative weight of the I/O
   * component in full class scans. 200 bytes is a reasonable middle ground
   * for graph databases with mixed property sizes.
   */
  private static final int ESTIMATED_ROW_SIZE_BYTES = 200;

  private CostModel() {
  }

  // -- Cost constant accessors -----------------------------------------------

  /** Sequential page read cost (baseline = 1.0). */
  public static double seqPageReadCost() {
    return GlobalConfiguration.QUERY_STATS_COST_SEQ_PAGE_READ
        .getValueAsDouble();
  }

  /** Random page read cost (default 4.0 — typical SSD sequential/random ratio). */
  public static double randomPageReadCost() {
    return GlobalConfiguration.QUERY_STATS_COST_RANDOM_PAGE_READ
        .getValueAsDouble();
  }

  /** Per-row CPU cost for filtering and comparison. */
  public static double perRowCpuCost() {
    return GlobalConfiguration.QUERY_STATS_COST_PER_ROW_CPU
        .getValueAsDouble();
  }

  /**
   * Index seek cost = {@link #randomPageReadCost()} × tree depth.
   * Tree depth defaults to 4 (covers B-trees up to ~millions of entries
   * with 8 KB pages and typical key sizes).
   */
  public static double indexSeekCost() {
    int depth = GlobalConfiguration
        .QUERY_STATS_DEFAULT_INDEX_TREE_DEPTH.getValueAsInteger();
    return randomPageReadCost() * depth;
  }

  // -- Cost formulas ---------------------------------------------------------

  /**
   * Cost of a full class scan (no index).
   *
   * <pre>
   * cost = classCount × COST_PER_ROW_CPU
   *      + (classCount / rowsPerPage) × COST_SEQ_PAGE_READ
   * </pre>
   */
  public static double fullClassScanCost(long classCount) {
    double cpuCost = classCount * perRowCpuCost();
    int rowsPerPage = Math.max(1, PAGE_SIZE_BYTES / ESTIMATED_ROW_SIZE_BYTES);
    double ioCost =
        ((double) classCount / rowsPerPage) * seqPageReadCost();
    return cpuCost + ioCost;
  }

  /**
   * Cost of an index equality seek (point lookup returning {@code estimatedRows}
   * records).
   *
   * <pre>
   * cost = COST_INDEX_SEEK + estimatedRows × COST_RANDOM_PAGE_READ
   * </pre>
   *
   * <p>Random page reads model the fact that matching records are typically
   * scattered across different data pages (heap / cluster).
   */
  public static double indexEqualityCost(long estimatedRows) {
    return indexSeekCost()
        + estimatedRows * randomPageReadCost();
  }

  /**
   * Cost of an index range scan returning {@code estimatedRows} records.
   *
   * <pre>
   * cost = COST_INDEX_SEEK + estimatedRows × COST_SEQ_PAGE_READ
   * </pre>
   *
   * <p>Sequential page reads model the fact that range-adjacent records in a
   * B-tree tend to be stored on consecutive leaf pages.
   */
  public static double indexRangeCost(long estimatedRows) {
    return indexSeekCost()
        + estimatedRows * seqPageReadCost();
  }

  /**
   * Cost of traversing one edge per source vertex in a MATCH pattern.
   *
   * <pre>
   * costPerSource = avgFanOut × COST_RANDOM_PAGE_READ
   * totalCost     = sourceRows × costPerSource
   * </pre>
   *
   * @param sourceRows estimated number of source vertices
   * @param avgFanOut  average number of target vertices per source
   *                   (from {@link
   *                   com.jetbrains.youtrackdb.internal.core.sql.executor.match.EdgeFanOutEstimator})
   * @return total edge traversal cost
   */
  public static double edgeTraversalCost(long sourceRows, double avgFanOut) {
    return sourceRows * avgFanOut * randomPageReadCost();
  }
}
