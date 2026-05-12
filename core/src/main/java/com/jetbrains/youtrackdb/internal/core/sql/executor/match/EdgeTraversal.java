package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.internal.common.profiler.metrics.CoreMetrics;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.MetricsRegistry;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.Ratio;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.sql.executor.RidFilterDescriptor;
import com.jetbrains.youtrackdb.internal.core.sql.executor.RidSet;
import com.jetbrains.youtrackdb.internal.core.sql.executor.TraversalPreFilterHelper;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLRid;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.HashMap;
import java.util.List;
import javax.annotation.Nullable;

/**
 * A scheduled traversal of a single {@link PatternEdge} in a specific direction.
 * <p>
 * While {@link PatternEdge} records the **syntactic** direction of an edge as written in
 * the `MATCH` expression, the topological scheduler (see
 * {@code MatchExecutionPlanner#getTopologicalSortedSchedule()}) may decide to traverse the
 * edge in either direction depending on cost estimates and dependency constraints.
 * `EdgeTraversal` captures this decision:
 * <p>
 * - **{@link #out} = `true`**  → traverse from `edge.out` to `edge.in` (forward / as
 *   written).
 * - **{@link #out} = `false`** → traverse from `edge.in` to `edge.out` (reverse).
 *
 * <pre>
 *   Syntactic:     (p) ──out('Knows')──→ (f)
 *                       PatternEdge.out=p, PatternEdge.in=f
 *
 *   Scheduled forward:  EdgeTraversal.out=true   → start at p, traverse to f
 *   Scheduled reverse:  EdgeTraversal.out=false  → start at f, traverse to p
 * </pre>
 *
 * Additionally, the planner annotates each `EdgeTraversal` with the **source node's**
 * class, RID, and `WHERE` filter (the "left" constraints). These are used by
 * {@link MatchReverseEdgeTraverser} to validate the target records when traversing in
 * reverse.
 *
 * @see MatchStep
 * @see MatchEdgeTraverser
 * @see MatchReverseEdgeTraverser
 */
public class EdgeTraversal {

  /**
   * The runtime traversal direction. `true` means forward (from `edge.out` to
   * `edge.in`); `false` means reverse (from `edge.in` to `edge.out`).
   */
  protected boolean out;

  /** The pattern edge being traversed. */
  public PatternEdge edge;

  /** Schema class constraint on the source (left-hand) node, or `null` if unconstrained. */
  private String leftClass;

  /** RID constraint on the source (left-hand) node, or `null` if unconstrained. */
  private SQLRid leftRid;

  /** `WHERE` filter on the source (left-hand) node, or `null` if unconstrained. */
  private SQLWhereClause leftFilter;

  /**
   * Pre-filter descriptor for adjacency list intersection. When set, the
   * traverser resolves this descriptor at runtime to produce a {@link
   * com.jetbrains.youtrackdb.internal.core.sql.executor.RidSet} that is
   * applied to the edge traversal results, skipping vertices not in the set.
   */
  @Nullable private RidFilterDescriptor intersectionDescriptor;

  /**
   * Semi-join descriptor for back-reference hash join optimization. When set,
   * {@link BackRefHashJoinStep} replaces the normal {@link MatchStep} for this
   * edge. Mutually exclusive with {@link #intersectionDescriptor} for the same
   * back-reference — when a semi-join descriptor is attached, no
   * {@link RidFilterDescriptor.EdgeRidLookup} is created for the back-ref edge.
   */
  @Nullable private SemiJoinDescriptor semiJoinDescriptor;

  /**
   * Plan-time forecast of total neighbors this edge will process across all
   * source vertices reaching it during a single query execution. Stamped by
   * {@code MatchExecutionPlanner} from {@code sourceRows × estimateFanOut(…)}
   * and reused at runtime in {@code resolveWithCache} to choose between
   * {@link Mode#BUILD_EAGER} and {@link Mode#DEFERRED_WITH_NET}. Sentinel
   * {@code -1} means the forecast is absent (planner could not produce a
   * number — e.g. unbound source alias, non-positive {@code approximateCount}).
   *
   * <p>Bind-independent and copied by {@link #copy()} — the same schedule
   * produces the same forecast regardless of bind values, so the plan cache
   * can amortize the schedule walk.
   */
  private long forecastN = -1L;

