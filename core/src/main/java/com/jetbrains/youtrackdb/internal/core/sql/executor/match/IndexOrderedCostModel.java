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
 * <p>A multi-source edge estimate may be clamped to {@code indexSize}. That clamp is not proof
 * every index entry is reachable, and under density {@code 1.0} {@link MultiSourceStrategy#GLOBAL_SCAN}
 * prices a LIMIT-sized walk. An earlier revision refused every capped estimate up front; that
 * also blocked true dense top-N shapes where GLOBAL wins. Capped estimates now go through the
 * same cost comparison as uncapped ones. When membership is sparse in key order, the runtime
 * scan budget on GLOBAL abandons the scan after roughly one entry per source edge.
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

  /**
   * Single-source prices in ordered-index-entry units. The load price never applies predicate
   * selectivity: even a selective filter must load every member of the source LinkBag.
   */
  record EqualityCosts(double orderedScan, double equalityFetch, double linkBagLoad) {
  }

  /**
   * Prices the candidate-side membership method before loading candidates. Every candidate costs
   * one equality-index entry, a record read and an estimated reverse-LinkBag walk. Unknown
   * cardinality disables equality fetch and preserves the existing ordered-scan estimate.
   *
   * @param filteredLinks source links in the target class, or the full bag size if not cheap to count
   * @param targetRecords class population used to turn equality cardinality into selectivity
   */
  static EqualityCosts equalityCosts(
      int linkBagSize, int filteredLinks, long indexSize, long targetRecords,
      long rowTarget, long equalityMatches, long memoryLimit,
      @Nullable EquiDepthHistogram histogram, boolean orderAsc) {
    double perEntry = scanCostPerEntry();
    double sortFactor = rowTarget > 0 && rowTarget < linkBagSize
        ? log2(rowTarget) : log2(linkBagSize);
    double load = (linkBagSize * recordReadCost()
        + linkBagSize * sortFactor * CostModel.perRowCpuCost()) / perEntry;
    var baseline = computeCosts(
        filteredLinks, indexSize, rowTarget, histogram, orderAsc);
    double scan = Double.POSITIVE_INFINITY;
    if (baseline != null) {
      double selectivity = equalityMatches < 0 || targetRecords <= 0
          ? 1.0 : Math.min(1.0, (double) equalityMatches / targetRecords);
      double length = selectivity == 0 ? indexSize
          : Math.min(indexSize, baseline.expectedScanLength() / selectivity);
      // Apply the scan cutoff after selectivity correction and the index-size cap.
      if (length <= GlobalConfiguration.QUERY_INDEX_ORDERED_MAX_SCAN.getValueAsLong()) {
        // Membership precedes the filter. Selectivity increases, never discounts, record loads.
        double records = selectivity == 0 ? linkBagSize
            : Math.min(linkBagSize, baseline.k() / selectivity);
        scan = (linkBagSize * CostModel.perRowCpuCost() + CostModel.indexSeekCost()
            + length * perEntry + records * CostModel.randomPageReadCost())
            * GlobalConfiguration.QUERY_INDEX_ORDERED_COST_BIAS.getValueAsDouble() / perEntry;
      }
    }
    double equality = Double.POSITIVE_INFINITY;
    if (equalityMatches >= 0 && equalityMatches <= memoryLimit && targetRecords > 0
        && hasValidScanCpuFactor()) {
      double reverseEdgesPerCandidate = Math.max(1.0, (double) linkBagSize / targetRecords);
      // Local ordering retains all candidates for downstream rejection and secondary keys.
      // Price a full sort, even when the final OrderByStep can use a bounded heap.
      double equalitySortFactor = log2(equalityMatches);
      equality = indexSeekEntryUnits()
          + equalityMatches * (1.0 + recordReadEntryUnits()
              + reverseEdgesPerCandidate * reverseEdgeEntryUnits())
          + equalityMatches * equalitySortFactor * CostModel.perRowCpuCost() / perEntry;
    }
    return new EqualityCosts(scan, equality, load);
  }

  /** Record loads and cursor advances have different costs in the shared work account. */
  static double recordReadEntryUnits() {
    return recordReadCost() / scanCostPerEntry();
  }

  /** Each link visited on the candidate's reverse LinkBag incurs a CPU charge. */
  static double reverseEdgeEntryUnits() {
    return CostModel.perRowCpuCost() / scanCostPerEntry();
  }

  /** A point-lookup seek spends work even when its equality cursor returns no entries. */
  static double indexSeekEntryUnits() {
    return CostModel.indexSeekCost() / scanCostPerEntry();
  }

  /** Paths are execution-local. LOAD is terminal and does not spend speculative work. */
  enum EqualityPath {
    ORDERED, EQUALITY, LOAD
  }

  /**
   * One speculative-work account for one execution, denominated in ordered-index entries.
   * The terminal LinkBag load is outside the account, so all failed attempts together cost at
   * most one load alternative. Callers must finish speculative reads before emitting any row.
   */
  static final class WorkAccount {
    private double remaining;
    private double attemptRemaining;
    private boolean orderedTried;
    private boolean equalityTried;
    private boolean terminal;
    private EqualityPath chosen;

    WorkAccount(int linkBagSize) {
      remaining = entriesWorthTheLoadAlternative(linkBagSize);
    }

    /**
     * Chooses the cheapest untried path using current prices. The caller raises underestimated
     * cardinalities before calling again. Ties prefer ORDERED, then EQUALITY, then LOAD.
     */
    EqualityPath choose(EqualityCosts costs) {
      if (terminal) {
        return EqualityPath.LOAD;
      }
      double ordered = orderedTried ? Double.POSITIVE_INFINITY : costs.orderedScan();
      double equality = equalityTried ? Double.POSITIVE_INFINITY : costs.equalityFetch();
      if (remaining > 0 && ordered <= equality && ordered < costs.linkBagLoad()) {
        orderedTried = true;
        chosen = EqualityPath.ORDERED;
      } else if (remaining > 0 && equality < costs.linkBagLoad()) {
        equalityTried = true;
        chosen = EqualityPath.EQUALITY;
      } else {
        terminal = true;
        chosen = EqualityPath.LOAD;
      }
      attemptRemaining = Double.POSITIVE_INFINITY;
      attemptRemaining = allowance(costs);
      return chosen;
    }

    /**
     * Caps the active attempt by the cheapest untried alternative and the remaining account.
     * The active path cannot cap itself. Repricing cannot replenish work already spent.
     */
    double allowance(EqualityCosts costs) {
      if (chosen == null || terminal) {
        return 0;
      }
      double cap = costs.linkBagLoad();
      if (!orderedTried) {
        cap = Math.min(cap, costs.orderedScan());
      }
      if (!equalityTried) {
        cap = Math.min(cap, costs.equalityFetch());
      }
      return Math.max(0, Math.min(attemptRemaining, Math.min(remaining, cap)));
    }

    /** Charges before the read. A refused read leaves the balance unchanged. */
    boolean spend(double entryUnits) {
      if (terminal || chosen == null || !Double.isFinite(entryUnits) || entryUnits <= 0
          || entryUnits > Math.min(remaining, attemptRemaining)) {
        return false;
      }
      remaining -= entryUnits;
      attemptRemaining -= entryUnits;
      return true;
    }

    /**
     * Converts the account to a candidate-record limit, not a cursor-entry limit. Candidate edge
     * reads still need individual charges while walking the reverse LinkBag without copying it.
     */
    long candidateReadLimit(EqualityCosts costs, long memoryLimit) {
      double perCandidate = 1.0 + recordReadEntryUnits();
      return Math.max(0, Math.min(memoryLimit, (long) (allowance(costs) / perCandidate)));
    }
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
    if (linkBagSize < minLinkBag || indexSize <= 0 || !hasValidScanCpuFactor()) {
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
  static boolean hasValidScanCpuFactor() {
    var cpuFactor =
        GlobalConfiguration.QUERY_INDEX_ORDERED_SCAN_CPU_FACTOR
            .getValueAsDouble();
    return Double.isFinite(cpuFactor) && cpuFactor > 0;
  }

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
   * <p>A non-positive or non-finite scan CPU factor produces zero. A valid, very large factor can
   * also produce zero when no complete index entry is worth its cost. Callers must test validity
   * separately when they need to distinguish invalid configuration from an uneconomic scan.
   *
   * <p>Do NOT use this for {@link MultiSourceStrategy#GLOBAL_SCAN}: that path loads every
   * entry's record, so one entry already costs about one record. Its budget is
   * {@code recordsReadByLoadAndSort} itself.
   */
  static long entriesWorthTheLoadAlternative(long recordsReadByLoadAndSort) {
    if (recordsReadByLoadAndSort <= 0) {
      return 0;
    }
    if (!hasValidScanCpuFactor()) {
      return 0;
    }
    var perEntry = scanCostPerEntry();
    if (perEntry <= 0) {
      return 0;
    }
    var entries = recordsReadByLoadAndSort * recordReadCost() / perEntry;
    return entries >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) entries;
  }

  /**
   * Picks the cheapest multi-source strategy among three options:
   * union RidSet scan, global scan, or load-all-sort.
   *
   * <p>Delegates to {@link #pickMultiSourceStrategy(int, long, long, EquiDepthHistogram, boolean,
   * boolean)} with {@code estimateCapped=false}.
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
   * instead. A runtime scan budget still covers plausible but wrong densities that were not
   * capped.
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
    if (!hasValidScanCpuFactor()) {
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
