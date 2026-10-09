package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

public class KnownEndpointExistsCostTest {

  /** All reverse edges cost work even when almost none belong to the source class. */
  @Test
  public void unrelatedHubEdgesCannotBecomeCheapThroughTypeSelectivity() {
    var costs = KnownEndpointExistsCost.estimate(1000, 1_000_000, 1, 0.000001, 1,
        1, 0, -1, false);
    assertThat(costs.targetFull()).isGreaterThan(1_000_000);
    assertThat(costs.targetWins()).isFalse();
  }

  /** Unknown demand requires a first-row win even when full target work is much lower. */
  @Test
  public void firstRowRuleRejectsBulkDiscoveryForLazyCallers() {
    var costs = KnownEndpointExistsCost.estimate(1_000_000, 200_000, 1, 1, 1,
        1, 0, -1, false);
    assertThat(costs.targetFull()).isLessThan(costs.sourceFull());
    assertThat(costs.targetWins()).isFalse();
  }

  /** A large LIMIT still allows a caller to read one row, so first-row cost must win. */
  @Test
  public void limitDoesNotProveCallerDemand() {
    var costs = KnownEndpointExistsCost.estimate(1_000_000, 100_000, 1, 1, 1,
        1, 0, 1_000_000, false);
    assertThat(costs.targetFull() * KnownEndpointExistsCost.SAFETY_MARGIN)
        .isLessThan(costs.sourceFull());
    assertThat(costs.targetWins()).isFalse();
  }

  /** Filter selectivity changes first-row candidate loads on both paths, not just scan loads. */
  @Test
  public void selectiveFiltersChargeBothPathsForRejectedRecords() {
    var costs = KnownEndpointExistsCost.estimate(1000, 100, 1, 1, 0.01,
        1, 0, -1, false);
    assertThat(costs.sourceFirst()).isEqualTo(1010);
    assertThat(costs.targetFirst()).isEqualTo(costs.targetFull());
  }

  /** Sparse endpoints can win for full and first-row work without known caller demand. */
  @Test
  public void sparseEndpointWinsBothComparisons() {
    assertThat(KnownEndpointExistsCost.estimate(1_000_000, 3, 1, 1, 1,
        1, 0, -1, false).targetWins()).isTrue();
  }

  /** Real degree refines limited scan work while candidate sorting still costs preparation. */
  @Test
  public void limitUsesDegreeAndIncludesCandidateSort() {
    var costs = KnownEndpointExistsCost.estimate(1000, 500, 1, 1, 1, 1, 0, 1, false);
    assertThat(costs.sourceFull()).isEqualTo(4);
    assertThat(costs.targetFull()).isGreaterThan(500);
    assertThat(costs.targetWins()).isFalse();
  }

  /** ORDER BY needs every candidate and result sorting before even its first row. */
  @Test
  public void fullInputHasFullFirstRowCost() {
    var costs = KnownEndpointExistsCost.estimate(1000, 20, 1, 1, 1, 2, 0, 1, true);
    assertThat(costs.sourceFirst()).isEqualTo(costs.sourceFull());
    assertThat(costs.targetFirst()).isEqualTo(costs.targetFull());
  }

  /** Empty classes and non-finite estimates cannot claim a cost advantage. */
  @Test
  public void unavailableEstimatesKeepTheSourcePath() {
    assertThat(KnownEndpointExistsCost.estimate(0, 0, 1, 0, 1, 1, 0, -1, false)
        .targetWins()).isFalse();
    assertThat(KnownEndpointExistsCost.estimate(100, 1, 1, 1, 1,
        Double.POSITIVE_INFINITY, 0, -1, false).targetWins()).isFalse();
  }
}
