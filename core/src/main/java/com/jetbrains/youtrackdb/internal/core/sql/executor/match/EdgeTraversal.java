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
import java.util.ArrayList;
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
   * {@code MatchExecutionPlanner} from {@code sourceRows × estimateMethodFanOut(…)}
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
   * Effective sample size at the root of the alias-lineage that feeds this
   * edge's source. Determines whether the central-limit theorem (CLT) lets
   * us trust {@link #forecastN} for the BUILD_EAGER decision.
   *
   * <p>{@code forecastN = sourceRows × fanOut_mean} treats the source as a
   * sample from the fan-out distribution. Its relative error scales with
   * {@code CV_fanOut / sqrt(N)} where {@code N} is the effective sample
   * size of the source. For LDBC SNB heavy-tail distributions (e.g.
   * messages-per-person), {@code CV_fanOut} is large, so small {@code N}
   * produces unbounded relative error and BUILD_EAGER commits the build
   * cost on an essentially-random forecast.
   *
   * <p>Stamped by {@link MatchExecutionPlanner#stampEdgeMetadata}:
   * <ul>
   *   <li>For an edge whose source alias is a root (its row count comes
   *       directly from {@code estimatedRootEntries}, not from upstream
   *       propagation), {@code rootSourceRows = sourceRows}.</li>
   *   <li>For an edge whose source alias was populated via propagation
   *       through an earlier edge, {@code rootSourceRows} inherits the
   *       upstream edge's {@code rootSourceRows} — the CLT sample-size
   *       at the original root of the lineage, not the propagated
   *       expected count along the way.</li>
   * </ul>
   *
   * <p>Bind-independent and propagated by {@link #copy()} — depends only on
   * schedule structure and per-class statistics, not on per-execution bind
   * values.
   *
   * <p>Sentinel {@code -1} means absent (planner could not compute, e.g.
   * unbound source alias). BUILD_EAGER requires a known value at or above
   * {@link #MIN_FOR_CLT}.
   */
  private long rootSourceRows = -1L;

  /**
   * Load-to-scan cost ratio used in the build amortization formula.
   * Represents how many times more expensive a random record load is compared
   * to scanning one entry in a pre-built RidSet (in-memory membership probe).
   * The value 100 is a reasonable default for cold SSD storage; operators
   * can override via {@code youtrackdb.query.prefilter.loadToScanRatio}.
   */
  static final double DEFAULT_LOAD_TO_SCAN_RATIO = 100.0;

  /**
   * Minimum {@link #rootSourceRows} required before BUILD_EAGER trusts the
   * plan-time {@link #forecastN}. Below this the CLT does not yet give a
   * reliable mean for skewed distributions, so the build-vs-defer decision
   * falls back to {@link Mode#DEFERRED_WITH_NET}, which inspects observed
   * per-vertex link bag sizes at runtime instead.
   *
   * <p>{@code 30} is the classical CLT rule-of-thumb threshold. For
   * heavy-tail distributions (LDBC SNB) {@code 30} samples is borderline,
   * but in combination with the {@link Mode#DEFERRED_WITH_NET} safety net
   * {@code T = max(2·forecastN, m)} the cost of being wrong is bounded.
   */
  static final long MIN_FOR_CLT = 30L;

  /**
   * Whether the owning query is in PROFILE mode. Controls whether the
   * one-shot {@link System#nanoTime()} pair around {@code desc.resolve()}
   * in {@link #materializeAndCache} fires. Cost when off: zero (the pair
   * is gated behind this flag). Cost when on: ~130 ns per RidSet build,
   * trivially amortised over the build wallclock (typically tens of
   * milliseconds for IndexLookup ranges).
   *
   * <p>The captured value, {@link #preFilterBuildTimeNanos}, is read only
   * by {@code MatchStep}'s PROFILE output. Off-profile queries never use
   * the value, so the measurement is pure overhead in that path — we skip
   * it.
   *
   * <p>Stamped at plan-build time by {@code MatchExecutionPlanner} after
   * the schedule is established. Bind-independent (depends on the
   * statement form, not on per-execution parameters), so {@link #copy()}
   * propagates it to per-execution copies.
   */
  private boolean profilingEnabled;

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
   * Sampled in-list selectivity (fraction of link-bag entries passing the
   * IndexLookup predicate), measured on the first vertex that reaches
   * {@link #checkIndexLookupAmortization}. Bind-dependent — per execution
   * a fresh sample is taken (the constructor leaves it at {@code NaN}).
   *
   * <p>When set ({@code ≥ 0}) it overrides the class-level
   * {@link #indexLookupSelectivity} in the {@code m} formula on the
   * DEFERRED_WITH_NET branch where the CLT confidence gate would
   * otherwise leave us trusting forecast-based heuristics over real data.
   *
   * <p>{@code NaN} — not sampled yet. {@code -1.0} — sampling attempted but
   * yielded no usable observations (empty bag, all loads failed); the code
   * then falls back to class-level selectivity. {@code [0, 1]} — valid
   * sample, used to recalibrate {@code m}.
   */
  private double inListSelectivity = Double.NaN;

  /**
   * Number of link-bag entries to sample when calibrating the in-list
   * selectivity. K=30 is the classical CLT rule-of-thumb sample size; the
   * binomial standard error of a hit-rate estimate at K=30 is roughly 9pp,
   * comfortably below the IC2/IC4 separation (~70% vs ~3%).
   */
  static final int IN_LIST_SAMPLE_SIZE = 30;

  /**
   * Multiplier applied to {@code m} on the calibrated-m path to correct the
   * implicit underestimation of build cost {@code B} in
   * {@link #computeMinNeighborsForBuild}. The base formula assumes
   * {@code B = estimatedSize × scan_cost}, which models build cost as one
   * bitmap-probe per entry. In reality the build performs B-tree traversal,
   * key deserialization, and RoaringBitmap insertion per entry —
   * roughly {@code 50-100×} more expensive than a simple probe. Without
   * this correction, the cost-model break-even fires far earlier than the
   * real volume at which the build pays off, leading to IC2-shaped
   * regressions.
   *
   * <p>Applied only on the calibrated path (sampled in-list selectivity
   * + CLT-fail) so existing CLT-pass behavior is unchanged. The base
   * formula stays the historical reference point for the BUILD_EAGER
   * decision when CLT confidence is established.
   */
  static final double BUILD_PER_ENTRY_MULTIPLIER = 10.0;

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
   * Skip reason recorded alongside each cached {@code null} entry in
   * {@link #cache}. Restored onto {@link #lastSkipReason} whenever a
   * cached-null hit occurs, so that an interleaved external
   * {@code recordPreFilterSkip(LINKBAG_TOO_SMALL)} call (from
   * {@code MatchEdgeTraverser.applyPreFilter} on a small-link-bag vertex)
   * cannot overwrite the original rejection cause between V1's caching and
   * V2's cached-null hit. Kept parallel to {@link #cache} rather than
   * merged because the existing cache contract (null = rejected,
   * non-null = resolved RidSet) is used in several places.
   */
  @Nullable private HashMap<Object, PreFilterSkipReason> cachedSkipReasons;

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
   * their results at the bitmap level. Composites are kept flat — if
   * either side is already a Composite, its children are spliced in rather
   * than nested, so {@link RidFilterDescriptor.Composite#findIndexLookup}
   * and other child scans always see every leaf in one pass.
   */
  public void addIntersectionDescriptor(RidFilterDescriptor descriptor) {
    if (intersectionDescriptor == null) {
      intersectionDescriptor = descriptor;
      return;
    }
    var flattened = new ArrayList<RidFilterDescriptor>();
    appendFlattened(flattened, intersectionDescriptor);
    appendFlattened(flattened, descriptor);
    intersectionDescriptor = new RidFilterDescriptor.Composite(flattened);
  }

  private static void appendFlattened(
      List<RidFilterDescriptor> out, RidFilterDescriptor desc) {
    if (desc instanceof RidFilterDescriptor.Composite c) {
      for (var child : c.descriptors()) {
        appendFlattened(out, child);
      }
    } else {
      out.add(desc);
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

  /**
   * Sets the effective root-lineage sample size for this edge. Called by
   * {@code MatchExecutionPlanner.stampEdgeMetadata}. Pass {@code -1} when
   * the planner cannot determine a sample size (the BUILD_EAGER gate then
   * routes to DEFERRED_WITH_NET, which is the safe fallback). Negative
   * non-sentinel values are normalised to {@code -1}.
   */
  public void setRootSourceRows(long rootSourceRows) {
    this.rootSourceRows = rootSourceRows < 0 ? -1L : rootSourceRows;
  }

  /** Returns the root-lineage sample size, or {@code -1} when absent. */
  public long getRootSourceRows() {
    return rootSourceRows;
  }

  /**
   * Marks this edge as running inside a {@code PROFILE} query. Enables the
   * build-time {@code nanoTime} pair in {@link #materializeAndCache}.
   * Off by default — standard query execution pays no measurement cost.
   */
  public void setProfilingEnabled(boolean profilingEnabled) {
    this.profilingEnabled = profilingEnabled;
  }

  /** Returns the memoized amortization mode (test/PROFILE visibility). */
  Mode getMode() {
    return mode;
  }

  /** Returns {@code true} once the in-list selectivity sample has been taken. */
  boolean isInListSampled() {
    return !Double.isNaN(inListSelectivity);
  }

  /** Returns the sampled in-list selectivity, or {@code NaN} when not sampled. */
  double getInListSelectivity() {
    return inListSelectivity;
  }

  /**
   * Records the in-list selectivity sample taken from the first vertex's
   * link bag. {@code -1.0} means sampling produced no usable result
   * (callers fall back to the class-level selectivity).
   */
  void setInListSelectivity(double sample) {
    this.inListSelectivity = sample;
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
   * Stores {@code reason} as the rejection cause for cache entry
   * {@code key}, so that future cached-null hits on this key can restore
   * {@link #lastSkipReason} accurately. The caller must already have
   * inserted {@code null} into {@link #cache} for the same key — the two
   * maps are kept in lock-step.
   */
  private void rememberCachedSkipReason(
      Object key, PreFilterSkipReason reason) {
    if (cachedSkipReasons == null) {
      cachedSkipReasons = new HashMap<>();
    }
    cachedSkipReasons.put(key, reason);
  }

  /**
   * Records that a pre-filter was applied for one vertex. Increments
   * {@link #preFilterAppliedCount} only — the probed/filtered totals are
   * updated lazily by {@link #recordPreFilterTraversalStats} once the
   * iterator that consumes the pre-filtered link bag is exhausted or
   * closed, so the values reflect the true intersection of link bag and
   * RidSet rather than the broken {@code linkBagSize − ridSet.size()}
   * estimate this method used to apply eagerly.
   */
  void recordPreFilterApplied() {
    preFilterAppliedCount++;
  }

  /**
   * Records the true probed/filtered counts for one vertex's iteration,
   * called from the wrapper {@link Ratio} returned by
   * {@link #resolveEffectivenessMetric()} once the iterator wrapping the
   * pre-filtered link bag is exhausted or closed.
   *
   * <p>{@code probed} is the number of link-bag entries that survived the
   * class filter and were tested against the RidSet; {@code filtered} is
   * the subset rejected by that test ({@code filtered ≤ probed}). The
   * iterator guards against zero-probe flushes so this method is only
   * reached with {@code probed > 0}.
   *
   * @param filtered count of link-bag entries rejected by the RidSet
   * @param probed   count of link-bag entries tested against the RidSet
   */
  void recordPreFilterTraversalStats(long filtered, long probed) {
    preFilterTotalProbed += probed;
    preFilterTotalFiltered += filtered;
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
    var hit = lookupCache(key);
    if (hit != null) {
      return hit.ridSet();
    }

    // 2. Cheap size estimate — no full materialization.
    //    Pass the cache key so EdgeRidLookup can reuse the pre-computed
    //    target RID instead of re-evaluating the expression.
    int estimatedSize = desc.estimatedSize(ctx, key);

    // 3. Absolute cap exceeded — safe to cache null permanently.
    if (rejectIfCapExceeded(estimatedSize, key)) {
      return null;
    }

    // 4. Descriptor-specific selectivity check against estimate.
    if (!evaluateDescriptorSelectivity(desc, estimatedSize, linkBagSize, key, ctx)) {
      return null;
    }

    // 5. First big-enough hit — resolve (materialize) and cache.
    return materializeAndCache(desc, key, ctx);
  }

  /**
   * Outcome of a cache lookup. {@code present == true} signals the caller
   * should short-circuit {@code resolveWithCache} and return {@link #ridSet}
   * (which may be {@code null} when the previous attempt at this key was
   * rejected). Used by {@link #lookupCache} so the caller can distinguish
   * "no entry" (compute) from "entry == null" (cached rejection).
   */
  private record CacheHit(@Nullable RidSet ridSet) {
  }

  /**
   * Returns a {@link CacheHit} on cache hit (success or cached null), or
   * {@code null} when there is no entry yet. Lazily allocates the cache map
   * on first miss so edges without an intersection descriptor pay nothing.
   *
   * <p>On a cached-null hit, records a skip reason: prefers the stored
   * reason from {@link #cachedSkipReasons} (so the original rejection
   * cause survives an interleaved external skip), falling back to a
   * bare counter bump.
   */
  @Nullable private CacheHit lookupCache(@Nullable Object key) {
    if (key == null) {
      return null;
    }
    if (cache == null) {
      cache = new HashMap<>();
    }
    if (!cache.containsKey(key)) {
      return null;
    }
    var cached = cache.get(key);
    if (cached == null) {
      var reason =
          cachedSkipReasons != null ? cachedSkipReasons.get(key) : null;
      if (reason != null) {
        recordPreFilterSkip(reason);
      } else {
        preFilterSkippedCount++;
      }
    }
    return new CacheHit(cached);
  }

  /**
   * Records {@link PreFilterSkipReason#CAP_EXCEEDED} and caches {@code null}
   * permanently when {@code estimatedSize} exceeds the absolute RidSet cap.
   * Returns {@code true} when the cap was exceeded (caller must return
   * {@code null} from {@code resolveWithCache}).
   *
   * <p>No vertex of any link bag size would benefit from a RidSet larger
   * than the cap, so caching the null is safe and saves the repeated
   * estimate call on later vertices with the same key.
   */
  private boolean rejectIfCapExceeded(int estimatedSize, @Nullable Object key) {
    if (estimatedSize <= TraversalPreFilterHelper.maxRidSetSize()) {
      return false;
    }
    recordPreFilterSkip(PreFilterSkipReason.CAP_EXCEEDED);
    if (key != null && cache != null && canCache(key)) {
      cache.put(key, null);
      rememberCachedSkipReason(key, PreFilterSkipReason.CAP_EXCEEDED);
    }
    return true;
  }

  /**
   * Routes the selectivity decision to the right strategy for {@code desc}:
   *
   * <ul>
   *   <li>Standalone {@code IndexLookup}: {@link #checkIndexLookupAmortization}.
   *       REJECT (selectivity too high or stats unknown) → return false.
   *       DEFER → return false (no cache; later vertex may trigger).
   *       PROCEED → return true.</li>
   *   <li>{@code Composite} containing an {@code IndexLookup} child:
   *       run the amortization check on the IndexLookup child. On REJECT,
   *       ask the Composite whether any NON-IndexLookup child still
   *       justifies the pre-filter (via {@code anyChildPassesExcluding}).
   *       The just-rejected IndexLookup is deliberately excluded from this
   *       re-check to avoid implicitly relying on its threshold agreeing
   *       with the amortization threshold.</li>
   *   <li>Any other descriptor ({@code EdgeRidLookup} / {@code DirectRid}
   *       / {@code Composite} without IndexLookup): delegate to
   *       {@link RidFilterDescriptor#passesSelectivityCheck}.</li>
   * </ul>
   *
   * @return {@code true} if the caller should fall through to materialise,
   *         {@code false} if the descriptor was rejected/deferred (and the
   *         appropriate skip reason already recorded).
   */
  private boolean evaluateDescriptorSelectivity(
      RidFilterDescriptor desc, int estimatedSize, int linkBagSize,
      @Nullable Object key, CommandContext ctx) {
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
        // Composite rescue: the IndexLookup child failed, but a sibling
        // (e.g. EdgeRidLookup back-reference) may still justify the build.
        var composite = (RidFilterDescriptor.Composite) desc;
        if (estimatedSize >= 0
            && !composite.anyChildPassesExcluding(
                RidFilterDescriptor.IndexLookup.class,
                estimatedSize, linkBagSize, ctx)) {
          recordPreFilterSkip(PreFilterSkipReason.OVERLAP_RATIO_TOO_HIGH);
          return false;
        }
        // Non-IndexLookup child passes → fall through to materialise.
        return true;
      }
      return decision == AmortizationDecision.PROCEED;
    }

    if (estimatedSize >= 0
        && !desc.passesSelectivityCheck(estimatedSize, linkBagSize, ctx)) {
      recordPreFilterSkip(PreFilterSkipReason.OVERLAP_RATIO_TOO_HIGH);
      return false;
    }
    return true;
  }

  /**
   * Cold-path materialisation: resolves the descriptor, records build time,
   * captures the RidSet size for PROFILE output, and caches the result
   * (success or BUILD_FAILED null) under {@code key}.
   *
   * <p>{@code canCache} permits overwriting an existing entry, so a
   * Composite REJECT-then-rescue path (where the IndexLookup amortisation
   * guard inserted a null at near-capacity) can replace that null with the
   * freshly-materialised RidSet rather than silently dropping it.
   */
  @Nullable private RidSet materializeAndCache(
      RidFilterDescriptor desc, @Nullable Object key, CommandContext ctx) {
    // Measure build wallclock only when the owning query asked for PROFILE.
    // {@code preFilterBuildTimeNanos} is consumed exclusively by the PROFILE
    // pretty-printer in {@code MatchStep}, so off-profile measurements would
    // be pure overhead. The pair adds ~130 ns/build; trivial in absolute
    // terms but unnecessary on the default path.
    long buildStart = profilingEnabled ? System.nanoTime() : 0L;
    var ridSet = desc.resolve(ctx, key);
    if (profilingEnabled) {
      preFilterBuildTimeNanos += System.nanoTime() - buildStart;
    }
    if (ridSet != null) {
      // Set once: an empty RidSet (size==0) does not "claim" the slot,
      // so the next non-empty materialization overwrites it. Reporting
      // ridSetSize=0 in PROFILE is meaningless.
      if (preFilterRidSetSize == 0) {
        preFilterRidSetSize = ridSet.size();
      }
      lastSkipReason = PreFilterSkipReason.NONE;
    } else {
      // resolve() returned null after passing all up-front guards — e.g.
      // resolveIndexToRidSet aborted on its runtime checkpoint guard, or
      // resolveReverseEdgeLookup hit RecordNotFoundException on the
      // target vertex. Cache the failure under BUILD_FAILED so a later
      // cached-null hit restores a meaningful cause onto lastSkipReason.
      recordPreFilterSkip(PreFilterSkipReason.BUILD_FAILED);
    }
    if (key != null && cache != null && canCache(key)) {
      cache.put(key, ridSet);
      if (ridSet != null) {
        // Drop any stale rejection reason from a prior REJECT before this
        // rescue path materialised a real RidSet at the same key.
        if (cachedSkipReasons != null) {
          cachedSkipReasons.remove(key);
        }
      } else {
        rememberCachedSkipReason(key, PreFilterSkipReason.BUILD_FAILED);
      }
    }
    return ridSet;
  }

  /**
   * Returns {@code true} if {@link #cache} can accept a put for
   * {@code key} without growing beyond {@link #CACHE_CAPACITY}. Allows
   * overwriting an existing entry (a {@code HashMap.put} on a key already
   * present does not increase the map's size), which is required by the
   * Composite REJECT-then-rescue path: an earlier REJECT may have inserted
   * a {@code null} for this key at near-capacity, and the subsequent
   * successful materialization must be able to replace that sentinel.
   *
   * <p>Caller must already have null-checked {@link #cache}.
   */
  private boolean canCache(Object key) {
    return cache.size() < CACHE_CAPACITY || cache.containsKey(key);
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
     * Amortization threshold met — caller should materialize.
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
   *                                 unknown — short-circuits to REJECT
   *                                 (see {@link PreFilterSkipReason#STATS_UNAVAILABLE})
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
    // Unknown selectivity → REJECT. See
    // {@link PreFilterSkipReason#STATS_UNAVAILABLE} for the bounded-loss
    // rationale. This stateless variant has no place to record a skip
    // reason; the caller (e.g. BackRefHashJoinStep) attaches its own.
    // NaN check is explicit because {@code NaN < 0} is {@code false} in
    // Java's IEEE-754 semantics, so a {@code Double.NaN} sentinel from an
    // index whose statistics produce a not-a-number would slip past the
    // negative-sentinel guard and reach the downstream arithmetic. No
    // current caller passes NaN, but the cost of the extra branch is
    // negligible and keeps the contract aligned with the javadoc.
    if (Double.isNaN(selectivity) || selectivity < 0) {
      return AmortizationDecision.REJECT;
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

    // Unknown selectivity → REJECT permanently, record STATS_UNAVAILABLE.
    // See {@link PreFilterSkipReason#STATS_UNAVAILABLE} for the bounded-loss
    // rationale. Caching the null with its reason lets every subsequent
    // vertex restore the diagnostic via cachedSkipReasons. REJECT (rather
    // than DEFER) also lets the Composite rescue path in resolveWithCache
    // try a non-IndexLookup child (e.g. EdgeRidLookup) that has its own
    // per-vertex bound and does not depend on histogram.
    if (indexLookupSelectivity < 0) {
      recordPreFilterSkip(PreFilterSkipReason.STATS_UNAVAILABLE);
      if (key != null && cache != null && canCache(key)) {
        cache.put(key, null);
        rememberCachedSkipReason(key, PreFilterSkipReason.STATS_UNAVAILABLE);
      }
      return AmortizationDecision.REJECT;
    }

    // Class-level selectivity too high → REJECT permanently, cache null.
    if (estimatedSize >= 0
        && indexLookupSelectivity
            > TraversalPreFilterHelper.indexLookupMaxSelectivity()) {
      recordPreFilterSkip(PreFilterSkipReason.SELECTIVITY_TOO_LOW);
      if (key != null && cache != null && canCache(key)) {
        cache.put(key, null);
        rememberCachedSkipReason(key, PreFilterSkipReason.SELECTIVITY_TOO_LOW);
      }
      return AmortizationDecision.REJECT;
    }

    // Compute the break-even m for both the mode decision and the
    // DEFERRED_WITH_NET trigger. Cheap — depends on estimatedSize and the
    // cached selectivity.
    //
    // Variant B (in-list calibration): when the CLT confidence gate fails
    // (rootSourceRows < MIN_FOR_CLT) and a per-edge sample was taken
    // upstream (MatchEdgeTraverser.applyPreFilter), use the measured
    // in-list selectivity in place of the class-level value. The class-level
    // figure averages across the whole class and is misleading when
    // adjacency lists are biased subsets (LDBC IC2: friends' link bags are
    // recent-biased — in-list ~70% vs class-level ~1%). Calibrating m
    // against the actual per-bag mix produces a break-even that matches
    // runtime cost-balance.
    double loadToScanRatio = currentLoadToScanRatio();
    boolean useCalibratedM = rootSourceRows >= 0 && rootSourceRows < MIN_FOR_CLT
        && !Double.isNaN(inListSelectivity) && inListSelectivity >= 0;
    double effectiveSelectivity = useCalibratedM
        ? inListSelectivity : indexLookupSelectivity;
    double m = computeMinNeighborsForBuild(
        estimatedSize, loadToScanRatio, effectiveSelectivity);
    if (useCalibratedM) {
      // Variant B+ (B-fix): correct the implicit B = estimatedSize ×
      // scan_cost underestimation. Real build_per_entry is roughly
      // 50-100× the bitmap-probe cost (B-tree descent + key
      // deserialization + bitmap insertion). Apply only on the
      // calibrated path so CLT-pass behavior stays at the historical
      // formula — the cost-model has been tuned for that regime over
      // multiple LDBC benchmark cycles.
      m *= BUILD_PER_ENTRY_MULTIPLIER;
    }

    // Decide mode on first call. BUILD_EAGER requires both:
    //   1. rootSourceRows >= MIN_FOR_CLT — CLT confidence in forecastN
    //   2. forecastN > ceil(m) — the cost-model break-even
    //
    // The first condition gates against forecast unreliability. When the
    // root-lineage sample size is small (RID-bound source, n=1; or a tiny
    // filter narrowing) the forecast is essentially {@code mean × const}
    // with full mean-variance exposure. For skewed fan-out distributions
    // (LDBC SNB messages-per-person is power-law) this produces an
    // essentially-random forecast — BUILD_EAGER on such a forecast commits
    // the one-time build cost without statistical justification.
    //
    // The second condition is the cost-model itself: forecast must exceed
    // the break-even between build cost and per-entry scan savings.
    //
    // Absent forecast (-1) or absent rootSourceRows (-1) fall through to
    // DEFERRED_WITH_NET, which inspects observed per-vertex link bag at
    // runtime instead of trusting the plan.
    if (mode == Mode.UNDETERMINED) {
      boolean hasStatisticalConfidence = rootSourceRows >= MIN_FOR_CLT;
      mode = (hasStatisticalConfidence && forecastN > (long) Math.ceil(m))
          ? Mode.BUILD_EAGER
          : Mode.DEFERRED_WITH_NET;
    }

    // BUILD_EAGER: materialise on first vertex, no accumulator. Worst-case
    // loss from a forecast over-estimate is bounded by the one-time build B.
    if (mode == Mode.BUILD_EAGER) {
      return AmortizationDecision.PROCEED;
    }

    // DEFERRED_WITH_NET: accumulate and check the adaptive safety-net
    // trigger.
    //
    // When the in-list sample calibrated m above (Variant B), the trigger
    // collapses to T = m — the cost-model break-even is now trustworthy
    // per-query, so the `2·forecastN` belt-and-braces floor is dropped.
    // Otherwise the historic trigger T = max(2·forecastN, m) keeps the
    // safety net for cases where neither forecast nor m can be trusted.
    accumulatedLinkBagTotal += linkBagSize;
    double trigger;
    if (useCalibratedM) {
      trigger = m;
    } else {
      long f = forecastN < 0 ? 0L : forecastN;
      trigger = Math.max(2.0 * f, m);
    }
    if (accumulatedLinkBagTotal < (long) Math.ceil(trigger)) {
      recordPreFilterSkip(PreFilterSkipReason.BUILD_NOT_AMORTIZED);
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
   *
   * <p>{@code m = estimatedSize / (loadToScanRatio · (1 − s))}
   *
   * <p>This is a pure cost-model break-even. It is reused unchanged by
   * both decision sites in {@link #checkIndexLookupAmortization}: the
   * BUILD_EAGER gate ({@code forecastN > ceil(m)}) and the
   * {@code DEFERRED_WITH_NET} safety-net trigger
   * ({@code T = max(2·forecastN, m)}).
   *
   * <p>Boundary handling:
   * <ul>
   *   <li>{@code estimatedSize <= 0} → {@code 0.0} (build immediately —
   *       trivially small or unknown size; the materialised RidSet will
   *       be empty or near-empty, so the threshold is moot)</li>
   *   <li>{@code selectivity < 0} → {@link Double#MAX_VALUE} (unknown
   *       selectivity: never build. The formula degenerates without a
   *       valid {@code s} — we cannot bound the build cost {@code B},
   *       which the design's bounded-loss contract requires before
   *       committing to BUILD_EAGER or computing the safety-net trigger
   *       {@code T = max(2·forecast, m)}. Callers above this method
   *       (the stateful {@link #checkIndexLookupAmortization} and the
   *       stateless {@link #evaluateIndexLookupAmortization}) also
   *       short-circuit on this case for diagnostic reasons — this
   *       return value is the defensive default if either ever forgets
   *       to.)</li>
   *   <li>{@code selectivity >= 1.0} → {@link Double#MAX_VALUE} (never
   *       build — no filtering benefit when all records match)</li>
   *   <li>Normal case → {@code estimatedSize / (loadToScanRatio ·
   *       (1 - selectivity))}</li>
   * </ul>
   *
   * @param estimatedSize    estimated RidSet size (index hits)
   * @param loadToScanRatio  cost ratio of random load vs. build scan
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
      return Double.MAX_VALUE;
    }
    if (selectivity >= 1.0) {
      return Double.MAX_VALUE;
    }
    return estimatedSize / (loadToScanRatio * (1.0 - selectivity));
  }

  /**
   * Resolves the effectiveness sink used by {@code applyPreFilter} and the
   * iterators it wraps. The returned {@link Ratio} fans out each
   * {@code record(filtered, probed)} call to two sinks:
   *
   * <ol>
   *   <li>the global {@link CoreMetrics#PREFILTER_EFFECTIVENESS} metric in
   *       the {@link MetricsRegistry} (or {@link Ratio#NOOP} when the
   *       registry is unavailable);</li>
   *   <li>this traversal's per-query {@link #preFilterTotalProbed} and
   *       {@link #preFilterTotalFiltered} counters, surfaced in PROFILE
   *       output via the {@code MatchStep} pretty-printer.</li>
   * </ol>
   *
   * <p>Cached for reuse across vertices so the wrapper is allocated at most
   * once per traversal; the per-vertex hot path only performs a single
   * delegated {@code record(...)} call followed by two {@code +=} updates.
   */
  Ratio resolveEffectivenessMetric() {
    if (cachedEffectiveness != null) {
      return cachedEffectiveness;
    }
    MetricsRegistry registry =
        YouTrackDBEnginesManager.instance().getMetricsRegistry();
    final Ratio globalDelegate = registry != null
        ? registry.globalMetric(CoreMetrics.PREFILTER_EFFECTIVENESS)
        : Ratio.NOOP;
    cachedEffectiveness = new Ratio() {
      @Override
      public void record(long filtered, long probed) {
        globalDelegate.record(filtered, probed);
        recordPreFilterTraversalStats(filtered, probed);
      }

      @Override
      public double getRatio() {
        return globalDelegate.getRatio();
      }
    };
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
    // forecastN and rootSourceRows are bind-independent (depend only on
    // schema/schedule + per-class statistics), so the plan cache reuses
    // them across executions.
    copy.forecastN = forecastN;
    copy.rootSourceRows = rootSourceRows;
    copy.profilingEnabled = profilingEnabled;
    // Cache, cachedSkipReasons, accumulatedLinkBagTotal,
    // indexLookupSelectivity, inListSelectivity, mode, metric references,
    // and pre-filter counters are intentionally not copied — stale data
    // from a previous execution must not leak into a new plan instance.
    // The constructor and field initializers reset them to their correct
    // initial values.
    return copy;
  }
}