  /**
   * Load-to-scan cost ratio used in the build amortization formula.
   * Represents how many times more expensive a random record load is compared
   * to scanning one entry in a pre-built RidSet. The value 100 is a reasonable
   * default for cold SSD storage; operators can override via
   * {@code youtrackdb.query.prefilter.loadToScanRatio}.
   */
  static final double DEFAULT_LOAD_TO_SCAN_RATIO = 100.0;

  /**
   * Cached reference to the {@link CoreMetrics#PREFILTER_EFFECTIVENESS}
   * metric. Lazily resolved on first pre-filter application. Falls back
   * to {@link Ratio#NOOP} if the MetricsRegistry is unavailable.
   * Not copied by {@link #copy()} — each query execution re-resolves.
   */
  @Nullable private Ratio cachedEffectiveness;

  /**
   * Running sum of link bag sizes across vertices for {@link
   * RidFilterDescriptor.IndexLookup} build amortization (standalone or
   * inside a {@link RidFilterDescriptor.Composite}). The accumulator
   * tracks how many total neighbors have been encountered; once the total
   * exceeds {@link #computeMinNeighborsForBuild}'s threshold, the RidSet
   * is materialized. Not copied by {@link #copy()} — each query execution
   * starts with a fresh accumulator (Java default 0L).
   */
  private long accumulatedLinkBagTotal;

  /**
   * Cached selectivity value for the {@link RidFilterDescriptor.IndexLookup}
   * component of the intersection descriptor (standalone or inside a
   * {@link RidFilterDescriptor.Composite}). {@code NaN} means not yet
   * computed; {@code < 0} (e.g. {@code -1.0}) means unknown (bypass
   * accumulator and build immediately). Not copied by {@link #copy()} —
   * each query execution re-computes on first use (the field initializer
   * resets to {@code NaN}).
   */
  private double indexLookupSelectivity = Double.NaN;

  /**
   * Amortization mode for an {@link RidFilterDescriptor.IndexLookup}-bearing
   * descriptor. Resolved from {@link #forecastN} vs the runtime-computed
   * {@code m} on the first {@code resolveWithCache} call, then memoized for
   * the rest of the execution.
   */
  enum Mode {
    /** Mode not yet decided. First {@code resolveWithCache} call computes it. */
    UNDETERMINED,
    /**
     * Forecast says build pays off ({@code forecastN > m}). Materialize on
     * the first vertex, no deferral phase. Worst-case loss when the forecast
     * over-estimates is bounded by the one-time build cost.
     */
    BUILD_EAGER,
    /**
     * Forecast says build does not pay off (or no forecast). Cache null on
     * first vertex but maintain the runtime accumulator with trigger
     * {@code T = max(2·forecastN, m)} as a safety net for the case where the
     * forecast under-estimated reality.
     */
    DEFERRED_WITH_NET
  }

  /**
   * Memoized amortization mode (see {@link Mode}). Defaults to
   * {@link Mode#UNDETERMINED}; resolved once per execution on the first
   * {@code resolveWithCache} call that encounters an IndexLookup descriptor.
   * Bind-dependent (depends on the runtime {@code estimatedSize} and
   * {@code estimateSelectivity}), so reset by {@link #copy()}.
   */
  private Mode mode = Mode.UNDETERMINED;

  // =========================================================================
  // Pre-filter observability counters — not copied by copy().
  // =========================================================================

  /**
   * Number of vertices where the pre-filter RidSet was successfully used
   * for intersection. Incremented in {@link #recordPreFilterApplied},
   * which is called from {@code applyPreFilter()} for each vertex that
   * passes both the selectivity and ratio checks.
   */
  private int preFilterAppliedCount;

  /**
   * Number of vertices where the pre-filter was skipped (not applied).
   * Complementary to {@link #preFilterAppliedCount}.
   */
  private int preFilterSkippedCount;

  /**
   * Total number of adjacency list entries probed across all vertices
   * where the pre-filter was applied. Equals the sum of link bag sizes
   * for applied vertices.
   */
  private long preFilterTotalProbed;

  /**
   * Total number of adjacency list entries filtered out by the pre-filter
   * across all vertices. Equals {@code totalProbed - totalRetained}.
   */
  private long preFilterTotalFiltered;

  /**
   * Accumulated wall-clock time (nanoseconds) spent building pre-filter
   * RidSets. Each cold-path materialization (one per distinct cache key)
   * adds its build duration via {@code +=}.
   */
  private long preFilterBuildTimeNanos;

  /**
   * Size of the materialized pre-filter RidSet (number of entries).
   * Set once at materialization time.
   */
  private int preFilterRidSetSize;

