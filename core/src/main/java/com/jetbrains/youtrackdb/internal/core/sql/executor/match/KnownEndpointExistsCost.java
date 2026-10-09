package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

/** Compares record reads, edge reads and CPU work in one record-read cost unit. */
final class KnownEndpointExistsCost {

  static final double SAFETY_MARGIN = 2;
  private static final double SORT_COMPARISON = 0.01;

  private KnownEndpointExistsCost() {
  }

  record Estimate(double sourceFull, double targetFull, double sourceFirst,
      double targetFirst) {

    boolean targetWins() {
      return Double.isFinite(targetFull) && Double.isFinite(sourceFull)
          && targetFull * SAFETY_MARGIN < sourceFull
          && targetFirst * SAFETY_MARGIN < sourceFirst;
    }
  }

  static Estimate estimate(double sourceCount, double degree, double targetLoads,
      double sourceTypeShare, double filterShare, double probeWork, double laterWork,
      long requiredRows, boolean fullInput) {
    // Degree counts all edges. Type selectivity only reduces candidate loads and later work.
    double candidates = Math.min(sourceCount, degree * sourceTypeShare);
    double pass = sourceCount > 0 ? Math.min(1, candidates * filterShare / sourceCount) : 0;
    double firstScan = pass > 0 ? Math.min(sourceCount, 1 / pass) : sourceCount;
    double scan = fullInput || requiredRows < 0 || pass <= 0 ? sourceCount
        : Math.min(sourceCount, requiredRows / pass);
    double sourceFull = scan + scan * filterShare * (probeWork + laterWork);
    double sort = degree < 2 ? 0 : degree * (Math.log(degree) / Math.log(2))
        * SORT_COMPARISON;
    double preparation = targetLoads + degree + sort;
    double loaded = fullInput || requiredRows < 0 || filterShare <= 0 ? candidates
        : Math.min(candidates, requiredRows / filterShare);
    double targetFull = preparation + loaded + loaded * filterShare * (probeWork + laterWork);
    // A full-input consumer can require a result sort in addition to candidate RID sorting.
    double passing = candidates * filterShare;
    double resultSort = !fullInput || passing < 2 ? 0
        : passing * (Math.log(passing) / Math.log(2)) * SORT_COMPARISON;
    sourceFull += resultSort;
    targetFull += resultSort;
    double sourceFirst = fullInput ? sourceFull
        : firstScan + firstScan * filterShare * (probeWork + laterWork);
    double firstLoads = filterShare > 0 ? Math.min(candidates, 1 / filterShare) : candidates;
    double targetFirst = fullInput ? targetFull
        : preparation + firstLoads + firstLoads * filterShare * (probeWork + laterWork);
    return new Estimate(sourceFull, targetFull, sourceFirst, targetFirst);
  }
}
