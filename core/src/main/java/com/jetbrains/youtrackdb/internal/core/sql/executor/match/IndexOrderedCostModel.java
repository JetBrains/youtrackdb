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
 *
 * <h2>Where admission is decided</h2>
 * The ordered scan reads index entries; the alternative reads records. Which one runs is
 * decided by {@link #computeCosts} and {@link #pickMultiSourceStrategy}, with no dominance
 * gate ahead of that comparison. An earlier gate converted records to entries through
 * {@link #scanCostPerEntry} and refused the scan when expected length exceeded that budget;
 * the conversion was unmeasured and stood in front of every ordered top-N MATCH plan, so it
 * was removed. Density, histogram skew, page amortization and the two cost estimates are the
 * whole plan-time decision.
 *
 * <p>A runtime valve covers clustered membership. Density prices hits as spread evenly
 * ({@code expectedScanLength = k / density}); when they are not, {@code IndexOrderedEdgeStep}
 * counts consumed index entries and abandons the scan for load-from-sources once spend reaches
 * {@link #entriesWorthTheLoadAlternative}. See {@code scanUnderBudget} there.
 *
 * <p>A capped multi-source edge estimate ({@code extrapolated >= indexSize}) is not proof that
 * every index entry is reachable. Under density {@code 1.0}, {@link MultiSourceStrategy#GLOBAL_SCAN}
 * prices a LIMIT-sized walk that can degrade to a near-full index scan when membership is sparse
 * in the ordered prefix. {@link #pickMultiSourceStrategy} therefore loads from LinkBags whenever
 * the caller marks the estimate as capped — independent of index size.
 *
 * <h2>Shared per-entry cursor cost</h2>
 * {@link #computeCosts} and {@link #pickMultiSourceStrategy} both price a filtered ordered-scan
 * cursor advance through {@link #scanCostPerEntry}: amortized leaf-page read plus
 * {@link GlobalConfiguration#QUERY_INDEX_ORDERED_SCAN_CPU_FACTOR} times per-row CPU. Default
 * factor is 5 — roughly one {@link CostModel#perRowCpuCost} each for read lock, RidSet bitmap
 * check, filter lambda, key compare, and leaf advance. Lower values favour index scan; raise
 * them to prefer load-all-and-sort.
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
   * Delegates to {@link #computeCosts(int, long, long, EquiDepthHistogram,
   * boolean, int)} with zero downstream edges.
   */
  @Nullable static CostEstimate computeCosts(
      int linkBagSize, long indexSize, long limit,
      @Nullable EquiDepthHistogram histogram, boolean orderAsc) {
    return computeCosts(linkBagSize, indexSize, limit, histogram, orderAsc, 0);
  }

  /**
   * Computes cost estimates for index scan vs load-all-and-sort.
   *
   * @param linkBagSize          number of edges from the source vertex
   * @param indexSize            total entries in the property index
   * @param limit                query LIMIT value, or -1 if no LIMIT
   * @param histogram            equi-depth histogram for skew correction, or null
   * @param orderAsc             true for ASC scan direction, false for DESC
   * @param downstreamEdgeCount  number of MATCH edges after the target alias.
   *     Both strategies compared here emit rows in index order under a LIMIT,
   *     so only K rows traverse these edges in either case. The term is still
   *     added (at ~randRead per row per edge) so the absolute estimates stay
   *     comparable with cost numbers produced elsewhere; it cancels out of the
   *     scan-versus-load-sort comparison itself.
   * @return cost estimate, or null if the index scan should be skipped
   *     (below threshold, zero index, or scan too large)
   */
  @Nullable static CostEstimate computeCosts(
      int linkBagSize, long indexSize, long limit,
      @Nullable EquiDepthHistogram histogram, boolean orderAsc,
      int downstreamEdgeCount) {
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

    // Union RidSet scan: build RidSet + scan (seq) + load matches.
    // Per-entry cursor cost is shared with pickMultiSourceStrategy via
    // scanCostPerEntry() (page amort + SCAN_CPU_FACTOR × cpu).
    double costUnionScan = linkBagSize * cpu
        + seekCost
        + expectedScanLength * scanCostPerEntry()
        + k * randRead;
    costUnionScan *= costBias;

    // Load all + sort
    double sortFactor = (limit > 0 && limit < linkBagSize)
        ? log2(limit) : log2(linkBagSize);
    double costLoadSort = (double) linkBagSize * randRead
        + (double) linkBagSize * cpu
        + (double) linkBagSize * sortFactor * cpu;

    // Downstream edge cost: with LIMIT, only K rows traverse downstream
    // edges in BOTH strategies. Index scan produces K rows directly;
    // loadSortFromLinkBag loads all N but sorts and marks PRE_SORTED=true,
    // so OrderByStep passes through and LimitStep stops after K rows —
    // only K rows go through downstream MATCH edges. Charging both sides
    // keeps the estimates on the same scale without biasing the choice.
    if (downstreamEdgeCount > 0 && limit > 0) {
      double downstreamPerRow = downstreamEdgeCount * randRead;
      costUnionScan += k * downstreamPerRow;
      costLoadSort += k * downstreamPerRow;
    }

    return new CostEstimate(
        expectedScanLength, k, seqRead, randRead, cpu, seekCost,
        costUnionScan, costLoadSort);
  }

  /**
   * Cost of advancing a filtered ordered scan by one index entry: one amortized leaf-page read
   * plus {@link GlobalConfiguration#QUERY_INDEX_ORDERED_SCAN_CPU_FACTOR} times per-row CPU
   * (read lock, RidSet bitmap, filter lambda, key compare, advance at the default factor).
   * Shared by {@link #computeCosts}, {@link #pickMultiSourceStrategy}, and
   * {@link #entriesWorthTheLoadAlternative}.
   */
  static double scanCostPerEntry() {
    int entriesPerPage =
        GlobalConfiguration.QUERY_INDEX_ORDERED_ENTRIES_PER_PAGE
            .getValueAsInteger();
    double cpuFactor =
        GlobalConfiguration.QUERY_INDEX_ORDERED_SCAN_CPU_FACTOR
            .getValueAsDouble();
    return CostModel.seqPageReadCost() / entriesPerPage
        + cpuFactor * CostModel.perRowCpuCost();
  }

  /**
   * Cost of reading one record: a random page read plus the per-row CPU. Same per-record term
   * {@link #computeCosts} charges the load-and-sort alternative.
   */
  static double recordReadCost() {
    return CostModel.randomPageReadCost() + CostModel.perRowCpuCost();
  }

  /**
   * Index entries whose <em>index-cursor</em> cost equals reading
   * {@code recordsReadByLoadAndSort} records. Budget for a UNION RidSet scan, where each entry
   * is a cheap cursor advance + bitmap check — not a record load.
   *
   * <p>Do NOT use this for {@link MultiSourceStrategy#GLOBAL_SCAN}: that path loads every
   * entry's record, so one entry already costs about one record. Its budget is
   * {@code recordsReadByLoadAndSort} itself.
   */
  static long entriesWorthTheLoadAlternative(long recordsReadByLoadAndSort) {
    if (recordsReadByLoadAndSort <= 0) {
      return 0;
    }
    var perEntry = scanCostPerEntry();
    if (perEntry <= 0) {
      return Long.MAX_VALUE;
    }
    var entries = recordsReadByLoadAndSort * recordReadCost() / perEntry;
    return entries >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) entries;
  }

  /**
   * Picks the cheapest multi-source strategy among three options: union RidSet scan, global
   * scan, or load-all-sort. Treats {@code totalEdges} as an uncapped estimate
   * ({@code estimateCapped == false}).
   */
  static MultiSourceStrategy pickMultiSourceStrategy(
      int totalEdges, long indexSize, long limit,
      @Nullable EquiDepthHistogram histogram, boolean orderAsc) {
    return pickMultiSourceStrategy(
        totalEdges, indexSize, limit, histogram, orderAsc, false);
  }

  /**
   * Picks the cheapest multi-source strategy among three options:
   * union RidSet scan, global scan, or load-all-sort.
   *
   * <p>When {@code estimateCapped} is true, the caller hit the structural ceiling
   * (extrapolated edges clamped to {@code indexSize}). That is not evidence every index
   * entry is reachable. Under density {@code 1.0}, {@link MultiSourceStrategy#GLOBAL_SCAN}
   * would price a LIMIT-sized walk and can degrade to a near-full index scan when hits are
   * sparse in key order. Refuse the capped estimate and load from the real source LinkBags
   * instead — independent of index size. A runtime scan budget still covers plausible but
   * wrong densities that were not capped.
   *
   * @param estimateCapped {@code true} when {@code totalEdges} came from a capped estimate
   */
  static MultiSourceStrategy pickMultiSourceStrategy(
      int totalEdges, long indexSize, long limit,
      @Nullable EquiDepthHistogram histogram, boolean orderAsc,
      boolean estimateCapped) {
    if (limit > 0 && estimateCapped) {
      return MultiSourceStrategy.LOAD_ALL_SORT;
    }

    var costs = computeCosts(totalEdges, indexSize, limit, histogram, orderAsc);
    if (costs == null) {
      return MultiSourceStrategy.LOAD_ALL_SORT;
    }

    double costBias =
        GlobalConfiguration.QUERY_INDEX_ORDERED_COST_BIAS.getValueAsDouble();

    // Multi-source union: build RidSet + scan index (seq) + load k matches.
    // Extra cpu term per match vs single-source: reverse-edge lookup cost.
    // Cursor advance uses the same scanCostPerEntry() as computeCosts.
    double costUnion = totalEdges * costs.cpu
        + costs.seekCost
        + costs.expectedScanLength * scanCostPerEntry()
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
