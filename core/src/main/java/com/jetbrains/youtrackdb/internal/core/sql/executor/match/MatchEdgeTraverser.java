package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Relation;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchPathItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLRid;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;


/**
 * Base class for all MATCH edge traversers; default behavior is **forward** traversal.
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
 * | `$matched`       | {@link com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ResultSetEdgeTraverser ResultSetEdgeTraverser} | Current result row; used by downstream WHERE     |
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
public class MatchEdgeTraverser {

  /** The upstream result row containing all previously matched aliases. */
  protected Result sourceRecord;

  /** The scheduled edge traversal (direction + constraints). May be `null` in sub-traversals. */
  protected EdgeTraversal edge;

  /** The AST path item describing the traversal method, filter, and WHILE clause. */
  protected SQLMatchPathItem item;

  /** Lazily initialized stream of traversal results. */
  protected ExecutionStream downstream;

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

  public boolean hasNext(CommandContext ctx) {
    init(ctx);
    return downstream.hasNext(ctx);
  }

  /**
   * Returns the next result row, or `null` if the traversed record conflicts with a
   * previously bound value for the same alias (consistency check).
   * <p>
   * Each returned row is a copy of the upstream row augmented with the new alias
   * mapping. If the edge's filter defines a `depthAlias` or `pathAlias`, the
   * corresponding metadata (`$depth`, `$matchPath`) is also stored as a property.
   */
  @Nullable
  public Result next(CommandContext ctx) {
    init(ctx);
    if (!downstream.hasNext(ctx)) {
      throw new IllegalStateException();
    }
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

      downstream = executeTraversal(ctx, this.item, (Result) startingElem, 0, null);
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
   * @param pathToHere     the sequence of records visited so far (for `$matchPath`),
   *                       `null` at the start
   * @return a stream of matching result records
   */
  protected ExecutionStream executeTraversal(
      CommandContext iCommandContext,
      SQLMatchPathItem item,
      Result startingPoint,
      int depth,
      List<Result> pathToHere) {
    SQLWhereClause filter = null;
    SQLWhereClause whileCondition = null;
    Integer maxDepth = null;
    String className = null;
    SQLRid targetRid = null;

    var session = iCommandContext.getDatabaseSession();
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
      var queryResult = traversePatternEdge(startingPoint, iCommandContext);
      final var theFilter = filter;
      final var theClassName = className;
      final var theTargetRid = targetRid;
      return queryResult.filter(
          (next, ctx) ->
              filter(
                  iCommandContext, theFilter, theClassName, theTargetRid, next,
                  ctx));
    } else {
      // ---- Recursive (WHILE / maxDepth) mode ----
      // The starting point IS included (depth 0) if it passes the filters. Expansion
      // continues while the WHILE condition holds and depth < maxDepth.
      List<Result> result = new ArrayList<>();
      iCommandContext.setSystemVariable(CommandContext.VAR_DEPTH, depth);
      var previousMatch = iCommandContext.getSystemVariable(CommandContext.VAR_CURRENT_MATCH);
      iCommandContext.setSystemVariable(CommandContext.VAR_CURRENT_MATCH, startingPoint);

      // Evaluate the starting point against all filters
      if (matchesFilters(iCommandContext, filter, startingPoint)
          && matchesClass(iCommandContext, className, startingPoint)
          && matchesRid(iCommandContext, targetRid, startingPoint)) {
        ResultInternal rs;
        if (startingPoint instanceof ResultInternal resultInternal) {
          rs = resultInternal;
        } else {
          rs = ResultInternal.toResultInternal(startingPoint, session, null);
        }
        // Store traversal metadata so the user can access it via depthAlias/pathAlias
        rs.setMetadata("$depth", depth);
        rs.setMetadata("$matchPath",
            pathToHere == null ? Collections.EMPTY_LIST : pathToHere);
        result.add(rs);
      }

      // Recurse into neighbors if depth allows and WHILE condition holds
      if ((maxDepth == null || depth < maxDepth)
          && (whileCondition == null
          || whileCondition.matchesFilters(startingPoint, iCommandContext))) {

        var queryResult = traversePatternEdge(startingPoint, iCommandContext);

        while (queryResult.hasNext(iCommandContext)) {
          var origin = ResultInternal.toResult(queryResult.next(iCommandContext), session);
          // Known limitation: the recursive expansion does not track already-visited
          // nodes; if the graph contains cycles, the same record may be traversed
          // multiple times at different depths. A break strategy (e.g. a visited set)
          // would prevent this but is not yet implemented. In practice, the maxDepth
          // or WHILE condition prevents infinite recursion.

          // Build the path by appending the current neighbor
          List<Result> newPath = new ArrayList<>();
          if (pathToHere != null) {
            newPath.addAll(pathToHere);
          }
          newPath.add(origin);

          // Recursive call with incremented depth
          var subResult =
              executeTraversal(iCommandContext, item, origin, depth + 1, newPath);
          while (subResult.hasNext(iCommandContext)) {
            var sub = subResult.next(iCommandContext);
            result.add(sub);
          }
        }
      }
      iCommandContext.setSystemVariable(CommandContext.VAR_CURRENT_MATCH, previousMatch);
      return ExecutionStream.resultIterator(result.iterator());
    }
  }

  /**
   * Applies the combined filter (WHERE + class + RID) to a single traversal result.
   * Temporarily sets the `$currentMatch` context variable so that the filter can
   * reference the current candidate via `$currentMatch`. Returns the result if it
   * passes, or `null` to signal rejection to the stream's filter operator.
   */
  @Nullable
  private Result filter(
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

    return toExecutionStream(qR, iCommandContext.getDatabaseSession());
  }

  /**
   * Converts a raw traversal result into a uniform {@link ExecutionStream}.
   * <p>
   * The raw return value from a graph method (e.g. `out()`, `in()`, `executeReverse()`)
   * may be a single {@link Identifiable}, a {@link ResultInternal}, a {@link Relation},
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
      case Relation<?> relation -> ExecutionStream.singleton(
          new ResultInternal(session, relation));
      case Iterable<?> iterable -> ExecutionStream.iterator(iterable.iterator());
      default -> ExecutionStream.empty();
    };
  }
}