  /**
   * Last reason a pre-filter was skipped. Initialized to {@link
   * PreFilterSkipReason#NONE} to indicate no skip has occurred.
   * Updated at each decision point in {@code resolveWithCache()} and
   * {@code applyPreFilter()}.
   */
  private PreFilterSkipReason lastSkipReason = PreFilterSkipReason.NONE;

  /**
   * Fixed-capacity cache of resolved RidSets, keyed by
   * {@link RidFilterDescriptor#cacheKey}. Stops accepting new entries
   * at capacity — no eviction, no LRU bookkeeping. Allocated lazily
   * on first cache miss to avoid wasting memory on edges that have
   * no intersection descriptor.
   */
  private static final int CACHE_CAPACITY = 64;
  @Nullable private HashMap<Object, RidSet> cache;

  /**
   * Collection IDs for the target node's class constraint. When set,
   * the traverser applies a zero-I/O class filter to the link bag,
   * skipping vertices whose collection ID does not match.
   */
  @Nullable private IntSet acceptedCollectionIds;

  /**
   * When {@code true}, this edge has been consumed by a {@link ChainSemiJoin}
   * descriptor on a subsequent edge. {@link MatchExecutionPlanner#addStepsFor}
   * skips consumed edges — the {@link BackRefHashJoinStep} on the next edge
   * covers both.
   */
  private boolean consumed;

  /**
   * When this edge has a {@link ChainSemiJoin} descriptor, points to the
   * consumed predecessor edge (the {@code .outE('E')} part). Used by
   * {@link BackRefHashJoinStep} to construct the correct two-edge fallback
   * traversal when the hash table build fails at runtime.
   */
  @Nullable private EdgeTraversal consumedPredecessor;

  /**
   * @param edge the pattern edge to traverse
   * @param out  `true` for forward traversal, `false` for reverse
   */
  public EdgeTraversal(PatternEdge edge, boolean out) {
    assert MatchAssertions.validateEdgeTraversalArgs(edge);
    this.edge = edge;
    this.out = out;
  }

  public void setLeftClass(String leftClass) {
    this.leftClass = leftClass;
  }

  public void setLeftFilter(SQLWhereClause leftFilter) {
    this.leftFilter = leftFilter;
  }

  public String getLeftClass() {
    return leftClass;
  }

  public SQLRid getLeftRid() {
    return leftRid;
  }

  public void setLeftRid(SQLRid leftRid) {
    this.leftRid = leftRid;
  }

  public SQLWhereClause getLeftFilter() {
    return leftFilter;
  }

  @Nullable public RidFilterDescriptor getIntersectionDescriptor() {
    return intersectionDescriptor;
  }

  public void setIntersectionDescriptor(
      @Nullable RidFilterDescriptor intersectionDescriptor) {
    this.intersectionDescriptor = intersectionDescriptor;
  }

  /**
   * Adds a descriptor to this edge. If a descriptor is already set,
   * wraps both in a {@link RidFilterDescriptor.Composite} that intersects
   * their results at the bitmap level.
   */
  public void addIntersectionDescriptor(RidFilterDescriptor descriptor) {
    if (intersectionDescriptor == null) {
      intersectionDescriptor = descriptor;
    } else {
      intersectionDescriptor = new RidFilterDescriptor.Composite(
          List.of(intersectionDescriptor, descriptor));
    }
  }

  @Nullable public SemiJoinDescriptor getSemiJoinDescriptor() {
    return semiJoinDescriptor;
  }

  public void setSemiJoinDescriptor(@Nullable SemiJoinDescriptor semiJoinDescriptor) {
    this.semiJoinDescriptor = semiJoinDescriptor;
  }

  /**
   * Sets the plan-time forecast of total neighbors this edge will process.
   * Called by {@code MatchExecutionPlanner} after the schedule walk; pass
   * {@code -1} to mark the forecast as absent. Negative values other than
   * {@code -1} are normalized to {@code -1} (absent).
   */
  public void setForecastN(long forecastN) {
    this.forecastN = forecastN < 0 ? -1L : forecastN;
  }

  /** Returns the plan-time forecast, or {@code -1} when absent. */
  public long getForecastN() {
    return forecastN;
  }

  /** Returns the memoized amortization mode (test/PROFILE visibility). */
  Mode getMode() {
    return mode;
  }

  public boolean isConsumed() {
    return consumed;
  }

  public void setConsumed(boolean consumed) {
    this.consumed = consumed;
  }

