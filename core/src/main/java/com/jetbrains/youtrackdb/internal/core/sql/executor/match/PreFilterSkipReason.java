package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

/**
 * Reason why a pre-filter was not applied during edge traversal.
 *
 * <p>Recorded by {@link EdgeTraversal} at each decision point in
 * {@code resolveWithCache()} and {@code applyPreFilter()} to provide
 * diagnostic information for PROFILE and EXPLAIN output.
 *
 * @see EdgeTraversal
 */
public enum PreFilterSkipReason {

  /** Pre-filter was applied successfully (no skip). */
  NONE,

  /** Estimated RidSet size exceeds the {@code maxRidSetSize} cap. */
  CAP_EXCEEDED,

  /**
   * {@link com.jetbrains.youtrackdb.internal.core.sql.executor.RidFilterDescriptor.IndexLookup
   * IndexLookup} selectivity exceeds the configured threshold — the index
   * matches too many records for the pre-filter to be effective.
   */
  SELECTIVITY_TOO_LOW,

  /**
   * Accumulated link bag total has not reached the break-even threshold
   * for building the RidSet (build amortization guard).
   */
  BUILD_NOT_AMORTIZED,

  /**
   * Current vertex's link bag is smaller than the minimum size for
   * pre-filter application ({@code minLinkBagSize}).
   */
  LINKBAG_TOO_SMALL,

  /**
   * {@link com.jetbrains.youtrackdb.internal.core.sql.executor.RidFilterDescriptor.EdgeRidLookup
   * EdgeRidLookup} overlap ratio exceeds the configured threshold — the
   * RidSet covers too large a fraction of the adjacency list.
   */
  OVERLAP_RATIO_TOO_HIGH,

  /**
   * Descriptor's {@code resolve()} returned {@code null} on the cold path
   * after passing all up-front guards. Causes include the runtime
   * checkpoint-abort in {@code resolveIndexToRidSet} (estimate was below
   * the cap but the iterated size grew past it), a
   * {@code RecordNotFoundException} on a reverse-edge target vertex, or
   * an empty result stream. Treated as a permanent rejection: cached so
   * subsequent vertices on the same key short-circuit instead of
   * re-attempting the build.
   */
  BUILD_FAILED,

  /**
   * Index statistics or histogram unavailable — {@code estimateSelectivity}
   * returned {@code -1}. The cost-model amortization formula
   * {@code m = estimatedSize / (loadToScanRatio · (1 − s))} cannot be
   * evaluated without a valid {@code s}, so the bounded-loss contract
   * for BUILD_EAGER / DEFERRED_WITH_NET does not hold. Cached as a
   * permanent rejection: class-level selectivity does not change during
   * a single query, so a missing statistic at the first vertex stays
   * missing for all subsequent vertices.
   */
  STATS_UNAVAILABLE
}
