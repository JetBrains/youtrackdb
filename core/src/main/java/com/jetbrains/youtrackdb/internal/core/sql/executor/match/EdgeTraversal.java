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
      if (key != null && cache.size() < CACHE_CAPACITY) {
        cache.put(key, null);
      }
      return null;
    }

    // 4. Descriptor-specific selectivity check against estimate.
    //    For EdgeRidLookup: per-vertex overlap ratio (DON'T cache null —
    //    a later vertex with a larger link bag may benefit).
    //    For IndexLookup: class-level selectivity (vertex-independent —
    //    cache null if it fails since the result won't change).
    //    For DirectRid: always passes (never reaches this branch in
    //    practice since estimatedSize is 1).
    if (estimatedSize >= 0
        && !desc.passesSelectivityCheck(estimatedSize, linkBagSize, ctx)) {
      if (desc instanceof RidFilterDescriptor.IndexLookup
          && key != null && cache.size() < CACHE_CAPACITY) {
        // IndexLookup selectivity is class-level (constant) — safe to
        // cache the rejection permanently.
        cache.put(key, null);
      }
      return null;
    }

    // 5. First big-enough hit — resolve (materialize) and cache.
    //    Pass the cache key so EdgeRidLookup reuses the target RID.
    var ridSet = desc.resolve(ctx, key);
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
    // Cache is intentionally not copied — stale data from a previous
    // execution must not leak into a new plan instance.
    return copy;
  }
}
