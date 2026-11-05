package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.internal.core.sql.executor.CostModel;

/** Work for a single directed hop. Presence reduces traversal work, never scan acquisition. */
final class PresenceRootCost {

  private PresenceRootCost() {
  }

  record Costs(double forward, double reverse) {
    boolean reverseWins() {
      return reverse < forward;
    }
  }

  static Costs compare(long sources, long selected, long targets, long edges, double density,
      boolean indexedSource, long requiredRows) {
    double fanOut = (double) edges / sources;
    double fanIn = (double) edges / targets;
    double output = selected * fanOut * density;
    double fraction = requiredRows > 0 ? Math.min(1, requiredRows / output) : 1;
    double forwardSources = Math.min(selected, Math.max(1, selected * fraction));
    double reverseTargets = Math.min(targets, Math.max(1, targets * fraction));
    double record = CostModel.randomPageReadCost() + CostModel.perRowCpuCost();
    double forwardAcquire = indexedSource
        ? CostModel.indexSeekCost() + forwardSources * record
        : CostModel.fullClassScanCost(sources) * forwardSources / Math.max(1, selected);
    // Each edge traversal reads an edge and its endpoint. Source-filter failures still pay both.
    double forward = forwardAcquire + forwardSources * fanOut * 2 * record;
    double reverse = CostModel.fullClassScanCost(targets) * reverseTargets / targets
        + reverseTargets * density * fanIn * 2 * record;
    return new Costs(forward, reverse);
  }
}