  @Nullable public EdgeTraversal getConsumedPredecessor() {
    return consumedPredecessor;
  }

  public void setConsumedPredecessor(@Nullable EdgeTraversal consumedPredecessor) {
    this.consumedPredecessor = consumedPredecessor;
  }

  @Nullable public IntSet getAcceptedCollectionIds() {
    return acceptedCollectionIds;
  }

  public void setAcceptedCollectionIds(@Nullable IntSet acceptedCollectionIds) {
    this.acceptedCollectionIds = acceptedCollectionIds;
  }

  // =========================================================================
  // Pre-filter counter accessors (read by MatchStep.prettyPrint)
  // =========================================================================

  public int getPreFilterAppliedCount() {
    return preFilterAppliedCount;
  }

  public int getPreFilterSkippedCount() {
    return preFilterSkippedCount;
  }

  public long getPreFilterTotalProbed() {
    return preFilterTotalProbed;
  }

  public long getPreFilterTotalFiltered() {
    return preFilterTotalFiltered;
  }

  public long getPreFilterBuildTimeNanos() {
    return preFilterBuildTimeNanos;
  }

  public int getPreFilterRidSetSize() {
    return preFilterRidSetSize;
  }

  public PreFilterSkipReason getLastSkipReason() {
    return lastSkipReason;
  }

  /**
   * Returns the cached IndexLookup selectivity, or {@code NaN} if not yet
   * computed. Used by {@code MatchStep.prettyPrint()} for PROFILE output
   * to show the selectivity that was used for the threshold check.
   */
  public double getIndexLookupSelectivity() {
    return indexLookupSelectivity;
  }

  // =========================================================================
  // Metric cache accessors (package-private — used by tests to verify
  // copy() resets without reflection)
  // =========================================================================

  /** Returns the cached effectiveness metric reference, or {@code null} if not yet resolved. */
  @Nullable Ratio getCachedEffectiveness() {
    return cachedEffectiveness;
  }

  /** Package-private setter for test setup — inject without reflection. */
  void setCachedEffectiveness(@Nullable Ratio value) {
    cachedEffectiveness = value;
  }

  // =========================================================================
  // Pre-filter counter mutators (called by MatchEdgeTraverser.applyPreFilter)
  // =========================================================================

  /**
   * Records a pre-filter skip event at a specific decision point in
   * {@code applyPreFilter()}. Increments {@link #preFilterSkippedCount}
   * and updates {@link #lastSkipReason}.
   */
  void recordPreFilterSkip(PreFilterSkipReason reason) {
    lastSkipReason = reason;
    preFilterSkippedCount++;
  }

  /**
   * Records a successful pre-filter application for one vertex.
   * Increments {@link #preFilterTotalProbed} by the link bag size and
   * {@link #preFilterTotalFiltered} by the estimated number of entries
   * removed (link bag size minus the RidSet size, floored at zero).
   *
   * @param linkBagSize the number of adjacency list entries for this vertex
   * @param ridSetSize  the number of entries in the pre-filter RidSet
   */
  void recordPreFilterApplied(int linkBagSize, int ridSetSize) {
    preFilterAppliedCount++;
    preFilterTotalProbed += linkBagSize;
    preFilterTotalFiltered += Math.max(0, linkBagSize - ridSetSize);
  }

