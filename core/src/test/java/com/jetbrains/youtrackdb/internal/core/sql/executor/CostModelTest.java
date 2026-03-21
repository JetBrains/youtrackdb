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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Tests for {@link CostModel} — the shared cost model used by query planners.
 *
 * <p>Verifies that all cost formulas produce correct values using default
 * configuration, and that runtime tuning of configuration parameters is
 * reflected in cost calculations.
 */
@Category(SequentialTest.class)
public class CostModelTest {

  // Save original values to restore after tests that modify config
  private Object origSeqPageRead;
  private Object origRandomPageRead;
  private Object origPerRowCpu;
  private Object origTreeDepth;

  @Before
  public void saveDefaults() {
    origSeqPageRead = GlobalConfiguration.QUERY_STATS_COST_SEQ_PAGE_READ
        .getValue();
    origRandomPageRead = GlobalConfiguration.QUERY_STATS_COST_RANDOM_PAGE_READ
        .getValue();
    origPerRowCpu = GlobalConfiguration.QUERY_STATS_COST_PER_ROW_CPU
        .getValue();
    origTreeDepth = GlobalConfiguration.QUERY_STATS_DEFAULT_INDEX_TREE_DEPTH
        .getValue();
  }

  @After
  public void restoreDefaults() {
    GlobalConfiguration.QUERY_STATS_COST_SEQ_PAGE_READ
        .setValue(origSeqPageRead);
    GlobalConfiguration.QUERY_STATS_COST_RANDOM_PAGE_READ
        .setValue(origRandomPageRead);
    GlobalConfiguration.QUERY_STATS_COST_PER_ROW_CPU
        .setValue(origPerRowCpu);
    GlobalConfiguration.QUERY_STATS_DEFAULT_INDEX_TREE_DEPTH
        .setValue(origTreeDepth);
  }

  // -- Cost constant accessors with defaults ---------------------------------

  @Test
  public void seqPageReadCost_defaultIsOne() {
    assertEquals(1.0, CostModel.seqPageReadCost(), 0.0001);
  }

  @Test
  public void randomPageReadCost_defaultIsFour() {
    assertEquals(4.0, CostModel.randomPageReadCost(), 0.0001);
  }

  @Test
  public void perRowCpuCost_defaultIsPointZeroOne() {
    assertEquals(0.01, CostModel.perRowCpuCost(), 0.0001);
  }

  @Test
  public void indexSeekCost_defaultIs16() {
    // 4.0 (random page read) × 4 (tree depth) = 16.0
    assertEquals(16.0, CostModel.indexSeekCost(), 0.0001);
  }

  // -- Runtime tuning --------------------------------------------------------

  @Test
  public void costConstants_reflectRuntimeConfigChanges() {
    // Given: modified configuration
    GlobalConfiguration.QUERY_STATS_COST_SEQ_PAGE_READ.setValue(2.0);
    GlobalConfiguration.QUERY_STATS_COST_RANDOM_PAGE_READ.setValue(8.0);
    GlobalConfiguration.QUERY_STATS_COST_PER_ROW_CPU.setValue(0.05);
    GlobalConfiguration.QUERY_STATS_DEFAULT_INDEX_TREE_DEPTH.setValue(3);

    // Then: accessors reflect new values
    assertEquals(2.0, CostModel.seqPageReadCost(), 0.0001);
    assertEquals(8.0, CostModel.randomPageReadCost(), 0.0001);
    assertEquals(0.05, CostModel.perRowCpuCost(), 0.0001);
    // indexSeekCost = 8.0 × 3 = 24.0
    assertEquals(24.0, CostModel.indexSeekCost(), 0.0001);
  }

  // -- fullClassScanCost -----------------------------------------------------

  @Test
  public void fullClassScanCost_combinesCpuAndIo() {
    // With defaults: rowsPerPage = 8192/200 = 40
    // cost = 1000 × 0.01 + (1000/40) × 1.0 = 10.0 + 25.0 = 35.0
    assertEquals(35.0, CostModel.fullClassScanCost(1000), 0.0001);
  }

  @Test
  public void fullClassScanCost_zeroRows_returnsZero() {
    assertEquals(0.0, CostModel.fullClassScanCost(0), 0.0001);
  }

  @Test
  public void fullClassScanCost_singleRow() {
    // cost = 1 × 0.01 + (1/40) × 1.0 = 0.01 + 0.025 = 0.035
    assertEquals(0.035, CostModel.fullClassScanCost(1), 0.0001);
  }

