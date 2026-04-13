package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

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
   * Default load-to-scan cost ratio used in the build amortization formula.
   * Represents how many times more expensive a random record load is compared
   * to scanning one entry in a pre-built RidSet. The value 100 is a reasonable
   * default for cold SSD storage. Track 5 replaces this with live metrics
   * from {@code MetricsRegistry}.
   */
  static final double DEFAULT_LOAD_TO_SCAN_RATIO = 100.0;

  /**
   * Running sum of link bag sizes across vertices for {@link
   * RidFilterDescriptor.IndexLookup} build amortization. The accumulator
   * tracks how many total neighbors have been encountered; once the total
   * exceeds {@link #computeMinNeighborsForBuild}'s threshold, the RidSet
   * is materialized. Not copied by {@link #copy()} — each query execution
   * starts with a fresh accumulator (Java default 0L).
   */
  private long accumulatedLinkBagTotal;

  /**
   * Cached selectivity value for the {@link RidFilterDescriptor.IndexLookup}
   * descriptor. {@code NaN} means not yet computed; {@code < 0} (e.g.
   * {@code -1.0}) means unknown (bypass accumulator and build immediately).
   * Not copied by {@link #copy()} — each query execution re-computes on
   * first use (the field initializer resets to {@code NaN}).
   */
  private double indexLookupSelectivity = Double.NaN;

  // =========================================================================
  // Pre-filter observability counters — not copied by copy().
  // =========================================================================

  /**
   * Number of vertices where the pre-filter RidSet was applied
   * (materialized and used for intersection).
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
   *       Returns {@code null} without caching when deferred.</li>
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
        return cache.get(key);
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
      if (key != null && cache.size() < CACHE_CAPACITY) {
        cache.put(key, null);
      }
      return null;
    }

    // 4. Descriptor-specific selectivity check against estimate.
    //    For IndexLookup: handled together with the build amortization
    //    guard below (step 4b) to avoid redundant estimateSelectivity()
    //    calls — the selectivity is cached once and reused for both the
    //    threshold check and the amortization formula.
    //    For EdgeRidLookup: per-vertex overlap ratio (DON'T cache null —
    //    a later vertex with a larger link bag may benefit).
    //    For DirectRid: always passes (never reaches this branch in
    //    practice since estimatedSize is 1).
    if (desc instanceof RidFilterDescriptor.IndexLookup indexLookup) {
      // 4a. Cache selectivity on first call — class-level, constant per query.
      if (Double.isNaN(indexLookupSelectivity)) {
        indexLookupSelectivity =
            indexLookup.indexDescriptor().estimateSelectivity(ctx);
      }
      // 4b. Selectivity threshold check (replaces passesSelectivityCheck
      //     for IndexLookup to reuse the cached value).
      if (estimatedSize >= 0
          && indexLookupSelectivity >= 0
          && indexLookupSelectivity
              > TraversalPreFilterHelper.indexLookupMaxSelectivity()) {
        // Class-level selectivity too high — cache null permanently.
        lastSkipReason = PreFilterSkipReason.SELECTIVITY_TOO_LOW;
        preFilterSkippedCount++;
        if (key != null && cache.size() < CACHE_CAPACITY) {
          cache.put(key, null);
        }
        return null;
      }
      // 4c. Build amortization guard: accumulate link bag sizes across
      //     vertices and defer materialization until the total justifies
      //     the build cost.
      if (indexLookupSelectivity >= 0) {
        accumulatedLinkBagTotal += linkBagSize;
        double minNeighbors = computeMinNeighborsForBuild(
            estimatedSize, DEFAULT_LOAD_TO_SCAN_RATIO,
            indexLookupSelectivity);
        if (accumulatedLinkBagTotal < (long) Math.ceil(minNeighbors)) {
          // Threshold not yet met — return null WITHOUT caching.
          // A later vertex may push the accumulated total over.
          lastSkipReason = PreFilterSkipReason.BUILD_NOT_AMORTIZED;
          preFilterSkippedCount++;
          assert key == null || !cache.containsKey(key)
              : "deferred build must not cache null";
          return null;
        }
      }
      // Threshold met (or unknown selectivity) — fall through to materialize.
    } else if (estimatedSize >= 0
        && !desc.passesSelectivityCheck(estimatedSize, linkBagSize, ctx)) {
      // EdgeRidLookup / DirectRid / Composite: delegate to the descriptor.
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
      if (preFilterRidSetSize == 0) {
        preFilterRidSetSize = ridSet.size();
      }
      preFilterAppliedCount++;
      lastSkipReason = PreFilterSkipReason.NONE;
    }
    if (key != null && cache.size() < CACHE_CAPACITY) {
      cache.put(key, ridSet);
    }
    return ridSet;
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
   *       trivially small or unknown size)</li>
   *   <li>{@code selectivity < 0} → {@code 0.0} (unknown selectivity —
   *       build immediately to be conservative, per review finding T1)</li>
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
    // Cache, accumulatedLinkBagTotal, indexLookupSelectivity, and
    // pre-filter counters are intentionally not copied — stale data
    // from a previous execution must not leak into a new plan instance.
    // The constructor and field initializers reset them to their correct
    // initial values.
    return copy;
  }
}