  /**
   * Resolves the intersection descriptor with lazy resolution and a
   * fixed-capacity cache. Uses a three-way decision based on a cheap
   * size estimate to avoid wasted materialization:
   *
   * <ol>
   *   <li><b>Cache hit</b> — return immediately. The caller performs
   *       a per-vertex ratio check in {@code applyPreFilter()}.</li>
   *   <li><b>Absolute cap exceeded</b> (estimate &gt; maxRidSetSize) —
   *       cache {@code null} permanently. No vertex of any link bag
   *       size would benefit from a RidSet this large.</li>
   *   <li><b>Per-vertex ratio check against estimate</b> — if this
   *       vertex's link bag is too small relative to the estimated
   *       RidSet size, return {@code null} without caching (a later
   *       vertex with a larger link bag may trigger resolution).</li>
   *   <li><b>Build amortization guard (IndexLookup only)</b> —
   *       accumulates link bag sizes across vertices and defers
   *       materialization until the total justifies the build cost.
   *       Returns {@code null} without caching when deferred.
   *       For Composites, if the IndexLookup child is REJECTED (high
   *       selectivity), falls back to
   *       {@code Composite.anyChildPassesExcluding(IndexLookup.class, …)}
   *       to let other children (e.g. EdgeRidLookup) justify the
   *       pre-filter — the just-rejected IndexLookup is deliberately
   *       excluded from this re-check. DEFER still applies to
   *       Composites (build cost is shared).</li>
   *   <li><b>First big-enough hit</b> — resolve (materialize) and
   *       cache. The cached RidSet is a pure function of descriptor
   *       parameters.</li>
   * </ol>
   *
   * @param ctx         command context
   * @param linkBagSize the forward link bag size for the current vertex
   * @return the cached or freshly built RidSet, or {@code null} if the
   *     descriptor is too large or the current vertex's link bag is
   *     too small to benefit
   */
  @Nullable public RidSet resolveWithCache(CommandContext ctx, int linkBagSize) {
    var desc = intersectionDescriptor;
    if (desc == null) {
      return null;
    }
    var key = desc.cacheKey(ctx);

    // 1. Cache hit — return immediately.
    //    Caller does per-vertex ratio check in applyPreFilter().
    if (key != null) {
      if (cache == null) {
        cache = new HashMap<>();
      }
      if (cache.containsKey(key)) {
        var cached = cache.get(key);
        if (cached == null) {
          // Previously rejected descriptor (e.g., CAP_EXCEEDED,
          // SELECTIVITY_TOO_LOW) — count each vertex that hits the
          // cached-null entry as a skip for accurate PROFILE output.
          preFilterSkippedCount++;
        }
        return cached;
      }
    }

    // 2. Cheap size estimate — no full materialization.
    //    Pass the cache key so EdgeRidLookup can reuse the pre-computed
    //    target RID instead of re-evaluating the expression.
    int estimatedSize = desc.estimatedSize(ctx, key);

    // 3. Absolute cap exceeded — safe to cache null permanently.
    //    No vertex of any link bag size would benefit from a RidSet
    //    this large (exceeds maxRidSetSize).
    if (estimatedSize > TraversalPreFilterHelper.maxRidSetSize()) {
      lastSkipReason = PreFilterSkipReason.CAP_EXCEEDED;
      preFilterSkippedCount++;
      if (key != null && cache != null && cache.size() < CACHE_CAPACITY) {
        cache.put(key, null);
      }
      return null;
    }

    // 4. Descriptor-specific selectivity check against estimate.
    //    Standalone IndexLookup: use checkIndexLookupAmortization().
    //    Composite containing IndexLookup: run the amortization check.
    //      - On REJECT (selectivity too high): ask the Composite whether
    //        any non-IndexLookup child (e.g. EdgeRidLookup for a
    //        back-reference) still justifies the pre-filter, via
    //        anyChildPassesExcluding(IndexLookup.class, …). The
    //        just-rejected IndexLookup is excluded explicitly from this
    //        re-check, to avoid implicitly relying on its threshold
    //        agreeing with the amortization threshold.
    //      - On DEFER (amortization not met): respect it — the build cost
    //        applies to the entire Composite.
    //    EdgeRidLookup / DirectRid: delegate to passesSelectivityCheck().
    RidFilterDescriptor.IndexLookup indexLookup = null;
    boolean isComposite = false;
    if (desc instanceof RidFilterDescriptor.IndexLookup il) {
      indexLookup = il;
    } else if (desc instanceof RidFilterDescriptor.Composite composite) {
      indexLookup = composite.findIndexLookup();
      isComposite = true;
    }

    if (indexLookup != null) {
      var decision = checkIndexLookupAmortization(
          indexLookup, estimatedSize, linkBagSize, key, ctx);
      if (decision == AmortizationDecision.REJECT && isComposite) {
        // Composite: the IndexLookup child's selectivity is too high,
        // but other children (e.g. EdgeRidLookup for a back-reference)
        // may still justify the pre-filter. Ask the Composite whether
        // any NON-IndexLookup child passes — using the regular
        // passesSelectivityCheck (any-child anyMatch) would re-include
        // the just-rejected IndexLookup, which is at best redundant and
        // at worst incorrect if its threshold ever diverges from the
        // amortization threshold.
        var composite = (RidFilterDescriptor.Composite) desc;
        if (estimatedSize >= 0
            && !composite.anyChildPassesExcluding(
                RidFilterDescriptor.IndexLookup.class,
                estimatedSize, linkBagSize, ctx)) {
          lastSkipReason = PreFilterSkipReason.OVERLAP_RATIO_TOO_HIGH;
          preFilterSkippedCount++;
          return null;
        }
        // At least one non-IndexLookup child passes → materialize.
      } else if (decision != AmortizationDecision.PROCEED) {
        // Standalone REJECT, or DEFER (accumulation not met yet).
        return null;
      }
      // PROCEED (or Composite fallback passed) → fall through to materialize.
    } else if (estimatedSize >= 0
        && !desc.passesSelectivityCheck(estimatedSize, linkBagSize, ctx)) {
      // EdgeRidLookup / DirectRid: delegate to the descriptor.
      lastSkipReason = PreFilterSkipReason.OVERLAP_RATIO_TOO_HIGH;
      preFilterSkippedCount++;
      return null;
    }

    // 5. First big-enough hit — resolve (materialize) and cache.
    //    Pass the cache key so EdgeRidLookup reuses the target RID.
    //    Record build time unconditionally on this cold path (T7).
    long buildStart = System.nanoTime();
    var ridSet = desc.resolve(ctx, key);
    preFilterBuildTimeNanos += System.nanoTime() - buildStart;
    if (ridSet != null) {
      // Set once: an empty RidSet (size==0) does not "claim" the slot,
      // so the next non-empty materialization overwrites it.  This is
      // intentional — reporting ridSetSize=0 in PROFILE is meaningless.
      if (preFilterRidSetSize == 0) {
        preFilterRidSetSize = ridSet.size();
      }
      lastSkipReason = PreFilterSkipReason.NONE;
    }
    if (key != null && cache != null && cache.size() < CACHE_CAPACITY) {
      cache.put(key, ridSet);
    }
    return ridSet;
  }

