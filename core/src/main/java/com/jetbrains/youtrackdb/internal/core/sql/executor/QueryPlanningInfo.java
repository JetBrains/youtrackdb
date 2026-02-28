package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLAndBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLFromClause;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLGroupBy;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLLetClause;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLLimit;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrderBy;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLProjection;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSkip;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLTimeout;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLUnwind;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.List;

/**
 * Mutable container for all the state accumulated during query planning inside
 * {@link SelectExecutionPlanner}.
 *
 * <p>An instance is created at the start of planning ({@code init()}) by shallow-copying
 * the relevant clauses from the parsed {@link com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSelectStatement}.
 * Subsequent optimization passes mutate these fields in-place (rewriting, splitting,
 * nullifying) without touching the original AST.
 *
 * <h2>Field lifecycle during planning</h2>
 * <pre>
 *  Phase            | Fields modified
 *  -----------------|---------------------------------------------------------
 *  init()           | All fields populated from the AST
 *  splitLet()       | perRecordLetClause -&gt; globalLetClause (items moved)
 *  extractSubQs()   | whereClause, projection, orderBy (subqueries extracted)
 *  flatten WHERE    | flattenedWhereClause populated
 *  splitProjections | preAggregateProjection, aggregateProjection, projection
 *  addOrderByProjs  | projectionAfterOrderBy, projection (ORDER BY aliases added)
 *  handleFetch*     | whereClause/flattenedWhereClause set to null when consumed
 *                   |   by index; orderApplied set to true when index sorts
 *  handleProject*   | projectionsCalculated set to true
 * </pre>
 *
 * <h2>Projection split for aggregation</h2>
 * <pre>
 *  SELECT city, count(*), max(price) FROM Product GROUP BY city
 *
 *  +---------------------------+     +----------------------+     +--------------------+
 *  | preAggregateProjection    |     | aggregateProjection  |     | projection (post)  |
 *  |  [city]                   | -&gt;  |  [count(*), max()]   | -&gt;  |  [city, cnt, mp]   |
 *  | (per-row fields for agg)  |     | (accumulators)       |     | (final output)     |
 *  +---------------------------+     +----------------------+     +--------------------+
 * </pre>
 *
 * <h2>Synthetic alias prefixes</h2>
 * During optimization the planner generates internal aliases to wire intermediate
 * expressions between pipeline phases. These are hidden from user-visible output
 * via the {@code internalAlias} flag on {@link com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier}.
 * <pre>
 *  Prefix                      | Source                       | Purpose
 *  ----------------------------|------------------------------|------------------------------
 *  _$$$OALIAS$$_N              | AggregateProjectionSplit     | Aggregate projection split
 *  $$$SUBQUERY$$_N             | SubQueryCollector            | Extracted inline subqueries
 *  _$$$ORDER_BY_ALIAS$$$_N     | addOrderByProjections()      | ORDER BY expressions not in SELECT
 *  _$$$GROUP_BY_ALIAS$$$_N     | addGroupByExpressionsToProj() | GROUP BY expressions not in SELECT
 * </pre>
 *
 * @see SelectExecutionPlanner
 */
public class QueryPlanningInfo {

  /** Query-level timeout (from SQL TIMEOUT clause or global config). */
  protected SQLTimeout timeout;

  /** {@code true} if the SELECT uses DISTINCT (either keyword or distinct() function). */
  public boolean distinct = false;

  /** {@code true} if the projection uses expand() (e.g. {@code SELECT expand(friends)}). */
  protected boolean expand = false;

  /** The alias/field name passed to expand(), e.g. "friends" in {@code expand(friends)}. */
  protected String expandAlias = null;

  // --------------- Projection split (see splitProjectionsForGroupBy) ---------------

  /**
   * Phase 1 projections: per-record expressions that feed into aggregate functions.
   * {@code null} when there are no aggregates.
   */
  protected SQLProjection preAggregateProjection;

  /**
   * Phase 2 projections: aggregate accumulators (count, sum, max, etc.).
   * {@code null} when there are no aggregates.
   */
  protected SQLProjection aggregateProjection;

  /**
   * Phase 3 / final projections: maps accumulated results to the user's SELECT list.
   * When no aggregation is needed this is the only projection used.
   */
  public SQLProjection projection = null;

  /**
   * Extra projection step added after ORDER BY to strip synthetic
   * {@code _$$$ORDER_BY_ALIAS$$$_N} columns that were temporarily added
   * to support sorting by expressions not in the SELECT list.
   * {@code null} when ORDER BY only references existing projection aliases.
   */
  protected SQLProjection projectionAfterOrderBy = null;

