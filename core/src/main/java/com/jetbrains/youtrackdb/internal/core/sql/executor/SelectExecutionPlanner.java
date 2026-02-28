package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.util.PairIntegerObject;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.parser.AggregateProjectionSplit;
import com.jetbrains.youtrackdb.internal.core.sql.parser.ExecutionPlanCache;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLAndBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBaseExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBooleanExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLEqualsOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLFromClause;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLFromItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLGroupBy;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIndexIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLInputParameter;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLInteger;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLLetClause;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLLetItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMetadataIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrderBy;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrderByItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLProjection;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLProjectionItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLRecordAttribute;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLRid;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSelectStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLTimeout;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SubQueryCollector;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * Converts a parsed {@link SQLSelectStatement} AST into a physical
 * {@link SelectExecutionPlan} -- an ordered chain of {@link ExecutionStepInternal}
 * nodes that, when started, produce a pull-based stream of results.
 *
 * <h2>High-level pipeline</h2>
 * <pre>
 * SQLSelectStatement (AST from JavaCC parser)
 *        |
 *        v
 * +-------------------------------+
 * | SelectExecutionPlanner        |
 * |  1. init()          copy AST  |
 * |  2. optimizeQuery() rewrite   |
 * |  3. build step chain          |
 * +-------------------------------+
 *        |
 *        v
 * SelectExecutionPlan (linked list of steps)
 *
 *  step1 --next--&gt; step2 --next--&gt; ... --next--&gt; stepN
 *        &lt;--prev--       &lt;--prev--     &lt;--prev--
 *
 *  Execution is PULL-BASED: the caller invokes stepN.start(ctx)
 *  which recursively pulls from step(N-1), etc.
 * </pre>
 *
 * <h2>Planning phases (in order)</h2>
 * <pre>
 *  Phase                        | Method(s)
 *  -----------------------------|-------------------------------------------
 *  1. Copy &amp; normalize AST      | init()
 *  2. Optimize query            | optimizeQuery()
 *     a. Split LET              |   splitLet()
 *     b. Rewrite index chains   |   rewriteIndexChainsAsSubqueries()
 *     c. Extract subqueries     |   extractSubQueries()
 *     d. Flatten WHERE          |   whereClause.flatten()
 *     e. Move equalities left   |   moveFlattenedEqualitiesLeft()
 *     f. Split projections      |   splitProjectionsForGroupBy()
 *     g. Add ORDER BY projs     |   addOrderByProjections()
 *  3. Hard-wired optimizations  | handleHardwiredOptimizations()
 *     (COUNT(*) short-circuits) |
 *  4. Global LET                | handleGlobalLet()
 *  5. Fetch from target         | handleFetchFromTarget()
 *  6. Per-record LET            | handleLet()
 *  7. WHERE filtering           | handleWhere()
 *  8. Projections block         | handleProjectionsBlock()
 *     (projections, EXPAND,     |
 *      UNWIND, ORDER BY,        |
 *      SKIP, LIMIT, DISTINCT)   |
 *  9. Timeout                   | AccumulatingTimeoutStep
 *  10. Cache plan (optional)    | ExecutionPlanCache.put()
 * </pre>
 *
 * <h2>Projection splitting for aggregation</h2>
 * When the SELECT list contains aggregate functions (e.g. {@code count(*), max(price)}),
 * the planner splits projections into three phases to support GROUP BY correctly:
 * <pre>
 *   SELECT city, count(*), max(price) FROM Product GROUP BY city
 *
 *   preAggregateProjection :  city           (per-record fields needed before grouping)
 *   aggregateProjection    :  count(*), max  (aggregate accumulators, grouped by city)
 *   projection (post)      :  city, count, max (final output mapping)
 *
 *   Pipeline:
 *   FetchFromClass -&gt; ProjectionCalc(pre) -&gt; AggregateProjectionCalc -&gt; ProjectionCalc(post)
 * </pre>
 *
 * <h2>Index selection strategy</h2>
 * For class-targeted queries with a WHERE clause the planner attempts, in order:
 * <ol>
 *   <li>Indexed function execution (e.g. spatial / full-text custom functions)</li>
 *   <li>Best-fit B-tree / hash index lookup via {@link #findBestIndexFor}</li>
 *   <li>Index-only sort (ORDER BY matches index field order)</li>
 *   <li>Full class scan with optional RID-ordering optimization</li>
 * </ol>
 *
 * <h2>Thread safety</h2>
 * Instances are <b>not</b> thread-safe. A new planner is created per query execution.
 * The resulting {@link SelectExecutionPlan} may be cached and copied for reuse.
 *
 * @see SelectExecutionPlan
 * @see QueryPlanningInfo
 * @see ExecutionStepInternal
 */
public class SelectExecutionPlanner {

  /** Mutable planning state -- populated by {@link #init} and mutated by optimization passes. */
  private QueryPlanningInfo info;

  /** The parsed SQL SELECT statement (immutable AST from the JavaCC parser). */
  private final SQLSelectStatement statement;

  public SelectExecutionPlanner(SQLSelectStatement oSelectStatement) {
    this.statement = oSelectStatement;
  }

  /**
   * Copies all relevant clauses from the parsed {@link SQLSelectStatement} into a mutable
   * {@link QueryPlanningInfo} so that subsequent optimization passes can freely rewrite them
   * without mutating the original AST (which may be cached or reused).
   *
   * <p>Also applies a default command-level timeout from {@link GlobalConfiguration#COMMAND_TIMEOUT}
   * if no explicit TIMEOUT clause was specified in the SQL.
   */
  private void init(CommandContext ctx) {
    // copying the content, so that it can be manipulated and optimized
    info = new QueryPlanningInfo();
    info.projection =
        this.statement.getProjection() == null ? null : this.statement.getProjection().copy();
    info.projection = translateDistinct(info.projection);
    info.distinct = info.projection != null && info.projection.isDistinct();
    if (info.projection != null) {
      info.projection.setDistinct(false);
    }

    info.target = this.statement.getTarget();
    info.whereClause =
        this.statement.getWhereClause() == null ? null : this.statement.getWhereClause().copy();
    info.whereClause = translateLucene(info.whereClause);
    info.perRecordLetClause =
        this.statement.getLetClause() == null ? null : this.statement.getLetClause().copy();
    info.groupBy = this.statement.getGroupBy() == null ? null : this.statement.getGroupBy().copy();
    info.orderBy = this.statement.getOrderBy() == null ? null : this.statement.getOrderBy().copy();
    info.unwind = this.statement.getUnwind() == null ? null : this.statement.getUnwind().copy();
    info.skip = this.statement.getSkip();
    info.limit = this.statement.getLimit();
    info.timeout = this.statement.getTimeout() == null ? null : this.statement.getTimeout().copy();
    if (info.timeout == null
        &&
        ctx.getDatabaseSession().getConfiguration()
            .getValueAsLong(GlobalConfiguration.COMMAND_TIMEOUT)
            > 0) {
      info.timeout = new SQLTimeout(-1);
      info.timeout.setVal(
          ctx.getDatabaseSession()
              .getConfiguration()
              .getValueAsLong(GlobalConfiguration.COMMAND_TIMEOUT));
    }
  }

  /**
   * Main entry point: builds and returns a fully assembled execution plan for the
   * SELECT statement that was passed to the constructor.
   *
   * <p>The method first checks the plan cache (when {@code useCache} is true and profiling
   * is disabled). If no cached plan is found it runs the full planning pipeline:
   * <pre>
   *   init() --&gt; optimizeQuery() --&gt; hardwired opts? --&gt; globalLet --&gt; fetch
   *          --&gt; perRecordLet --&gt; where --&gt; projectionsBlock --&gt; timeout
   * </pre>
   *
   * @param ctx              command context carrying the database session and input parameters
   * @param enableProfiling  if true, each step wraps its output stream with profiling counters
   * @param useCache         if true, the planner will check / populate the
   *                         {@link ExecutionPlanCache}
   * @return a ready-to-execute {@link InternalExecutionPlan}
   */
  public InternalExecutionPlan createExecutionPlan(
      CommandContext ctx, boolean enableProfiling, boolean useCache) {
    var session = ctx.getDatabaseSession();

    // --- 1. Check the plan cache before doing any work ---
    if (useCache && !enableProfiling && statement.executinPlanCanBeCached(session)) {
      var plan = ExecutionPlanCache.get(statement.getOriginalStatement(), ctx, session);
      if (plan != null) {
        return (InternalExecutionPlan) plan;
      }
    }

    // Record the timestamp so we can avoid caching a stale plan if the schema
    // was modified concurrently during planning.
    var planningStart = System.currentTimeMillis();

    // --- 2. Copy AST into mutable QueryPlanningInfo ---
    init(ctx);
    var result = new SelectExecutionPlan(ctx);

    // DISTINCT + expand() is an unsupported combination -- fail fast.
    if (info.expand && info.distinct) {
      throw new CommandExecutionException(session,
          "Cannot execute a statement with DISTINCT expand(), please use a subquery");
    }

    // --- 3. Optimize the query: rewrite LET, flatten WHERE, split projections, etc. ---
    optimizeQuery(info, ctx);

    // --- 4. Try hardwired short-circuit optimizations (e.g. COUNT(*) without scan) ---
    if (handleHardwiredOptimizations(result, ctx, enableProfiling)) {
      return result;
    }

    // --- 5. Build the step chain in pipeline order ---
    handleGlobalLet(result, info, ctx, enableProfiling);       // global LET (executed once)

    handleFetchFromTarget(result, info, ctx, enableProfiling); // data source

    handleLet(result, info, ctx, enableProfiling);             // per-record LET

    handleWhere(result, info, ctx, enableProfiling);           // WHERE filtering

    handleProjectionsBlock(result, info, ctx, enableProfiling);// projections, ORDER BY, etc.

    // --- 6. Append timeout enforcement step if configured ---
    if (info.timeout != null) {
      result.chain(new AccumulatingTimeoutStep(info.timeout, ctx, enableProfiling));
    }

    // --- 7. Store the assembled plan in the cache for future reuse ---
    if (useCache
        && !enableProfiling
        && statement.executinPlanCanBeCached(session)
        && result.canBeCached()
        && ExecutionPlanCache.getLastInvalidation(session) < planningStart) {
      ExecutionPlanCache.put(statement.getOriginalStatement(), result, ctx.getDatabaseSession());
    }
    return result;
  }

  /**
   * Assembles the tail of the execution plan covering projections, EXPAND, UNWIND,
   * ORDER BY, DISTINCT, SKIP and LIMIT.
   *
   * <p>The ordering of these steps depends on which SQL features are used. Three
   * mutually exclusive code paths handle all combinations:
   *
   * <pre>
   * Path A -- EXPAND / UNWIND / GROUP BY present:
   *   Projections -&gt; Expand -&gt; Unwind -&gt; OrderBy -&gt; Skip -&gt; Limit
   *   (ORDER BY must come AFTER expand/unwind because those change the row count)
   *
   * Path B -- DISTINCT / aggregation (no expand/unwind):
   *   OrderBy -&gt; Projections -&gt; Distinct -&gt; Skip -&gt; Limit
   *   (ORDER BY before projections so sort keys are still available)
   *
   * Path C -- simple query (no expand/unwind/distinct/aggregation):
   *   Skip -&gt; Limit -&gt; Projections
   *   (SKIP/LIMIT applied early to minimize projection work)
   * </pre>
   *
   * <p>In all paths, if an ORDER BY clause is present,
   * {@link #handleProjectionsBeforeOrderBy} is called first to ensure that ORDER BY
   * expressions that are not part of the user's SELECT list are temporarily added as
   * projections (they will be stripped later by {@code projectionAfterOrderBy}).
   */
  public static void handleProjectionsBlock(
      SelectExecutionPlan result,
      QueryPlanningInfo info,
      CommandContext ctx,
      boolean enableProfiling) {

    // Ensure ORDER BY expressions are available as projected columns.
    handleProjectionsBeforeOrderBy(result, info, ctx, enableProfiling);

    if (info.expand || info.unwind != null || info.groupBy != null) {
      // --- Path A: EXPAND / UNWIND / GROUP BY ---
      // Projections must be computed before expand/unwind because those operators
      // consume the projected field to produce new rows.  ORDER BY runs after
      // because the row cardinality has changed.
      handleProjections(result, info, ctx, enableProfiling);
      handleExpand(result, info, ctx, enableProfiling);
      handleUnwind(result, info, ctx, enableProfiling);
      handleOrderBy(result, info, ctx, enableProfiling);
      if (info.skip != null) {
        result.chain(new SkipExecutionStep(info.skip, ctx, enableProfiling));
      }
      if (info.limit != null) {
        result.chain(new LimitExecutionStep(info.limit, ctx, enableProfiling));
      }
    } else {
      // ORDER BY runs first so sort keys (which may be raw record fields
      // not present in the final projection) are still accessible.
      handleOrderBy(result, info, ctx, enableProfiling);

      if (info.distinct || info.groupBy != null || info.aggregateProjection != null) {
        // --- Path B: DISTINCT / aggregation ---
        // Note: info.groupBy != null is a defensive guard here. In practice, the outer
        // if-branch (Path A) already handles groupBy != null. It is retained to ensure
        // correct behavior if the Path A condition is ever modified.
        // Projections run after sorting; DISTINCT deduplicates the projected rows.
        handleProjections(result, info, ctx, enableProfiling);
        handleDistinct(result, info, ctx, enableProfiling);
        if (info.skip != null) {
          result.chain(new SkipExecutionStep(info.skip, ctx, enableProfiling));
        }
        if (info.limit != null) {
          result.chain(new LimitExecutionStep(info.limit, ctx, enableProfiling));
        }
      } else {
        // --- Path C: simple query ---
        // SKIP/LIMIT before projections to avoid computing projections on
        // rows that will be discarded anyway.
        if (info.skip != null) {
          result.chain(new SkipExecutionStep(info.skip, ctx, enableProfiling));
        }
        if (info.limit != null) {
          result.chain(new LimitExecutionStep(info.limit, ctx, enableProfiling));
        }
        handleProjections(result, info, ctx, enableProfiling);
      }
    }
  }

  /**
   * Rewrites legacy Lucene-style operators in the WHERE clause to the standard form.
   * Returns the (possibly mutated) WHERE clause, or {@code null} if the input was null.
   */
  @Nullable
  private static SQLWhereClause translateLucene(SQLWhereClause whereClause) {
    if (whereClause == null) {
      return null;
    }

    if (whereClause.getBaseExpression() != null) {
      whereClause.getBaseExpression().translateLuceneOperator();
    }
    return whereClause;
  }

  /**
   * For backward compatibility, translates the legacy {@code distinct(foo)} function
   * call syntax into the standard {@code SELECT DISTINCT foo} form. Returns a new
   * projection with {@code isDistinct() == true} and the inner expression unwrapped.
   * If the projection does not contain a legacy distinct() call, it is returned as-is.
   */
  public static SQLProjection translateDistinct(SQLProjection projection) {
    if (projection != null && projection.getItems().size() == 1) {
      if (isDistinct(projection.getItems().getFirst())) {
        projection = projection.copy();
        var item = projection.getItems().getFirst();
        var function =
            ((SQLBaseExpression) item.getExpression().getMathExpression())
                .getIdentifier()
                .getLevelZero()
                .getFunctionCall();
        var exp = function.getParams().getFirst();
        var resultItem = new SQLProjectionItem(-1);
        resultItem.setAlias(item.getAlias());
        resultItem.setExpression(exp.copy());
        var result = new SQLProjection(-1);
        result.setItems(new ArrayList<>());
        result.setDistinct(true);
        result.getItems().add(resultItem);
        return result;
      }
    }
    return projection;
  }

  /**
   * Returns {@code true} if the given projection item is a legacy {@code distinct(expr)}
   * function call. The new executor does not support {@code distinct()} as a function;
   * instead, the caller rewrites it to {@code SELECT DISTINCT expr} via
   * {@link #translateDistinct}.
   */
  private static boolean isDistinct(SQLProjectionItem item) {
    if (item.getExpression() == null) {
      return false;
    }
    if (item.getExpression().getMathExpression() == null) {
      return false;
    }
    if (!(item.getExpression().getMathExpression() instanceof SQLBaseExpression base)) {
      return false;
    }
    if (base.getIdentifier() == null) {
      return false;
    }
    if (base.getModifier() != null) {
      return false;
    }
    if (base.getIdentifier().getLevelZero() == null) {
      return false;
    }
    var function = base.getIdentifier().getLevelZero().getFunctionCall();
    if (function == null) {
      return false;
    }
    return function.getName().getStringValue().equalsIgnoreCase("distinct");
  }

  /**
   * Attempts to short-circuit the entire plan with a single optimized step when the
   * query is a simple {@code SELECT count(*) FROM ClassName} (optionally with a
   * single indexed equality condition).
   *
   * <pre>
   *  Case 1 -- bare count:   SELECT count(*) FROM Foo
   *    =&gt; CountFromClassStep  (O(1) metadata lookup, no record scan)
   *
   *  Case 2 -- indexed count: SELECT count(*) FROM Foo WHERE bar = ?
   *    =&gt; CountFromIndexWithKeyStep  (single index key count)
   * </pre>
   *
   * @return {@code true} if the optimization was applied and the plan is complete
   */
  private boolean handleHardwiredOptimizations(
      SelectExecutionPlan result, CommandContext ctx, boolean profilingEnabled) {
    if (handleHardwiredCountOnClass(result, info, ctx, profilingEnabled)) {
      return true;
    }
    return handleHardwiredCountOnClassUsingIndex(result, info, ctx, profilingEnabled);
  }

  /**
   * Handles the special case of {@code SELECT count(*) FROM ClassName} with no WHERE,
   * no GROUP BY, no ORDER BY, no SKIP/LIMIT, no LET, etc.
   *
   * <p>If all preconditions are met and no security policies restrict reads on the target
   * class, the plan is reduced to a single {@link CountFromClassStep} which reads the
   * record count from class metadata in O(1) time.
   *
   * @return {@code true} if the optimization was applied
   */
  private static boolean handleHardwiredCountOnClass(
      SelectExecutionPlan result,
      QueryPlanningInfo info,
      CommandContext ctx,
      boolean profilingEnabled) {
    var session = ctx.getDatabaseSession();
    var targetClass = info.target == null ? null : info.target.getSchemaClass(session);
    if (targetClass == null) {
      return false;
    }

    if (info.distinct || info.expand) {
      return false;
    }
    if (info.preAggregateProjection != null) {
      return false;
    }
    if (!isCountStar(info)) {
      return false;
    }
    if (!isMinimalQuery(info)) {
      return false;
    }
    if (securityPoliciesExistForClass(targetClass, ctx)) {
      return false;
    }
    result.chain(
        new CountFromClassStep(
            targetClass, info.projection.getAllAliases().iterator().next(), ctx, profilingEnabled));
    return true;
  }

  /**
   * Returns {@code true} if a security policy restricts read access to the given class,
   * which would make a direct metadata count incorrect (the count must respect row-level
   * security filtering).
   */
  private static boolean securityPoliciesExistForClass(SchemaClassInternal targetClass,
      CommandContext ctx) {
    if (targetClass == null) {
      return false;
    }

    var session = ctx.getDatabaseSession();
    var security = session.getSharedContext().getSecurity();

    return security.isReadRestrictedBySecurityPolicy(session,
        "database.class." + targetClass.getName());
  }

  /**
   * Handles the special case of {@code SELECT count(*) FROM ClassName WHERE field = ?}
   * when a single-field index exists on the filtered property.
   *
   * <p>Constraints checked (all must be true for the optimization to apply):
   * <ul>
   *   <li>Target is a class (not a subquery, variable, etc.)</li>
   *   <li>Projection is exactly one count(*)</li>
   *   <li>WHERE is a single equality condition on a base identifier</li>
   *   <li>A single-field class index covers the equality field</li>
   *   <li>No security policies restrict reads</li>
   * </ul>
   *
   * @return {@code true} if the optimization was applied
   */
  private static boolean handleHardwiredCountOnClassUsingIndex(
      SelectExecutionPlan result,
      QueryPlanningInfo info,
      CommandContext ctx,
      boolean profilingEnabled) {
    var session = ctx.getDatabaseSession();
    var targetClass = info.target == null ? null : info.target.getSchemaClass(session);
    if (targetClass == null) {
      return false;
    }
    if (info.distinct || info.expand) {
      return false;
    }
    if (info.preAggregateProjection != null) {
      return false;
    }
    if (!isCountStar(info)) {
      return false;
    }
    if (info.projectionAfterOrderBy != null
        || info.globalLetClause != null
        || info.perRecordLetClause != null
        || info.groupBy != null
        || info.orderBy != null
        || info.unwind != null
        || info.skip != null) {
      return false;
    }

    if (info.flattenedWhereClause == null
        || info.flattenedWhereClause.size() > 1
        || info.flattenedWhereClause.getFirst().getSubBlocks().size() > 1) {
      // for now it only handles a single equality condition, it can be extended
      return false;
    }
    var condition = info.flattenedWhereClause.getFirst().getSubBlocks().getFirst();
    if (!(condition instanceof SQLBinaryCondition binaryCondition)) {
      return false;
    }
    if (!binaryCondition.getLeft().isBaseIdentifier()) {
      return false;
    }
    if (!(binaryCondition.getOperator() instanceof SQLEqualsOperator)) {
      // this can be extended to use range operators too
      return false;
    }
    if (securityPoliciesExistForClass(targetClass, ctx)) {
      return false;
    }

    for (var classIndex : targetClass.getClassIndexesInternal()) {
      var fields = classIndex.getDefinition().getProperties();
      if (fields.size() == 1
          && fields.getFirst()
          .equals(binaryCondition.getLeft().getDefaultAlias().getStringValue())) {
        var expr = binaryCondition.getRight();
        result.chain(
            new CountFromIndexWithKeyStep(
                new SQLIndexIdentifier(classIndex.getName(), SQLIndexIdentifier.Type.INDEX),
                expr,
                info.projection.getAllAliases().iterator().next(),
                ctx,
                profilingEnabled));
        return true;
      }
    }

    return false;
  }

  /**
   * Returns {@code true} if the query has no WHERE, no SKIP/LIMIT, no UNWIND,
   * no GROUP BY, no ORDER BY, and no LET -- i.e. the simplest possible query
   * form that qualifies for hardwired optimizations like metadata-based count.
   */
  private static boolean isMinimalQuery(QueryPlanningInfo info) {
    return info.projectionAfterOrderBy == null
        && info.globalLetClause == null
        && info.perRecordLetClause == null
        && info.whereClause == null
        && info.flattenedWhereClause == null
        && info.groupBy == null
        && info.orderBy == null
        && info.unwind == null
        && info.skip == null;
  }

  /**
   * Returns {@code true} if the query is exactly {@code SELECT count(*)} with a
   * single aggregate projection item and a single output projection item.
   */
  private static boolean isCountStar(QueryPlanningInfo info) {
    if (info.aggregateProjection == null
        || info.projection == null
        || info.aggregateProjection.getItems().size() != 1
        || info.projection.getItems().size() != 1) {
      return false;
    }
    var item = info.aggregateProjection.getItems().getFirst();
    return item.getExpression().toString().equalsIgnoreCase("count(*)");
  }

  /**
   * Returns {@code true} if the query has exactly one aggregate projection that is a
   * bare {@code count()} (with no modifier), and only one user-visible output column
   * (synthetic ORDER BY aliases are excluded from the count).
   */
  private static boolean isCountOnly(QueryPlanningInfo info) {
    if (info.aggregateProjection == null
        || info.projection == null
        || info.aggregateProjection.getItems().size() != 1
        || info.projection.getItems().stream()
        .filter(x -> !x.getProjectionAliasAsString().startsWith("_$$$ORDER_BY_ALIAS$$$_"))
        .count()
        != 1) {
      return false;
    }
    var item = info.aggregateProjection.getItems().getFirst();
    var exp = item.getExpression();
    if (exp.getMathExpression() != null
        && exp.getMathExpression() instanceof SQLBaseExpression base) {
      return base.isCount() && base.getModifier() == null;
    }
    return false;
  }

  /**
   * Appends an {@link UnwindStep} if the query contains an UNWIND clause.
   *
   * <p>UNWIND flattens a collection-valued field into multiple rows:
   * <pre>
   *   Input:  { name: "Alice", tags: ["a","b"] }
   *   Output: { name: "Alice", tags: "a" }
   *           { name: "Alice", tags: "b" }
   * </pre>
   */
  public static void handleUnwind(
      SelectExecutionPlan result,
      QueryPlanningInfo info,
      CommandContext ctx,
      boolean profilingEnabled) {
    if (info.unwind != null) {
      result.chain(new UnwindStep(info.unwind, ctx, profilingEnabled));
    }
  }

  /** Appends a {@link DistinctExecutionStep} if the query uses DISTINCT. */
  private static void handleDistinct(
      SelectExecutionPlan result,
      QueryPlanningInfo info,
      CommandContext ctx,
      boolean profilingEnabled) {
    if (info.distinct) {
      result.chain(new DistinctExecutionStep(ctx, profilingEnabled));
    }
  }

  /**
   * If an ORDER BY clause is present, projections are calculated early so that
   * sort keys derived from projected expressions are available to
   * {@link OrderByStep}. Without ORDER BY this is a no-op (projections are
   * deferred for efficiency).
   */
  private static void handleProjectionsBeforeOrderBy(
      SelectExecutionPlan result,
      QueryPlanningInfo info,
      CommandContext ctx,
      boolean profilingEnabled) {
    if (info.orderBy != null) {
      handleProjections(result, info, ctx, profilingEnabled);
    }
  }

  /**
   * Builds the projection sub-pipeline (up to three steps) and appends it to the plan.
   *
   * <p>When aggregation is involved the projection is split into three phases
   * (see {@link #splitProjectionsForGroupBy}):
   * <pre>
   *   Phase 1 (optional): ProjectionCalculationStep(preAggregateProjection)
   *       -- evaluates per-row expressions needed by the aggregate
   *
   *   Phase 2 (optional): AggregateProjectionCalculationStep(aggregateProjection)
   *       -- accumulates aggregate functions, grouped by GROUP BY keys
   *       +  GuaranteeEmptyCountStep (only for bare count() without GROUP BY)
   *
   *   Phase 3 (always):   ProjectionCalculationStep(projection)
   *       -- computes the final output columns
   * </pre>
   *
   * <p>The {@code projectionsCalculated} flag prevents this method from appending
   * projection steps more than once (it can be called from both
   * {@link #handleProjectionsBeforeOrderBy} and the main projections block).
   *
   * <p>When no ORDER BY is present, a combined aggregation limit
   * ({@code SKIP + LIMIT}) is passed into the aggregation step so it can
   * stop accumulating early once enough groups have been produced.
   */
  private static void handleProjections(
      SelectExecutionPlan result,
      QueryPlanningInfo info,
      CommandContext ctx,
      boolean profilingEnabled) {
    if (!info.projectionsCalculated && info.projection != null) {

      // Phase 1: pre-aggregate projections (per-row expressions feeding aggregates)
      if (info.preAggregateProjection != null) {
        result.chain(
            new ProjectionCalculationStep(info.preAggregateProjection, ctx, profilingEnabled));
      }

      // Phase 2: aggregate accumulation (GROUP BY + aggregate functions)
      if (info.aggregateProjection != null) {
        // When there is no ORDER BY, we can limit how many groups the aggregation
        // produces to (SKIP + LIMIT) -- an early-termination optimization.
        long aggregationLimit = -1;
        if (info.orderBy == null && info.limit != null) {
          aggregationLimit = info.limit.getValue(ctx);
          if (info.skip != null && info.skip.getValue(ctx) > 0) {
            aggregationLimit += info.skip.getValue(ctx);
          }
        }
        result.chain(
            new AggregateProjectionCalculationStep(
                info.aggregateProjection,
                info.groupBy,
                aggregationLimit,
                ctx,
                info.timeout != null ? info.timeout.getVal().longValue() : -1,
                profilingEnabled));

        // For a bare "SELECT count(*) FROM ..." (no GROUP BY), guarantee that
        // the result contains at least one row with count=0 even when the
        // upstream produces zero records.
        if (isCountOnly(info) && info.groupBy == null) {
          result.chain(
              new GuaranteeEmptyCountStep(
                  info.aggregateProjection.getItems().getFirst(), ctx, profilingEnabled));
        }
      }

      // Phase 3: final projection (maps accumulated / raw fields to output columns)
      result.chain(new ProjectionCalculationStep(info.projection, ctx, profilingEnabled));

      info.projectionsCalculated = true;
    }
  }

  /**
   * Master optimization pass that rewrites the mutable {@link QueryPlanningInfo} in-place.
   *
   * <p>The sub-passes run in a fixed order because each may depend on the output of
   * the previous one:
   * <pre>
   *  1. splitLet           -- separate global vs per-record LET items
   *  2. rewriteIndexChains -- convert chained index traversals to subqueries
   *  3. extractSubQueries  -- pull inline subqueries into LET variables
   *  4. detect expand()    -- extract EXPAND projection into a flag + alias
   *  5. flatten WHERE      -- convert OR/AND tree to a list of AND blocks
   *  6. equalities left    -- reorder each AND block: equalities first (index-friendly)
   *  7. splitProjections   -- split into pre-aggregate / aggregate / post-aggregate
   *  8. addOrderByProjs    -- add synthetic projections for ORDER BY expressions
   * </pre>
   *
   * <p>After this method completes, {@code info.flattenedWhereClause} is a
   * {@code List<SQLAndBlock>} where each block represents one OR-branch, and within
   * each block the conditions are ordered with equalities first (which allows the
   * index selection logic to match index prefixes greedily).
   */
  public static void optimizeQuery(QueryPlanningInfo info, CommandContext ctx) {
    splitLet(info, ctx);
    rewriteIndexChainsAsSubqueries(info, ctx);
    extractSubQueries(info);

    // Detect and extract expand() from the projection
    if (info.projection != null && info.projection.isExpand()) {
      info.expand = true;
      info.expandAlias = info.projection.getExpandAlias();
      info.projection = info.projection.getExpandContent();
    }

    // Flatten the WHERE tree into a list of AND blocks (one per OR branch)
    // and reorder equalities to the front of each block for index matching.
    if (info.whereClause != null) {
      if (info.target == null) {
        info.flattenedWhereClause = info.whereClause.flatten(ctx, null);
      } else {
        info.flattenedWhereClause = info.whereClause.flatten(ctx,
            info.target.getSchemaClass(ctx.getDatabaseSession()));
      }
      // Move equality conditions to the left of each AND block so the index
      // selection logic can greedily match the longest index prefix.
      info.flattenedWhereClause = moveFlattenedEqualitiesLeft(info.flattenedWhereClause);
    }

    splitProjectionsForGroupBy(info, ctx);
    addOrderByProjections(info);
  }

  /**
   * Rewrites chained index traversals in the WHERE clause into subqueries.
   * For example, {@code WHERE friend.name = 'Alice'} where {@code friend} is a link
   * and {@code name} is indexed can be rewritten as a subquery that first resolves
   * the index lookup, then matches the link.
   */
  private static void rewriteIndexChainsAsSubqueries(QueryPlanningInfo info, CommandContext ctx) {
    if (ctx == null) {
      return;
    }

    var session = ctx.getDatabaseSession();
    if (session == null) {
      return;
    }

    if (info.whereClause != null
        && info.target != null) {
      var clazz = info.target.getSchemaClass(session);
      if (clazz != null) {
        info.whereClause.getBaseExpression().rewriteIndexChainsAsSubqueries(ctx, clazz);
      }
    }
  }

  /**
   * Splits the per-record LET clause into global and per-record items.
   *
   * <p>Items that can be evaluated once (before the main fetch) are promoted to
   * global LET. The promotion criteria are:
   * <ul>
   *   <li>Expression is early-calculable (does not depend on the current record)</li>
   *   <li>Expression is a set-combination function (unionAll, intersect, difference)</li>
   *   <li>Query subexpression does not reference {@code $parent} (no back-reference
   *       to the outer record)</li>
   * </ul>
   *
   * <p>Promoted items are removed from {@code info.perRecordLetClause} and added to
   * {@code info.globalLetClause}.
   */
  private static void splitLet(QueryPlanningInfo info, CommandContext ctx) {
    if (info.perRecordLetClause != null && info.perRecordLetClause.getItems() != null) {
      var iterator = info.perRecordLetClause.getItems().iterator();
      while (iterator.hasNext()) {
        var item = iterator.next();
        if (item.getExpression() != null
            && (item.getExpression().isEarlyCalculated(ctx)
            || isCombinationOfQueries(item.getExpression()))) {
          iterator.remove();
          addGlobalLet(info, item.getVarName(), item.getExpression());
        } else if (item.getQuery() != null && !item.getQuery().refersToParent()) {
          iterator.remove();
          addGlobalLet(info, item.getVarName(), item.getQuery());
        }
      }
    }
  }

  private static final Set<String> COMBINATION_FUNCTIONS =
      Set.of("unionall", "intersect", "difference");

  /**
   * Checks whether an expression is a set-combination function ({@code unionAll},
   * {@code intersect}, {@code difference}) that operates on query-result variables.
   * Such expressions are always promoted to global LET because they aggregate
   * multiple result sets and do not depend on individual records.
   */
  private static boolean isCombinationOfQueries(SQLExpression expression) {
    if (expression.getMathExpression() instanceof SQLBaseExpression exp) {
      if (exp.getIdentifier() != null
          && exp.getModifier() == null
          && exp.getIdentifier().getLevelZero() != null
          && exp.getIdentifier().getLevelZero().getFunctionCall() != null) {
        var fc = exp.getIdentifier().getLevelZero().getFunctionCall();
        if (COMBINATION_FUNCTIONS.stream()
            .anyMatch(fc.getName().getStringValue()::equalsIgnoreCase)) {
          for (var param : fc.getParams()) {
            if (!param.toString().isEmpty() && param.toString().charAt(0) == '$') {
              return true;
            }
          }
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Reorders the conditions within each AND block so that equality conditions
   * ({@code field = value}) appear before range/other conditions.
   *
   * <p>This is critical for index selection: indexes are matched by prefix, so having
   * equalities first maximizes the number of index fields that can be used.
   *
   * <pre>
   * Before: [age &gt; 20, name = 'Alice', city = 'NYC']
   * After:  [name = 'Alice', city = 'NYC', age &gt; 20]
   *          ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^  equality prefix for composite index
   * </pre>
   */
  @Nullable
  private static List<SQLAndBlock> moveFlattenedEqualitiesLeft(
      List<SQLAndBlock> flattenedWhereClause) {
    if (flattenedWhereClause == null) {
      return null;
    }

    List<SQLAndBlock> result = new ArrayList<>();
    for (var block : flattenedWhereClause) {
      List<SQLBooleanExpression> equalityExpressions = new ArrayList<>();
      List<SQLBooleanExpression> nonEqualityExpressions = new ArrayList<>();
      var newBlock = block.copy();
      for (var exp : newBlock.getSubBlocks()) {
        if (exp instanceof SQLBinaryCondition binCond) {
          if (binCond.getOperator() instanceof SQLEqualsOperator) {
            equalityExpressions.add(exp);
          } else {
            nonEqualityExpressions.add(exp);
          }
        } else {
          nonEqualityExpressions.add(exp);
        }
      }
      var newAnd = new SQLAndBlock(-1);
      newAnd.getSubBlocks().addAll(equalityExpressions);
      newAnd.getSubBlocks().addAll(nonEqualityExpressions);
      result.add(newAnd);
    }

    return result;
  }

  /**
   * Adds synthetic projection items for ORDER BY expressions that are not already
   * present in the user's SELECT list.
   *
   * <p>When a query sorts by an expression not in the projection (e.g.
   * {@code SELECT name FROM Person ORDER BY age}), the sort step needs access to
   * {@code age}. This method:
   * <ol>
   *   <li>Adds a temporary projection with alias {@code _$$$ORDER_BY_ALIAS$$$_N}</li>
   *   <li>Rewrites the ORDER BY item to reference this alias</li>
   *   <li>Creates a {@code projectionAfterOrderBy} that strips the temporary
   *       aliases after sorting is complete</li>
   * </ol>
   *
   * <pre>
   *  Before:  SELECT name FROM Person ORDER BY age
   *  After:   SELECT name, age AS _$$$ORDER_BY_ALIAS$$$_0 ... ORDER BY _$$$ORDER_BY_ALIAS$$$_0
   *           + projectionAfterOrderBy: SELECT name   (strips the temporary column)
   * </pre>
   */
  private static void addOrderByProjections(QueryPlanningInfo info) {
    if (info.orderApplied
        || info.expand
        || info.unwind != null
        || info.orderBy == null
        || info.orderBy.getItems().isEmpty()
        || info.projection == null
        || info.projection.getItems() == null
        || (info.projection.getItems().size() == 1 && info.projection.getItems().getFirst()
        .isAll())) {
      return;
    }

    var newOrderBy = info.orderBy == null ? null : info.orderBy.copy();
    var additionalOrderByProjections =
        calculateAdditionalOrderByProjections(info.projection.getAllAliases(), newOrderBy);
    if (!additionalOrderByProjections.isEmpty()) {
      info.orderBy = newOrderBy; // the ORDER BY has changed
    }
    if (!additionalOrderByProjections.isEmpty()) {
      info.projectionAfterOrderBy = new SQLProjection(-1);
      info.projectionAfterOrderBy.setItems(new ArrayList<>());
      for (var alias : info.projection.getAllAliases()) {
        info.projectionAfterOrderBy.getItems().add(projectionFromAlias(new SQLIdentifier(alias)));
      }

      for (var item : additionalOrderByProjections) {
        if (info.preAggregateProjection != null) {
          info.preAggregateProjection.getItems().add(item);
          info.aggregateProjection.getItems().add(projectionFromAlias(item.getAlias()));
          info.projection.getItems().add(projectionFromAlias(item.getAlias()));
        } else {
          info.projection.getItems().add(item);
        }
      }
    }
  }

  /**
   * Computes synthetic projection items for ORDER BY expressions not covered by existing
   * projection aliases.
   *
   * <p>For each ORDER BY item whose alias is not in {@code allAliases}, a new
   * {@link SQLProjectionItem} is created with a synthetic alias
   * ({@code _$$$ORDER_BY_ALIAS$$$_0}, {@code _$$$ORDER_BY_ALIAS$$$_1}, ...) and the
   * ORDER BY item is rewritten to reference this alias instead of the original expression.
   *
   * @param allAliases existing aliases in the user's projection
   * @param orderBy    the ORDER BY clause (mutated in-place: items are rewritten to use
   *                   the synthetic aliases)
   * @return synthetic projection items to append (empty if all ORDER BY expressions are
   *         already projected)
   */
  private static List<SQLProjectionItem> calculateAdditionalOrderByProjections(
      Set<String> allAliases, SQLOrderBy orderBy) {
    List<SQLProjectionItem> result = new ArrayList<>();
    var nextAliasCount = 0;
    if ((orderBy != null && orderBy.getItems() != null) || !orderBy.getItems().isEmpty()) {
      for (var item : orderBy.getItems()) {
        if (!allAliases.contains(item.getAlias())) {
          var newProj = new SQLProjectionItem(-1);
          if (item.getAlias() != null) {
            newProj.setExpression(
                new SQLExpression(new SQLIdentifier(item.getAlias()), item.getModifier()));
          } else if (item.getRecordAttr() != null) {
            var attr = new SQLRecordAttribute(-1);
            attr.setName(item.getRecordAttr());
            newProj.setExpression(new SQLExpression(attr, item.getModifier()));
          } else if (item.getRid() != null) {
            var exp = new SQLExpression(-1);
            exp.setRid(item.getRid().copy());
            newProj.setExpression(exp);
          }
          var newAlias = new SQLIdentifier("_$$$ORDER_BY_ALIAS$$$_" + nextAliasCount++);
          newProj.setAlias(newAlias);
          item.setAlias(newAlias.getStringValue());
          item.setModifier(null);
          result.add(newProj);
        }
      }
    }
    return result;
  }

  /**
   * Splits the user's SELECT-list projections into three phases to support SQL
   * aggregation with GROUP BY correctly.
   *
   * <pre>
   *  Example:
   *    SELECT city, count(*) AS cnt, max(price) AS mp FROM Product GROUP BY city
   *
   *  Split result:
   *    preAggregateProjection : [city]
   *        -- per-record fields consumed by the aggregation / grouping
   *    aggregateProjection    : [count(*), max(price)]
   *        -- aggregate accumulators, each producing one value per group
   *    projection (post)      : [city, cnt, mp]
   *        -- final output mapping from aliases to accumulated results
   *
   *  Non-aggregate items (e.g. "city") are forwarded through all three phases
   *  so they remain accessible after aggregation.
   *
   *  Pipeline built later:
   *    ... -&gt; ProjectionCalc(pre) -&gt; AggregateProjectionCalc -&gt; ProjectionCalc(post)
   * </pre>
   *
   * <p>If no aggregate functions are found the three-phase split is skipped entirely
   * (the {@code isSplitted} flag stays {@code false}).
   */
  private static void splitProjectionsForGroupBy(QueryPlanningInfo info, CommandContext ctx) {
    if (info.projection == null) {
      return;
    }

    var preAggregate = new SQLProjection(-1);
    preAggregate.setItems(new ArrayList<>());
    var aggregate = new SQLProjection(-1);
    aggregate.setItems(new ArrayList<>());
    var postAggregate = new SQLProjection(-1);
    postAggregate.setItems(new ArrayList<>());

    var isSplitted = false;

    var db = ctx.getDatabaseSession();
    // split for aggregate projections
    var result = new AggregateProjectionSplit();
    for (var item : info.projection.getItems()) {
      result.reset();
      if (isAggregate(db, item)) {
        isSplitted = true;
        var post = item.splitForAggregation(result, ctx);
        var postAlias = item.getProjectionAlias();
        postAlias = new SQLIdentifier(postAlias, true);
        post.setAlias(postAlias);
        postAggregate.getItems().add(post);
        aggregate.getItems().addAll(result.getAggregate());
        preAggregate.getItems().addAll(result.getPreAggregate());
      } else {
        preAggregate.getItems().add(item);
        // also push the alias forward in the chain
        var aggItem = new SQLProjectionItem(-1);
        aggItem.setExpression(new SQLExpression(item.getProjectionAlias()));
        aggregate.getItems().add(aggItem);
        postAggregate.getItems().add(aggItem);
      }
    }

    // bind split projections to the execution planner
    if (isSplitted) {
      info.preAggregateProjection = preAggregate;
      if (info.preAggregateProjection.getItems() == null
          || info.preAggregateProjection.getItems().isEmpty()) {
        info.preAggregateProjection = null;
      }
      info.aggregateProjection = aggregate;
      if (info.aggregateProjection.getItems() == null
          || info.aggregateProjection.getItems().isEmpty()) {
        info.aggregateProjection = null;
      }
      info.projection = postAggregate;

      addGroupByExpressionsToProjections(db, info);
    }
  }

  /** Delegates to the projection item's own aggregate detection logic. */
  private static boolean isAggregate(DatabaseSessionEmbedded session, SQLProjectionItem item) {
    return item.isAggregate(session);
  }

  /** Creates a pass-through projection item that simply references an existing alias by name. */
  private static SQLProjectionItem projectionFromAlias(SQLIdentifier oIdentifier) {
    var result = new SQLProjectionItem(-1);
    result.setExpression(new SQLExpression(oIdentifier));
    return result;
  }

  /**
   * Ensures that GROUP BY expressions are present in the pre-aggregate projections.
   *
   * <p>If a GROUP BY expression matches an existing pre-aggregate alias (by name), it
   * is reused directly. Otherwise, a synthetic projection with alias
   * {@code _$$$GROUP_BY_ALIAS$$$_N} is added to the pre-aggregate projection, and the
   * GROUP BY is rewritten to reference this alias.
   *
   * <pre>
   *  Example:
   *    SELECT count(*) FROM Product GROUP BY city * 2
   *
   *    city * 2 is not in the pre-aggregate projections, so:
   *      preAggregateProjection += [city * 2 AS _$$$GROUP_BY_ALIAS$$$_0]
   *      GROUP BY rewritten to: _$$$GROUP_BY_ALIAS$$$_0
   * </pre>
   */
  private static void addGroupByExpressionsToProjections(DatabaseSessionEmbedded session,
      QueryPlanningInfo info) {
    if (info.groupBy == null
        || info.groupBy.getItems() == null
        || info.groupBy.getItems().isEmpty()) {
      return;
    }
    // Build a new GROUP BY that references projection aliases instead of raw expressions.
    var newGroupBy = new SQLGroupBy(-1);
    var i = 0;
    for (var exp : info.groupBy.getItems()) {
      if (exp.isAggregate(session)) {
        throw new CommandExecutionException(session, "Cannot group by an aggregate function");
      }
      var found = false;
      if (info.preAggregateProjection != null) {
        for (var alias : info.preAggregateProjection.getAllAliases()) {
          // If the GROUP BY expression is a simple identifier matching an existing
          // projection alias, reuse it -- no need for a synthetic alias.
          if (alias.equals(exp.getDefaultAlias().getStringValue()) && exp.isBaseIdentifier()) {
            found = true;
            newGroupBy.getItems().add(exp);
            break;
          }
        }
      }
      if (!found) {
        // The GROUP BY expression is not already projected -- add a synthetic
        // projection (e.g. _$$$GROUP_BY_ALIAS$$$_0) and rewrite the GROUP BY
        // to reference this alias.
        var newItem = new SQLProjectionItem(-1);
        newItem.setExpression(exp);
        var groupByAlias = new SQLIdentifier("_$$$GROUP_BY_ALIAS$$$_" + i++);
        newItem.setAlias(groupByAlias);
        if (info.preAggregateProjection == null) {
          info.preAggregateProjection = new SQLProjection(-1);
        }
        if (info.preAggregateProjection.getItems() == null) {
          info.preAggregateProjection.setItems(new ArrayList<>());
        }
        info.preAggregateProjection.getItems().add(newItem);
        newGroupBy.getItems().add(new SQLExpression(groupByAlias));
      }

      // Replace the original GROUP BY with the rewritten version after each iteration.
      // This is inside the loop so that subsequent iterations see the updated groupBy.
      info.groupBy = newGroupBy;
    }
  }

  /**
   * Extracts inline subqueries from the WHERE, projection, ORDER BY, and GROUP BY
   * clauses and rewrites them as LET variables.
   *
   * <p>Each extracted subquery is assigned a synthetic alias (prefixed
   * {@code $$$SUBQUERY$$_}) and replaced in-place by a variable reference.
   * The subquery itself is added either to the global LET (when it does not
   * reference the current record) or to the per-record LET (when it uses
   * {@code $parent} or similar back-references).
   *
   * <pre>
   *  Before: SELECT * FROM Foo WHERE bar IN (SELECT id FROM Bar)
   *  After:  LET $$$SUBQUERY$$_0 = (SELECT id FROM Bar)
   *          SELECT * FROM Foo WHERE bar IN $$$SUBQUERY$$_0
   * </pre>
   */
  private static void extractSubQueries(QueryPlanningInfo info) {
    var collector = new SubQueryCollector();
    if (info.perRecordLetClause != null) {
      info.perRecordLetClause.extractSubQueries(collector);
    }
    var i = 0;
    var j = 0;
    for (var entry : collector.getSubQueries().entrySet()) {
      var alias = entry.getKey();
      var query = entry.getValue();
      if (query.refersToParent()) {
        addRecordLevelLet(info, alias, query, j++);
      } else {
        addGlobalLet(info, alias, query, i++);
      }
    }
    collector.reset();

    if (info.whereClause != null) {
      info.whereClause.extractSubQueries(collector);
    }
    if (info.projection != null) {
      info.projection.extractSubQueries(collector);
    }
    if (info.orderBy != null) {
      info.orderBy.extractSubQueries(collector);
    }
    if (info.groupBy != null) {
      info.groupBy.extractSubQueries(collector);
    }

    for (var entry : collector.getSubQueries().entrySet()) {
      var alias = entry.getKey();
      var query = entry.getValue();
      if (query.refersToParent()) {
        addRecordLevelLet(info, alias, query);
      } else {
        addGlobalLet(info, alias, query);
      }
    }
  }

  /** Appends an expression-based LET item to the global LET clause. */
  private static void addGlobalLet(QueryPlanningInfo info, SQLIdentifier alias, SQLExpression exp) {
    if (info.globalLetClause == null) {
      info.globalLetClause = new SQLLetClause(-1);
    }
    var item = new SQLLetItem(-1);
    item.setVarName(alias);
    item.setExpression(exp);
    info.globalLetClause.addItem(item);
  }

  /** Appends a subquery-based LET item to the global LET clause. */
  private static void addGlobalLet(QueryPlanningInfo info, SQLIdentifier alias, SQLStatement stm) {
    if (info.globalLetClause == null) {
      info.globalLetClause = new SQLLetClause(-1);
    }
    var item = new SQLLetItem(-1);
    item.setVarName(alias);
    item.setQuery(stm);
    info.globalLetClause.addItem(item);
  }

  /** Inserts a subquery-based LET item at position {@code pos} in the global LET clause. */
  private static void addGlobalLet(
      QueryPlanningInfo info, SQLIdentifier alias, SQLStatement stm, int pos) {
    if (info.globalLetClause == null) {
      info.globalLetClause = new SQLLetClause(-1);
    }
    var item = new SQLLetItem(-1);
    item.setVarName(alias);
    item.setQuery(stm);
    info.globalLetClause.getItems().add(pos, item);
  }

  /** Appends a subquery-based LET item to the per-record LET clause. */
  private static void addRecordLevelLet(QueryPlanningInfo info, SQLIdentifier alias,
      SQLStatement stm) {
    if (info.perRecordLetClause == null) {
      info.perRecordLetClause = new SQLLetClause(-1);
    }
    var item = new SQLLetItem(-1);
    item.setVarName(alias);
    item.setQuery(stm);
    info.perRecordLetClause.addItem(item);
  }

  /** Inserts a subquery-based LET item at position {@code pos} in the per-record LET clause. */
  private static void addRecordLevelLet(
      QueryPlanningInfo info, SQLIdentifier alias, SQLStatement stm, int pos) {
    if (info.perRecordLetClause == null) {
      info.perRecordLetClause = new SQLLetClause(-1);
    }
    var item = new SQLLetItem(-1);
    item.setVarName(alias);
    item.setQuery(stm);
    info.perRecordLetClause.getItems().add(pos, item);
  }

  /**
   * Determines the data source for the SELECT and appends the appropriate fetch step(s).
   *
   * <p>Target resolution strategy (first match wins):
   * <pre>
   *  Target AST node         | Handler                     | Fetch step(s)
   *  ------------------------|-----------------------------|--------------------------
   *  null                    | handleNoTarget()            | EmptyDataGeneratorStep
   *  $variable               | handleVariableAsTarget()    | FetchFromVariableStep
   *  ClassName               | handleClassAsTarget()       | FetchFromClass/Index/...
   *  (SELECT subquery)       | handleSubqueryAsTarget()    | SubQueryStep
   *  :inputParam             | handleInputParamAsTarget()  | depends on param type
   *  multiple :params        | (parallel sub-plans)        | ParallelExecStep
   *  metadata:SCHEMA/...     | handleMetadataAsTarget()    | FetchFromRids/Metadata
   *  [#rid1, #rid2, ...]     | handleRidsAsTarget()        | FetchFromRidsStep
   * </pre>
   *
   * <p>For class targets, index-based optimizations are attempted before falling
   * back to a full class scan (see {@link #handleClassAsTarget}).
   */
  private void handleFetchFromTarget(
      SelectExecutionPlan result,
      QueryPlanningInfo info,
      CommandContext ctx,
      boolean profilingEnabled) {

    var target = info.target == null ? null : info.target.getItem();
    if (target == null) {
      handleNoTarget(result, ctx, profilingEnabled);
    } else if (target.getIdentifier() != null) {
      var className = target.getIdentifier().getStringValue();
      if (!className.isEmpty() && className.charAt(0) == '$'
          && !ctx.getDatabaseSession()
          .getMetadata()
          .getImmutableSchemaSnapshot()
          .existsClass(className)) {
        handleVariableAsTarget(result, info, ctx, profilingEnabled);
      } else {
        var ridRangeConditions = extractRidRanges(info.flattenedWhereClause, ctx);
        if (!ridRangeConditions.isEmpty()) {
          info.ridRangeConditions = ridRangeConditions;
        }

        handleClassAsTarget(result, info, ctx, profilingEnabled);
      }
    } else if (target.getStatement() != null) {
      handleSubqueryAsTarget(
          result, target.getStatement(), ctx, profilingEnabled);
    } else if (target.getFunctionCall() != null) {
      throw new CommandExecutionException(ctx.getDatabaseSession(),
          "function call as target is not supported yet");
    } else if (target.getInputParam() != null) {
      handleInputParamAsTarget(
          result,
          info,
          target.getInputParam(),
          ctx,
          profilingEnabled);
    } else if (target.getInputParams() != null && !target.getInputParams().isEmpty()) {
      List<InternalExecutionPlan> plans = new ArrayList<>();
      for (var param : target.getInputParams()) {
        var subPlan = new SelectExecutionPlan(ctx);
        handleInputParamAsTarget(
            subPlan,
            info,
            param,
            ctx,
            profilingEnabled);
        plans.add(subPlan);
      }
      result.chain(new ParallelExecStep(plans, ctx, profilingEnabled));
    } else if (target.getMetadata() != null) {
      handleMetadataAsTarget(result, target.getMetadata(), ctx, profilingEnabled);
    } else if (target.getRids() != null && !target.getRids().isEmpty()) {
      handleRidsAsTarget(result, target.getRids(), ctx, profilingEnabled);
    } else {
      throw new UnsupportedOperationException();
    }
  }

  /**
   * Handles a context variable (e.g. {@code $myVar}) as the FROM target.
   * The variable's value is expected to be iterable (typically a result set
   * from a previous LET assignment).
   */
  private static void handleVariableAsTarget(
      SelectExecutionPlan plan,
      QueryPlanningInfo info,
      CommandContext ctx,
      boolean profilingEnabled) {
    final var targetItem = info.target.getItem();
    if (targetItem.getModifier() != null) {
      throw new CommandExecutionException(ctx.getDatabaseSession(),
          "Modifiers cannot be used with variables: " + targetItem);
    }
    plan.chain(
        new FetchFromVariableStep(
            targetItem.getIdentifier().getStringValue(), ctx, profilingEnabled));
  }

  /**
   * Extracts RID range conditions (e.g. {@code @rid > #10:5 AND @rid < #10:100}) from
   * the flattened WHERE clause. These conditions are stored separately in
   * {@link QueryPlanningInfo#ridRangeConditions} and used to narrow the collection scan
   * range in {@link FetchFromClassExecutionStep}.
   */
  private static SQLAndBlock extractRidRanges(List<SQLAndBlock> flattenedWhereClause,
      CommandContext ctx) {
    var result = new SQLAndBlock(-1);

    if (flattenedWhereClause == null || flattenedWhereClause.size() != 1) {
      return result;
    }

    for (var booleanExpression : flattenedWhereClause.getFirst().getSubBlocks()) {
      if (isRidRange(booleanExpression, ctx)) {
        result.getSubBlocks().add(booleanExpression.copy());
      }
    }

    return result;
  }

  /**
   * Returns {@code true} if the given boolean expression is a range comparison on
   * {@code @rid} (e.g. {@code @rid > #10:5}).
   */
  private static boolean isRidRange(SQLBooleanExpression booleanExpression, CommandContext ctx) {
    if (booleanExpression instanceof SQLBinaryCondition cond) {
      var operator = cond.getOperator();
      if (operator.isRangeOperator() && cond.getLeft().toString().equalsIgnoreCase("@rid")) {
        Object obj;
        if (cond.getRight().getRid() != null) {
          obj = cond.getRight().getRid().toRecordId((Result) null, ctx);
        } else {
          obj = cond.getRight().execute((Result) null, ctx);
        }
        return obj instanceof Identifiable;
      }
    }
    return false;
  }

  /**
   * Handles a positional / named input parameter ({@code ?} or {@code :param}) as
   * the FROM target. The runtime value is inspected and dispatched to the
   * appropriate handler:
   * <ul>
   *   <li>{@code null} -- {@link EmptyStep} (no rows)</li>
   *   <li>{@link SchemaClass} or String -- treated as a class name</li>
   *   <li>{@link Identifiable} -- single RID fetch</li>
   *   <li>{@link Iterable} -- collection of RIDs</li>
   * </ul>
   */
  private void handleInputParamAsTarget(
      SelectExecutionPlan result,
      QueryPlanningInfo info,
      SQLInputParameter inputParam,
      CommandContext ctx,
      boolean profilingEnabled) {
    var session = ctx.getDatabaseSession();
    var paramValue = inputParam.getValue(ctx.getInputParameters());
    switch (paramValue) {
      case null -> result.chain(new EmptyStep(ctx, profilingEnabled)); // nothing to return
      case SchemaClass schemaClass -> {
        var from = new SQLFromClause(-1);
        var item = new SQLFromItem(-1);
        from.setItem(item);
        item.setIdentifier(new SQLIdentifier(schemaClass.getName()));
        handleClassAsTarget(result, from, info, ctx, profilingEnabled);
      }
      case String s -> {
        // strings are treated as classes
        var from = new SQLFromClause(-1);
        var item = new SQLFromItem(-1);
        from.setItem(item);
        item.setIdentifier(new SQLIdentifier(s));
        handleClassAsTarget(result, from, info, ctx, profilingEnabled);
      }
      case Identifiable identifiable -> {
        var orid = identifiable.getIdentity();

        var rid = new SQLRid(-1);
        var collection = new SQLInteger(-1);
        collection.setValue(orid.getCollectionId());
        var position = new SQLInteger(-1);
        position.setValue(orid.getCollectionPosition());
        rid.setLegacy(true);
        rid.setCollection(collection);
        rid.setPosition(position);
        handleRidsAsTarget(result, Collections.singletonList(rid), ctx, profilingEnabled);
      }
      case Iterable<?> iterable -> {
        // try list of RIDs
        List<SQLRid> rids = new ArrayList<>();
        for (var x : iterable) {
          if (!(x instanceof Identifiable id)) {
            throw new CommandExecutionException(session,
                "Cannot use colleciton as target: " + paramValue);
          }
          var orid = id.getIdentity();

          var rid = new SQLRid(-1);
          var collection = new SQLInteger(-1);
          collection.setValue(orid.getCollectionId());
          var position = new SQLInteger(-1);
          position.setValue(orid.getCollectionPosition());
          rid.setCollection(collection);
          rid.setPosition(position);
          rids.add(rid);
        }
        if (!rids.isEmpty()) {
          handleRidsAsTarget(result, rids, ctx, profilingEnabled);
        } else {
          result.chain(new EmptyStep(ctx, profilingEnabled)); // nothing to return
        }
      }
      default -> throw new CommandExecutionException(session, "Invalid target: " + paramValue);
    }
  }

  /**
   * Handles a SELECT without a FROM clause (e.g. {@code SELECT 1+1, sysdate()}).
   * Produces a single empty record so that the projection step can evaluate
   * expressions exactly once.
   */
  private static void handleNoTarget(
      SelectExecutionPlan result, CommandContext ctx, boolean profilingEnabled) {
    result.chain(new EmptyDataGeneratorStep(1, ctx, profilingEnabled));
  }


  /**
   * Handles {@code SELECT FROM metadata:SCHEMA}, {@code metadata:INDEXES},
   * {@code metadata:STORAGE}, or {@code metadata:DATABASE} targets.
   * Each is mapped to a specialized fetch step that reads the corresponding
   * metadata structures.
   */
  private static void handleMetadataAsTarget(
      SelectExecutionPlan plan,
      SQLMetadataIdentifier metadata,
      CommandContext ctx,
      boolean profilingEnabled) {
    var db = ctx.getDatabaseSession();
    String schemaRecordIdAsString;
    if (metadata.getName().equalsIgnoreCase("SCHEMA")) {
      schemaRecordIdAsString = db.getStorage().getSchemaRecordId();
      var schemaRid = RecordIdInternal.fromString(schemaRecordIdAsString, false);
      plan.chain(new FetchFromRidsStep(Collections.singleton(schemaRid), ctx, profilingEnabled));
    } else if (metadata.getName().equalsIgnoreCase("INDEXES")) {
      plan.chain(new FetchFromIndexManagerStep(ctx, profilingEnabled));
    } else if (metadata.getName().equalsIgnoreCase("STORAGE")) {
      plan.chain(new FetchFromStorageMetadataStep(ctx, profilingEnabled));
    } else if (metadata.getName().equalsIgnoreCase("DATABASE")) {
      plan.chain(new FetchFromDatabaseMetadataStep(ctx, profilingEnabled));
    } else {
      throw new UnsupportedOperationException("Invalid metadata: " + metadata.getName());
    }
  }

  /**
   * Handles an explicit list of RIDs as the FROM target
   * (e.g. {@code SELECT FROM [#10:3, #10:7]}).
   */
  private static void handleRidsAsTarget(
      SelectExecutionPlan plan, List<SQLRid> rids, CommandContext ctx, boolean profilingEnabled) {
    List<RecordIdInternal> actualRids = new ArrayList<>();
    for (var rid : rids) {
      actualRids.add(rid.toRecordId((Result) null, ctx));
    }
    plan.chain(new FetchFromRidsStep(actualRids, ctx, profilingEnabled));
  }

  /**
   * Appends an {@link ExpandStep} when the projection uses {@code expand(field)}.
   * EXPAND takes a single link/collection field and expands each element into its
   * own result row (analogous to UNWIND but resolves links to full records).
   */
  private static void handleExpand(
      SelectExecutionPlan result,
      QueryPlanningInfo info,
      CommandContext ctx,
      boolean profilingEnabled) {
    if (info.expand) {
      result.chain(new ExpandStep(ctx, profilingEnabled, info.expandAlias));
    }
  }

  /**
   * Appends global LET steps to the plan. Global LET items are evaluated exactly
   * once (before the main fetch) and their results are stored in the command context
   * for later use by per-record expressions.
   *
   * <p>Two step types are used:
   * <ul>
   *   <li>{@link GlobalLetExpressionStep} -- for simple expressions
   *       (e.g. {@code LET $x = 42})</li>
   *   <li>{@link GlobalLetQueryStep} -- for subquery expressions
   *       (e.g. {@code LET $x = (SELECT FROM Foo)})</li>
   * </ul>
   *
   * <p>The items are sorted to match the original LET declaration order
   * (important when one LET variable references a previous one).
   */
  private void handleGlobalLet(
      SelectExecutionPlan result,
      QueryPlanningInfo info,
      CommandContext ctx,
      boolean profilingEnabled) {
    if (info.globalLetClause != null) {
      var items = info.globalLetClause.getItems();
      items = sortLet(items, this.statement.getLetClause());
      List<String> scriptVars = new ArrayList<>();
      for (var item : items) {
        if (item.getExpression() != null) {
          result.chain(
              new GlobalLetExpressionStep(
                  item.getVarName(), item.getExpression(), ctx, profilingEnabled));
        } else {
          result.chain(
              new GlobalLetQueryStep(
                  item.getVarName(), item.getQuery(), ctx, profilingEnabled, scriptVars));
        }
        scriptVars.add(item.getVarName().getStringValue());
        info.globalLetPresent = true;
      }
    }
  }

  /**
   * Appends per-record LET steps. Unlike global LETs, these are evaluated once
   * for every record flowing through the pipeline.
   *
   * <p>Uses {@link LetExpressionStep} for expressions and {@link LetQueryStep}
   * for subqueries. Results are stored as metadata on the current result row
   * so they can be referenced by subsequent WHERE / projection expressions.
   *
   * <p>Note: this method may be called multiple times during planning (e.g.
   * from the indexed-function path that injects per-record LETs into sub-plans).
   */
  private void handleLet(
      SelectExecutionPlan plan,
      QueryPlanningInfo info,
      CommandContext ctx,
      boolean profilingEnabled) {
    // this could be invoked multiple times
    // so it can be optimized
    // checking whether the execution plan already contains some LET steps
    // and in case skip
    if (info.perRecordLetClause != null) {
      var items = info.perRecordLetClause.getItems();
      items = sortLet(items, this.statement.getLetClause());

      for (var item : items) {
        if (item.getExpression() != null) {
          plan.chain(
              new LetExpressionStep(
                  item.getVarName(), item.getExpression(), ctx, profilingEnabled));
        } else {
          plan.chain(new LetQueryStep(item.getVarName(), item.getQuery(), ctx, profilingEnabled));
        }
      }
    }
  }

  /**
   * Sorts LET items to match the order declared in the original SQL LET clause.
   * This is important because LET items may reference previously declared variables
   * (e.g. {@code LET $a = 1, $b = $a + 1}) so evaluation order must be preserved.
   *
   * <p>Items present in {@code items} but not in the original {@code letClause}
   * (e.g. synthetic variables from {@code extractSubQueries()}) are appended at
   * the end in their original insertion order.
   */
  private static List<SQLLetItem> sortLet(List<SQLLetItem> items, SQLLetClause letClause) {
    if (letClause == null) {
      return items;
    }
    List<SQLLetItem> i = new ArrayList<>(items);
    var result = new ArrayList<SQLLetItem>();
    for (var item : letClause.getItems()) {
      var var = item.getVarName().getStringValue();
      var iterator = i.iterator();
      while (iterator.hasNext()) {
        var x = iterator.next();
        if (x.getVarName().getStringValue().equals(var)) {
          iterator.remove();
          result.add(x);
          break;
        }
      }
    }
    result.addAll(i);
    return result;
  }

  /**
   * Appends a {@link FilterStep} for the WHERE clause (if present and not already
   * consumed by an index-based optimization).
   *
   * <p>Note: when index-based fetch is used, the planner may have already set
   * {@code info.whereClause = null} to indicate that the WHERE was fully satisfied
   * by the index. In that case this method is a no-op.
   */
  private void handleWhere(
      SelectExecutionPlan plan,
      QueryPlanningInfo info,
      CommandContext ctx,
      boolean profilingEnabled) {
    if (info.whereClause != null) {
      plan.chain(
          new FilterStep(
              info.whereClause,
              ctx,
              this.info.timeout != null ? this.info.timeout.getVal().longValue() : -1,
              profilingEnabled));
    }
  }

  /**
   * Appends an {@link OrderByStep} (and optionally a post-ORDER-BY projection) if:
   * <ul>
   *   <li>An ORDER BY clause exists</li>
   *   <li>The order has not already been satisfied by an index scan
   *       ({@code info.orderApplied == false})</li>
   * </ul>
   *
   * <p>The step loads all upstream records into memory and sorts them. When both SKIP
   * and LIMIT are specified (and no EXPAND/UNWIND invalidates them), the step is told
   * the maximum number of results needed ({@code SKIP + LIMIT}) so it can use a
   * bounded priority queue instead of a full sort.
   *
   * <p>Edge properties (e.g. {@code out_FriendOf}) are detected and flagged so the
   * comparator can handle LINKBAG values correctly.
   *
   * <p>If {@code projectionAfterOrderBy} is set (i.e. synthetic ORDER BY aliases were
   * added during planning), an additional projection step strips those temporary columns.
   */
  public static void handleOrderBy(
      SelectExecutionPlan plan,
      QueryPlanningInfo info,
      CommandContext ctx,
      boolean profilingEnabled) {
    var session = ctx.getDatabaseSession();
    var skipSize = info.skip == null ? 0 : info.skip.getValue(ctx);
    if (skipSize < 0) {
      throw new CommandExecutionException(session, "Cannot execute a query with a negative SKIP");
    }
    var limitSize = info.limit == null ? -1 : info.limit.getValue(ctx);
    Integer maxResults = null;
    if (limitSize >= 0) {
      maxResults = skipSize + limitSize;
    }
    if (info.expand || info.unwind != null) {
      maxResults = null;
    }

    if (!info.orderApplied
        && info.orderBy != null
        && info.orderBy.getItems() != null
        && !info.orderBy.getItems().isEmpty()) {

      if (info.target != null) {
        var targetClass = info.target.getSchemaClass(session);
        if (targetClass != null) {
          info.orderBy
              .getItems()
              .forEach(
                  item -> {
                    var possibleEdgeProperty =
                        targetClass.getProperty("out_" + item.getAlias());
                    if (possibleEdgeProperty != null
                        && possibleEdgeProperty.getType() == PropertyType.LINKBAG) {
                      item.setEdge(true);
                    }
                  });
        }
      }
      plan.chain(
          new OrderByStep(
              info.orderBy,
              maxResults,
              ctx,
              info.timeout != null ? info.timeout.getVal().longValue() : -1,
              profilingEnabled));
      if (info.projectionAfterOrderBy != null) {
        plan.chain(
            new ProjectionCalculationStep(info.projectionAfterOrderBy, ctx, profilingEnabled));
      }
    }
  }

  /** Delegates to the full {@link #handleClassAsTarget} with the info's own target. */
  private void handleClassAsTarget(
      SelectExecutionPlan plan,
      QueryPlanningInfo info,
      CommandContext ctx,
      boolean profilingEnabled) {
    handleClassAsTarget(plan, info.target, info, ctx, profilingEnabled);
  }

  /**
   * Central entry point for class-based data fetch. Tries optimizations in this order:
   *
   * <pre>
   *   1. handleClassAsTargetWithIndexedFunction  -- indexed function in WHERE
   *   2. handleClassAsTargetWithIndex            -- regular index lookup
   *   3. handleClassWithIndexForSortOnly         -- index used only for ORDER BY
   *   4. FetchFromClassExecutionStep             -- full class scan (fallback)
   * </pre>
   *
   * <p>After the fetch step, a {@link FilterByClassStep} is always appended when an
   * index was used, because the index may cover a superclass and return records from
   * sibling classes that must be filtered out.
   *
   * <p>For the full-scan fallback, RID ordering (ASC/DESC) is pushed down to the
   * fetch step when the ORDER BY is simply {@code ORDER BY @rid}.
   */
  private void handleClassAsTarget(
      SelectExecutionPlan plan,
      SQLFromClause from,
      QueryPlanningInfo info,
      CommandContext ctx,
      boolean profilingEnabled) {
    var identifier = from.getItem().getIdentifier();
    if (handleClassAsTargetWithIndexedFunction(
        plan, identifier, info, ctx, profilingEnabled)) {
      plan.chain(new FilterByClassStep(identifier, ctx, profilingEnabled));
      return;
    }

    if (handleClassAsTargetWithIndex(
        plan, identifier, info, ctx, profilingEnabled)) {
      plan.chain(new FilterByClassStep(identifier, ctx, profilingEnabled));
      return;
    }

    if (info.orderBy != null
        && handleClassWithIndexForSortOnly(
        plan, identifier, info, ctx, profilingEnabled)) {
      plan.chain(new FilterByClassStep(identifier, ctx, profilingEnabled));
      return;
    }

    Boolean orderByRidAsc = null; // null: no order. true: asc, false:desc
    if (isOrderByRidAsc(info)) {
      orderByRidAsc = true;
    } else if (isOrderByRidDesc(info)) {
      orderByRidAsc = false;
    }
    var className = identifier.getStringValue();
    Schema schema = getSchemaFromContext(ctx);

    AbstractExecutionStep fetcher;
    if (schema.getClass(className) != null) {
      fetcher =
          new FetchFromClassExecutionStep(
              className, null, info, ctx, orderByRidAsc, profilingEnabled);
    } else {
      throw new CommandExecutionException(ctx.getDatabaseSession(),
          "Class or View not present in the schema: " + className);
    }

    if (orderByRidAsc != null) {
      info.orderApplied = true;
    }
    plan.chain(fetcher);
  }

  /**
   * Filters a class's polymorphic collection IDs to only those whose names appear in
   * the given set. Used when a subclass hierarchy query should only scan specific
   * collections.
   *
   * @param db                the database session (for resolving collection names)
   * @param clazz             the schema class whose polymorphic collection IDs are filtered
   * @param filterCollections the set of allowed collection names
   * @return an {@link IntArrayList} containing only the matching collection IDs
   */
  private static IntArrayList classCollectionsFiltered(
      DatabaseSessionEmbedded db, SchemaClass clazz, Set<String> filterCollections) {
    var ids = clazz.getPolymorphicCollectionIds();
    var filtered = new IntArrayList();
    for (var id : ids) {
      if (filterCollections.contains(db.getCollectionNameById(id))) {
        filtered.add(id);
      }
    }
    return filtered;
  }

  /**
   * Attempts to execute the query using indexed functions found in the WHERE clause
   * (e.g. spatial functions like {@code ST_Within()}).
   *
   * <p>For each OR-branch (AND block) in the flattened WHERE clause, the planner:
   * <ol>
   *   <li>Checks for indexed function conditions via
   *       {@code block.getIndexedFunctionConditions()}</li>
   *   <li>If found, picks the best candidate function and creates a
   *       {@link FetchFromIndexedFunctionStep}</li>
   *   <li>If not found, falls back to regular index lookup or full scan for that branch</li>
   *   <li>Remaining WHERE conditions (not covered by the function) are applied as
   *       post-fetch {@link FilterStep}</li>
   * </ol>
   *
   * <p>When the WHERE has multiple OR branches, each branch produces a sub-plan and
   * all are combined via {@link ParallelExecStep} + {@link DistinctExecutionStep}.
   *
   * <p>If this method succeeds, it clears {@code info.whereClause} and
   * {@code info.flattenedWhereClause} to signal that the WHERE has been fully handled.
   *
   * @return {@code true} if the query was handled via indexed functions
   */
  private boolean handleClassAsTargetWithIndexedFunction(
      SelectExecutionPlan plan,
      SQLIdentifier queryTarget,
      QueryPlanningInfo info,
      CommandContext ctx,
      boolean profilingEnabled) {
    if (queryTarget == null) {
      return false;
    }
    var schema = getSchemaFromContext(ctx);
    var clazz = schema.getClassInternal(queryTarget.getStringValue());
    if (clazz == null) {
      throw new CommandExecutionException(ctx.getDatabaseSession(),
          "Class not found: " + queryTarget);
    }
    if (info.flattenedWhereClause == null || info.flattenedWhereClause.isEmpty()) {
      return false;
    }

    List<InternalExecutionPlan> resultSubPlans = new ArrayList<>();

    var indexedFunctionsFound = false;

    for (var block : info.flattenedWhereClause) {
      var indexedFunctionConditions =
          block.getIndexedFunctionConditions(clazz, ctx.getDatabaseSession());

      indexedFunctionConditions =
          filterIndexedFunctionsWithoutIndex(indexedFunctionConditions, info.target, ctx);

      if (indexedFunctionConditions == null || indexedFunctionConditions.isEmpty()) {
        var bestIndex = findBestIndexFor(ctx,
            clazz.getIndexesInternal(),
            block, clazz);
        if (bestIndex != null) {

          var step = new FetchFromIndexStep(bestIndex, true, ctx, profilingEnabled);

          var subPlan = new SelectExecutionPlan(ctx);
          subPlan.chain(step);
          IntArrayList filterCollectionIds;

          filterCollectionIds = IntArrayList.of(clazz.getPolymorphicCollectionIds());
          subPlan.chain(new GetValueFromIndexEntryStep(ctx, filterCollectionIds, profilingEnabled));
          if (bestIndex.requiresDistinctStep()) {
            subPlan.chain(new DistinctExecutionStep(ctx, profilingEnabled));
          }
          if (!block.getSubBlocks().isEmpty()) {
            if ((info.perRecordLetClause != null && refersToLet(block.getSubBlocks()))) {
              handleLet(subPlan, info, ctx, profilingEnabled);
            }
            subPlan.chain(
                new FilterStep(
                    createWhereFrom(block),
                    ctx,
                    this.info.timeout != null ? this.info.timeout.getVal().longValue() : -1,
                    profilingEnabled));
          }
          resultSubPlans.add(subPlan);
        } else {
          FetchFromClassExecutionStep step;
          step =
              new FetchFromClassExecutionStep(
                  clazz.getName(), null, ctx, true, profilingEnabled);

          var subPlan = new SelectExecutionPlan(ctx);
          subPlan.chain(step);
          if (!block.getSubBlocks().isEmpty()) {
            if ((info.perRecordLetClause != null && refersToLet(block.getSubBlocks()))) {
              handleLet(subPlan, info, ctx, profilingEnabled);
            }
            subPlan.chain(
                new FilterStep(
                    createWhereFrom(block),
                    ctx,
                    this.info.timeout != null ? this.info.timeout.getVal().longValue() : -1,
                    profilingEnabled));
          }
          resultSubPlans.add(subPlan);
        }
      } else {
        SQLBinaryCondition blockCandidateFunction = null;
        for (var cond : indexedFunctionConditions) {
          if (!cond.allowsIndexedFunctionExecutionOnTarget(info.target, ctx)) {
            if (!cond.canExecuteIndexedFunctionWithoutIndex(info.target, ctx)) {
              throw new CommandExecutionException(ctx.getDatabaseSession(),
                  "Cannot execute " + block + " on " + queryTarget);
            }
          }
          if (blockCandidateFunction == null) {
            blockCandidateFunction = cond;
          } else {
            var thisAllowsNoIndex =
                cond.canExecuteIndexedFunctionWithoutIndex(info.target, ctx);
            var prevAllowsNoIndex =
                blockCandidateFunction.canExecuteIndexedFunctionWithoutIndex(info.target, ctx);
            if (!thisAllowsNoIndex && !prevAllowsNoIndex) {
              // none of the functions allow execution without index, so cannot choose one
              throw new CommandExecutionException(ctx.getDatabaseSession(),
                  "Cannot choose indexed function between "
                      + cond
                      + " and "
                      + blockCandidateFunction
                      + ". Both require indexed execution");
            } else if (thisAllowsNoIndex && prevAllowsNoIndex) {
              // both can be calculated without index, choose the best one for index execution
              var thisEstimate = cond.estimateIndexed(info.target, ctx);
              var lastEstimate = blockCandidateFunction.estimateIndexed(info.target, ctx);
              if (thisEstimate > -1 && thisEstimate < lastEstimate) {
                blockCandidateFunction = cond;
              }
            } else if (prevAllowsNoIndex) {
              // choose current condition, because the other one can be calculated without index
              blockCandidateFunction = cond;
            }
          }
        }

        var step =
            new FetchFromIndexedFunctionStep(
                blockCandidateFunction, info.target, ctx, profilingEnabled);
        if (!blockCandidateFunction.executeIndexedFunctionAfterIndexSearch(info.target, ctx)) {
          block = block.copy();
          block.getSubBlocks().remove(blockCandidateFunction);
        }
        if (info.flattenedWhereClause.size() == 1) {
          plan.chain(step);
          if (!block.getSubBlocks().isEmpty()) {
            if ((info.perRecordLetClause != null && refersToLet(block.getSubBlocks()))) {
              handleLet(plan, info, ctx, profilingEnabled);
            }
            plan.chain(
                new FilterStep(
                    createWhereFrom(block),
                    ctx,
                    this.info.timeout != null ? this.info.timeout.getVal().longValue() : -1,
                    profilingEnabled));
          }
        } else {
          var subPlan = new SelectExecutionPlan(ctx);
          subPlan.chain(step);
          if (!block.getSubBlocks().isEmpty()) {
            subPlan.chain(
                new FilterStep(
                    createWhereFrom(block),
                    ctx,
                    this.info.timeout != null ? this.info.timeout.getVal().longValue() : -1,
                    profilingEnabled));
          }
          resultSubPlans.add(subPlan);
        }
        indexedFunctionsFound = true;
      }
    }

    if (indexedFunctionsFound) {
      if (resultSubPlans.size()
          > 1) { // if resultSubPlans.size() == 1 the step was already chained (see above)
        plan.chain(new ParallelExecStep(resultSubPlans, ctx, profilingEnabled));
        plan.chain(new DistinctExecutionStep(ctx, profilingEnabled));
      }
      // WHERE condition already applied
      info.whereClause = null;
      info.flattenedWhereClause = null;
      return true;
    } else {
      return false;
    }
  }

  /**
   * Returns {@code true} if any of the boolean expressions reference a LET variable
   * (identifiable by a {@code $} prefix). When LET variables are referenced in a
   * WHERE sub-block, the per-record LET steps must be injected into the sub-plan
   * before the filter step.
   */
  private static boolean refersToLet(List<SQLBooleanExpression> subBlocks) {
    if (subBlocks == null) {
      return false;
    }
    for (var exp : subBlocks) {
      if (!exp.toString().isEmpty() && exp.toString().charAt(0) == '$') {
        return true;
      }
    }
    return false;
  }

  /**
   * Filters out indexed function conditions that cannot actually be executed via an
   * index on the given target. Conditions that can be evaluated without an index
   * (fallback mode) are silently excluded; conditions that require an index but
   * none exists cause an exception.
   */
  @Nullable
  private static List<SQLBinaryCondition> filterIndexedFunctionsWithoutIndex(
      List<SQLBinaryCondition> indexedFunctionConditions,
      SQLFromClause fromClause,
      CommandContext ctx) {
    if (indexedFunctionConditions == null) {
      return null;
    }
    List<SQLBinaryCondition> result = new ArrayList<>();
    for (var cond : indexedFunctionConditions) {
      if (cond.allowsIndexedFunctionExecutionOnTarget(fromClause, ctx)) {
        result.add(cond);
      } else if (!cond.canExecuteIndexedFunctionWithoutIndex(fromClause, ctx)) {
        throw new CommandExecutionException(ctx.getDatabaseSession(),
            "Cannot evaluate " + cond + ": no index defined");
      }
    }
    return result;
  }

  /**
   * Attempts to satisfy the ORDER BY clause using an index scan (without a WHERE
   * index lookup). When an index's field order matches the ORDER BY fields, the step
   * can iterate the index in the desired direction to produce pre-sorted results,
   * eliminating the need for an in-memory sort.
   *
   * <pre>
   *  Example:
   *    SELECT FROM Person ORDER BY lastName ASC
   *    Index on [lastName]
   *
   *    Pipeline:
   *      FetchFromIndexValuesStep(ASC) -&gt; GetValueFromIndexEntryStep -&gt; ...
   *      (no OrderByStep needed -- data is already sorted)
   * </pre>
   *
   * @return {@code true} if an index was used for sorting (plan is updated);
   *         {@code false} if no suitable index was found (caller should fall back)
   */
  private boolean handleClassWithIndexForSortOnly(
      SelectExecutionPlan plan,
      SQLIdentifier queryTarget,
      QueryPlanningInfo info,
      CommandContext ctx,
      boolean profilingEnabled) {
    var schema = getSchemaFromContext(ctx);
    var clazz = schema.getClassInternal(queryTarget.getStringValue());
    if (clazz == null) {
      throw new CommandExecutionException(ctx.getDatabaseSession(),
          "Class not found: " + queryTarget);
    }

    for (var idx :
        clazz.getIndexesInternal().stream()
            .filter(i -> i.getDefinition() != null)
            .toList()) {
      var indexFields = idx.getDefinition().getProperties();
      if (indexFields.size() < info.orderBy.getItems().size()) {
        continue;
      }
      var indexFound = true;
      String orderType = null;
      for (var i = 0; i < info.orderBy.getItems().size(); i++) {
        var orderItem = info.orderBy.getItems().get(i);
        if (orderItem.getCollate() != null) {
          return false;
        }
        var indexField = indexFields.get(i);
        if (i == 0) {
          orderType = orderItem.getType();
        } else {
          if (orderType == null || !orderType.equals(orderItem.getType())) {
            indexFound = false;
            break; // ASC/DESC interleaved, cannot be used with index.
          }
        }
        if (!(indexField.equals(orderItem.getAlias())
            || isInOriginalProjection(indexField, orderItem.getAlias()))) {
          indexFound = false;
          break;
        }
      }
      if (indexFound && orderType != null) {
        plan.chain(
            new FetchFromIndexValuesStep(
                new IndexSearchDescriptor(idx),
                orderType.equals(SQLOrderByItem.ASC),
                ctx,
                profilingEnabled));
        IntArrayList filterCollectionIds;
        filterCollectionIds = IntArrayList.of(clazz.getPolymorphicCollectionIds());
        plan.chain(new GetValueFromIndexEntryStep(ctx, filterCollectionIds, profilingEnabled));
        info.orderApplied = true;
        return true;
      }
    }
    return false;
  }

  /**
   * Returns {@code true} if {@code alias} is a projected alias for an expression
   * that equals {@code indexField}. This is needed to match ORDER BY items that
   * reference a projection alias rather than the raw field name.
   */
  private boolean isInOriginalProjection(String indexField, String alias) {
    if (info.projection == null) {
      return false;
    }
    if (info.projection.getItems() == null) {
      return false;
    }
    return info.projection.getItems().stream()
        .filter(proj -> proj.getExpression().toString().equals(indexField))
        .filter(proj -> proj.getAlias() != null)
        .anyMatch(proj -> proj.getAlias().getStringValue().equals(alias));
  }

  /**
   * Attempts to satisfy the entire WHERE clause using index lookups on the target
   * class. If the target class itself has no suitable index but is the root of a
   * class hierarchy with subclasses that do, the planner recursively tries each
   * subclass and combines results via {@link ParallelExecStep}.
   *
   * <p>If successful, clears {@code info.whereClause} and
   * {@code info.flattenedWhereClause}.
   *
   * @return {@code true} if index-based fetch was set up for all OR-branches
   */
  private boolean handleClassAsTargetWithIndex(
      SelectExecutionPlan plan,
      SQLIdentifier targetClass,
      QueryPlanningInfo info,
      CommandContext ctx,
      boolean profilingEnabled) {

    var result =
        handleClassAsTargetWithIndex(
            targetClass.getStringValue(), null, info, ctx,
            profilingEnabled, true);

    if (result != null) {
      result.forEach(plan::chain);
      info.whereClause = null;
      info.flattenedWhereClause = null;
      return true;
    }

    var schema = getSchemaFromContext(ctx);
    var clazz = schema.getClassInternal(targetClass.getStringValue());

    if (clazz == null) {
      throw new CommandExecutionException(ctx.getDatabaseSession(),
          "Class not found: " + targetClass);
    }

    var session = ctx.getDatabaseSession();
    if (clazz.count(session, false) != 0 || clazz.getSubclasses().isEmpty()
        || isDiamondHierarchy(clazz)) {
      return false;
    }
    // try subclasses

    var subclasses = clazz.getSubclasses();

    List<InternalExecutionPlan> subclassPlans = new ArrayList<>();
    for (var subClass : subclasses) {
      var subSteps =
          handleClassAsTargetWithIndexRecursive(
              subClass.getName(), null, info, ctx, profilingEnabled);
      if (subSteps == null || subSteps.isEmpty()) {
        return false;
      }
      var subPlan = new SelectExecutionPlan(ctx);
      subSteps.forEach(subPlan::chain);
      subclassPlans.add(subPlan);
    }
    if (!subclassPlans.isEmpty()) {
      plan.chain(new ParallelExecStep(subclassPlans, ctx, profilingEnabled));
      return true;
    }
    return false;
  }

  /**
   * Returns {@code true} if the class is the root of a diamond inheritance hierarchy
   * (i.e. two or more subclasses share a common descendant). Diamond hierarchies
   * prevent per-subclass index plans because a record could appear in multiple
   * subclass scans, leading to incorrect duplicate results.
   *
   * <pre>
   *    A         &lt;-- clazz
   *   / \
   *  B   C       &lt;-- A's subclasses
   *   \ /
   *    D         &lt;-- diamond: D is reachable from both B and C
   * </pre>
   */
  private static boolean isDiamondHierarchy(SchemaClass clazz) {
    Set<SchemaClass> traversed = new HashSet<>();
    List<SchemaClass> stack = new ArrayList<>();
    stack.add(clazz);
    while (!stack.isEmpty()) {
      var current = stack.removeFirst();
      traversed.add(current);
      for (var sub : current.getSubclasses()) {
        if (traversed.contains(sub)) {
          return true;
        }
        stack.add(sub);
        traversed.add(sub);
      }
    }
    return false;
  }

  /**
   * Recursively tries to find index-based fetch steps for the given class or its
   * subclasses. Used when the parent class has no records of its own (abstract
   * hierarchy root) but subclasses may have their own indexes.
   *
   * @return list of steps if successful, {@code null} if any branch cannot be indexed
   */
  @Nullable
  private List<ExecutionStepInternal> handleClassAsTargetWithIndexRecursive(
      String targetClass,
      Set<String> filterCollections,
      QueryPlanningInfo info,
      CommandContext ctx,
      boolean profilingEnabled) {
    var result =
        handleClassAsTargetWithIndex(targetClass, filterCollections, info, ctx, profilingEnabled,
            false);
    var session = ctx.getDatabaseSession();
    if (result == null) {
      result = new ArrayList<>();
      var clazz = getSchemaFromContext(ctx).getClassInternal(targetClass);
      if (clazz == null) {
        throw new CommandExecutionException(ctx.getDatabaseSession(),
            "Cannot find class " + targetClass);
      }
      if (clazz.count(session, false) != 0
          || clazz.getSubclasses().isEmpty()
          || isDiamondHierarchy(clazz)) {
        return null;
      }

      var subclasses = clazz.getSubclasses();

      List<InternalExecutionPlan> subclassPlans = new ArrayList<>();
      for (var subClass : subclasses) {
        var subSteps =
            handleClassAsTargetWithIndexRecursive(
                subClass.getName(), filterCollections, info, ctx, profilingEnabled);
        if (subSteps == null || subSteps.isEmpty()) {
          return null;
        }
        var subPlan = new SelectExecutionPlan(ctx);
        subSteps.forEach(subPlan::chain);
        subclassPlans.add(subPlan);
      }
      if (!subclassPlans.isEmpty()) {
        result.add(new ParallelExecStep(subclassPlans, ctx, profilingEnabled));
      }
    }
    return result.isEmpty() ? null : result;
  }

  /**
   * Core index lookup logic for a single class. For each OR-branch in the flattened
   * WHERE, calls {@link #findBestIndexFor} to select the optimal index. If all branches
   * can be covered by indexes, assembles the fetch steps.
   *
   * <pre>
   *  WHERE (a = 1 AND b = 2) OR (c = 3)
   *  flattenedWhereClause: [AND(a=1,b=2), AND(c=3)]
   *
   *  Each AND block is matched independently:
   *    AND(a=1,b=2) -&gt; idx_a_b (composite index)
   *    AND(c=3)      -&gt; idx_c  (single-field index)
   *
   *  If both succeed -&gt; combine via commonFactor + executionStepFromIndexes
   *  If any fails    -&gt; return null (cannot use indexes for this class)
   * </pre>
   *
   * @param isHierarchyRoot if true, ORDER BY can be satisfied from the index;
   *                        for subclass branches this is false
   * @return list of execution steps, or {@code null} if indexes cannot cover all branches
   */
  @Nullable
  private List<ExecutionStepInternal> handleClassAsTargetWithIndex(
      String targetClass,
      Set<String> filterCollections,
      QueryPlanningInfo info,
      CommandContext ctx,
      boolean profilingEnabled,
      boolean isHierarchyRoot
  ) {
    if (info.flattenedWhereClause == null || info.flattenedWhereClause.isEmpty()) {
      return null;
    }

    var clazz = getSchemaFromContext(ctx).getClassInternal(targetClass);
    if (clazz == null) {
      throw new CommandExecutionException(ctx.getDatabaseSession(),
          "Cannot find class " + targetClass);
    }

    var indexes = clazz.getIndexesInternal();

    final SchemaClass c = clazz;
    var indexSearchDescriptors =
        info.flattenedWhereClause.stream()
            .map(x -> findBestIndexFor(ctx, indexes, x, c))
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    if (indexSearchDescriptors.size() != info.flattenedWhereClause.size()) {
      return null; // some blocks could not be managed with an index
    }

    var optimumIndexSearchDescriptors =
        commonFactor(indexSearchDescriptors);

    return executionStepFromIndexes(
        filterCollections,
        clazz,
        info,
        ctx,
        profilingEnabled,
        optimumIndexSearchDescriptors,
        isHierarchyRoot
    );
  }

  /**
   * Converts a list of {@link IndexSearchDescriptor}s into concrete execution steps.
   *
   * <p>Single-descriptor case: creates a linear chain:
   * <pre>
   *   FetchFromIndexStep -&gt; GetValueFromIndexEntryStep [-&gt; DistinctStep] [-&gt; FilterStep]
   * </pre>
   *
   * <p>Multi-descriptor case: creates parallel sub-plans merged with
   * {@link ParallelExecStep} + {@link DistinctExecutionStep} to deduplicate results
   * from overlapping index ranges.
   */
  private List<ExecutionStepInternal> executionStepFromIndexes(
      Set<String> filterCollections,
      SchemaClass clazz,
      QueryPlanningInfo info,
      CommandContext ctx,
      boolean profilingEnabled,
      List<IndexSearchDescriptor> optimumIndexSearchDescriptors,
      boolean isHierarchyRoot
  ) {
    List<ExecutionStepInternal> result;
    if (optimumIndexSearchDescriptors.size() == 1) {
      var desc = optimumIndexSearchDescriptors.getFirst();
      result = new ArrayList<>();
      var orderAsc = getOrderDirection(info);
      result.add(
          new FetchFromIndexStep(desc, !Boolean.FALSE.equals(orderAsc), ctx, profilingEnabled));
      IntArrayList filterCollectionIds;
      if (filterCollections != null) {
        filterCollectionIds = classCollectionsFiltered(ctx.getDatabaseSession(), clazz,
            filterCollections);
      } else {
        filterCollectionIds = IntArrayList.of(clazz.getPolymorphicCollectionIds());
      }
      result.add(new GetValueFromIndexEntryStep(ctx, filterCollectionIds, profilingEnabled));
      if (desc.requiresDistinctStep()) {
        result.add(new DistinctExecutionStep(ctx, profilingEnabled));
      }
      // at the moment, we allow this optimization only for root classes in the hierarchy.
      // I.e. For B and C that are subclasses of A, `select from A where aField > 10` will
      // apply this optimization only if `aField` is indexed in the root class A.
      if (isHierarchyRoot
          && orderAsc != null
          && info.orderBy != null
          && fullySorted(info.orderBy, desc)) {
        info.orderApplied = true;
      }
      if (desc.getRemainingCondition() != null && !desc.getRemainingCondition().isEmpty()) {
        if ((info.perRecordLetClause != null
            && refersToLet(Collections.singletonList(desc.getRemainingCondition())))) {
          var stubPlan = new SelectExecutionPlan(ctx);
          handleLet(stubPlan, info, ctx, profilingEnabled);
          for (var step : stubPlan.getSteps()) {
            result.add((ExecutionStepInternal) step);
          }
        }
        result.add(
            new FilterStep(
                createWhereFrom(desc.getRemainingCondition()),
                ctx,
                this.info.timeout != null ? this.info.timeout.getVal().longValue() : -1,
                profilingEnabled));
      }
    } else {
      result = new ArrayList<>();
      result.add(
          createParallelIndexFetch(
              optimumIndexSearchDescriptors, filterCollections, ctx, profilingEnabled));
      if (optimumIndexSearchDescriptors.size() > 1) {
        result.add(new DistinctExecutionStep(ctx, profilingEnabled));
      }
    }
    return result;
  }

  /** Returns the immutable schema snapshot from the session for thread-safe class lookups. */
  private static SchemaInternal getSchemaFromContext(CommandContext ctx) {
    return ctx.getDatabaseSession().getMetadata().getImmutableSchemaSnapshot();
  }

  /**
   * Returns {@code true} if the ORDER BY is fully covered by the index field order
   * in the given descriptor (i.e. no in-memory sort is needed).
   */
  private static boolean fullySorted(SQLOrderBy orderBy, IndexSearchDescriptor desc) {
    if (orderBy.ordersWithCollate() || !orderBy.ordersSameDirection()) {
      return false;
    }
    return desc.fullySorted(orderBy.getProperties());
  }

  /**
   * returns TRUE if all the order clauses are ASC, FALSE if all are DESC, null otherwise
   *
   * @return TRUE if all the order clauses are ASC, FALSE if all are DESC, null otherwise
   */
  @Nullable
  private static Boolean getOrderDirection(QueryPlanningInfo info) {
    if (info.orderBy == null) {
      return null;
    }
    String result = null;
    for (var item : info.orderBy.getItems()) {
      if (result == null) {
        result = item.getType() == null ? SQLOrderByItem.ASC : item.getType();
      } else {
        var newType = item.getType() == null ? SQLOrderByItem.ASC : item.getType();
        if (!newType.equals(result)) {
          return null;
        }
      }
    }
    return result == null || result.equals(SQLOrderByItem.ASC);
  }

  /**
   * Creates a {@link ParallelExecStep} that runs one index fetch sub-plan per
   * {@link IndexSearchDescriptor}. Each sub-plan is:
   * {@code FetchFromIndex -> GetValueFromIndexEntry [-> Distinct] [-> Filter]}.
   */
  private ExecutionStepInternal createParallelIndexFetch(
      List<IndexSearchDescriptor> indexSearchDescriptors,
      Set<String> filterCollections,
      CommandContext ctx,
      boolean profilingEnabled) {
    List<InternalExecutionPlan> subPlans = new ArrayList<>();
    for (var desc : indexSearchDescriptors) {
      var subPlan = new SelectExecutionPlan(ctx);
      subPlan.chain(new FetchFromIndexStep(desc, true, ctx, profilingEnabled));
      IntArrayList filterCollectionIds = null;
      if (filterCollections != null) {
        filterCollectionIds = IntArrayList.of(
            ctx.getDatabaseSession().getCollectionsIds(filterCollections));
      }
      subPlan.chain(new GetValueFromIndexEntryStep(ctx, filterCollectionIds, profilingEnabled));
      if (desc.requiresDistinctStep()) {
        subPlan.chain(new DistinctExecutionStep(ctx, profilingEnabled));
      }
      if (desc.getRemainingCondition() != null && !desc.getRemainingCondition().isEmpty()) {
        subPlan.chain(
            new FilterStep(
                createWhereFrom(desc.getRemainingCondition()),
                ctx,
                this.info.timeout != null ? this.info.timeout.getVal().longValue() : -1,
                profilingEnabled));
      }
      subPlans.add(subPlan);
    }
    return new ParallelExecStep(subPlans, ctx, profilingEnabled);
  }

  /** Wraps a single boolean expression in a {@link SQLWhereClause} for use with {@link FilterStep}. */
  private static SQLWhereClause createWhereFrom(SQLBooleanExpression remainingCondition) {
    var result = new SQLWhereClause(-1);
    result.setBaseExpression(remainingCondition);
    return result;
  }

  /**
   * Selects the best index from the given candidates to satisfy as many conditions
   * as possible within the AND block. The selection algorithm works in four stages:
   *
   * <pre>
   *  Stage 1: Build candidates
   *    - For each equality/range index: buildIndexSearchDescriptor()
   *    - For each FULLTEXT index:       buildIndexSearchDescriptorForFulltext()
   *
   *  Stage 2: Prune redundant candidates
   *    - removeGenericIndexes(): prefer target-class index over superclass index
   *    - removePrefixIndexes():  if [a,b] and [a] both match, discard [a]
   *
   *  Stage 3: Sort by cost
   *    - IndexSearchDescriptor.cost() estimates I/O cost
   *    - Keep only candidates tied for lowest cost
   *
   *  Stage 4: Pick the widest
   *    - Among equal-cost candidates, pick the one covering the most fields
   * </pre>
   *
   * @param indexes all indexes defined on the target class
   * @param block   a single AND block from the flattened WHERE clause
   * @param clazz   the target schema class
   * @return the best index descriptor, or {@code null} if no index can be used
   */
  @Nullable
  private static IndexSearchDescriptor findBestIndexFor(
      CommandContext ctx, Set<Index> indexes, SQLAndBlock block, SchemaClass clazz) {
    // get all valid index descriptors
    var descriptors =
        indexes.stream()
            .filter(Index::canBeUsedInEqualityOperators)
            .map(index -> buildIndexSearchDescriptor(ctx, index, block, clazz))
            .filter(Objects::nonNull)
            .filter(x -> x.getKeyCondition() != null)
            .filter(x -> x.blockCount() > 0)
            .collect(Collectors.toList());

    var fullTextIndexDescriptors =
        indexes.stream()
            .filter(idx -> idx.getType().equalsIgnoreCase("FULLTEXT"))
            .filter(idx -> !idx.getAlgorithm().equalsIgnoreCase("LUCENE"))
            .map(idx -> buildIndexSearchDescriptorForFulltext(idx, block))
            .filter(Objects::nonNull)
            .filter(x -> x.getKeyCondition() != null)
            .filter(x -> x.blockCount() > 0)
            .toList();

    descriptors.addAll(fullTextIndexDescriptors);

    descriptors = removeGenericIndexes(descriptors, clazz);

    // remove the redundant descriptors (eg. if I have one on [a] and one on [a, b], the first one
    // is redundant, just discard it)
    descriptors = removePrefixIndexes(descriptors);

    // sort by cost
    var sortedDescriptors =
        descriptors.stream().map(x -> new PairIntegerObject<>(x.cost(ctx), x)).sorted().toList();

    // get only the descriptors with the lowest cost
    if (sortedDescriptors.isEmpty()) {
      descriptors = Collections.emptyList();
    } else {
      descriptors =
          sortedDescriptors.stream()
              .filter(x -> x.key == sortedDescriptors.getFirst().key)
              .map(x -> x.value)
              .collect(Collectors.toList());
    }

    // sort remaining by the number of indexed fields
    descriptors =
        descriptors.stream()
            .sorted(Comparator.comparingInt(IndexSearchDescriptor::blockCount))
            .collect(Collectors.toList());

    // get the one that has more indexed fields
    return descriptors.isEmpty() ? null : descriptors.getLast();
  }

  /**
   * If between the index candidates there are for the same property target class index and super
   * class index prefer the target class.
   */
  private static List<IndexSearchDescriptor> removeGenericIndexes(
      List<IndexSearchDescriptor> descriptors, SchemaClass clazz) {
    List<IndexSearchDescriptor> results = new ArrayList<>();
    for (var desc : descriptors) {
      IndexSearchDescriptor matching = null;
      for (var result : results) {
        if (desc.isSameCondition(result)) {
          matching = result;
          break;
        }
      }
      if (matching != null) {
        if (clazz.getName().equals(desc.getIndex().getDefinition().getClassName())) {
          results.remove(matching);
          results.add(desc);
        }
      } else {
        results.add(desc);
      }
    }
    return results;
  }

  /**
   * Removes index descriptors that are strict prefixes of other descriptors in the list.
   * When two indexes cover overlapping condition prefixes, the longer (more specific) one
   * is preferred because it narrows the result set further.
   */
  private static List<IndexSearchDescriptor> removePrefixIndexes(
      List<IndexSearchDescriptor> descriptors) {
    List<IndexSearchDescriptor> result = new ArrayList<>();
    for (var desc : descriptors) {
      if (result.isEmpty()) {
        result.add(desc);
      } else {
        var prefixes = findPrefixes(desc, result);
        if (prefixes.isEmpty()) {
          if (!isPrefixOfAny(desc, result)) {
            result.add(desc);
          }
        } else {
          result.removeAll(prefixes);
          result.add(desc);
        }
      }
    }
    return result;
  }

  /** Returns {@code true} if {@code desc} is a condition prefix of any descriptor in the list. */
  private static boolean isPrefixOfAny(IndexSearchDescriptor desc,
      List<IndexSearchDescriptor> result) {
    for (var item : result) {
      if (desc.isPrefixOf(item)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns all descriptors in {@code descriptors} whose condition blocks are a prefix
   * of (or equal to) {@code desc}'s condition blocks. For example, if {@code desc}
   * covers conditions [a, b] and the list contains a descriptor covering [a], that
   * descriptor is returned because [a] is a prefix of [a, b].
   */
  private static List<IndexSearchDescriptor> findPrefixes(
      IndexSearchDescriptor desc, List<IndexSearchDescriptor> descriptors) {
    List<IndexSearchDescriptor> result = new ArrayList<>();
    for (var item : descriptors) {
      if (item.isPrefixOf(desc)) {
        result.add(item);
      }
    }
    return result;
  }

  /**
   * Builds an {@link IndexSearchDescriptor} that describes how to use the given index
   * to evaluate as many conditions as possible from the AND block.
   *
   * <p>The algorithm walks the index's property list (which defines the composite key
   * order) and greedily matches WHERE conditions:
   *
   * <pre>
   *  Index on [city, age, name]
   *  WHERE city = 'NYC' AND age &gt; 20 AND name = 'Alice' AND salary &gt; 50000
   *
   *  Matching:
   *    city  -&gt; city = 'NYC'     (equality, continue to next field)
   *    age   -&gt; age &gt; 20         (range, STOP -- range can only be on last field)
   *    name  -&gt; not reachable (after range)
   *
   *  Result:
   *    indexKeyValue:      [city = 'NYC', age &gt; 20]
   *    remainingCondition: name = 'Alice' AND salary &gt; 50000
   * </pre>
   *
   * <p>For a single indexed property with two conditions on the same field (e.g.
   * {@code age >= 20 AND age < 30}), the method attempts to merge them into a single
   * between-style range condition for the index key.
   *
   * <p>Hash indexes (which do not support ordered iteration) require all key fields
   * to be present; if only a prefix matches, the descriptor is rejected.
   *
   * @return a descriptor, or {@code null} if the index cannot be used for this block
   */
  @Nullable
  private static IndexSearchDescriptor buildIndexSearchDescriptor(
      CommandContext ctx, Index index, SQLAndBlock block, SchemaClass clazz) {
    var indexProperties = index.getDefinition().getProperties();

    //copy as we will modify the list of expressions
    var blockCopy = block.copy();

    var indexKeyValue = new SQLAndBlock(-1);

    //used if we need to generate a range query instead of a point query and applied to an end range
    //interval of the key to search in index.
    SQLBinaryCondition additionalRangeCondition = null;

    var booleanExpressions = blockCopy.getSubBlocks();
    var propertyNameBooleanExpressionMap =
        new HashMap<String, List<SQLBooleanExpression>>(booleanExpressions.size());

    //group all boolean expressions by indexed property they test,
    // all SQL expressions should be already flattened at this moment
    // so will use only a single property in the expression
    for (var booleanExpression : booleanExpressions) {
      //skip expressions that do not use properties we will apply them later on post filtering
      var indexPropertyName = booleanExpression.getRelatedIndexPropertyName();
      if (indexPropertyName != null) {
        var list = propertyNameBooleanExpressionMap.computeIfAbsent(indexPropertyName,
            k -> new ArrayList<>());
        list.add(booleanExpression);
      }
    }

    //Flag is used to indicate the situation when applied expressions make usage of indexes
    //impossible to use.
    //One of the most typical is the usage of several range conditions as we can apply only
    //one.
    var invalidConditions = new boolean[1];
    //Range condition should always go after equality condition, so sort all expressions by
    //the type of operator they use.
    //Then we will merge all expressions for the same property name that have more than two
    //conditions as we can apply only single equals and single range condition for each property.
    propertyNameBooleanExpressionMap.forEach((indexPropertyName, expressions) -> {
      if (expressions.size() > 1) {
        //merge all tail expressions as we can support only one range condition per index
        //try to mere first condition with the rest of the conditions too
        var resultingExpressions = new ArrayList<SQLBooleanExpression>(2);

        var firstExpression = expressions.getFirst();
        var expressionToMerge = expressions.get(1);

        var mergedExpression = firstExpression.mergeUsingAnd(expressionToMerge, ctx);
        if (mergedExpression != null) {
          expressionToMerge = mergedExpression;
        } else {
          resultingExpressions.add(firstExpression);
        }

        if (expressions.size() > 2) {
          for (var i = 2; i < expressions.size(); i++) {
            var nextBlockToMerge = expressions.get(i);
            expressionToMerge = expressionToMerge.mergeUsingAnd(nextBlockToMerge, ctx);

            //unable to merge expressions
            if (expressionToMerge == null) {
              invalidConditions[0] = true;
              return;
            }
          }
        }

        resultingExpressions.add(expressionToMerge);

        expressions.clear();
        expressions.addAll(resultingExpressions);
      }
    });

    //there are more than two boolean expressions for the same property, skip the current index
    if (invalidConditions[0]) {
      return null;
    }

    for (var indexProperty : indexProperties) {
      var propertyExpressions = propertyNameBooleanExpressionMap.get(indexProperty);
      if (propertyExpressions == null) {
        break;
      }

      if (propertyExpressions.size() > 2) {
        break;
      }

      var info =
          new IndexSearchInfo(
              indexProperty,
              true,
              isMap(clazz, indexProperty),
              isIndexByKey(index, indexProperty),
              isIndexByValue(index, indexProperty),
              clazz, ctx);

      var firstPropertyExpression = propertyExpressions.getFirst();
      if (firstPropertyExpression.isRangeExpression()) {
        if (!info.allowsRangeQueries()) {
          break;
        }
      }

      if (propertyExpressions.size() == 2) {
        var secondPropertyExpression = propertyExpressions.get(1);
        if (secondPropertyExpression.isIndexAware(info, ctx)) {
          if (secondPropertyExpression.canCreateRangeWith(firstPropertyExpression)) {
            additionalRangeCondition = (SQLBinaryCondition) secondPropertyExpression;
          } else {
            break;
          }
        } else {
          return null;
        }
      }

      if (firstPropertyExpression.isIndexAware(info, ctx)) {
        indexKeyValue.getSubBlocks().add(firstPropertyExpression.copy());
        if (firstPropertyExpression.isRangeExpression()) {
          //we can have only a single range condition per index
          break;
        }
      } else {
        break;
      }
    }

    return new IndexSearchDescriptor(index, indexKeyValue, additionalRangeCondition, blockCopy);
  }

  /**
   * Builds an {@link IndexSearchDescriptor} for a FULLTEXT (non-Lucene) index.
   *
   * <p>Iterates over the index's field list and, for each field, looks for a matching
   * CONTAINSTEXT condition in the AND block. Matched conditions become the index key;
   * unmatched conditions remain as post-fetch filters.
   *
   * <p>Like {@link #buildIndexSearchDescriptor}, hash-type fulltext indexes require a
   * complete key match (all fields present).
   *
   * @return a descriptor, or {@code null} if no CONTAINSTEXT match was found
   */
  @Nullable
  private static IndexSearchDescriptor buildIndexSearchDescriptorForFulltext(
      Index index, SQLAndBlock block) {
    var indexFields = index.getDefinition().getProperties();
    var found = false;

    var blockCopy = block.copy();
    Iterator<SQLBooleanExpression> blockIterator;

    var indexKeyValue = new SQLAndBlock(-1);

    for (var indexField : indexFields) {
      blockIterator = blockCopy.getSubBlocks().iterator();
      var indexFieldFound = false;
      while (blockIterator.hasNext()) {
        var singleExp = blockIterator.next();
        if (singleExp.isFullTextIndexAware(indexField)) {
          found = true;
          indexFieldFound = true;
          indexKeyValue.getSubBlocks().add(singleExp.copy());
          blockIterator.remove();
          break;
        }
      }
      if (!indexFieldFound) {
        break;
      }
    }

    if (found) {
      return new IndexSearchDescriptor(index, indexKeyValue, null, blockCopy);
    }
    return null;
  }

  /** Returns {@code true} if the given field is indexed "by key" (for map-type indexes). */
  private static boolean isIndexByKey(Index index, String field) {
    var def = index.getDefinition();
    for (var o : def.getFieldsToIndex()) {
      if (o.equalsIgnoreCase(field + " by key")) {
        return true;
      }
    }
    return false;
  }

  /** Returns {@code true} if the given field is indexed "by value" (for map-type indexes). */
  private static boolean isIndexByValue(Index index, String field) {
    var def = index.getDefinition();
    for (var o : def.getFieldsToIndex()) {
      if (o.equalsIgnoreCase(field + " by value")) {
        return true;
      }
    }
    return false;
  }

  /** Returns {@code true} if the given field's type is {@link PropertyType#EMBEDDEDMAP}. */
  private static boolean isMap(SchemaClass clazz,
      String indexField) {
    var prop = clazz.getProperty(indexField);
    if (prop == null) {
      return false;
    }
    return prop.getType() == PropertyType.EMBEDDEDMAP;
  }

  /**
   * Aggregates multiple {@link IndexSearchDescriptor}s that share the same index and
   * key condition into a single descriptor with an OR-combined remaining filter.
   *
   * <p>This handles the case where multiple OR-branches of the WHERE clause share the
   * same index key but differ in their residual filter:
   * <pre>
   *  WHERE (a = 1 AND b = 2) OR (a = 1 AND b = 3)
   *           same index key: a=1
   *           residual: b=2 OR b=3  (combined into a single SQLOrBlock)
   * </pre>
   *
   * @param indexSearchDescriptors one descriptor per OR-branch
   * @return deduplicated descriptors with OR-combined residual filters
   */
  private static List<IndexSearchDescriptor> commonFactor(
      List<IndexSearchDescriptor> indexSearchDescriptors) {
    // index, key condition, additional filter (to aggregate in OR)
    Map<Index, Map<IndexCondPair, SQLOrBlock>> aggregation = new HashMap<>();
    for (var item : indexSearchDescriptors) {
      var filtersForIndex = aggregation.computeIfAbsent(item.getIndex(), k -> new HashMap<>());
      var extendedCond =
          new IndexCondPair(item.getKeyCondition(), item.getAdditionalRangeCondition());

      var existingAdditionalConditions = filtersForIndex.get(extendedCond);
      if (existingAdditionalConditions == null) {
        existingAdditionalConditions = new SQLOrBlock(-1);
        filtersForIndex.put(extendedCond, existingAdditionalConditions);
      }
      existingAdditionalConditions.getSubBlocks().add(item.getRemainingCondition());
    }
    List<IndexSearchDescriptor> result = new ArrayList<>();
    for (var item : aggregation.entrySet()) {
      for (var filters : item.getValue().entrySet()) {
        result.add(
            new IndexSearchDescriptor(
                item.getKey(),
                filters.getKey().mainCondition,
                filters.getKey().additionalRange,
                filters.getValue()));
      }
    }
    return result;
  }


  /**
   * Wraps a subquery statement as the FROM target using a {@link SubQueryStep}.
   * A child {@link BasicCommandContext} is created for the subquery so that its
   * variables do not leak into the outer query scope.
   */
  private static void handleSubqueryAsTarget(
      SelectExecutionPlan plan,
      SQLStatement subQuery,
      CommandContext ctx,
      boolean profilingEnabled) {
    var subCtx = new BasicCommandContext();
    subCtx.setDatabaseSession(ctx.getDatabaseSession());
    subCtx.setParent(ctx);
    var subExecutionPlan =
        subQuery.createExecutionPlan(subCtx, profilingEnabled);
    plan.chain(new SubQueryStep(subExecutionPlan, ctx, subCtx, profilingEnabled));
  }

  /** Returns {@code true} if the ORDER BY is exactly {@code ORDER BY @rid DESC}. */
  private static boolean isOrderByRidDesc(QueryPlanningInfo info) {
    if (!hasTargetWithSortedRids(info)) {
      return false;
    }

    if (info.orderBy == null) {
      return false;
    }
    if (info.orderBy.getItems().size() == 1) {
      var item = info.orderBy.getItems().getFirst();
      var recordAttr = item.getRecordAttr();
      return recordAttr != null
          && recordAttr.equalsIgnoreCase("@rid")
          && SQLOrderByItem.DESC.equals(item.getType());
    }
    return false;
  }

  /** Returns {@code true} if the ORDER BY is exactly {@code ORDER BY @rid ASC} (or default). */
  private static boolean isOrderByRidAsc(QueryPlanningInfo info) {
    if (!hasTargetWithSortedRids(info)) {
      return false;
    }

    if (info.orderBy == null) {
      return false;
    }
    if (info.orderBy.getItems().size() == 1) {
      var item = info.orderBy.getItems().getFirst();
      var recordAttr = item.getRecordAttr();
      return recordAttr != null
          && recordAttr.equalsIgnoreCase("@rid")
          && (item.getType() == null || SQLOrderByItem.ASC.equals(item.getType()));
    }
    return false;
  }

  /**
   * Returns {@code true} if the target is a class/identifier (as opposed to a subquery
   * or variable), which means records can be scanned in RID order.
   */
  private static boolean hasTargetWithSortedRids(QueryPlanningInfo info) {
    if (info.target == null) {
      return false;
    }
    if (info.target.getItem() == null) {
      return false;
    }

    return info.target.getItem().getIdentifier() != null;
  }
}
