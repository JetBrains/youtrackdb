package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

  /** Unknown degree allows sparse LIMIT preparation, then the same model refines the budget. */
  @Test
  public void unknownDegreeBudgetAllowsWorkWithinTheRefinedSourceEstimate() {
    var initial = KnownEndpointExistsCost.estimate(1000, 0, 0, 1, 1, 0.002, 0, 1, false);
    var refined = KnownEndpointExistsCost.estimate(1000, 1, 1, 1, 1, 0.002, 0, 1, false);
    assertThat(initial.sourceFull()).isEqualTo(refined.sourceFull()).isEqualTo(1002);
    var budget = new KnownEndpointExistsCost.WorkBudget(true, initial.sourceFull());
    budget.charge(2);
    budget.refine(refined.sourceFull());
    budget.charge(1000);
    assertThatThrownBy(() -> budget.charge(1))
        .isInstanceOf(KnownEndpointExistsCost.BudgetExceeded.class);
    assertThat(budget.spent()).isEqualTo(refined.sourceFull());
  }

  /** Spending exactly the allowance succeeds. The next action fails without spending its cost. */
  @Test
  public void budgetChargesBeforeAnActionAndCanRefineItsEstimate() {
    var budget = new KnownEndpointExistsCost.WorkBudget(true, 2);
    budget.charge(2);
    assertThat(budget.spent()).isEqualTo(2);
    assertThatThrownBy(() -> budget.charge(1))
        .isInstanceOf(KnownEndpointExistsCost.BudgetExceeded.class);
    assertThat(budget.spent()).isEqualTo(2);
    budget.refine(3);
    budget.charge(1);
    assertThat(budget.spent()).isEqualTo(budget.limit());
    assertThatThrownBy(() -> budget.refine(2))
        .isInstanceOf(KnownEndpointExistsCost.BudgetExceeded.class);
  }

  /** Single-step short lists have no running budget. Long lists enable a finite allowance. */
  @Test
  public void budgetActivationAndInvalidEstimatesAreConservative() {
    var budget = new KnownEndpointExistsCost.WorkBudget(false, 1);
    budget.charge(100);
    assertThat(budget.spent()).isZero();
    assertThat(budget.limit()).isZero();
    budget.enable(false);
    budget.enable(true);
    budget.charge(1);
    assertThatThrownBy(() -> budget.charge(-1))
        .isInstanceOf(KnownEndpointExistsCost.BudgetExceeded.class);
    assertThatThrownBy(() -> budget.refine(Double.NaN))
        .isInstanceOf(KnownEndpointExistsCost.BudgetExceeded.class);
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