  // --------------- LET clauses ---------------

  /**
   * Global LET items (executed once before the main fetch). Populated by
   * {@link SelectExecutionPlanner#splitLet} and
   * {@link SelectExecutionPlanner#extractSubQueries}.
   */
  protected SQLLetClause globalLetClause = null;

  /** {@code true} if at least one global LET step was added to the plan. */
  protected boolean globalLetPresent = false;

  /**
   * Per-record LET items (executed once for every row flowing through the pipeline).
   * Items that can be evaluated globally are moved to {@link #globalLetClause} by
   * {@link SelectExecutionPlanner#splitLet}.
   */
  protected SQLLetClause perRecordLetClause = null;

  // --------------- Core clauses ---------------

  /** FROM clause (may be null for "SELECT 1+1" style queries). */
  protected SQLFromClause target;

  /**
   * Original WHERE clause (tree form). Set to {@code null} by the planner once the
   * WHERE has been fully consumed (e.g. by an index-based fetch).
   */
  protected SQLWhereClause whereClause;

  /**
   * WHERE clause flattened into a list of AND blocks (one per OR branch).
   *
   * <pre>
   *  Original WHERE tree:
   *    OR
   *    +-- AND
   *    |   +-- a = 1
   *    |   +-- b = 2
   *    +-- AND
   *        +-- c = 3
   *        +-- d = 4
   *
   *  flattenedWhereClause (after flatten + moveFlattenedEqualitiesLeft):
   *    [0] AND[a=1, b=2]    -- equalities moved left for index prefix matching
   *    [1] AND[c=3, d=4]
   * </pre>
   *
   * <p>Each AND block represents one OR-branch. The index selection logic tries
   * each block independently. Set to {@code null} once consumed by an index fetch.
   */
  protected List<SQLAndBlock> flattenedWhereClause;

  /** GROUP BY clause (null if not present). */
  public SQLGroupBy groupBy;

  /** ORDER BY clause (null if not present). */
  public SQLOrderBy orderBy;

  /** UNWIND clause (null if not present). */
  public SQLUnwind unwind;

  /** SKIP clause (null if not present). */
  public SQLSkip skip;

  /** LIMIT clause (null if not present). */
  public SQLLimit limit;

  // --------------- Optimization flags ---------------

  /**
   * Set to {@code true} when the ORDER BY has been satisfied by an index scan
   * (so no in-memory sort is needed).
   */
  protected boolean orderApplied = false;

  /**
   * Set to {@code true} once projection steps have been appended to the plan.
   * Prevents double-appending (projections may be triggered from multiple code paths).
   */
  protected boolean projectionsCalculated = false;

  /**
   * RID range conditions extracted from the WHERE clause (e.g. {@code @rid > #10:5}).
   * Used to narrow the collection scan range in
   * {@link FetchFromClassExecutionStep}.
   */
  protected SQLAndBlock ridRangeConditions;

  /**
   * Creates a shallow copy of this planning info.
   *
   * <p><b>Note:</b> all fields are copied by reference, not deep-copied. This is
   * intentional -- the copy is used by {@link FetchFromClassExecutionStep} and
   * {@link FetchFromCollectionExecutionStep} to carry planning metadata into copied
   * plans. The AST nodes (projection, whereClause, etc.) are immutable once planning
   * completes, because the planner performs deep copies of the original AST during
   * {@code init()} and never mutates them after step construction.
   */
  public QueryPlanningInfo copy() {
    var result = new QueryPlanningInfo();
    result.distinct = this.distinct;
    result.expand = this.expand;
    result.expandAlias = this.expandAlias;
    result.preAggregateProjection = this.preAggregateProjection;
    result.aggregateProjection = this.aggregateProjection;
    result.projection = this.projection;
    result.projectionAfterOrderBy = this.projectionAfterOrderBy;
    result.globalLetClause = this.globalLetClause;
    result.globalLetPresent = this.globalLetPresent;
    result.perRecordLetClause = this.perRecordLetClause;
    result.target = this.target;
    result.whereClause = this.whereClause;
    result.flattenedWhereClause = this.flattenedWhereClause;
    result.groupBy = this.groupBy;
    result.orderBy = this.orderBy;
    result.unwind = this.unwind;
    result.skip = this.skip;
    result.limit = this.limit;
    result.orderApplied = this.orderApplied;
    result.projectionsCalculated = this.projectionsCalculated;
    result.ridRangeConditions = this.ridRangeConditions;

    return result;
  }
}