  /**
   * Result of the IndexLookup amortization check in
   * {@link #checkIndexLookupAmortization} and
   * {@link #evaluateIndexLookupAmortization}.
   *
   * <p>Package-private so sibling step classes in this package (e.g.
   * {@link BackRefHashJoinStep}) can reuse the same decision vocabulary
   * without depending on EdgeTraversal instance state.
   */
  enum AmortizationDecision {
    /** Selectivity too high — cache null permanently. */
    REJECT,
    /** Build not yet amortized — return null without caching. */
    DEFER,
    /**
     * Caller should materialize. Returned when the amortization threshold
     * has been met, or when selectivity is unknown ({@code < 0}) — in the
     * latter case PROCEED is optimistic, relying on downstream guards
     * ({@code maxRidSetSize} cap, per-vertex ratio for EdgeRidLookup) to
     * bound the worst case.
     */
    PROCEED
  }

  /**
   * Stateless, pure-function variant of {@link #checkIndexLookupAmortization}
   * that computes the decision from inputs only. The caller maintains the
   * accumulator and cached selectivity; no fields are mutated here.
   *
   * <p>Used by {@link BackRefHashJoinStep.buildChainHashTable} to apply
   * the same amortization logic to its direct call to {@link
   * TraversalPreFilterHelper#resolveIndexToRidSet} — the MATCH path goes
   * through {@link #checkIndexLookupAmortization} which owns the
   * EdgeTraversal-local state.
   *
   * @param estimatedSize            estimated index hits; ignored when
   *                                 {@code < 0} (unknown)
   * @param selectivity              cached class-level selectivity;
   *                                 {@code NaN} or {@code < 0} means
   *                                 unknown — see PROCEED handling below
   * @param accumulatedLinkBagTotal  running sum of link bag sizes across
   *                                 vertices/back-refs observed so far
   *                                 (caller must include current call's
   *                                 link bag before invoking)
   * @param loadToScanRatio          cost ratio of random record load vs.
   *                                 RidSet scan entry
   */
  static AmortizationDecision evaluateIndexLookupAmortization(
      int estimatedSize,
      double selectivity,
      long accumulatedLinkBagTotal,
      double loadToScanRatio) {
    // Unknown selectivity (negative sentinel) → PROCEED optimistically.
    // Rationale: REJECT would cache null permanently for the whole query,
    // too aggressive when stats may just be transiently missing; DEFER is
    // meaningless because the amortization formula degenerates to 0
    // without a valid selectivity. Other guards still bound the worst
    // case (maxRidSetSize cap in resolveWithCache, per-vertex ratio for
    // EdgeRidLookup in applyPreFilter), and IndexLookup.passesSelectivityCheck
    // already returns true for unknown selectivity — keeping these in sync.
    if (selectivity < 0) {
      return AmortizationDecision.PROCEED;
    }
    if (estimatedSize >= 0
        && selectivity
            > TraversalPreFilterHelper.indexLookupMaxSelectivity()) {
      return AmortizationDecision.REJECT;
    }
    double minNeighbors = computeMinNeighborsForBuild(
        estimatedSize, loadToScanRatio, selectivity);
    if (accumulatedLinkBagTotal < (long) Math.ceil(minNeighbors)) {
      return AmortizationDecision.DEFER;
    }
    return AmortizationDecision.PROCEED;
  }