  @Test
  public void fullClassScanCost_respectsConfigChanges() {
    GlobalConfiguration.QUERY_STATS_COST_SEQ_PAGE_READ.setValue(2.0);
    GlobalConfiguration.QUERY_STATS_COST_PER_ROW_CPU.setValue(0.1);

    // cost = 100 × 0.1 + (100/40) × 2.0 = 10.0 + 5.0 = 15.0
    assertEquals(15.0, CostModel.fullClassScanCost(100), 0.0001);
  }

  // -- indexEqualityCost -----------------------------------------------------

  @Test
  public void indexEqualityCost_seekPlusRandomReads() {
    // cost = 16.0 (seek) + 10 × 4.0 (random reads) = 56.0
    assertEquals(56.0, CostModel.indexEqualityCost(10), 0.0001);
  }

  @Test
  public void indexEqualityCost_singleRow() {
    // cost = 16.0 + 1 × 4.0 = 20.0
    assertEquals(20.0, CostModel.indexEqualityCost(1), 0.0001);
  }

  @Test
  public void indexEqualityCost_zeroRows_returnsSeekCostOnly() {
    // cost = 16.0 + 0 = 16.0
    assertEquals(16.0, CostModel.indexEqualityCost(0), 0.0001);
  }

  // -- indexRangeCost --------------------------------------------------------

  @Test
  public void indexRangeCost_seekPlusSequentialReads() {
    // cost = 16.0 (seek) + 100 × 1.0 (sequential reads) = 116.0
    assertEquals(116.0, CostModel.indexRangeCost(100), 0.0001);
  }

  @Test
  public void indexRangeCost_cheaperThanEqualityForSameRowCount() {
    // Range uses sequential reads (1.0), equality uses random reads (4.0)
    long rows = 50;
    assertTrue(
        "Range scan should be cheaper than equality for same row count",
        CostModel.indexRangeCost(rows) < CostModel.indexEqualityCost(rows));
  }

  @Test
  public void indexRangeCost_zeroRows_returnsSeekCostOnly() {
    assertEquals(16.0, CostModel.indexRangeCost(0), 0.0001);
  }

  @Test
  public void indexRangeCost_nonDefaultSeqCost_usesMultiplication() {
    // With seqPageReadCost=2.0, indexRangeCost(100) = 16.0 + 100*2.0 = 216.0.
    // If the mutation replaces * with /, result = 16.0 + 100/2.0 = 66.0.
    // This kills the multiplication→division mutation at CostModel line 133.
    GlobalConfiguration.QUERY_STATS_COST_SEQ_PAGE_READ.setValue(2.0);
    assertEquals(216.0, CostModel.indexRangeCost(100), 0.0001);
  }

  // -- edgeTraversalCost -----------------------------------------------------

  @Test
  public void edgeTraversalCost_sourceTimesNodeTimesRandomRead() {
    // cost = 100 (source) × 5.0 (fanout) × 4.0 (random) = 2000.0
    assertEquals(2000.0, CostModel.edgeTraversalCost(100, 5.0), 0.0001);
  }

  @Test
  public void edgeTraversalCost_zeroSources_returnsZero() {
    assertEquals(0.0, CostModel.edgeTraversalCost(0, 10.0), 0.0001);
  }

  @Test
  public void edgeTraversalCost_zeroFanOut_returnsZero() {
    assertEquals(0.0, CostModel.edgeTraversalCost(100, 0.0), 0.0001);
  }

  @Test
  public void edgeTraversalCost_fractionalFanOut() {
    // cost = 50 × 2.5 × 4.0 = 500.0
    assertEquals(500.0, CostModel.edgeTraversalCost(50, 2.5), 0.0001);
  }

  // -- Relative cost comparisons (sanity checks) ----------------------------

  @Test
  public void indexSeek_cheaperThanFullScanForLargeTable() {
    // A point lookup returning 1 row should be much cheaper than scanning
    // 10,000 rows.
    double seekCost = CostModel.indexEqualityCost(1);
    double scanCost = CostModel.fullClassScanCost(10_000);
    assertTrue(
        "Index seek should be cheaper than full scan of 10k rows",
        seekCost < scanCost);
  }

  @Test
  public void fullScan_cheaperThanIndexSeekReturningAllRows() {
    // Scanning 1000 rows sequentially should be cheaper than fetching
    // all 1000 via random reads from an index seek.
    double scanCost = CostModel.fullClassScanCost(1000);
    double seekCost = CostModel.indexEqualityCost(1000);
    assertTrue(
        "Full scan should be cheaper than index-fetching all 1000 rows",
        scanCost < seekCost);
  }
}
