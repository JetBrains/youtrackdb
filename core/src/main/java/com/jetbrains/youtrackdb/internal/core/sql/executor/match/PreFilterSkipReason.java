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
  OVERLAP_RATIO_TOO_HIGH
}