  /**
   * Returns the load-to-scan ratio for the build amortization formula.
   * Honors the {@code youtrackdb.query.prefilter.loadToScanRatio} override
   * when set to a positive value, otherwise returns
   * {@link #DEFAULT_LOAD_TO_SCAN_RATIO}.
   */
  static double currentLoadToScanRatio() {
    double configured = TraversalPreFilterHelper.configuredLoadToScanRatio();
    return configured > 0 ? configured : DEFAULT_LOAD_TO_SCAN_RATIO;
  }

  /**
   * Checks IndexLookup selectivity threshold and build amortization guard
   * with mode-aware trigger.
   *
   * <p>On the first vertex this method decides between
   * {@link Mode#BUILD_EAGER} (materialise from neighbor 1) and
   * {@link Mode#DEFERRED_WITH_NET} (keep an accumulator with safety-net
   * trigger {@code T = max(2·forecastN, m)}). The mode is memoized on the
   * {@link EdgeTraversal} instance for the rest of the execution.
   *
   * <p>Returns {@link AmortizationDecision#REJECT} when selectivity is too
   * high (cache null permanently), {@link AmortizationDecision#DEFER} when
   * {@link Mode#DEFERRED_WITH_NET} accumulator has not yet crossed
   * {@code T} (return null without caching), or
   * {@link AmortizationDecision#PROCEED} when the caller should fall through
   * to materialise.
   */
  private AmortizationDecision checkIndexLookupAmortization(
      RidFilterDescriptor.IndexLookup indexLookup,
      int estimatedSize, int linkBagSize,
      @Nullable Object key, CommandContext ctx) {
    // Cache selectivity on first call — class-level, constant per query.
    if (Double.isNaN(indexLookupSelectivity)) {
      indexLookupSelectivity =
          indexLookup.indexDescriptor().estimateSelectivity(ctx);
    }

    // Unknown selectivity → PROCEED optimistically (existing behavior,
    // no mode decision; other guards bound the worst case).
    if (indexLookupSelectivity < 0) {
      return AmortizationDecision.PROCEED;
    }

    // Class-level selectivity too high → REJECT permanently, cache null.
    if (estimatedSize >= 0
        && indexLookupSelectivity
            > TraversalPreFilterHelper.indexLookupMaxSelectivity()) {
      lastSkipReason = PreFilterSkipReason.SELECTIVITY_TOO_LOW;
      preFilterSkippedCount++;
      if (key != null && cache != null && cache.size() < CACHE_CAPACITY) {
        cache.put(key, null);
      }
      return AmortizationDecision.REJECT;
    }

    // Compute the break-even m for both the mode decision and the
    // DEFERRED_WITH_NET trigger. Cheap — depends on estimatedSize and the
    // cached selectivity.
    double loadToScanRatio = currentLoadToScanRatio();
    double m = computeMinNeighborsForBuild(
        estimatedSize, loadToScanRatio, indexLookupSelectivity);

    // Decide mode on first call. forecastN > ceil(m) → BUILD_EAGER, else
    // DEFERRED_WITH_NET. Absent forecast (-1) falls through to DEFERRED_WITH_NET.
    if (mode == Mode.UNDETERMINED) {
      mode = forecastN > (long) Math.ceil(m)
          ? Mode.BUILD_EAGER
          : Mode.DEFERRED_WITH_NET;
    }

    // BUILD_EAGER: materialise on first vertex, no accumulator. Worst-case
    // loss from a forecast over-estimate is bounded by the one-time build B.
    if (mode == Mode.BUILD_EAGER) {
      return AmortizationDecision.PROCEED;
    }

    // DEFERRED_WITH_NET: accumulate and check the adaptive safety-net
    // trigger T = max(2·forecastN, m). Absent forecast collapses to f = 0,
    // so T = m (floor). Triggering near T bounds the excess vs no-prefilter
    // at ~B per edge.
    accumulatedLinkBagTotal += linkBagSize;
    long f = forecastN < 0 ? 0L : forecastN;
    double trigger = Math.max(2.0 * f, m);
    if (accumulatedLinkBagTotal < (long) Math.ceil(trigger)) {
      lastSkipReason = PreFilterSkipReason.BUILD_NOT_AMORTIZED;
      preFilterSkippedCount++;
      assert key == null || cache == null || !cache.containsKey(key)
          : "deferred build must not cache null";
      return AmortizationDecision.DEFER;
    }
    return AmortizationDecision.PROCEED;
  }

