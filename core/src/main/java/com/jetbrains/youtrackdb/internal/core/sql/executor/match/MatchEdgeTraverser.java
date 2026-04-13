package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.internal.common.profiler.metrics.CoreMetrics;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.MetricsRegistry;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.Ratio;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.record.impl.PreFilterableLinkBagIterable;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.RidFilterDescriptor;
import com.jetbrains.youtrackdb.internal.core.sql.executor.RidSet;
import com.jetbrains.youtrackdb.internal.core.sql.executor.TraversalPreFilterHelper;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchPathItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLRid;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base class for all MATCH edge traversers; default behavior is **forward** traversal.
 * <p>
 * Implements {@link ExecutionStream} directly, with built-in null-skipping: the
 * {@link #computeNext} method may return {@code null} to signal a rejected candidate
 * (e.g. consistency check failure), and {@link #hasNext} will transparently skip
 * such nulls, only exposing valid results to the consumer. After each result is consumed
 * via {@link #next}, the context variable {@code $matched} is updated.
 *
 * <pre>
 *                       MatchEdgeTraverser  (forward, default)
 *                      /        |           \           \
 *   MatchReverse    MatchField   MatchMulti   OptionalMatch
 *   EdgeTraverser   Traverser   EdgeTraverser EdgeTraverser
 * </pre>
 *
 * A `MatchEdgeTraverser` is instantiated for each upstream result row by
 * {@link MatchStep#createTraverser}. It:
 * <p>
 * 1. Extracts the starting record from the upstream row using the source alias.
 * 2. Executes the traversal method (e.g. `out()`, `in()`, `both()`) to find neighbor
 *    records.
 * 3. Applies the target node's filter, class constraint, and RID constraint.
 * 4. For each matching neighbor, builds a new result row that copies all previously
 *    matched aliases and adds the new alias → record mapping.
 * <p>
 * ### WHILE / recursive traversals
 * <p>
 * When the edge's filter includes a `WHILE` condition or a `maxDepth`, the traverser
 * performs **recursive** expansion (see {@link #executeTraversal}): it evaluates the
 * starting point at depth 0, then traverses one level deeper on each iteration,
 * accumulating all intermediate results. Metadata `$depth` and `$matchPath` are
 * stored on each result for later access.
 *
 * <pre>
 *   depth=0: Alice (startingPoint)
 *            ├─ depth=1: Bob
 *            │   ├─ depth=2: Carol
 *            │   └─ depth=2: Dave
 *            └─ depth=1: Eve
 *                └─ depth=2: Frank
 * </pre>
 *
 * ### Consistency check
 * <p>
 * If the target alias was already bound in a previous traversal (i.e., the same alias
 * appears in two different edges), the traverser performs an **equality check**: it
 * returns `null` (which is filtered out) if the newly traversed record differs from
 * the previously bound value, effectively enforcing a join condition.
 * <p>
 * ### Context variables
 * <p>
 * The traversal uses three context variables with distinct roles:
 * <p>
 * | Variable         | Set by                          | Purpose                                         |
 * |------------------|---------------------------------|-------------------------------------------------|
 * | `$matched`       | {@link #next}                    | Current result row; used by downstream WHERE     |
 * |                  |                                 | clauses to reference previously matched aliases  |
 * |                  |                                 | via {@code $matched.<alias>}.                    |
 * | `$currentMatch`  | {@link #executeTraversal}        | The candidate record being evaluated in a filter |
 * |                  |                                 | or WHILE condition. Scoped to a single filter    |
 * |                  |                                 | evaluation and restored afterwards.              |
 * | `$current`       | {@link #traversePatternEdge}     | The starting-point record, temporarily set so    |
 * |                  |                                 | that the method implementation (e.g. `out()`)    |
 * |                  |                                 | can reference it. Restored after the call.       |
 *
 * @see MatchReverseEdgeTraverser
 * @see MatchFieldTraverser
 * @see MatchMultiEdgeTraverser
 * @see OptionalMatchEdgeTraverser
 */
public class MatchEdgeTraverser implements ExecutionStream {

  private static final Logger logger =
      LoggerFactory.getLogger(MatchEdgeTraverser.class);

  /** The upstream result row containing all previously matched aliases. */
  protected Result sourceRecord;

  /** The scheduled edge traversal (direction + constraints). May be `null` in sub-traversals. */
  protected EdgeTraversal edge;

  /** The AST path item describing the traversal method, filter, and WHILE clause. */
  protected SQLMatchPathItem item;

  /**
   * Pre-filter RIDs resolved from the {@link RidFilterDescriptor} attached to the
   * current edge. Set by {@link #executeTraversal} before calling
   * {@link #traversePatternEdge} so subclasses can access it.
   */
  @Nullable protected Set<RID> currentPreFilterRids;

  /** Lazily initialized stream of traversal results. */
  protected ExecutionStream downstream;

  /** Buffered next non-null result. */
  private Result bufferedResult;

  /** True when the downstream stream is exhausted and no buffered result remains. */
  private boolean exhausted;

  /**
   * Constructs a traverser from a scheduled edge traversal (normal use case).
   *
   * @param lastUpstreamRecord the upstream result row
   * @param edge               the edge traversal (contains direction and constraints)
   */
  public MatchEdgeTraverser(Result lastUpstreamRecord, EdgeTraversal edge) {
    assert MatchAssertions.checkNotNull(edge, "edge");
    assert MatchAssertions.checkNotNull(edge.edge, "pattern edge");
    assert MatchAssertions.checkNotNull(edge.edge.item, "path item");
    this.sourceRecord = lastUpstreamRecord;
    this.edge = edge;
    this.item = edge.edge.item;
  }

  /**
   * Constructs a traverser from a raw path item (used in sub-traversals, e.g.
   * by {@link MatchMultiEdgeTraverser} for WHILE condition evaluation).
   *
   * @param lastUpstreamRecord the upstream result row
   * @param item               the path item to traverse
   */
  public MatchEdgeTraverser(Result lastUpstreamRecord, SQLMatchPathItem item) {
    assert MatchAssertions.checkNotNull(item, "path item");
    this.sourceRecord = lastUpstreamRecord;
    this.item = item;
  }

  /**
   * Returns {@code true} if there is a next non-null result. Internally advances
   * the downstream stream, skipping any {@code null} results (rejected candidates),
   * until a valid result is found or the stream is exhausted.
   */
  @Override
  public boolean hasNext(CommandContext ctx) {
    if (bufferedResult != null) {
      return true;
    }
    if (exhausted) {
      return false;
    }
    init(ctx);
    while (downstream.hasNext(ctx)) {
      var result = computeNext(ctx);
      if (result != null) {
        bufferedResult = result;
        return true;
      }
    }
    exhausted = true;
    return false;
  }

  /**
   * Returns the next non-null result and updates the {@code $matched} context variable
   * so downstream WHERE clauses can reference previously matched aliases.
   */
  @Override
  public Result next(CommandContext ctx) {
    if (!hasNext(ctx)) {
      throw new IllegalStateException();
    }
    var result = bufferedResult;
    bufferedResult = null;
    ctx.setSystemVariable(CommandContext.VAR_MATCHED, result);
    return result;
  }

  @Override
  public void close(CommandContext ctx) {
    if (downstream != null) {
      downstream.close(ctx);
    }
  }

  /**
   * Produces the next result row, or {@code null} if the traversed record conflicts with
   * a previously bound value for the same alias (consistency check).
   * <p>
   * Each returned row is a copy of the upstream row augmented with the new alias
   * mapping. If the edge's filter defines a {@code depthAlias} or {@code pathAlias}, the
   * corresponding metadata ({@code $depth}, {@code $matchPath}) is also stored as a
   * property.
   * <p>
   * Subclasses (e.g. {@link OptionalMatchEdgeTraverser}) override this method to
   * implement different merging semantics.
   */
  @Nullable protected Result computeNext(CommandContext ctx) {
    var endPointAlias = getEndpointAlias();
    var nextR = downstream.next(ctx);
    var session = ctx.getDatabaseSession();

    // Consistency check (join condition):
    //
    //   upstream row: {p: Person#1, f: Person#2}   (alias "f" already bound)
    //   edge target alias: "f"
    //   traversal yields: Person#3
    //
    //   prevValue = row["f"] = Person#2
    //   nextValue = Person#3
    //   Person#2 != Person#3  -->  return null (row rejected)
    //   Person#2 == Person#2  -->  pass through (join satisfied)
    //
    // This enforces that when the same alias appears in two different edges,
    // both edges must resolve to the same record.
    var prevValue = ResultInternal.toResult(sourceRecord.getProperty(endPointAlias), session);
    if (prevValue != null && !Objects.equals(nextR, prevValue)) {
      return null;
    }

    // Build the output row: layer the new alias on top of the upstream row
    var result = new MatchResultRow(session, sourceRecord, endPointAlias, nextR);

    // Propagate WHILE-traversal metadata if the user declared depth/path aliases
    if (edge.edge.item.getFilter().getDepthAlias() != null) {
      result.setProperty(edge.edge.item.getFilter().getDepthAlias(),
          ((ResultInternal) nextR).getMetadata("$depth"));
    }
    if (edge.edge.item.getFilter().getPathAlias() != null) {
      result.setProperty(
          edge.edge.item.getFilter().getPathAlias(),
          ((ResultInternal) nextR).getMetadata("$matchPath"));
    }
    return result;
  }

  /** Wraps a raw database record into a {@link ResultInternal}. */
  protected static Object toResult(DatabaseSessionEmbedded db, Identifiable nextElement) {
    return new ResultInternal(db, nextElement);
  }

  /**
   * Returns the alias of the **source** (starting) node for this traversal.
   * In forward mode this is `edge.out`; overridden by
   * {@link MatchReverseEdgeTraverser} to return `edge.in`.
   */
  protected String getStartingPointAlias() {
    return this.edge.edge.out.alias;
  }

  /**
   * Returns the alias of the **target** (endpoint) node for this traversal.
   * Defaults to the filter's alias from the path item; falls back to `edge.in`.
   */
  protected String getEndpointAlias() {
    if (this.item != null) {
      return this.item.getFilter().getAlias();
    }
    return this.edge.edge.in.alias;
  }

  /**
   * Lazily initializes the {@link #downstream} stream by extracting the starting
   * record from the upstream row and executing the traversal.
   */
  protected void init(CommandContext ctx) {
    if (downstream == null) {
      var startingElem = sourceRecord.getProperty(getStartingPointAlias());
      if (!(startingElem instanceof Result)) {
        startingElem = ResultInternal.toResultInternal(startingElem, ctx.getDatabaseSession());
      }

      downstream =
          executeTraversal(ctx, this.item, (Result) startingElem, 0, null, new RidSet());
    }
  }

  /**
   * Core traversal logic shared by all edge traverser subclasses.
   * <p>
   * There are two modes of operation depending on whether the edge defines a
   * `WHILE` condition and/or a `maxDepth`:
   * <p>
   * ### Simple (non-recursive) mode
   * When neither `WHILE` nor `maxDepth` is specified, the method performs a **single-hop**
   * traversal: it calls {@link #traversePatternEdge} to get immediate neighbors, then
   * filters them by class, RID, and WHERE clause. The starting point itself is **not**
   * included in the results.
   * <p>
   * ### Recursive (WHILE / maxDepth) mode
   * When `WHILE` or `maxDepth` is present, the method performs a **recursive DFS**:
   * - **Depth 0**: the starting point is evaluated and, if it passes the filters, is
   *   included in the results with `$depth = 0`.
   * - **Depth 1..N**: for each visited record, neighbors are expanded one level deeper.
   *   Expansion continues as long as `depth < maxDepth` (if set) and the `WHILE`
   *   condition evaluates to `true` on the current record.
   * - Each result carries `$depth` and `$matchPath` metadata, which can be exposed via
   *   `depthAlias` and `pathAlias` in the MATCH filter.
   *
   * <pre>
   * ┌─────────────────────────────────────────────────────────────────┐
   * │                    executeTraversal()                          │
   * │                                                                │
   * │  WHILE/maxDepth set?                                           │
   * │    NO (simple):                                                │
   * │      startingPoint ──traversePatternEdge()──→ neighbors        │
   * │      filter each by (class, RID, WHERE)                        │
   * │      return matching neighbors only                            │
   * │                                                                │
   * │    YES (recursive):                                            │
   * │      depth=0: evaluate startingPoint itself                    │
   * │               if passes filters → add to results ($depth=0)    │
   * │      depth=1..N: for each visited record:                      │
   * │               if WHILE holds AND depth &lt; maxDepth:          │
   * │                 traversePatternEdge() → neighbors              │
   * │                 for each neighbor:                              │
   * │                   recursive call(depth+1, path ++ neighbor)    │
   * │      return all accumulated results                            │
   * └─────────────────────────────────────────────────────────────────┘
   * </pre>
   *
   * @param iCommandContext the command context
   * @param item           the path item describing the traversal
   * @param startingPoint  the record to traverse from
   * @param depth          current recursion depth (0 at the start)
   * @param pathToHere     immutable cons-cell path of records visited so far (for
   *                       `$matchPath`), or `null` at the start. Appending is O(1);
   *                       materialization to a list is deferred to when pathAlias is read.
   * @param visited        mutable bitmap set of RIDs already emitted; used to deduplicate
   *                       vertices reachable via multiple paths in diamond/cyclic graphs
   * @return a stream of matching result records
   */
  protected ExecutionStream executeTraversal(
      CommandContext iCommandContext,
      SQLMatchPathItem item,
      Result startingPoint,
      int depth,
      @Nullable PathNode pathToHere,
      RidSet visited) {
    SQLWhereClause filter = null;
    SQLWhereClause whileCondition = null;
    Integer maxDepth = null;
    String className = null;
    SQLRid targetRid = null;

    if (item.getFilter() != null) {
      filter = getTargetFilter(item);
      whileCondition = item.getFilter().getWhileCondition();
      maxDepth = item.getFilter().getMaxDepth();
      className = targetClassName(item, iCommandContext);
      targetRid = targetRid(item, iCommandContext);
    }

    if (whileCondition == null && maxDepth == null) {
      // ---- Simple (single-hop) mode ----
      // The starting point is NOT included; only immediate neighbors that pass the
      // filter are returned.

      // Pre-filter is resolved lazily inside traversePatternEdge() after
      // the raw traversal result is available, so the actual link bag size
      // can drive the adaptive abort decision.
      this.currentPreFilterRids = null;

      var queryResult = traversePatternEdge(startingPoint, iCommandContext);

      // Skip FilterExecutionStream when no filter criteria exist
      if (filter == null && className == null && targetRid == null) {
        return queryResult;
      }

      final var theFilter = filter;
      final var theClassName = className;
      final var theTargetRid = targetRid;
      return queryResult.filter(
          (next, ctx) -> filter(
              iCommandContext, theFilter, theClassName, theTargetRid, next,
              ctx));
    } else {
      // ---- Recursive (WHILE / maxDepth) mode ----
      // Pull-based LazyRecursiveTraversalStream: yields results on demand so
      // downstream LIMIT/short-circuit can stop expansion without materializing
      // the whole sub-tree. Uses a parallel-array DFS stack so per-vertex cost
      // is O(1) amortized allocation — cheaper than both the old eager
      // ArrayList path (no result drain) and the older boxed-Frame lazy path.
      //
      // Dedup strategy: when no pathAlias is declared, a RidSet tracks emitted
      // vertices so diamond/cyclic graphs don't produce duplicates. When a
      // pathAlias IS declared the user is asking for distinct *paths*, so each
      // path to a vertex is a legitimate separate result and we skip dedup.
      assert item.getFilter() != null : "filter guaranteed non-null in recursive branch";
      var hasPathAlias = item.getFilter().getPathAlias() != null;
      var dedupVisited = hasPathAlias ? null : visited;

      return new LazyRecursiveTraversalStream(
          this, iCommandContext, startingPoint, depth, pathToHere,
          filter, whileCondition, maxDepth, className, targetRid,
          hasPathAlias, dedupVisited);
    }
  }

  /**
   * Applies the combined filter (WHERE + class + RID) to a single traversal result.
   * Temporarily sets the `$currentMatch` context variable so that the filter can
   * reference the current candidate via `$currentMatch`. Returns the result if it
   * passes, or `null` to signal rejection to the stream's filter operator.
   */
  @Nullable private Result filter(
      CommandContext iCommandContext,
      final SQLWhereClause theFilter,
      final String theClassName,
      final SQLRid theTargetRid,
      Result next,
      CommandContext ctx) {
    var previousMatch = ctx.getSystemVariable(CommandContext.VAR_CURRENT_MATCH);
    var matched = (ResultInternal) ctx.getSystemVariable(CommandContext.VAR_MATCHED);
    if (matched != null) {
      matched.setProperty(
          getStartingPointAlias(), sourceRecord.getProperty(getStartingPointAlias()));
    }
    iCommandContext.setSystemVariable(CommandContext.VAR_CURRENT_MATCH, next);
    if (matchesFilters(iCommandContext, theFilter, next)
        && matchesClass(iCommandContext, theClassName, next)
        && matchesRid(iCommandContext, theTargetRid, next)) {
      ctx.setSystemVariable(CommandContext.VAR_CURRENT_MATCH, previousMatch);
      return next;
    } else {
      ctx.setSystemVariable(CommandContext.VAR_CURRENT_MATCH, previousMatch);
      return null;
    }
  }

  /** Returns the `WHERE` filter for the **target** node. Overridden by reverse traversers. */
  protected SQLWhereClause getTargetFilter(SQLMatchPathItem item) {
    return item.getFilter().getFilter();
  }

  /** Returns the class constraint for the **target** node. Overridden by reverse traversers. */
  protected String targetClassName(SQLMatchPathItem item, CommandContext iCommandContext) {
    return item.getFilter().getClassName(iCommandContext);
  }

  /** Returns the RID constraint for the **target** node. Overridden by reverse traversers. */
  protected SQLRid targetRid(SQLMatchPathItem item, CommandContext iCommandContext) {
    return item.getFilter().getRid(iCommandContext);
  }

  /**
   * Checks whether the result's entity is an instance of (or subclass of) the given
   * class name. Returns `true` if no class constraint is specified.
   */
  static boolean matchesClass(
      CommandContext context, String className, Result origin) {
    if (className == null) {
      return true;
    }

    var session = context.getDatabaseSession();
    var entity = (EntityImpl) origin.asEntityOrNull();
    if (entity != null) {
      var clazz = entity.getImmutableSchemaClass(session);
      if (clazz == null) {
        return false;
      }
      return clazz.isSubClassOf(className);
    }

    return false;
  }

  /** Checks whether the result's RID matches the given RID constraint. */
  static boolean matchesRid(CommandContext iCommandContext, SQLRid rid,
      Result origin) {
    if (rid == null) {
      return true;
    }
    if (origin == null) {
      return false;
    }

    if (origin.getIdentity() == null) {
      return false;
    }

    return origin.getIdentity().equals(rid.toRecordId(origin, iCommandContext));
  }

  /**
   * Evaluates the given `WHERE` filter against a candidate record.
   *
   * @param iCommandContext the command context (passed to the filter for variable resolution)
   * @param filter          the WHERE clause to evaluate, or `null` to unconditionally pass
   * @param origin          the candidate record to test
   * @return `true` if no filter is specified or the record satisfies the filter
   */
  protected static boolean matchesFilters(
      CommandContext iCommandContext, SQLWhereClause filter, Result origin) {
    return filter == null || filter.matchesFilters(origin, iCommandContext);
  }

  /**
   * Executes the edge's method (e.g. `out()`, `in()`, `both()`) on the given starting
   * record and converts the raw result into an {@link ExecutionStream}.
   * <p>
   * The method temporarily sets the `$current` context variable to the starting point
   * so that the method implementation can reference it.
   */
  /**
   * Traverses the edge from the starting point. If a
   * {@link RidFilterDescriptor} is attached to the current edge, it is
   * resolved <em>after</em> the raw traversal so the actual link bag
   * size is available for adaptive abort decisions.
   *
   * <p>Subclasses (e.g. {@link MatchFieldTraverser}) override this to
   * implement non-edge traversals.
   */
  protected ExecutionStream traversePatternEdge(
      Result startingPoint, CommandContext iCommandContext) {

    var prevCurrent = iCommandContext.getSystemVariable(CommandContext.VAR_CURRENT);
    iCommandContext.setSystemVariable(CommandContext.VAR_CURRENT, startingPoint);
    Object qR;
    try {
      qR = this.item.getMethod().execute(startingPoint, iCommandContext);
    } finally {
      iCommandContext.setSystemVariable(CommandContext.VAR_CURRENT, prevCurrent);
    }

    qR = applyPreFilter(qR, iCommandContext);

    return toExecutionStream(qR, iCommandContext.getDatabaseSession());
  }

  /**
   * Resolves the intersection descriptor (if any) against the raw
   * traversal result. Uses {@link EdgeTraversal#resolveWithCache} to
   * avoid rebuilding the RidSet when the same descriptor key applies
   * across multiple vertices. The per-vertex ratio check is performed
   * here using the actual link bag size.
   *
   * <p>Sets {@link #currentPreFilterRids} for subclass access.
   */
  protected Object applyPreFilter(Object qR, CommandContext ctx) {
    if (edge == null) {
      return qR;
    }
    if (!(qR instanceof PreFilterableLinkBagIterable pfli)) {
      return qR;
    }

    // Apply class filter (zero I/O — collection ID is embedded in the RID)
    if (edge.getAcceptedCollectionIds() != null) {
      pfli = pfli.withClassFilter(edge.getAcceptedCollectionIds());
    }

    // Apply RidSet intersection filter.
    // resolveWithCache() performs a pre-resolution estimate check: if the
    // estimated RidSet size is too large relative to this vertex's link bag,
    // it returns null without materializing. Only the first vertex whose
    // link bag is large enough triggers actual resolution and caching.
    if (edge.getIntersectionDescriptor() != null) {
      int linkBagSize = pfli.size();
      if (linkBagSize < TraversalPreFilterHelper.minLinkBagSize()) {
        // Link bag too small for pre-filter to be worthwhile.
        edge.recordPreFilterSkip(PreFilterSkipReason.LINKBAG_TOO_SMALL);
      } else {
        var ridSet = edge.resolveWithCache(ctx, linkBagSize);
        // ridSet == null → skip reason already set by resolveWithCache().
        // IndexLookup selectivity is class-level (constant per query) — if
        // resolveWithCache returned non-null the check already passed, so
        // skip the redundant per-vertex recomputation.  EdgeRidLookup still
        // needs the per-vertex re-check because resolveWithCache used an
        // estimate, but applyPreFilter has the actual ridSet.size().
        if (ridSet != null) {
          if (edge.getIntersectionDescriptor() instanceof RidFilterDescriptor.IndexLookup
              || edge.getIntersectionDescriptor()
                  .passesSelectivityCheck(
                      ridSet.size(), linkBagSize, ctx)) {
            this.currentPreFilterRids = ridSet;
            edge.recordPreFilterApplied(linkBagSize, ridSet.size());
            // Record effectiveness metric: filtered = (linkBagSize - ridSetSize),
            // probed = linkBagSize. Guarded by probed > 0 (R5).
            if (linkBagSize > 0) {
              long filtered = Math.max(0, linkBagSize - ridSet.size());
              resolveEffectivenessMetric().record(filtered, linkBagSize);
            }
            if (logger.isDebugEnabled()) {
              logger.debug(
                  "MATCH pre-filter applied: linkBag={} ridSet={} descriptor={}",
                  linkBagSize, ridSet.size(),
                  edge.getIntersectionDescriptor());
            }
            pfli = pfli.withRidFilter(ridSet);
          } else {
            // EdgeRidLookup ratio check failed on actual ridSet.size().
            edge.recordPreFilterSkip(
                PreFilterSkipReason.OVERLAP_RATIO_TOO_HIGH);
          }
        }
      }
    }

    return pfli;
  }

  /**
   * Resolves the {@link CoreMetrics#PREFILTER_EFFECTIVENESS} metric from
   * the global registry, falling back to {@link Ratio#NOOP} when the
   * registry is unavailable (early startup, tests without engine).
   */
  static Ratio resolveEffectivenessMetric() {
    MetricsRegistry registry =
        YouTrackDBEnginesManager.instance().getMetricsRegistry();
    return registry != null
        ? registry.globalMetric(CoreMetrics.PREFILTER_EFFECTIVENESS)
        : Ratio.NOOP;
  }

  /**
   * Converts a raw traversal result into a uniform {@link ExecutionStream}.
   * <p>
   * The raw return value from a graph method (e.g. `out()`, `in()`, `executeReverse()`)
   * may be a single {@link Identifiable}, a {@link ResultInternal},
   * an {@link Iterable}, or `null`. Each case is normalized into a stream. Any other
   * return type is silently treated as empty — the candidate is simply not traversed.
   *
   * @param rawResult the raw object returned by a traversal method
   * @param session   the database session for wrapping records
   * @return a stream of matching result records
   */
  static ExecutionStream toExecutionStream(
      @Nullable Object rawResult, DatabaseSessionEmbedded session) {
    return switch (rawResult) {
      case null -> ExecutionStream.empty();
      case ResultInternal resultInternal -> ExecutionStream.singleton(resultInternal);
      case Identifiable identifiable -> ExecutionStream.singleton(
          new ResultInternal(session, identifiable));
      case Iterable<?> iterable -> ExecutionStream.iterator(iterable.iterator());
      default -> ExecutionStream.empty();
    };
  }
}
