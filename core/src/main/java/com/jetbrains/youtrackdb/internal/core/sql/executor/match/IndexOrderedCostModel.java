package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.index.engine.EquiDepthHistogram;
import com.jetbrains.youtrackdb.internal.core.sql.executor.CostModel;
import javax.annotation.Nullable;

/**
 * Cost-based heuristic for choosing between index scan and load-all-and-sort
 * strategies in {@link IndexOrderedEdgeStep}.
 *
 * <p>All methods are pure static functions with no database or index dependencies,
 * enabling direct unit testing. The cost model reads tuning parameters from
 * {@link GlobalConfiguration} at each call.
 */
final class IndexOrderedCostModel {

  private IndexOrderedCostModel() {
  }

  /** Packed cost estimate produced by {@link #computeCosts}. */
  record CostEstimate(
      double expectedScanLength,
      long k,
      double seqRead,
      double randRead,
      double cpu,
      double seekCost,
      double costUnionScan,
      double costLoadSort) {
  }

  /** Multi-source execution strategy chosen by {@link #pickMultiSourceStrategy}. */
  enum MultiSourceStrategy {
    UNION_RIDSET_SCAN, GLOBAL_SCAN, LOAD_ALL_SORT
  }

  /**
   * Computes cost estimates for index scan vs load-all-and-sort.
   *
   * @param linkBagSize number of edges from the source vertex
   * @param indexSize   total entries in the property index
   * @param limit       query LIMIT value, or -1 if no LIMIT
   * @param histogram   equi-depth histogram for skew correction, or null
   * @param orderAsc    true for ASC scan direction, false for DESC
   * @return cost estimate, or null if the index scan should be skipped
   *     (below threshold, zero index, or scan too large)
   */
  @Nullable static CostEstimate computeCosts(
      int linkBagSize, long indexSize, long limit,
      @Nullable EquiDepthHistogram histogram, boolean orderAsc) {
    int minLinkBag =
        GlobalConfiguration.QUERY_INDEX_ORDERED_MIN_LINKBAG.getValueAsInteger();
    if (linkBagSize < minLinkBag || indexSize <= 0) {
      return null;
    }

    long k = limit > 0 ? Math.min(limit, linkBagSize) : linkBagSize;
    double density = Math.min((double) linkBagSize / indexSize, 1.0);
    if (density <= 0.0) {
      return null;
    }
    double expectedScanLength = k / density;

    if (histogram != null && histogram.nonNullCount() > 0) {
      expectedScanLength = applyHistogramSkew(
          expectedScanLength, indexSize, histogram, orderAsc);
    }

    long maxScan =
        GlobalConfiguration.QUERY_INDEX_ORDERED_MAX_SCAN.getValueAsLong();
    if (expectedScanLength > maxScan) {
      return null;
    }

    double seqRead = CostModel.seqPageReadCost();
    double randRead = CostModel.randomPageReadCost();
    double cpu = CostModel.perRowCpuCost();
    double seekCost = CostModel.indexSeekCost();
    double costBias =
        GlobalConfiguration.QUERY_INDEX_ORDERED_COST_BIAS.getValueAsDouble();

    // Union RidSet scan: build RidSet + scan (seq) + load matches
    double costUnionScan = linkBagSize * cpu
        + seekCost
        + expectedScanLength * (seqRead + cpu)
        + k * randRead;
    costUnionScan *= costBias;

    // Load all + sort
    double sortFactor = (limit > 0 && limit < linkBagSize)
        ? log2(limit) : log2(linkBagSize);
    double costLoadSort = (double) linkBagSize * randRead
        + (double) linkBagSize * cpu
        + (double) linkBagSize * sortFactor * cpu;

    return new CostEstimate(
        expectedScanLength, k, seqRead, randRead, cpu, seekCost,
        costUnionScan, costLoadSort);
  }

  /**
   * Picks the cheapest multi-source strategy among three options:
   * union RidSet scan, global scan, or load-all-sort.
   */
  static MultiSourceStrategy pickMultiSourceStrategy(
      int totalEdges, long indexSize, long limit,
      @Nullable EquiDepthHistogram histogram, boolean orderAsc) {
    var costs = computeCosts(totalEdges, indexSize, limit, histogram, orderAsc);
    if (costs == null) {
      return MultiSourceStrategy.LOAD_ALL_SORT;
    }

    double costBias =
        GlobalConfiguration.QUERY_INDEX_ORDERED_COST_BIAS.getValueAsDouble();

    // Multi-source union: build RidSet + scan index (seq) + load k matches.
    // Extra cpu term per match vs single-source: reverse-edge lookup cost.
    double costUnion = totalEdges * costs.cpu
        + costs.seekCost
        + costs.expectedScanLength * (costs.seqRead + costs.cpu)
        + costs.k * (costs.randRead + costs.cpu);
    costUnion *= costBias;

    // Global scan: no RidSet build, load every entry (randRead, not seqRead
    // because records are scattered across pages unlike B-tree leaves).
    double costGlobal = costs.seekCost
        + costs.expectedScanLength * (costs.randRead + costs.cpu);
    costGlobal *= costBias;

    double costSort = costs.costLoadSort;

    if (costSort <= costUnion && costSort <= costGlobal) {
      return MultiSourceStrategy.LOAD_ALL_SORT;
    }
    if (costUnion <= costGlobal) {
      return MultiSourceStrategy.UNION_RIDSET_SCAN;
    }
    return MultiSourceStrategy.GLOBAL_SCAN;
  }

  /**
   * Adjusts expected scan length using equi-depth histogram bucket frequencies.
   * Scans the first (ASC) or last (DESC) N buckets to estimate skew in the
   * scan region. Clamped to [0.5, 3.0] to prevent extreme corrections.
   */
  static double applyHistogramSkew(
      double expectedScanLength, long indexSize,
      EquiDepthHistogram histogram, boolean orderAsc) {
    double targetFraction = Math.min(expectedScanLength / indexSize, 1.0);
    int bucketsToScan = Math.max(1,
        (int) Math.ceil(targetFraction * histogram.bucketCount()));
    bucketsToScan = Math.min(bucketsToScan, histogram.bucketCount());

    long scanRegionEntries;
    if (orderAsc) {
      scanRegionEntries = sumFrequencies(
          histogram.frequencies(), 0, bucketsToScan);
    } else {
      int start = histogram.bucketCount() - bucketsToScan;
      scanRegionEntries = sumFrequencies(
          histogram.frequencies(), Math.max(0, start), histogram.bucketCount());
    }

    double uniformExpected = targetFraction * histogram.nonNullCount();
    if (uniformExpected <= 0) {
      return expectedScanLength;
    }

    double skew = scanRegionEntries / uniformExpected;
    skew = Math.max(0.5, Math.min(3.0, skew));
    return expectedScanLength * skew;
  }

  static long sumFrequencies(long[] frequencies, int from, int to) {
    long sum = 0;
    for (int i = from; i < to; i++) {
      sum += Math.max(frequencies[i], 0);
    }
    return sum;
  }

  private static double log2(double x) {
    return x <= 1.0 ? 0.0 : Math.log(x) / Math.log(2.0);
  }
}