  /**
   * Computes the minimum accumulated neighbor count (sum of link bag sizes
   * across vertices) before building an {@code IndexLookup} RidSet is
   * cost-effective.
   *
   * <p>The formula models the break-even point where the total scan savings
   * from the pre-filter equal the one-time build cost:
   * {@code estimatedSize / (loadToScanRatio * (1 - selectivity))}.
   *
   * <p>Boundary handling:
   * <ul>
   *   <li>{@code estimatedSize <= 0} → {@code 0.0} (build immediately —
   *       trivially small or unknown size; the materialised RidSet will
   *       be empty or near-empty, so the threshold is moot)</li>
   *   <li>{@code selectivity < 0} → {@code 0.0} (unknown selectivity:
   *       build immediately. The formula degenerates without a valid
   *       selectivity, and rejecting permanently would be too aggressive
   *       — other guards still bound the worst case. Note that callers
   *       that go through {@link #evaluateIndexLookupAmortization}
   *       short-circuit on this case before reaching this method)</li>
   *   <li>{@code selectivity >= 1.0} → {@link Double#MAX_VALUE} (never
   *       build — no filtering benefit when all records match)</li>
   *   <li>Normal case → {@code estimatedSize / (loadToScanRatio *
   *       (1 - selectivity))}</li>
   * </ul>
   *
   * @param estimatedSize    estimated RidSet size (index hits)
   * @param loadToScanRatio  cost ratio of random load vs. RidSet scan
   * @param selectivity      fraction of records matching (0.0–1.0)
   * @return minimum accumulated link bag total to justify building
   */
  static double computeMinNeighborsForBuild(
      int estimatedSize, double loadToScanRatio, double selectivity) {
    assert loadToScanRatio > 0 && Double.isFinite(loadToScanRatio)
        : "loadToScanRatio must be positive and finite: " + loadToScanRatio;
    if (estimatedSize <= 0) {
      return 0.0;
    }
    if (selectivity < 0) {
      return 0.0;
    }
    if (selectivity >= 1.0) {
      return Double.MAX_VALUE;
    }
    return estimatedSize / (loadToScanRatio * (1.0 - selectivity));
  }

  /**
   * Resolves the {@link CoreMetrics#PREFILTER_EFFECTIVENESS} metric,
   * caching the reference for reuse across vertices. Falls back to
   * {@link Ratio#NOOP} when the MetricsRegistry is unavailable.
   */
  Ratio resolveEffectivenessMetric() {
    if (cachedEffectiveness != null) {
      return cachedEffectiveness;
    }
    MetricsRegistry registry =
        YouTrackDBEnginesManager.instance().getMetricsRegistry();
    cachedEffectiveness = registry != null
        ? registry.globalMetric(CoreMetrics.PREFILTER_EFFECTIVENESS)
        : Ratio.NOOP;
    return cachedEffectiveness;
  }

  @Override
  public String toString() {
    return edge.toString();
  }

  /** Returns a shallow copy with deep-copied mutable fields (filter, RID). */
  public EdgeTraversal copy() {
    var copy = new EdgeTraversal(edge, out);

    if (leftClass != null) {
      copy.leftClass = leftClass;
    }
    if (leftFilter != null) {
      copy.leftFilter = leftFilter.copy();
    }
    if (leftRid != null) {
      copy.leftRid = leftRid.copy();
    }
    copy.intersectionDescriptor = intersectionDescriptor;
    copy.semiJoinDescriptor = semiJoinDescriptor;
    copy.acceptedCollectionIds = acceptedCollectionIds;
    copy.consumed = consumed;
    copy.consumedPredecessor = consumedPredecessor;
    // forecastN is bind-independent (depends only on schema/schedule), so the
    // plan cache reuses it across executions.
    copy.forecastN = forecastN;
    // Cache, accumulatedLinkBagTotal, indexLookupSelectivity, mode, metric
    // references, and pre-filter counters are intentionally not copied —
    // stale data from a previous execution must not leak into a new plan
    // instance. The constructor and field initializers reset them to their
    // correct initial values.
    return copy;
  }
}
