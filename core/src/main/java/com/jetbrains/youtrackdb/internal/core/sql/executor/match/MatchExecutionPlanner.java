package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.util.PairLongObject;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Direction;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.index.engine.SelectivityEstimator;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.AbstractExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.CartesianProductStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.CostModel;
import com.jetbrains.youtrackdb.internal.core.sql.executor.DistinctExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.EmptyStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ExecutionStepInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.LimitExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.OrderByStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ProjectionCalculationStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.QueryPlanningInfo;
import com.jetbrains.youtrackdb.internal.core.sql.executor.RidFilterDescriptor;
import com.jetbrains.youtrackdb.internal.core.sql.executor.SelectExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.SelectExecutionPlanner;
import com.jetbrains.youtrackdb.internal.core.sql.executor.SkipExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.TraversalPreFilterHelper;
import com.jetbrains.youtrackdb.internal.core.sql.executor.UnwindStep;
import com.jetbrains.youtrackdb.internal.core.sql.parser.Pattern;
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
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLLimit;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchFilter;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMethodCall;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMultiMatchPathItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLNeOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLNestedProjection;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLNotBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrderBy;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrderByItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLProjection;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLProjectionItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLRecordAttribute;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLRid;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSelectStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSkip;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLUnwind;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YqlExecutionPlanCache;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Converts a parsed `MATCH` statement into a physical execution plan.
 *
 * <p>## Overview
 *
 * <p>The `MATCH` statement is YouTrackDB's graph pattern-matching query language construct,
 * inspired by the pattern-matching semantics of graph query languages such as Cypher.
 * A typical `MATCH` query defines one or more **patterns** — chains of nodes connected by
 * directed or bidirectional edges — along with optional `WHERE` filters, `WHILE` recursive
 * traversal conditions, and a `RETURN` projection.
 *
 * <p>### Example
 *
 * <p>```sql
 * MATCH {class: Person, as: p, where: (name = 'Alice')}
 *         .out('Knows') {as: friend}
 *         .out('Lives') {as: city, where: (name = 'Berlin')}
 * RETURN p.name, friend.name, city.name
 * ```
 *
 * <p>This planner takes the AST produced by the SQL parser ({@link SQLMatchStatement}) and
 * produces a {@link SelectExecutionPlan} composed of execution steps that evaluate the
 * pattern at runtime.
 *
 * <p>## End-to-end flow
 *
 * <p><pre>
 *   SQL text ──→ Parser ──→ SQLMatchStatement (AST)
 *                                   │
 *                          MatchExecutionPlanner
 *                                   │
 *                  ┌────────────────┼────────────────────┐
 *                  │                │                    │
 *           buildPatterns()   estimateRoots()   topologicalSort()
 *            (Pattern graph)  (cardinality map)  (EdgeTraversal schedule)
 *                  │                │                    │
 *                  └────────────────┼────────────────────┘
 *                                   │
 *                          Step generation:
 *                  MatchPrefetchStep (small aliases)
 *                  MatchFirstStep (initial scan)
 *                  MatchStep / OptionalMatchStep (per edge)
 *                  FilterNotMatchPatternStep (NOT patterns)
 *                  RemoveEmptyOptionalsStep (optional cleanup)
 *                  ReturnMatch*Step (projection)
 *                                   │
 *                                   ▼
 *                        SelectExecutionPlan (ready to execute)
 * </pre>
 *
 * <p>## Planning phases
 *
 * <p>The phases below correspond to the inline comments in
 * {@link #createExecutionPlan(CommandContext, boolean)}:
 *
 * <p>1. **Build the pattern graph** — converts the list of {@link SQLMatchExpression}s into a
 *    {@link Pattern} (an adjacency structure of {@link PatternNode}s and
 *    {@link PatternEdge}s), and extracts per-alias metadata such as class constraints,
 *    RID constraints, and `WHERE` filters.
 *
 * <p>2. **Split disjoint sub-patterns** — if the `MATCH` contains multiple disconnected
 *    sub-graphs (e.g. `MATCH {as: a}.out(){as: b}, {as: x}.out(){as: y}`), each connected
 *    component is planned independently and later joined via a
 *    {@link CartesianProductStep}.
 *
 * <p>3. **Estimate root cardinalities** — for every aliased node that has a class or RID
 *    constraint, estimate the number of root records using schema statistics. Aliases
 *    whose estimated cardinality falls below {@link #THRESHOLD} are **prefetched** and
 *    cached in the execution context so that the traversal can start from the smallest
 *    set. Aliases whose filters reference `$matched` cannot be prefetched because their
 *    filter depends on values produced during traversal (see
 *    {@link #dependsOnExecutionContext}).
 *
 * <p>4. **Prefetch small alias sets** — for each alias below the threshold, a
 *    {@link MatchPrefetchStep} eagerly loads all matching records into the execution
 *    context under a well-known key.
 *
 * <p>5. **Topological scheduling and step generation** — edges in the pattern graph are
 *    ordered via a cost-driven depth-first traversal
 *    ({@link #getTopologicalSortedSchedule}). The algorithm picks the cheapest root node
 *    first, then greedily expands outward, respecting dependency constraints introduced
 *    by `$matched` references in `WHERE` clauses. The sorted list of
 *    {@link EdgeTraversal}s is mapped to execution steps:
 *    - The first node becomes a {@link MatchFirstStep} (initial record scan/lookup).
 *    - Each subsequent edge becomes either a {@link MatchStep} or an
 *      {@link OptionalMatchStep}.
 *
 * <p>6. **NOT patterns** — any `NOT { … }` sub-patterns are appended as
 *    {@link FilterNotMatchPatternStep}s that discard rows matching the negative pattern.
 *
 * <p>7. **Optional cleanup** — if the query contains optional nodes, a
 *    {@link RemoveEmptyOptionalsStep} replaces sentinel
 *    {@link OptionalMatchEdgeTraverser#EMPTY_OPTIONAL} markers with `null`.
 *
 * <p>8. **Return projection** — depending on the `RETURN` clause, one of several
 *    projection steps is appended (`$elements`, `$paths`, `$patterns`,
 *    `$pathElements`, or a custom expression list).
 *
 * <p>## Result row evolution
 *
 * <p>As each step in the pipeline processes a row, the row's property map grows.
 * The diagram below shows a concrete example for the query:
 *
 * <p>```sql
 * MATCH {class: Person, as: p, where: (name = 'Alice')}
 *         .out('Knows') {as: friend, optional: true}
 *         .out('Lives') {as: city}
 * RETURN p.name, friend.name, city.name
 * ```
 *
 * <p><pre>
 *   Step                        Row contents
 *   ─────────────────────────   ──────────────────────────────────────────────
 *   MatchFirstStep (p)          {p: Person#1}
 *   OptionalMatchStep (→friend) {p: Person#1, friend: Person#2}
 *                            or {p: Person#1, friend: EMPTY_OPTIONAL}
 *   MatchStep (→city)           {p: Person#1, friend: Person#2, city: City#3}
 *   RemoveEmptyOptionalsStep    {p: Person#1, friend: null,     city: City#3}
 *                                                    ^^ sentinel replaced
 *   ReturnMatch*Step / project  {p.name: "Alice", friend.name: null, city.name: "Berlin"}
 * </pre>
 *
 * <p>When a `depthAlias` or `pathAlias` is declared on a WHILE edge, the
 * corresponding metadata is also stored as a top-level property:
 * `{p: Person#1, friend: Person#2, depth: 2, path: [Person#1, ...]}`.
 *
 * <p>## Cartesian product for disjoint sub-patterns
 *
 * <p>When the MATCH query contains multiple disconnected sub-graphs (e.g.
 * `MATCH {as: a}.out(){as: b}, {as: x}.out(){as: y}`), each connected
 * component is planned independently via {@link #createPlanForPattern} and
 * produces its own stream of partial rows. A {@link CartesianProductStep}
 * then joins these streams by computing the Cartesian (cross) product:
 * every row from sub-pattern 1 is combined with every row from sub-pattern 2,
 * yielding a merged row that contains all aliases from both sub-patterns.
 *
 * <p><pre>
 *   Sub-pattern 1: {a: A#1, b: B#2}   Sub-pattern 2: {x: X#5, y: Y#6}
 *                  {a: A#3, b: B#4}                   {x: X#7, y: Y#8}
 *
 *   CartesianProductStep output:
 *     {a: A#1, b: B#2, x: X#5, y: Y#6}
 *     {a: A#1, b: B#2, x: X#7, y: Y#8}
 *     {a: A#3, b: B#4, x: X#5, y: Y#6}
 *     {a: A#3, b: B#4, x: X#7, y: Y#8}
 * </pre>
 *
 * @see MatchStep
 * @see MatchFirstStep
 * @see MatchEdgeTraverser
 * @see Pattern
 * @see PatternNode
 * @see PatternEdge
 * @see EdgeTraversal
 */
public class MatchExecutionPlanner {

  private static final Logger logger =
      LoggerFactory.getLogger(MatchExecutionPlanner.class);

  /**
   * Prefix prepended to auto-generated aliases for pattern nodes that the user did not
   * name explicitly. Properties whose name starts with this prefix are treated as
   * **internal** and are stripped from the final result set by
   * {@link ReturnMatchPatternsStep} and {@link ReturnMatchElementsStep}.
   */
  static final String DEFAULT_ALIAS_PREFIX = "$YOUTRACKDB_DEFAULT_ALIAS_";

  /** The original parsed `MATCH` statement, used for execution plan caching. */
  private SQLMatchStatement statement;

  /** Positive `MATCH` expressions (the main graph pattern). */
  protected List<SQLMatchExpression> matchExpressions;

  /** Negative `NOT MATCH` expressions that filter out matching rows. */
  protected List<SQLMatchExpression> notMatchExpressions;

  /** Expressions to evaluate in the `RETURN` clause. */
  protected List<SQLExpression> returnItems;

  /** User-specified aliases for each return expression (may contain `null` entries). */
  protected List<SQLIdentifier> returnAliases;

  /** Optional nested projections applied to return items. */
  protected List<SQLNestedProjection> returnNestedProjections;

  /** `true` when the user wrote `RETURN $elements` — unrolls matched nodes. */
  private boolean returnElements;

  /** `true` when the user wrote `RETURN $paths` — returns full matched paths. */
  private boolean returnPaths;

  /** `true` when the user wrote `RETURN $patterns` — returns matched patterns. */
  private boolean returnPatterns;

  /** `true` when the user wrote `RETURN $pathElements` — unrolls *all* path nodes. */
  private boolean returnPathElements;

  /** `true` when the `RETURN` clause contains the `DISTINCT` keyword. */
  private boolean returnDistinct;

  protected SQLSkip skip;
  private final SQLGroupBy groupBy;
  private final SQLOrderBy orderBy;
  private final SQLUnwind unwind;
  protected SQLLimit limit;

  // ---- Post-parsing state (populated lazily by buildPatterns / splitDisjointPatterns) ----

  /** The complete pattern graph built from {@link #matchExpressions}. */
  private Pattern pattern;

  /** Connected components of {@link #pattern}, one per disjoint sub-graph. */
  private List<Pattern> subPatterns;

  /**
   * Accumulated `WHERE` filters per alias.  When the same alias appears in multiple
   * `MATCH` expressions, all its `WHERE` predicates are AND-ed together.
   */
  private Map<String, SQLWhereClause> aliasFilters;

  /** Maps each alias to the schema class name it is constrained to. */
  private Map<String, String> aliasClasses;

  /** Maps each alias to a specific RID if one was provided in the pattern. */
  private Map<String, SQLRid> aliasRids;

  /** Set to `true` if at least one node in the pattern is marked `optional: true`. */
  private boolean foundOptional = false;

  /**
   * Aliases with an estimated record count below this threshold are **prefetched** into
   * memory before the traversal starts. This avoids repeated scans of very small sets
   * during the nested-loop pattern matching.
   */
  private static final long THRESHOLD = 100;

  /**
   * Captures a detected opportunity for index-ordered MATCH traversal.
   * When present, the edge identified by {@code edgeTraversal} will be executed
   * via {@link IndexOrderedEdgeStep} instead of the standard {@link MatchStep},
   * and the ORDER BY step will be suppressed because the index scan already
   * produces results in the requested order.
   *
   * @param edgeTraversal  the scheduled edge to replace with index-ordered scan
   * @param sourceAlias    alias of the source vertex (whose LinkBag is read)
   * @param targetAlias    alias of the target vertex (bound from index results)
   * @param edgeClassName  edge class name (e.g., "HAS_CREATOR")
   * @param linkBagFieldName field on source vertex containing the LinkBag
   *                         (e.g., "in_HAS_CREATOR")
   * @param index            index on the target class property to scan in order
   * @param orderAsc         true for ASC, false for DESC
   * @param limit            query LIMIT value, or -1 if no LIMIT is set
   * @param multiSourceMode  execution mode for multi-source, or null for single-source
   * @param reverseFieldName LinkBag field on the target vertex that points back
   *                         to the source (e.g., "out_HAS_CREATOR" on Message)
   * @param sourceClassName  source vertex class name (for class-check modes)
   * @param targetFilter     WHERE clause on target alias, or null if none
   * @param targetClassName  class constraint on target alias, or null if none
   */
  private record IndexOrderedCandidate(
      EdgeTraversal edgeTraversal,
      String sourceAlias,
      String targetAlias,
      String edgeClassName,
      String linkBagFieldName,
      Index index,
      boolean orderAsc,
      long limit,
      @Nullable MultiSourceMode multiSourceMode,
      @Nullable String reverseFieldName,
      @Nullable String sourceClassName,
      boolean multiFieldOrderBy,
      @Nullable SQLWhereClause targetFilter,
      @Nullable String targetClassName,
      boolean isEdgeTraversal) {
  }

  /**
   * Multi-source execution strategy. Chosen based on two independent dimensions:
   * whether the source has a WHERE filter, and whether the source alias is
   * referenced by downstream steps (RETURN, later edges).
   */
  enum MultiSourceMode {
    /** Filter + binding: materialize sourceMap, reverse lookup per hit. */
    FILTERED_BOUND,
    /** Filter + no binding: union RidSet from filtered LinkBags, bitmap check. */
    FILTERED_UNBOUND,
    /** No filter + binding: class check on reverse edge, lazy load source. */
    UNFILTERED_BOUND,
    /** No filter + no binding: class check only, no source load. */
    UNFILTERED_UNBOUND
  }

  /**
   * Creates a planner from a pre-built pattern IR. Bypasses SQL AST parsing entirely:
   * {@link #buildPatterns} becomes a no-op because {@code pattern} is already set.
   * Intended for non-SQL front-ends (e.g. GQL) that build the match IR directly.
   *
   * @param pattern      the pattern graph (nodes and edges)
   * @param aliasClasses maps each alias to the schema class name it is constrained to
   */
  public MatchExecutionPlanner(Pattern pattern, Map<String, String> aliasClasses) {
    this(pattern, aliasClasses, Map.of());
  }

  /**
   * Creates a planner from a pre-built pattern IR with per-alias WHERE filters.
   * Intended for GQL front-ends that provide inline property filters
   * (e.g. {@code MATCH (a:Person {name: 'Karl'})}).
   *
   * @param pattern      the pattern graph (nodes and edges)
   * @param aliasClasses maps each alias to the schema class name it is constrained to
   * @param aliasFilters per-alias WHERE clauses built from inline property filters
   */
  public MatchExecutionPlanner(Pattern pattern, Map<String, String> aliasClasses,
      Map<String, SQLWhereClause> aliasFilters) {
    this.matchExpressions = List.of();
    this.notMatchExpressions = List.of();
    this.returnItems = List.of();
    this.returnAliases = List.of();
    this.returnNestedProjections = List.of();
    this.groupBy = null;
    this.orderBy = null;
    this.unwind = null;

    this.pattern = pattern;
    this.aliasClasses = aliasClasses;
    this.aliasFilters = aliasFilters;
    this.aliasRids = Map.of();
  }

  /**
   * Creates a planner by **deep-copying** every mutable component from the parsed
   * statement so that planning can freely mutate state (e.g. assign default aliases,
   * merge filters) without affecting the original AST.
   *
   * @param stm the parsed `MATCH` statement AST node
   */
  public MatchExecutionPlanner(SQLMatchStatement stm) {
    this.statement = stm;
    // Deep-copy all mutable AST components to allow safe in-place mutation during planning
    this.matchExpressions =
        stm.getMatchExpressions().stream().map(SQLMatchExpression::copy)
            .collect(Collectors.toList());
    this.notMatchExpressions =
        stm.getNotMatchExpressions().stream().map(SQLMatchExpression::copy)
            .collect(Collectors.toList());
    this.returnItems =
        stm.getReturnItems().stream().map(SQLExpression::copy).collect(Collectors.toList());
    this.returnAliases =
        stm.getReturnAliases().stream()
            .map(x -> x == null ? null : x.copy())
            .collect(Collectors.toList());
    this.returnNestedProjections =
        stm.getReturnNestedProjections().stream()
            .map(x -> x == null ? null : x.copy())
            .collect(Collectors.toList());
    this.limit = stm.getLimit() == null ? null : stm.getLimit().copy();
    this.skip = stm.getSkip() == null ? null : stm.getSkip().copy();

    this.returnElements = stm.returnsElements();
    this.returnPaths = stm.returnsPaths();
    this.returnPatterns = stm.returnsPatterns();
    this.returnPathElements = stm.returnsPathElements();
    this.returnDistinct = stm.isReturnDistinct();
    this.groupBy = stm.getGroupBy() == null ? null : stm.getGroupBy().copy();
    this.orderBy = stm.getOrderBy() == null ? null : stm.getOrderBy().copy();
    this.unwind = stm.getUnwind() == null ? null : stm.getUnwind().copy();
  }

  /**
   * Builds the complete physical execution plan for this `MATCH` query.
   *
   * <p>The plan is assembled as a pipeline of {@link ExecutionStepInternal} instances chained
   * inside a {@link SelectExecutionPlan}. See the class-level Javadoc for the full list
   * of planning phases (1–8). The inline comments in this method reference those same
   * phase numbers.
   *
   * @param context          the command execution context (holds the database session,
   *                         variables, etc.)
   * @param enableProfiling  when `true`, each step will collect timing/count statistics
   * @param useCache         when `true`, attempts to retrieve/store the plan from/to the
   *                         {@link YqlExecutionPlanCache}
   * @return the assembled execution plan, ready to be {@linkplain InternalExecutionPlan#start()
   *         started}
   */
  public InternalExecutionPlan createExecutionPlan(
      CommandContext context, boolean enableProfiling, boolean useCache) {

    var session = context.getDatabaseSession();

    // --- Check the plan cache before doing any work ---
    if (useCache && !enableProfiling && statement.executinPlanCanBeCached(session)) {
      var plan = YqlExecutionPlanCache.get(statement.getOriginalStatement(), context, session);
      if (plan != null) {
        return (InternalExecutionPlan) plan;
      }
    }

    // Record the timestamp so we can avoid caching a stale plan if the schema
    // was modified concurrently during planning.
    var planningStart = System.nanoTime();

    // Phase 1: Build the pattern graph and extract per-alias metadata
    buildPatterns(context);
    // Phase 2: Identify disconnected sub-graphs that must be joined via Cartesian product
    splitDisjointPatterns();

    var result = new SelectExecutionPlan(context);

    // Phase 3: Estimate how many root records each aliased node will produce
    var estimatedRootEntries =
        estimateRootEntries(aliasClasses, aliasRids, aliasFilters, context);

    // Aliases with fewer records than THRESHOLD and no dependency on $matched are prefetched
    var aliasesToPrefetch =
        estimatedRootEntries.entrySet().stream()
            .filter(x -> x.getValue() < THRESHOLD)
            .filter(x -> !dependsOnExecutionContext(x.getKey()))
            .map(Entry::getKey)
            .collect(Collectors.toSet());

    // Short-circuit: if any non-optional alias has zero estimated records, the query
    // is guaranteed to produce no results
    for (var entry : estimatedRootEntries.entrySet()) {
      if (entry.getValue() == 0L && !isOptional(entry.getKey())) {
        result.chain(new EmptyStep(context, enableProfiling));
        return result;
      }
    }

    // Phase 4b: Detect index-ordered MATCH traversal opportunity.
    // Only applicable for single connected patterns (no Cartesian product).
    // Detected BEFORE prefetch so we can exclude the target alias from prefetching —
    // prefetching it would pre-bind the target, causing IndexOrderedEdgeStep to
    // reject all index results via the isAlreadyBoundAndDifferent check.
    IndexOrderedCandidate indexOrderedCandidate = null;
    if (subPatterns.size() == 1 && orderBy != null) {
      var probeEdges = getTopologicalSortedSchedule(
          estimatedRootEntries, pattern, aliasClasses, context.getDatabaseSession());
      indexOrderedCandidate =
          detectIndexOrderedCandidate(
              probeEdges, context, estimatedRootEntries);
    }

    // Phase 4: Prefetch small alias sets into the context variable map (see class Javadoc)
    if (indexOrderedCandidate != null) {
      // Exclude the target alias — it will be bound by IndexOrderedEdgeStep
      aliasesToPrefetch.remove(indexOrderedCandidate.targetAlias());
    }
    addPrefetchSteps(result, aliasesToPrefetch, context, enableProfiling);

    // Phase 5: Topological scheduling + step generation for each connected component
    if (subPatterns.size() > 1) {
      // Multiple disjoint sub-patterns → Cartesian product of their independent results
      var step = new CartesianProductStep(context, enableProfiling);
      for (var subPattern : subPatterns) {
        step.addSubPlan(
            createPlanForPattern(
                subPattern, context, estimatedRootEntries, aliasesToPrefetch,
                null, enableProfiling));
      }
      result.chain(step);
    } else {
      // Single connected pattern → inline the steps directly into the main plan
      var plan =
          createPlanForPattern(
              pattern, context, estimatedRootEntries, aliasesToPrefetch,
              indexOrderedCandidate, enableProfiling);
      for (var step : plan.getSteps()) {
        result.chain((ExecutionStepInternal) step);
      }
    }

    // Phase 6: Append NOT-pattern filter steps
    manageNotPatterns(result, pattern, notMatchExpressions, context, enableProfiling);

    // Phase 7: If optional nodes were encountered, replace EMPTY_OPTIONAL sentinels with null
    if (foundOptional) {
      result.chain(new RemoveEmptyOptionalsStep(context, enableProfiling));
    }

    // Phase 8: Append return projection and post-processing steps
    if (returnElements || returnPaths || returnPatterns || returnPathElements) {
      // Built-in return modes ($elements, $paths, $patterns, $pathElements)
      addReturnStep(result, context, enableProfiling);

      if (this.returnDistinct) {
        result.chain(new DistinctExecutionStep(context, enableProfiling));
      }
      if (groupBy != null) {
        throw new CommandExecutionException(context.getDatabaseSession(),
            "Cannot execute GROUP BY in MATCH query with RETURN $elements, $pathElements, $patterns"
                + " or $paths");
      }

      if (this.unwind != null) {
        result.chain(new UnwindStep(unwind, context, enableProfiling));
      }

      if (this.orderBy != null) {
        Integer maxResults = null;
        if (this.limit != null && this.limit.getValue(context) >= 0) {
          var skipSize = (this.skip != null && this.skip.getValue(context) >= 0)
              ? this.skip.getValue(context) : 0;
          maxResults = skipSize + this.limit.getValue(context);
        }
        // Multi-field + candidate → primary key cutoff hint for early
        // termination in the bounded heap.
        SQLOrderByItem primaryHint = null;
        if (indexOrderedCandidate != null
            && indexOrderedCandidate.multiFieldOrderBy()
            && !this.returnDistinct) {
          primaryHint = orderBy.getItems().getFirst();
        }
        // indexOrderedUpstream: OrderByStep checks runtime context variable
        // to pass through when IndexOrderedEdgeStep produces sorted output.
        var indexOrderedUpstream = indexOrderedCandidate != null
            && !this.returnDistinct;
        result.chain(new OrderByStep(
            orderBy, maxResults, primaryHint, indexOrderedUpstream,
            context, -1, enableProfiling));
      }

      if (this.skip != null && skip.getValue(context) >= 0) {
        result.chain(new SkipExecutionStep(skip, context, enableProfiling));
      }
      if (this.limit != null && limit.getValue(context) >= 0) {
        result.chain(new LimitExecutionStep(limit, context, enableProfiling));
      }
    } else {
      // Custom RETURN expressions — delegate to the SELECT planner for projection,
      // GROUP BY, ORDER BY, UNWIND, SKIP, LIMIT handling
      var info = new QueryPlanningInfo();
      List<SQLProjectionItem> items = new ArrayList<>();
      for (var i = 0; i < this.returnItems.size(); i++) {
        var item =
            new SQLProjectionItem(
                returnItems.get(i), this.returnAliases.get(i), returnNestedProjections.get(i));
        items.add(item);
      }
      info.projection = new SQLProjection(items, returnDistinct);

      info.projection = SelectExecutionPlanner.translateDistinct(info.projection);
      info.distinct = info.projection != null && info.projection.isDistinct();
      if (info.projection != null) {
        info.projection.setDistinct(false);
      }

      info.groupBy = this.groupBy;
      info.orderBy = this.orderBy;
      info.unwind = this.unwind;
      info.skip = this.skip;
      info.limit = this.limit;

      // When index-ordered traversal is active AND no GROUP BY or DISTINCT:
      // pass indexOrderedUpstream flag so OrderByStep can detect pre-sorted
      // input at runtime and pass through without sorting.
      if (indexOrderedCandidate != null
          && this.groupBy == null
          && !this.returnDistinct) {
        info.indexOrderedUpstream = true;
        if (indexOrderedCandidate.multiFieldOrderBy()) {
          info.primaryKeySortedInput = orderBy.getItems().getFirst();
        }
      }

      SelectExecutionPlanner.optimizeQuery(info, context);
      SelectExecutionPlanner.handleProjectionsBlock(result, info, context, enableProfiling);
    }

    // --- Store the assembled plan in the cache for future reuse ---
    if (useCache
        && !enableProfiling
        && statement.executinPlanCanBeCached(session)
        && result.canBeCached()
        && YqlExecutionPlanCache.getLastInvalidation(session) < planningStart) {
      YqlExecutionPlanCache.put(statement.getOriginalStatement(), result, session);
    }

    return result;
  }

  /**
   * Checks whether the filter for the given alias references runtime context variables
   * such as `$matched`, which means the alias cannot be prefetched because its filter
   * depends on values produced during pattern traversal.
   */
  private boolean dependsOnExecutionContext(String key) {
    var filter = aliasFilters.get(key);
    if (filter == null) {
      return false;
    }
    if (filter.refersToParent()) {
      return true;
    }
    return filter.toString().toLowerCase(Locale.ROOT).contains("$matched.");
  }

  private boolean isOptional(String key) {
    var node = this.pattern.aliasToNode.get(key);
    return node != null && node.isOptionalNode();
  }

  /**
   * Converts each `NOT { … }` expression into a {@link FilterNotMatchPatternStep} and
   * appends it to the plan. The NOT pattern reuses the same traversal mechanics as
   * positive patterns, but wraps them in a filter that **discards** rows for which the
   * pattern matches.
   *
   * <p>### Constraints (current implementation)
   *
   * <p>- The first alias in a NOT expression **must** already exist in the positive pattern.
   * - `WHERE` conditions on the origin node of a NOT expression are not yet supported.
   * - Multi-path items ({@link SQLMultiMatchPathItem}) inside NOT are not yet supported.
   *
   * @param result               the plan being assembled
   * @param pattern              the positive pattern (used to validate alias references)
   * @param notMatchExpressions  the list of negative match expressions
   * @param context              the command context
   * @param enableProfiling      whether to enable step profiling
   */
  private static void manageNotPatterns(
      SelectExecutionPlan result,
      Pattern pattern,
      List<SQLMatchExpression> notMatchExpressions,
      CommandContext context,
      boolean enableProfiling) {
    for (var exp : notMatchExpressions) {
      if (pattern.aliasToNode.get(exp.getOrigin().getAlias()) == null) {
        throw new CommandExecutionException(context.getDatabaseSession(),
            "This kind of NOT expression is not supported (yet). "
                + "The first alias in a NOT expression has to be present in the positive pattern");
      }

      if (exp.getOrigin().getFilter() != null) {
        throw new CommandExecutionException(context.getDatabaseSession(),
            "This kind of NOT expression is not supported (yet): "
                + "WHERE condition on the initial alias");
        // TODO implement his
      }

      var lastFilter = exp.getOrigin();
      List<AbstractExecutionStep> steps = new ArrayList<>();
      for (var item : exp.getItems()) {
        if (item instanceof SQLMultiMatchPathItem) {
          throw new CommandExecutionException(context.getDatabaseSession(),
              "This kind of NOT expression is not supported (yet): " + item);
        }
        var edge = new PatternEdge();
        edge.item = item;
        edge.out = new PatternNode();
        edge.out.alias = lastFilter.getAlias();
        edge.in = new PatternNode();
        edge.in.alias = item.getFilter().getAlias();
        var traversal = new EdgeTraversal(edge, true);
        var step = new MatchStep(context, traversal, enableProfiling);
        steps.add(step);
        lastFilter = item.getFilter();
      }
      result.chain(new FilterNotMatchPatternStep(steps, context, enableProfiling));
    }
  }

  /**
   * Appends the appropriate return-projection step based on the `RETURN` mode
   * (`$elements`, `$paths`, `$patterns`, or `$pathElements`).
   */
  private void addReturnStep(
      SelectExecutionPlan result, CommandContext context, boolean profilingEnabled) {
    if (returnElements) {
      result.chain(new ReturnMatchElementsStep(context, profilingEnabled));
    } else if (returnPaths) {
      result.chain(new ReturnMatchPathsStep(context, profilingEnabled));
    } else if (returnPatterns) {
      result.chain(new ReturnMatchPatternsStep(context, profilingEnabled));
    } else if (returnPathElements) {
      result.chain(new ReturnMatchPathElementsStep(context, profilingEnabled));
    } else {
      var projection = new SQLProjection(-1);
      projection.setItems(new ArrayList<>());
      for (var i = 0; i < returnAliases.size(); i++) {
        var item = new SQLProjectionItem(-1);
        item.setExpression(returnItems.get(i));
        item.setAlias(returnAliases.get(i));
        item.setNestedProjection(returnNestedProjections.get(i));
        projection.getItems().add(item);
      }
      result.chain(new ProjectionCalculationStep(projection, context, profilingEnabled));
    }
  }

  /**
   * Creates an execution plan for a single connected pattern (sub-graph).
   *
   * <p>The method first computes a topological traversal order via
   * {@link #getTopologicalSortedSchedule}, then emits one execution step per edge.
   * If the pattern has no edges (a single isolated node), a standalone
   * {@link MatchFirstStep} is emitted instead.
   *
   * @param pattern              the connected pattern to plan
   * @param context              the command context
   * @param estimatedRootEntries per-alias cardinality estimates used to pick the
   *                             cheapest starting node
   * @param prefetchedAliases    aliases whose records have already been prefetched
   * @param profilingEnabled     whether to collect execution statistics
   * @return an execution plan for this sub-pattern
   */
  private InternalExecutionPlan createPlanForPattern(
      Pattern pattern,
      CommandContext context,
      Map<String, Long> estimatedRootEntries,
      Set<String> prefetchedAliases,
      @Nullable IndexOrderedCandidate candidate,
      boolean profilingEnabled) {
    var plan = new SelectExecutionPlan(context);
    var sortedEdges = getTopologicalSortedSchedule(estimatedRootEntries, pattern,
        aliasClasses, aliasFilters, context.getDatabaseSession());

    var first = true;
    if (!sortedEdges.isEmpty()) {
      for (var edge : sortedEdges) {
        // Annotate each edge traversal with the source node's class/RID/filter constraints
        // so that MatchReverseEdgeTraverser can apply them when traversing in reverse
        if (edge.edge.out.alias != null) {
          edge.setLeftClass(aliasClasses.get(edge.edge.out.alias));
          edge.setLeftRid(aliasRids.get(edge.edge.out.alias));
          edge.setLeftClass(aliasClasses.get(edge.edge.out.alias));
          edge.setLeftFilter(aliasFilters.get(edge.edge.out.alias));
        }
      }

      optimizeScheduleWithIntersections(sortedEdges, context);
      attachCollectionIdFilters(sortedEdges, context);

      for (var edge : sortedEdges) {
        addStepsFor(plan, edge, context, first, candidate, profilingEnabled);
        first = false;
      }
    } else {
      // No edges → single isolated node. Use prefetched data if available, otherwise
      // build a SELECT execution plan to scan/fetch the node's records.
      var node = pattern.getAliasToNode().values().iterator().next();
      if (prefetchedAliases.contains(node.alias)) {
        plan.chain(new MatchFirstStep(context, node, profilingEnabled));
      } else {
        var clazz = aliasClasses.get(node.alias);
        var rid = aliasRids.get(node.alias);
        var filter = aliasFilters.get(node.alias);
        var select = createSelectStatement(clazz, rid, filter);
        plan.chain(
            new MatchFirstStep(
                context,
                node,
                select.createExecutionPlan(context, profilingEnabled),
                profilingEnabled));
      }
    }
    return plan;
  }

  /**
   * Computes the **edge schedule** — the order in which pattern edges will be traversed
   * at runtime.
   *
   * <p>The algorithm is a cost-driven, dependency-aware, depth-first graph traversal:
   *
   * <p>1. Compute per-alias dependencies from `$matched` references in `WHERE` clauses.
   * 2. Sort candidate root nodes by their estimated cardinality (ascending) so that the
   *    traversal starts from the smallest set.
   * 3. Repeatedly pick the cheapest unvisited, dependency-free root node and perform a
   *    depth-first expansion, appending each discovered edge to the schedule.
   * 4. Continue until all edges have been scheduled.
   *
   * <p>If the algorithm stalls before scheduling all edges (e.g. due to circular
   * `$matched` dependencies), a {@link CommandExecutionException} is thrown.
   *
   * <p><pre>
   * ┌────────────────────────────────────────────────────────────────┐
   * │ getTopologicalSortedSchedule()                                │
   * │                                                               │
   * │ 1. Compute dependency map: alias → {aliases it depends on}    │
   * │    (from $matched references in WHERE clauses)                │
   * │                                                               │
   * │ 2. Sort candidate roots by estimated cardinality (ascending)  │
   * │    [cheapest first]                                           │
   * │                                                               │
   * │ 3. Main loop (while unscheduled edges remain):                │
   * │    a. Pick cheapest unvisited root with no unmet dependencies │
   * │    b. DFS from that root (updateScheduleStartingAt):          │
   * │       ┌────────────────────────────────────────────┐          │
   * │       │ Mark node visited, clear it from deps      │          │
   * │       │ For each outgoing/bidirectional edge:      │          │
   * │       │   neighbor has unmet deps? → skip          │          │
   * │       │   neighbor visited, edge not? → add edge   │          │
   * │       │   neighbor unvisited? → add edge, recurse  │          │
   * │       └────────────────────────────────────────────┘          │
   * │    c. Repeat until no more expansions possible               │
   * │                                                               │
   * │ 4. If edges remain unscheduled → circular dependency error    │
   * └────────────────────────────────────────────────────────────────┘
   *
   * Worked example:
   *
   *   Query: MATCH {class:A, as:a}.out(){as:b}.out(){as:c}
   *
   *   Estimated roots: {a: 50, b: 10000, c: 500}
   *   Sorted roots:    [a(50), c(500), b(10000)]
   *
   *   Pass 1: start at 'a' (cheapest, no unmet deps)
   *     DFS: visit a
   *            → edge(a→b): b unvisited, deps met → add edge(a→b, fwd), visit b
   *              → edge(b→c): c unvisited, deps met → add edge(b→c, fwd), visit c
   *     Schedule: [edge(a→b, fwd), edge(b→c, fwd)]
   *
   *   All 2 edges scheduled → done.
   * </pre>
   *
   * @param estimatedRootEntries per-alias cardinality estimates
   * @param pattern              the pattern graph to schedule
   * @param session              the database session (used for error reporting)
   * @return an ordered list of {@link EdgeTraversal}s representing the traversal schedule
   * @throws CommandExecutionException if the pattern contains unresolvable circular
   *                                    dependencies
   */
  private List<EdgeTraversal> getTopologicalSortedSchedule(
      Map<String, Long> estimatedRootEntries, Pattern pattern,
      Map<String, String> aliasClasses, Map<String, SQLWhereClause> aliasFilters,
      DatabaseSessionEmbedded session) {
    List<EdgeTraversal> resultingSchedule = new ArrayList<>();
    var remainingDependencies = getDependencies(pattern);
    Set<PatternNode> visitedNodes = new HashSet<>();
    Set<PatternEdge> visitedEdges = new HashSet<>();

    // Sort the possible root vertices in order of estimated size, since we want to start with a
    // small vertex set.
    List<PairLongObject<String>> rootWeights = new ArrayList<>();
    for (var root : estimatedRootEntries.entrySet()) {
      rootWeights.add(new PairLongObject<>(root.getValue(), root.getKey()));
    }
    Collections.sort(rootWeights);

    // Add the starting vertices, in the correct order, to an ordered set.
    Set<String> remainingStarts = new LinkedHashSet<>();
    for (var item : rootWeights) {
      remainingStarts.add(item.getValue());
    }
    // Add all the remaining aliases after all the suggested start points.
    remainingStarts.addAll(pattern.aliasToNode.keySet());

    while (resultingSchedule.size() < pattern.numOfEdges) {
      // Start a new depth-first pass, adding all nodes with satisfied dependencies.
      // 1. Find a starting vertex for the depth-first pass.
      PatternNode startingNode = null;
      List<String> startsToRemove = new ArrayList<>();
      for (var currentAlias : remainingStarts) {
        var currentNode = pattern.aliasToNode.get(currentAlias);

        if (visitedNodes.contains(currentNode)) {
          // If a previous traversal already visited this alias, remove it from further
          // consideration.
          startsToRemove.add(currentAlias);
        } else if (remainingDependencies.get(currentAlias) == null
            || remainingDependencies.get(currentAlias).isEmpty()) {
          // If it hasn't been visited, and has all dependencies satisfied, visit it.
          startsToRemove.add(currentAlias);
          startingNode = currentNode;
          break;
        }
      }
      startsToRemove.forEach(remainingStarts::remove);

      if (startingNode == null) {
        // We didn't manage to find a valid root, and yet we haven't constructed a complete
        // schedule.
        // This means there must be a cycle in our dependency graph, or all dependency-free nodes
        // are optional.
        // Therefore, the query is invalid.
        throw new CommandExecutionException(session,
            "This query contains MATCH conditions that cannot be evaluated, "
                + "like an undefined alias or a circular dependency on a $matched condition.");
      }

      // 2. Having found a starting vertex, traverse its neighbors depth-first,
      //    adding any non-visited ones with satisfied dependencies to our schedule.
      updateScheduleStartingAt(
          startingNode, visitedNodes, visitedEdges, remainingDependencies,
          resultingSchedule, estimatedRootEntries, aliasClasses, aliasFilters,
          session);
    }

    if (resultingSchedule.size() != pattern.numOfEdges) {
      throw new AssertionError(
          "Incorrect number of edges: " + resultingSchedule.size() + " vs " + pattern.numOfEdges);
    }

    return resultingSchedule;
  }

  /**
   * Start a depth-first traversal from the starting node, adding all viable unscheduled edges and
   * vertices.
   *
   * @param startNode             the node from which to start the depth-first traversal
   * @param visitedNodes          set of nodes that are already visited (mutated in this function)
   * @param visitedEdges          set of edges that are already visited and therefore don't need to
   *                              be scheduled (mutated in this function)
   * @param remainingDependencies dependency map including only the dependencies that haven't yet
   *                              been satisfied (mutated in this function)
   * @param resultingSchedule     the schedule being computed i.e. appended to (mutated in this
   *                              function)
   */
  private static void updateScheduleStartingAt(
      PatternNode startNode,
      Set<PatternNode> visitedNodes,
      Set<PatternEdge> visitedEdges,
      Map<String, Set<String>> remainingDependencies,
      List<EdgeTraversal> resultingSchedule,
      Map<String, Long> estimatedRootEntries,
      Map<String, String> aliasClasses,
      Map<String, SQLWhereClause> aliasFilters,
      DatabaseSessionEmbedded session) {
    // YouTrackDB requires the schedule to contain all edges present in the query, which is a stronger
    // condition
    // than simply visiting all nodes in the query. Consider the following example query:
    //     MATCH {
    //         class: A,
    //         as: foo
    //     }.in() {
    //         as: bar
    //     }, {
    //         class: B,
    //         as: bar
    //     }.out() {
    //         as: foo
    //     } RETURN $matches
    // The schedule for the above query must have two edges, even though there are only two nodes
    // and they can both
    // be visited with the traversal of a single edge.
    //
    // To satisfy it, we obey the following for each non-optional node:
    // - ignore edges to neighboring nodes which have unsatisfied dependencies;
    // - for visited neighboring nodes, add their edge if it wasn't already present in the schedule,
    // but do not
    //   recurse into the neighboring node;
    // - for unvisited neighboring nodes with satisfied dependencies, add their edge and recurse
    // into them.
    visitedNodes.add(startNode);
    for (var dependencies : remainingDependencies.values()) {
      dependencies.remove(startNode.alias);
    }

    // Build candidate edges with estimated costs, sorted cheapest first.
    List<Map.Entry<PatternEdge, Boolean>> sortedEdges = new ArrayList<>();
    for (var outEdge : startNode.out) {
      sortedEdges.add(Map.entry(outEdge, true));
    }
    for (var inEdge : startNode.in) {
      if (inEdge.item.isBidirectional()) {
        sortedEdges.add(Map.entry(inEdge, false));
      } else if (visitedNodes.contains(inEdge.out)) {
        // Non-bidirectional incoming edge from an already-visited node.
        // This happens when a node with $matched dependencies becomes a start
        // node after its dependencies are resolved by a different branch of the
        // DFS. The edge must still be scheduled (from the visited source toward
        // this node), even though we cannot traverse it in reverse.
        //
        // isOutbound=false: from startNode's perspective, this edge is incoming.
        // In the traversal direction logic below, for non-optional
        // non-bidirectional edges this yields traversalDirection=false (reverse),
        // which causes MatchReverseEdgeTraverser to start at edge.in (startNode)
        // and call executeReverse() to reach edge.out (the visited node). Both
        // endpoints are already visited, so this is a join/verification step.
        // For optional nodes, the direction is flipped to !isOutbound=true
        // (outbound from the visited source toward the optional start node).
        sortedEdges.add(Map.entry(inEdge, false));
      }
    }

    // Sort by estimated edge traversal cost (cheapest first).
    // Already-visited neighbors get cost 0.0 (just a join, no traversal).
    // When estimates are unavailable (MAX_VALUE), preserve original order.
    var sourceAlias = startNode.alias;
    // Use THRESHOLD as fallback for unestimated nodes — consistent with the
    // prefetch threshold used elsewhere in the planner. A moderate value avoids
    // making unknown-cost edges appear artificially cheap or expensive.
    long sourceRows = estimatedRootEntries.getOrDefault(sourceAlias, THRESHOLD);
    // Pre-compute costs to avoid redundant schema lookups during sort comparisons.
    // The cost combines fan-out-based traversal cost with target node selectivity:
    // edges whose target has a selective WHERE clause (low estimated cardinality)
    // are cheaper because they produce fewer intermediate results to join against.
    var edgeCosts = new HashMap<PatternEdge, Double>(sortedEdges.size());
    for (var entry : sortedEdges) {
      var neighbor = entry.getValue() ? entry.getKey().in : entry.getKey().out;
      double cost;
      if (visitedNodes.contains(neighbor)) {
        cost = 0.0;
      } else {
        cost = estimateEdgeCost(
            entry.getKey(), sourceAlias, sourceRows, aliasClasses, session);
        if (cost < Double.MAX_VALUE) {
          cost = applyTargetSelectivity(
              cost, neighbor.alias, entry.getKey(), entry.getValue(),
              aliasClasses, aliasFilters, estimatedRootEntries, session);
          cost = applyDepthMultiplier(cost, entry.getKey());
        }
      }
      edgeCosts.put(entry.getKey(), cost);
    }
    // TimSort is stable: equal-cost edges (including those with MAX_VALUE when
    // cost cannot be estimated) retain their insertion order, preserving the
    // original out-first-then-bidirectional-in ordering as a tiebreaker.
    sortedEdges.sort(Comparator.comparingDouble(
        entry -> edgeCosts.getOrDefault(entry.getKey(), Double.MAX_VALUE)));

    // Process edges in cost order, retrying any that were skipped due to unsatisfied
    // $matched dependencies. Traversing cheaper edges first may resolve dependencies
    // for more expensive edges (e.g., visiting 'author' before 'knowsCheck' which
    // references $matched.author). We loop until no further progress is made.
    var pending = new ArrayList<>(sortedEdges);
    boolean progress = true;
    while (progress && !pending.isEmpty()) {
      progress = false;
      var deferred = new ArrayList<Map.Entry<PatternEdge, Boolean>>();

      for (var edgeData : pending) {
        var edge = edgeData.getKey();
        boolean isOutbound = edgeData.getValue();
        var neighboringNode = isOutbound ? edge.in : edge.out;

        var deps = remainingDependencies.get(neighboringNode.alias);
        if (!deps.isEmpty()) {
          // Unsatisfied dependencies — defer to a later pass.
          if (logger.isTraceEnabled()) {
            logger.trace("Deferred edge {} to {} due to unsatisfied dependencies: {}",
                edge, neighboringNode.alias, deps);
          }
          deferred.add(edgeData);
          continue;
        }

        if (visitedNodes.contains(neighboringNode)) {
          if (!visitedEdges.contains(edge)) {
            // If we are executing in this block, we are in the following situation:
            // - the startNode has not been visited yet;
            // - it has a neighboringNode that has already been visited;
            // - the edge between the startNode and the neighboringNode has not been
            //   scheduled yet.
            //
            // The isOutbound value shows us whether the edge is outbound from the point
            // of view of the startNode. However, if there are edges to the startNode, we
            // must visit the startNode from an already-visited neighbor, to preserve the
            // validity of the traversal. Therefore, we negate the value of isOutbound to
            // ensure that the edge is always scheduled in the direction from the
            // already-visited neighbor toward the startNode. Notably, this is also the
            // case when evaluating "optional" nodes -- we always visit the optional node
            // from its non-optional and already-visited neighbor.
            //
            // The only exception to the above is when we have edges with "while"
            // conditions. We are not allowed to flip their directionality, so we leave
            // them as-is.
            //
            // Example: bidirectional edge between visited node B and new node A
            //
            //   Pattern:    (A) ──both('Knows')──> (B)
            //                        edge.out=A, edge.in=B
            //
            //   DFS state at this point:
            //     - B is already in visitedNodes (it was reached earlier)
            //     - A is the startNode being processed for the first time
            //     - isOutbound = true (edge goes out from A's perspective)
            //
            //   Without flip:  schedule as EdgeTraversal(edge, fwd)  -> A is source
            //     Problem: A hasn't been produced by any prior step yet, so
            //     MatchStep cannot look up A in the upstream row.
            //
            //   With flip:     schedule as EdgeTraversal(edge, rev)  -> B is source
            //     Correct: B was already matched in a previous step, so
            //     MatchReverseEdgeTraverser starts from B and traverses to A.
            //
            // The flip is applied when:
            //   - startNode is optional (must be reached FROM a visited node), or
            //   - edge is bidirectional (direction is arbitrary; pick the valid one)
            // The flip is NOT applied for WHILE edges because recursive traversal
            // semantics depend on the original syntactic direction.
            boolean traversalDirection;
            if (startNode.optional || edge.item.isBidirectional()) {
              traversalDirection = !isOutbound;
            } else {
              traversalDirection = isOutbound;
            }

            visitedEdges.add(edge);
            resultingSchedule.add(new EdgeTraversal(edge, traversalDirection));
            progress = true;
          }
        } else if (!startNode.optional
            || isOptionalChain(startNode, edge, neighboringNode)) {
          // If the neighboring node wasn't visited, we don't expand the optional node
          // into it, hence the above check. Instead, we'll allow the neighboring node
          // to add the edge we failed to visit, via the above block.
          if (visitedEdges.contains(edge)) {
            // Should never happen.
            throw new AssertionError(
                "The edge was visited, but the neighboring vertex was not: "
                    + edge
                    + " "
                    + neighboringNode);
          }

          visitedEdges.add(edge);
          resultingSchedule.add(new EdgeTraversal(edge, isOutbound));
          updateScheduleStartingAt(
              neighboringNode, visitedNodes, visitedEdges, remainingDependencies,
              resultingSchedule, estimatedRootEntries, aliasClasses, aliasFilters,
              session);
          progress = true;
        }
      }

      pending = deferred;
    }
  }

  /**
   * Determines whether the given edge connects two nodes that belong to a fully-optional
   * chain — i.e. every node reachable through outgoing edges from the start node is
   * optional. If so, the scheduler is allowed to expand into the neighboring node even
   * though the start node is itself optional.
   */
  private static boolean isOptionalChain(
      PatternNode startNode, PatternEdge edge, PatternNode neighboringNode) {
    return isOptionalChain(startNode, edge, neighboringNode, new HashSet<>());
  }

  private static boolean isOptionalChain(
      PatternNode startNode,
      PatternEdge edge,
      PatternNode neighboringNode,
      Set<PatternEdge> visitedEdges) {
    if (!startNode.isOptionalNode() || !neighboringNode.isOptionalNode()) {
      return false;
    }

    visitedEdges.add(edge);

    if (neighboringNode.out != null) {
      for (var patternEdge : neighboringNode.out) {
        if (!visitedEdges.contains(patternEdge)
            && !isOptionalChain(neighboringNode, patternEdge, patternEdge.in, visitedEdges)) {
          return false;
        }
      }
    }

    return true;
  }

  /**
   * Estimates the cost of traversing {@code edge} from the node whose alias is
   * {@code sourceAlias}, using the edge's fan-out and the source's estimated
   * cardinality.
   *
   * @param edge         the pattern edge to traverse
   * @param sourceAlias  alias of the node we are traversing FROM
   * @param sourceRows   estimated rows for the source alias
   * @param aliasClasses alias → class name mapping
   * @param session      database session for schema access
   * @return estimated cost (lower is better), or {@link Double#MAX_VALUE} if
   *         the cost cannot be estimated
   */
  static double estimateEdgeCost(
      PatternEdge edge,
      String sourceAlias,
      long sourceRows,
      Map<String, String> aliasClasses,
      DatabaseSessionEmbedded session) {
    var method = edge.item.getMethod();
    if (method == null) {
      return Double.MAX_VALUE;
    }

    // Extract direction from the method name (out/in/both).
    Direction direction = parseDirection(method.getMethodNameString());
    if (direction == null) {
      return Double.MAX_VALUE;
    }

    // Extract edge class name from the method's first parameter, if present.
    // e.g., .out('Knows') → edgeClassName = "Knows"
    String edgeClassName = extractEdgeClassName(method);

    // Determine OUT/IN vertex classes from the edge schema (if available).
    String outVertexClass = null;
    String inVertexClass = null;
    if (edgeClassName != null) {
      var schema = session.getMetadata().getImmutableSchemaSnapshot();
      if (schema != null) {
        var edgeClass = schema.getClassInternal(edgeClassName);
        if (edgeClass != null) {
          var outProp = edgeClass.getPropertyInternal("out");
          if (outProp != null && outProp.getLinkedClass() != null) {
            outVertexClass = outProp.getLinkedClass().getName();
          }
          var inProp = edgeClass.getPropertyInternal("in");
          if (inProp != null && inProp.getLinkedClass() != null) {
            inVertexClass = inProp.getLinkedClass().getName();
          }
        }
      }
    }

    String sourceClassName = aliasClasses.get(sourceAlias);

    double fanOut = EdgeFanOutEstimator.estimateFanOut(
        session, edgeClassName, sourceClassName, direction,
        outVertexClass, inVertexClass);

    return CostModel.edgeTraversalCost(sourceRows, fanOut);
  }

  /**
   * Adjusts the traversal cost of an edge by the selectivity of the target node's
   * WHERE clause. Uses two complementary strategies:
   *
   * <ol>
   *   <li><b>Filter-shape heuristic</b> — inspects the AST of the target's WHERE clause
   *       to classify it as an equality ({@code name = :value} → selectivity ≈ 1/classCount)
   *       or inequality ({@code name <> :value} → selectivity ≈ (classCount−1)/classCount).
   *       This works regardless of table size and does not require an index.</li>
   *   <li><b>Estimated cardinality ratio</b> — when the heuristic cannot classify the
   *       filter, falls back to the ratio of estimated filtered rows
   *       ({@code estimatedRootEntries}) to total class count.</li>
   * </ol>
   *
   * <p>If the target node has no explicit {@code class:} constraint, the method infers
   * the class from the edge schema's linked vertex property (e.g., {@code HAS_TAG.in}
   * linked to {@code Tag}).
   *
   * @param baseCost             the fan-out-based traversal cost from
   *                             {@link #estimateEdgeCost}
   * @param targetAlias          alias of the target (neighbor) node
   * @param edge                 the pattern edge being evaluated
   * @param isOutbound           whether the edge is outbound from the source node
   * @param aliasClasses         alias → class name mapping
   * @param aliasFilters         alias → WHERE clause mapping
   * @param estimatedRootEntries estimated cardinality per alias
   * @param session              database session for schema access
   * @return adjusted cost (lower when the target's WHERE is more selective)
   */
  static double applyTargetSelectivity(
      double baseCost,
      String targetAlias,
      PatternEdge edge,
      boolean isOutbound,
      Map<String, String> aliasClasses,
      Map<String, SQLWhereClause> aliasFilters,
      Map<String, Long> estimatedRootEntries,
      DatabaseSessionEmbedded session) {
    var targetClass = resolveTargetClass(
        targetAlias, edge, isOutbound, aliasClasses, session);
    if (targetClass == null) {
      return baseCost;
    }

    var schema = session.getMetadata().getImmutableSchemaSnapshot();
    if (schema == null || !schema.existsClass(targetClass)) {
      return baseCost;
    }

    long classCount = schema.getClassInternal(targetClass).approximateCount(session);
    if (classCount <= 0) {
      return baseCost;
    }

    var filter = aliasFilters != null ? aliasFilters.get(targetAlias) : null;
    if (filter != null) {
      var schemaClass = schema.getClassInternal(targetClass);
      double heuristic = estimateFilterSelectivity(
          filter, classCount, schemaClass, session);
      if (heuristic >= 0.0) {
        return baseCost * heuristic;
      }
    }

    var targetEstimate = estimatedRootEntries.get(targetAlias);
    if (targetEstimate == null) {
      return baseCost;
    }
    double selectivity = (double) targetEstimate / classCount;
    return baseCost * selectivity;
  }

  /**
   * Resolves the target vertex class for an edge traversal. First checks
   * {@code aliasClasses} for an explicit constraint; if absent, infers the class
   * from the edge schema's linked vertex property.
   *
   * <p>For {@code .out('HAS_TAG')} the target is the "in" vertex of the edge class,
   * so we read the linked class of the {@code in} property, and vice versa.
   */
  @Nullable private static String resolveTargetClass(
      String targetAlias,
      PatternEdge edge,
      boolean isOutbound,
      Map<String, String> aliasClasses,
      DatabaseSessionEmbedded session) {
    var explicit = aliasClasses.get(targetAlias);
    if (explicit != null) {
      return explicit;
    }
    // Infer target class from edge schema's linked vertex property.
    // Chain of null-safe lookups: method → edgeClassName → schema → edgeClass → linkedProp.
    var method = edge.item.getMethod();
    var edgeClassName = method != null ? extractEdgeClassName(method) : null;
    var schema = edgeClassName != null
        ? session.getMetadata().getImmutableSchemaSnapshot() : null;
    var edgeClass = schema != null ? schema.getClassInternal(edgeClassName) : null;
    if (edgeClass == null) {
      return null;
    }
    var linkedPropName = isOutbound ? "in" : "out";
    var linkedProp = edgeClass.getPropertyInternal(linkedPropName);
    return (linkedProp != null && linkedProp.getLinkedClass() != null)
        ? linkedProp.getLinkedClass().getName() : null;
  }

  /**
   * Classifies a WHERE clause by inspecting its AST to produce a selectivity
   * estimate. When the filter is a simple binary condition on an indexed
   * property, uses {@code distinctCount} from index statistics for accuracy:
   *
   * <ul>
   *   <li>{@code field = value} → {@code 1.0 / distinctCount}</li>
   *   <li>{@code field <> value} → {@code (distinctCount - 1.0) / distinctCount}</li>
   * </ul>
   *
   * <p>Falls back to {@code classCount} when no index statistics are available.
   * Returns {@code -1.0} for compound or unrecognizable filters to signal that
   * the caller should fall back to the cardinality-ratio estimate.
   */
  static double estimateFilterSelectivity(
      SQLWhereClause filter,
      long classCount,
      @Nullable SchemaClassInternal schemaClass,
      @Nullable DatabaseSessionEmbedded session) {
    var base = classCount > 0 ? filter.getBaseExpression() : null;
    var condition = base != null ? unwrapSingleCondition(base) : null;
    if (condition == null) {
      return -1.0;
    }

    // Compound AND: multiply individual selectivities (independence assumption).
    // For example, creationDate >= X AND creationDate < Y → sel(>=X) * sel(<Y).
    if (condition instanceof SQLAndBlock andBlock && andBlock.getSubBlocks().size() > 1) {
      return estimateCompoundAndSelectivity(
          andBlock, classCount, schemaClass, session);
    }

    // Compound OR: inclusion-exclusion (independence assumption).
    // sel(A OR B) = 1 - (1 - sel(A)) * (1 - sel(B))
    if (condition instanceof SQLOrBlock orBlock && orBlock.getSubBlocks().size() > 1) {
      return estimateCompoundOrSelectivity(
          orBlock, classCount, schemaClass, session);
    }

    if (!(condition instanceof SQLBinaryCondition binary)) {
      return -1.0;
    }
    return estimateSingleConditionSelectivity(
        binary, classCount, schemaClass, session);
  }

  /**
   * Estimates selectivity of a compound AND filter by multiplying individual
   * condition selectivities (independence assumption). Returns -1.0 if no
   * sub-condition could be estimated.
   */
  private static double estimateCompoundAndSelectivity(
      SQLAndBlock andBlock, long classCount,
      @Nullable SchemaClassInternal schemaClass,
      @Nullable DatabaseSessionEmbedded session) {
    double combined = 1.0;
    boolean anyEstimated = false;
    for (var sub : andBlock.getSubBlocks()) {
      double sel = estimateSubExpression(sub, classCount, schemaClass, session);
      if (sel >= 0.0) {
        combined *= sel;
        anyEstimated = true;
      }
    }
    return anyEstimated ? combined : -1.0;
  }

  /**
   * Estimates selectivity of a compound OR filter using the inclusion-exclusion
   * principle (independence assumption):
   * {@code sel(A OR B) = 1 - (1 - sel(A)) * (1 - sel(B))}.
   * Returns -1.0 if no sub-condition could be estimated.
   */
  private static double estimateCompoundOrSelectivity(
      SQLOrBlock orBlock, long classCount,
      @Nullable SchemaClassInternal schemaClass,
      @Nullable DatabaseSessionEmbedded session) {
    double complementProduct = 1.0;
    boolean anyEstimated = false;
    for (var sub : orBlock.getSubBlocks()) {
      double sel = estimateSubExpression(sub, classCount, schemaClass, session);
      if (sel >= 0.0) {
        complementProduct *= (1.0 - sel);
        anyEstimated = true;
      }
    }
    return anyEstimated ? 1.0 - complementProduct : -1.0;
  }

  /**
   * Estimates selectivity of a single sub-expression within a compound
   * AND/OR block. Handles nested AND blocks, OR blocks, and leaf binary
   * conditions via recursive dispatch.
   */
  private static double estimateSubExpression(
      SQLBooleanExpression sub, long classCount,
      @Nullable SchemaClassInternal schemaClass,
      @Nullable DatabaseSessionEmbedded session) {
    var unwrapped = unwrapSingleCondition(sub);
    if (unwrapped instanceof SQLBinaryCondition binary) {
      return estimateSingleConditionSelectivity(
          binary, classCount, schemaClass, session);
    }
    if (unwrapped instanceof SQLAndBlock nested && nested.getSubBlocks().size() > 1) {
      return estimateCompoundAndSelectivity(
          nested, classCount, schemaClass, session);
    }
    if (unwrapped instanceof SQLOrBlock nested && nested.getSubBlocks().size() > 1) {
      return estimateCompoundOrSelectivity(
          nested, classCount, schemaClass, session);
    }
    return -1.0;
  }

  /**
   * Estimates selectivity for a single binary condition using a three-tier
   * approach:
   * <ol>
   *   <li>{@code @class = 'X'} — class count ratio (meta-attribute)</li>
   *   <li>Histogram-aware estimation via {@link SelectivityEstimator}</li>
   *   <li>Uniform-distribution fallback using {@code distinctCount}</li>
   * </ol>
   */
  private static double estimateSingleConditionSelectivity(
      SQLBinaryCondition binary, long classCount,
      @Nullable SchemaClassInternal schemaClass,
      @Nullable DatabaseSessionEmbedded session) {
    // 1. @class = 'X' — meta-attribute, not index-backed
    double classSel = estimateClassAttributeSelectivity(
        binary, classCount, schemaClass, session);
    if (classSel >= 0.0) {
      return classSel;
    }

    // 2. Histogram-aware estimation (all operators: =, <>, >, <, >=, <=, IN)
    double histogramSel = estimateViaHistogram(binary, schemaClass, session);
    if (histogramSel >= 0.0) {
      return histogramSel;
    }

    // 3. Fallback: uniform-distribution using distinctCount
    var op = binary.getOperator();
    long divisor = resolveDistinctCount(binary, schemaClass, session);
    if (divisor <= 0) {
      divisor = classCount;
    }
    if (op instanceof SQLEqualsOperator) {
      return 1.0 / divisor;
    } else if (op instanceof SQLNeOperator) {
      return (divisor - 1.0) / divisor;
    }
    return -1.0;
  }

  /**
   * Attempts histogram-aware selectivity estimation by looking up index statistics
   * and histogram for the property in the binary condition, then delegating to
   * {@link SelectivityEstimator#estimateForOperator}.
   *
   * @return selectivity in [0.0, 1.0], or -1.0 if estimation is not possible
   *     (no index, no statistics, or value cannot be resolved at plan time)
   */
  private static double estimateViaHistogram(
      SQLBinaryCondition binary,
      @Nullable SchemaClassInternal schemaClass,
      @Nullable DatabaseSessionEmbedded session) {
    if (schemaClass == null || session == null) {
      return -1.0;
    }
    var propName = binary.getRelatedIndexPropertyName();
    if (propName == null) {
      return -1.0;
    }
    var indexes = schemaClass.getInvolvedIndexesInternal(session, propName);
    if (indexes == null) {
      return -1.0;
    }
    // Try to resolve the comparison value at plan time. Only literal values
    // can be resolved — parameterized queries (e.g. :startDate) depend on
    // runtime input parameters which are not available during planning.
    // Catches RuntimeException (not just CommandExecutionException) because
    // execute() with a null Result/context can also throw NPE, ClassCastException,
    // etc. for expressions that reference runtime state. All such failures
    // are non-fatal — they simply mean the value cannot be resolved at plan time.
    Object value;
    try {
      value = binary.getRight().execute(
          (com.jetbrains.youtrackdb.internal.core.query.Result) null, null);
    } catch (RuntimeException e) {
      value = null;
    }
    if (value == null) {
      return -1.0;
    }
    // Pick the most selective index (lowest selectivity estimate) to avoid
    // random plan jumps when multiple indexes cover the same property.
    double bestSel = -1.0;
    for (var index : indexes) {
      var stats = index.getStatistics(session);
      if (stats == null || stats.totalCount() <= 0) {
        continue;
      }
      var histogram = index.getHistogram(session);
      var sel = SelectivityEstimator.estimateForOperator(
          binary.getOperator(), stats, histogram, value);
      if (sel >= 0.0 && (bestSel < 0.0 || sel < bestSel)) {
        bestSel = sel;
      }
    }
    return bestSel;
  }

  /**
   * Attempts to resolve the number of distinct values for the property
   * referenced in a binary condition by looking up index statistics. Returns
   * {@code -1} if no indexed property can be identified or no statistics are
   * available.
   */
  private static long resolveDistinctCount(
      SQLBinaryCondition binary,
      @Nullable SchemaClassInternal schemaClass,
      @Nullable DatabaseSessionEmbedded session) {
    var propName = (schemaClass != null && session != null)
        ? binary.getRelatedIndexPropertyName() : null;
    var indexes = propName != null
        ? schemaClass.getInvolvedIndexesInternal(session, propName) : null;
    if (indexes != null) {
      // Pick the most selective index (lowest selectivity estimate) and return
      // its distinct count. Same criterion as resolveSelectivity() — ensures
      // both methods use the same index for a given property.
      Object value;
      try {
        value = binary.getRight().execute(
            (com.jetbrains.youtrackdb.internal.core.query.Result) null, null);
      } catch (RuntimeException e) {
        value = null;
      }
      double bestSel = -1.0;
      long bestDistinct = -1;
      for (var index : indexes) {
        var stats = index.getStatistics(session);
        if (stats == null || stats.distinctCount() <= 0) {
          continue;
        }
        if (value != null && stats.totalCount() > 0) {
          var histogram = index.getHistogram(session);
          var sel = SelectivityEstimator.estimateForOperator(
              binary.getOperator(), stats, histogram, value);
          if (sel >= 0.0 && (bestSel < 0.0 || sel < bestSel)) {
            bestSel = sel;
            bestDistinct = stats.distinctCount();
          }
        } else if (bestDistinct < 0) {
          // Fallback when value cannot be resolved: take first available.
          bestDistinct = stats.distinctCount();
        }
      }
      return bestDistinct;
    }
    return -1;
  }

  /**
   * Adjusts edge cost based on the maximum traversal depth specified in
   * a WHILE clause. Edges with {@code $depth < N} (or explicit maxDepth)
   * produce intermediate results proportional to the depth limit. A
   * lower maxDepth means fewer hops and cheaper traversal.
   *
   * <p>When no depth limit is set (simple one-hop edge), the cost is
   * unchanged. For WHILE edges without maxDepth, a default multiplier
   * of {@value #DEFAULT_WHILE_DEPTH} is applied to reflect the
   * potentially unbounded recursive expansion.
   *
   * @param baseCost the cost computed from fan-out and target selectivity
   * @param edge     the pattern edge to inspect for depth limits
   * @return adjusted cost, multiplied by the depth factor
   */
  static double applyDepthMultiplier(double baseCost, PatternEdge edge) {
    var filter = edge.item.getFilter();
    if (filter == null) {
      return baseCost;
    }
    var whileCondition = filter.getWhileCondition();
    if (whileCondition == null) {
      return baseCost;
    }
    var maxDepth = filter.getMaxDepth();
    if (maxDepth != null && maxDepth > 0) {
      return baseCost * maxDepth;
    }
    return baseCost * DEFAULT_WHILE_DEPTH;
  }

  private static final int DEFAULT_WHILE_DEPTH = 10;

  /**
   * Handles the {@code @class = 'ClassName'} selectivity heuristic. When the
   * left side of the condition is a record attribute ({@code @class}) and the
   * operator is equality, the selectivity is the ratio of the subclass record
   * count to the total target class count:
   * {@code subclassCount / targetClassCount}.
   *
   * <p>For example, with target class {@code Message} (10000 records) and
   * filter {@code @class = 'Post'} where {@code Post} has 3000 records,
   * selectivity = 3000/10000 = 0.3.
   *
   * @return selectivity in [0.0, 1.0], or {@code -1.0} if the condition
   *     is not a {@code @class = 'X'} pattern
   */
  private static double estimateClassAttributeSelectivity(
      SQLBinaryCondition binary,
      long targetClassCount,
      @Nullable SchemaClassInternal schemaClass,
      @Nullable DatabaseSessionEmbedded session) {
    if (schemaClass == null || session == null || targetClassCount <= 0) {
      return -1.0;
    }
    // Verify left side is @class, evaluate right side, and look up subclass — all
    // in a single guard chain. Any null/mismatch returns -1.0 to signal "not applicable".
    var recAttr = extractRecordAttribute(binary.getLeft());
    var isClassAttr = recAttr != null && "@class".equalsIgnoreCase(recAttr.getName());
    var subclassName = isClassAttr ? evaluateAsString(binary.getRight()) : null;
    var schema = subclassName != null
        ? session.getMetadata().getImmutableSchemaSnapshot() : null;
    if (schema == null || !schema.existsClass(subclassName)) {
      return -1.0;
    }
    long subclassCount = schema.getClassInternal(subclassName).approximateCount(session);
    return (double) subclassCount / targetClassCount;
  }

  /** Extracts the SQLRecordAttribute from an expression's left side, or null. */
  @Nullable private static SQLRecordAttribute extractRecordAttribute(
      @Nullable SQLExpression expr) {
    if (expr == null || expr.getMathExpression() == null) {
      return null;
    }
    if (!(expr.getMathExpression() instanceof SQLBaseExpression base)) {
      return null;
    }
    var identifier = base.getIdentifier();
    if (identifier == null || identifier.getSuffix() == null) {
      return null;
    }
    return identifier.getSuffix().getRecordAttribute();
  }

  /**
   * Evaluates an expression and returns the result as a String, or null.
   *
   * <p>Catches {@link RuntimeException} (not just {@link CommandExecutionException})
   * because {@code execute()} is called at plan time with a null {@code Result} and
   * a bare {@link BasicCommandContext}. Expressions that reference runtime state
   * ({@code $matched}, {@code $parent}, input parameters, record fields) may throw
   * {@code NullPointerException}, {@code ClassCastException}, or other unchecked
   * exceptions in addition to {@code CommandExecutionException}. All such failures
   * are non-fatal — they simply mean the value cannot be resolved at plan time.
   */
  @Nullable private static String evaluateAsString(@Nullable SQLExpression expr) {
    if (expr == null) {
      return null;
    }
    try {
      var value = expr.execute(
          (com.jetbrains.youtrackdb.internal.core.query.Result) null,
          new BasicCommandContext());
      return value instanceof String s ? s : null;
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * Unwraps single-element AND/OR blocks to find the innermost condition.
   * Returns the expression as-is if it is already a leaf condition or if
   * there are multiple sub-blocks (compound filter).
   */
  @Nullable private static SQLBooleanExpression unwrapSingleCondition(SQLBooleanExpression expr) {
    if (expr instanceof SQLAndBlock and) {
      if (and.getSubBlocks().size() == 1) {
        return unwrapSingleCondition(and.getSubBlocks().getFirst());
      }
    } else if (expr instanceof SQLOrBlock or) {
      if (or.getSubBlocks().size() == 1) {
        return unwrapSingleCondition(or.getSubBlocks().getFirst());
      }
    } else if (expr instanceof SQLNotBlock not) {
      if (!not.isNegate()) {
        return unwrapSingleCondition(not.getSub());
      }
    }
    return expr;
  }

  /**
   * Parses "out", "in", "both" (and their E-variants "outE", "inE", "bothE")
   * into a {@link Direction}. Returns {@code null} for unrecognized methods.
   */
  static Direction parseDirection(String methodName) {
    if (methodName == null) {
      return null;
    }
    return switch (methodName.toLowerCase(Locale.ENGLISH)) {
      case "out", "oute" -> Direction.OUT;
      case "in", "ine" -> Direction.IN;
      case "both", "bothe" -> Direction.BOTH;
      default -> null;
    };
  }

  /**
   * Extracts the edge class name from the method call's first parameter.
   * Tries {@code execute()} first to get the evaluated literal value;
   * falls back to stripping surrounding quotes from {@code toString()}
   * if execution returns null (e.g., context-dependent expressions).
   *
   * @return the edge class name, or {@code null} if no parameter is present
   *     or the value cannot be resolved to a string
   */
  static String extractEdgeClassName(SQLMethodCall method) {
    var params = method.getParams();
    if (params == null || params.isEmpty()) {
      return null;
    }
    var firstParam = params.getFirst();

    // Try evaluating the expression first (handles all literal types cleanly).
    // Catches RuntimeException: execute() with null Result / bare context can
    // throw NPE, ClassCastException, etc. — not just CommandExecutionException.
    try {
      var value = firstParam.execute((Result) null, new BasicCommandContext());
      if (value instanceof String s) {
        return s;
      }
    } catch (RuntimeException e) {
      if (logger.isTraceEnabled()) {
        logger.trace("Could not evaluate edge class parameter, "
            + "falling back to toString", e);
      }
    }

    // Fallback: strip surrounding quotes from the string representation
    if (firstParam.getMathExpression() instanceof SQLBaseExpression base) {
      var raw = base.toString();
      if (raw != null && raw.length() >= 2) {
        char first = raw.charAt(0);
        char last = raw.charAt(raw.length() - 1);
        if ((first == '"' && last == '"')
            || (first == '\'' && last == '\'')) {
          return raw.substring(1, raw.length() - 1);
        }
      }
      return raw;
    }
    return null;
  }

  /**
   * Post-scheduling optimization pass: detects back-reference and index-based
   * pre-filter opportunities and attaches {@link RidFilterDescriptor}s
   * to the appropriate edges.
   *
   * <p><b>Back-reference detection</b>: when edge_j's target filter contains
   * {@code @rid = $matched.X.@rid}, the intermediate node (edge_j's source)
   * must be in {@code X.reverse(edge_j)}. A {@link
   * RidFilterDescriptor.EdgeRidLookup} is attached to the preceding edge
   * (edge_i) that produces the intermediate node, so that edge_i's traversal
   * results are intersected with the pre-computed RidSet.
   *
   * <p><b>Index pre-filter detection</b>: when an edge's target node has an
   * indexable condition that does not reference {@code $matched}, a {@link
   * RidFilterDescriptor.IndexLookup} is attached to the edge.
   */
  private void optimizeScheduleWithIntersections(
      List<EdgeTraversal> schedule, CommandContext ctx) {
    // Build a map: target alias → edge index, so we can find the producing edge
    Map<String, Integer> targetAliasToEdgeIndex = new HashMap<>();
    for (var i = 0; i < schedule.size(); i++) {
      var et = schedule.get(i);
      var targetAlias = et.out ? et.edge.in.alias : et.edge.out.alias;
      if (targetAlias != null) {
        targetAliasToEdgeIndex.put(targetAlias, i);
      }
    }

    for (var j = 0; j < schedule.size(); j++) {
      var edgeJ = schedule.get(j);
      var targetAliasJ = edgeJ.out ? edgeJ.edge.in.alias : edgeJ.edge.out.alias;
      if (targetAliasJ == null) {
        continue;
      }

      var targetFilter = aliasFilters.get(targetAliasJ);
      if (targetFilter == null) {
        continue;
      }

      // --- Back-reference detection ---
      // Check if target filter contains @rid = $matched.X.@rid
      // Uses findRidEquality() (non-destructive) instead of
      // extractAndRemoveRidEquality() to avoid mutating the filter.
      var ridExpr = targetFilter.findRidEquality();
      if (ridExpr != null) {
        var involvedAliases = ridExpr.getMatchPatternInvolvedAliases();
        if (involvedAliases != null && !involvedAliases.isEmpty()) {
          var edgeClass = getEdgeClassName(edgeJ);
          var edgeDirection = getEdgeDirection(edgeJ);

          if (edgeClass != null && edgeDirection != null) {
            var sourceAliasJ = edgeJ.out
                ? edgeJ.edge.out.alias : edgeJ.edge.in.alias;
            var producingEdgeIdx = targetAliasToEdgeIndex.get(sourceAliasJ);
            if (producingEdgeIdx != null) {
              var edgeI = schedule.get(producingEdgeIdx);
              edgeI.addIntersectionDescriptor(
                  new RidFilterDescriptor.EdgeRidLookup(
                      edgeClass, edgeDirection, ridExpr));
              logger.debug(
                  "MATCH pre-filter: EdgeRidLookup on edge[{}] "
                      + "({}({}) back-ref from alias '{}')",
                  producingEdgeIdx, edgeDirection, edgeClass, targetAliasJ);
            }
          }
        }
      }

      // --- Index pre-filter detection ---
      var targetClass = aliasClasses.get(targetAliasJ);
      if (targetClass == null) {
        continue;
      }

      // Split the filter: only the non-$matched part can use an index.
      SQLWhereClause indexableFilter = targetFilter;
      var matchedSplit = targetFilter.splitByMatchedReference();
      if (matchedSplit != null) {
        indexableFilter = matchedSplit.nonMatchedReferencing();
      }
      if (indexableFilter == null) {
        continue;
      }

      var indexDesc = TraversalPreFilterHelper.findIndexForFilter(
          indexableFilter, targetClass, ctx);
      if (indexDesc != null) {
        edgeJ.addIntersectionDescriptor(
            new RidFilterDescriptor.IndexLookup(indexDesc));
        logger.debug(
            "MATCH pre-filter: IndexLookup on edge[{}] "
                + "(class '{}' for alias '{}')",
            j, targetClass, targetAliasJ);
      }
    }
  }

  /**
   * Resolves each edge's target class constraint to collection IDs at plan
   * time. The traverser applies this as a zero-I/O class filter on the link
   * bag, skipping vertices whose collection ID does not match.
   */
  private void attachCollectionIdFilters(
      List<EdgeTraversal> schedule, CommandContext ctx) {
    var session = ctx.getDatabaseSession();
    if (session == null) {
      return;
    }
    var schema = session.getMetadata().getImmutableSchemaSnapshot();
    for (var et : schedule) {
      var targetAlias = et.out ? et.edge.in.alias : et.edge.out.alias;
      if (targetAlias == null) {
        continue;
      }
      var className = aliasClasses.get(targetAlias);
      if (className == null) {
        continue;
      }
      var schemaClass = schema.getClassInternal(className);
      if (schemaClass == null) {
        continue;
      }
      et.setAcceptedCollectionIds(
          TraversalPreFilterHelper.collectionIdsForClass(schemaClass));
    }
  }

  /**
   * Returns the edge class name from an {@link EdgeTraversal}'s path item
   * method, or {@code null} if none is specified.
   */
  @Nullable static String getEdgeClassName(EdgeTraversal et) {
    var method = et.edge.item.getMethod();
    if (method == null) {
      return null;
    }
    var params = method.getParams();
    if (params == null || params.isEmpty()) {
      return null;
    }
    var expr = params.getFirst();
    if (!(expr.getMathExpression() instanceof SQLBaseExpression base)) {
      return null;
    }
    if (base.getModifier() != null) {
      return null;
    }
    var value = base.execute((Result) null, new BasicCommandContext());
    if (value instanceof String s && !s.isEmpty()) {
      return s;
    }
    return null;
  }

  /**
   * Returns the traversal direction ({@code "out"} or {@code "in"}) for the
   * given edge traversal, considering the scheduled direction.
   */
  @Nullable static String getEdgeDirection(EdgeTraversal et) {
    var method = et.edge.item.getMethod();
    if (method == null) {
      return null;
    }
    var nameStr = method.getMethodNameString();
    if (nameStr == null) {
      return null;
    }
    var syntacticDirection = nameStr.toLowerCase(Locale.ROOT);
    if (et.out) {
      return syntacticDirection;
    }
    // Reverse: flip out↔in
    return switch (syntacticDirection) {
      case "out" -> "in";
      case "in" -> "out";
      default -> syntacticDirection;
    };
  }

  /**
   * Computes a dependency map for the topological scheduler. An alias `A` depends on
   * alias `B` if `A`'s `WHERE` clause contains a reference to `$matched.B` — meaning
   * that `B` must be resolved (visited) before `A` can be evaluated.
   *
   * @param pattern the pattern whose nodes' filters are analyzed
   * @return a map from alias name to the set of alias names it depends on
   */
  private Map<String, Set<String>> getDependencies(Pattern pattern) {
    Map<String, Set<String>> result = new HashMap<>();

    for (var node : pattern.aliasToNode.values()) {
      Set<String> currentDependencies = new HashSet<>();

      var filter = aliasFilters.get(node.alias);
      if (filter != null && filter.getBaseExpression() != null) {
        var involvedAliases = filter.getBaseExpression().getMatchPatternInvolvedAliases();
        if (involvedAliases != null) {
          currentDependencies.addAll(involvedAliases);
        }
      }

      result.put(node.alias, currentDependencies);
    }

    return result;
  }

  /**
   * Splits the global pattern graph into its connected components (disjoint sub-patterns).
   * Each connected component will be planned independently; the results are later joined
   * via a {@link CartesianProductStep}. This method is idempotent.
   */
  private void splitDisjointPatterns() {
    if (this.subPatterns != null) {
      return;
    }

    this.subPatterns = pattern.getDisjointPatterns();
  }

  /**
   * Emits execution steps for a single edge traversal.
   *
   * <p>When {@code first} is {@code true}, two steps are chained: a {@link MatchFirstStep}
   * (initial record scan for the source node) followed by a {@link MatchStep} or
   * {@link OptionalMatchStep}. When {@code first} is {@code false}, only the traversal
   * step is appended — the source node was already produced by a preceding step.
   *
   * <pre>
   *   first=true:   plan ← MatchFirstStep(source) ← MatchStep(edge)
   *   first=false:  plan ← MatchStep(edge)
   *
   *   Note: MatchStep is ALWAYS appended, even when first=true. The MatchFirstStep
   *   only provides the initial record set — the MatchStep still performs the actual
   *   edge traversal from that starting point.
   * </pre>
   *
   * If the target node of the edge is optional, an {@link OptionalMatchStep} is used
   * instead of a regular {@link MatchStep}.
   */
  private void addStepsFor(
      SelectExecutionPlan plan,
      EdgeTraversal edge,
      CommandContext context,
      boolean first,
      @Nullable IndexOrderedCandidate candidate,
      boolean profilingEnabled) {
    if (first) {
      var patternNode = edge.out ? edge.edge.out : edge.edge.in;
      var clazz = this.aliasClasses.get(patternNode.alias);
      var rid = this.aliasRids.get(patternNode.alias);
      var where = aliasFilters.get(patternNode.alias);
      var select = new SQLSelectStatement(-1);
      select.setTarget(new SQLFromClause(-1));
      select.getTarget().setItem(new SQLFromItem(-1));
      if (clazz != null) {
        select.getTarget().getItem().setIdentifier(new SQLIdentifier(clazz));
      } else if (rid != null) {
        select.getTarget().getItem().setRids(Collections.singletonList(rid));
      }
      select.setWhereClause(where == null ? null : where.copy());
      var subContxt = new BasicCommandContext();
      subContxt.setParentWithoutOverridingChild(context);
      plan.chain(
          new MatchFirstStep(
              context,
              patternNode,
              select.createExecutionPlan(subContxt, profilingEnabled),
              profilingEnabled));
    }
    if (edge.edge.in.isOptionalNode()) {
      foundOptional = true;
      plan.chain(new OptionalMatchStep(context, edge, profilingEnabled));
    } else if (candidate != null && isIndexOrderedEdge(candidate, edge)) {
      // Index-ordered traversal: replace MatchStep with IndexOrderedEdgeStep
      plan.chain(new IndexOrderedEdgeStep(
          context,
          candidate.sourceAlias(),
          candidate.targetAlias(),
          candidate.edgeClassName(),
          candidate.linkBagFieldName(),
          candidate.index(),
          candidate.orderAsc(),
          edge,
          candidate.limit(),
          candidate.multiSourceMode(),
          candidate.reverseFieldName(),
          candidate.sourceClassName(),
          candidate.targetFilter(),
          candidate.targetClassName(),
          candidate.isEdgeTraversal(),
          profilingEnabled));
    } else {
      plan.chain(new MatchStep(context, edge, profilingEnabled));
    }
  }

  /**
   * Checks whether the given edge matches the index-ordered candidate by comparing
   * the underlying PatternEdge identity. The candidate was detected on a probing
   * schedule (separate EdgeTraversal instances), so we compare the wrapped
   * PatternEdge objects which are shared across schedule computations.
   */
  private static boolean isIndexOrderedEdge(
      IndexOrderedCandidate candidate, EdgeTraversal edge) {
    return candidate.edgeTraversal().edge == edge.edge;
  }

  /**
   * Detects whether the ORDER BY clause can be satisfied by an index-ordered
   * edge traversal. When the following conditions are all met, returns a
   * candidate describing the optimization:
   *
   * <ol>
   *   <li>ORDER BY has exactly one item (single-property sort)</li>
   *   <li>The ORDER BY field resolves (through RETURN projection aliases) to
   *       {@code <alias>.<property>} where {@code <alias>} is a pattern alias</li>
   *   <li>The alias is the target of a simple (non-WHILE) edge in the schedule</li>
   *   <li>An index exists on the target alias's class for that property</li>
   *   <li>The edge traversal method is directional ({@code in()} or {@code out()},
   *       not {@code both()})</li>
   * </ol>
   *
   * @param sortedEdges the topologically sorted edge schedule
   * @param context     the command context (provides database session for index lookup)
   * @return the candidate, or {@code null} if the optimization does not apply
   */
  @Nullable private IndexOrderedCandidate detectIndexOrderedCandidate(
      List<EdgeTraversal> sortedEdges,
      CommandContext context,
      Map<String, Long> estimatedRootEntries) {
    // 1. ORDER BY must have at least one item; first item must have no collate
    if (orderBy == null || orderBy.getItems() == null || orderBy.getItems().isEmpty()) {
      return null;
    }
    var orderItem = orderBy.getItems().getFirst();
    if (orderItem.getCollate() != null) {
      return null;
    }

    // 2. Resolve ORDER BY alias → targetAlias.property
    var resolved = resolveOrderByToAliasProperty(orderItem);
    if (resolved == null) {
      return null;
    }
    var targetAlias = resolved[0];
    var propertyName = resolved[1];

    // 3. Verify targetAlias is a known pattern alias
    if (pattern == null || !pattern.getAliasToNode().containsKey(targetAlias)) {
      return null;
    }

    // 4. Find the edge in the schedule that targets this alias
    EdgeTraversal matchedEdge = null;
    for (var edge : sortedEdges) {
      var target = edge.out ? edge.edge.in : edge.edge.out;
      if (targetAlias.equals(target.alias)) {
        matchedEdge = edge;
        break;
      }
    }
    if (matchedEdge == null) {
      return null;
    }

    // 5. Edge must be simple (no WHILE, no maxDepth)
    var item = matchedEdge.edge.item;
    var filter = item.getFilter();
    if (filter != null
        && (filter.getWhileCondition() != null || filter.getMaxDepth() != null)) {
      return null;
    }

    // 6. Extract method direction and edge class name.
    //    Accepts in/out (vertex traversal) and inE/outE (edge traversal).
    var method = item.getMethod();
    if (method == null || method.getMethodName() == null) {
      return null;
    }
    var methodDirection =
        method.getMethodName().getStringValue().toLowerCase(Locale.ENGLISH);
    var isEdgeTraversal =
        "ine".equals(methodDirection) || "oute".equals(methodDirection);
    var baseDirection = isEdgeTraversal
        ? methodDirection.substring(0, methodDirection.length() - 1)
        : methodDirection;
    if (!"in".equals(baseDirection) && !"out".equals(baseDirection)) {
      return null; // "both" / "bothE" not supported
    }
    var methodParams = method.getParams();
    if (methodParams == null || methodParams.isEmpty()) {
      return null; // no edge class specified
    }
    // Extract edge class name from the method parameter (e.g., .in('TEST_HAS_CREATOR')).
    // Use execute() to properly decode the AST string literal, avoiding fragile
    // toString() + quote-stripping that breaks on escaped characters or backticks.
    var edgeClassValue = methodParams.getFirst().execute((Result) null, context);
    if (!(edgeClassValue instanceof String edgeClassName) || edgeClassName.isEmpty()) {
      return null;
    }

    // 7. Compute linkBagFieldName and sourceAlias based on traversal direction.
    //    When edge.out == true: execution source = edge.edge.out, method applies directly.
    //    When edge.out == false: execution source = edge.edge.in, method is reversed.
    //    Use baseDirection ("in"/"out") regardless of whether this is an edge traversal,
    //    because the LinkBag field name is the same (e.g., in_LIKES for both .in() and .inE()).
    String linkBagDirection;
    if (matchedEdge.out) {
      linkBagDirection = baseDirection;
    } else {
      linkBagDirection = "in".equals(baseDirection) ? "out" : "in";
    }
    var sourceAlias =
        matchedEdge.out ? matchedEdge.edge.out.alias : matchedEdge.edge.in.alias;
    var linkBagFieldName = linkBagDirection + "_" + edgeClassName;

    // 7b. Compute reverse field name (for multi-source reverse edge lookup).
    //     For vertex traversal (.in/.out): reverse field on vertex is out_/in_ + edgeClassName.
    //     For edge traversal (.inE/.outE): reverse field on edge record is just "out"/"in".
    var reverseDirection = "in".equals(linkBagDirection) ? "out" : "in";
    var reverseFieldName = isEdgeTraversal
        ? reverseDirection
        : reverseDirection + "_" + edgeClassName;

    // 8. Look up index on target class for the property.
    //    For .inE()/.outE(), the target alias IS the edge record.
    //    Use the edge class name as target class if not already inferred.
    var targetClassName = aliasClasses.get(targetAlias);
    if (targetClassName == null && isEdgeTraversal) {
      targetClassName = edgeClassName;
    }
    if (targetClassName == null) {
      return null;
    }
    var sourceClassName = aliasClasses.get(sourceAlias);
    var session = context.getDatabaseSession();
    var schema = session.getMetadata().getImmutableSchemaSnapshot();
    var clazz = schema.getClassInternal(targetClassName);
    if (clazz == null) {
      return null;
    }

    Index matchedIndex = null;
    for (var idx : clazz.getIndexesInternal()) {
      if (idx.getDefinition() == null) {
        continue;
      }
      var props = idx.getDefinition().getProperties();
      // Single-field index matching the ORDER BY property
      if (props.size() == 1 && props.iterator().next().equals(propertyName)) {
        matchedIndex = idx;
        break;
      }
    }
    if (matchedIndex == null) {
      return null;
    }

    // 9. Determine multi-source mode (null = single-source).
    // Single-source is safe ONLY when the source has a RID constraint (guaranteed
    // exactly 1 row). With class + WHERE filter, the estimator may undercount
    // (e.g., LIKE matching multiple rows estimated as 1). In single-source mode,
    // flatMap concatenates per-source results — but OrderByStep is suppressed,
    // so the output would be incorrectly ordered if >1 source rows arrive.
    // Multi-source mode always produces globally sorted results, so it is the
    // safe default whenever the source is not pinned to a single RID.
    var sourceHasRidConstraint = aliasRids.get(sourceAlias) != null;
    MultiSourceMode multiSourceMode = null;
    if (!sourceHasRidConstraint) {
      // Verify reverse field can exist on target class. The in_/out_ LinkBag
      // fields are created implicitly by the edge system — they won't appear as
      // schema properties via getPropertyInternal(). Instead, verify that the edge
      // class exists in the schema, which guarantees the LinkBag fields exist on
      // connected vertices.
      var hasReverseField = schema.existsClass(edgeClassName);
      var hasSourceFilter = aliasFilters.get(sourceAlias) != null;
      // If the source alias is the target of an earlier edge in the schedule,
      // it is implicitly filtered by those earlier edges. UNFILTERED modes
      // only check source class, which is too permissive when earlier edges
      // constrain which source vertices are valid (e.g., Message subclasses
      // include both Post and Comment).
      var sourceConstrainedByEarlierEdges =
          isTargetOfEarlierEdge(sourceAlias, sortedEdges, matchedEdge);
      var effectivelyFiltered = hasSourceFilter || sourceConstrainedByEarlierEdges;
      var upstreamBindingNeeded = isUpstreamBindingNeeded(
          sourceAlias, sortedEdges, matchedEdge);

      // FILTERED modes materialize upstream (sourceMap) before scanning.
      // The plan-time cost check gates this: if the cost model says index
      // scan won't help, reject early to avoid materialization overhead.
      // Without LIMIT, the normal path's OrderByStep materializes everything
      // for sorting anyway, so the sourceMap overhead is minor — the real
      // benefit is avoiding the O(N log N) sort via pre-sorted index scan.

      if (effectivelyFiltered && upstreamBindingNeeded) {
        if (!hasReverseField) {
          return null;
        }
        if (!isFilteredScanLikelyWorthwhile(
            sourceAlias, matchedIndex, orderItem,
            estimatedRootEntries, session, context)) {
          return null;
        }
        multiSourceMode = MultiSourceMode.FILTERED_BOUND;
      } else if (effectivelyFiltered) {
        if (!isFilteredScanLikelyWorthwhile(
            sourceAlias, matchedIndex, orderItem,
            estimatedRootEntries, session, context)) {
          return null;
        }
        multiSourceMode = MultiSourceMode.FILTERED_UNBOUND;
      } else if (upstreamBindingNeeded) {
        // Class check + lazy load → reverse field required
        if (!hasReverseField || sourceClassName == null) {
          return null;
        }
        multiSourceMode = MultiSourceMode.UNFILTERED_BOUND;
      } else {
        // Pure scan + class check → reverse field required
        if (!hasReverseField || sourceClassName == null) {
          return null;
        }
        multiSourceMode = MultiSourceMode.UNFILTERED_UNBOUND;
      }
    }

    // 10. Determine sort direction and query LIMIT
    var orderAsc = SQLOrderByItem.ASC.equals(orderItem.getType());
    long skipSize = skip != null && skip.getValue(context) >= 0
        ? skip.getValue(context) : 0;
    long limitSize = limit != null && limit.getValue(context) >= 0
        ? limit.getValue(context) : -1;
    long queryLimit = limitSize >= 0 ? skipSize + limitSize : -1;

    var multiFieldOrderBy = orderBy.getItems().size() > 1;

    // Extract target WHERE filter from the edge's path item filter.
    // filter is the SQLMatchFilterItem; filter.getFilter() is the WHERE clause.
    var targetFilter = filter != null ? filter.getFilter() : null;

    // 11. Reject when target WHERE uses $matched or $currentMatch.
    // IndexOrderedEdgeStep does not maintain these context variables;
    // evaluating such filters would produce wrong results.
    if (targetFilter != null && targetFilter.getBaseExpression() != null
        && (targetFilter.getBaseExpression().varMightBeInUse("$matched")
            || targetFilter.getBaseExpression()
                .varMightBeInUse("$currentMatch"))) {
      return null;
    }

    return new IndexOrderedCandidate(
        matchedEdge, sourceAlias, targetAlias, edgeClassName,
        linkBagFieldName, matchedIndex, orderAsc, queryLimit,
        multiSourceMode, reverseFieldName, sourceClassName,
        multiFieldOrderBy, targetFilter, targetClassName, isEdgeTraversal);
  }

  /**
   * Resolves an ORDER BY item to a {@code [targetAlias, propertyName]} pair.
   * Handles two cases:
   * <ul>
   *   <li>Parsed dot notation: {@code ORDER BY message.creationDate} — parser
   *       produces alias="message" with a suffix modifier "creationDate"</li>
   *   <li>Projection alias: {@code ORDER BY messageCreationDate} — resolves
   *       through RETURN projection to find the underlying
   *       {@code alias.property} expression</li>
   * </ul>
   *
   * @return a two-element array [targetAlias, propertyName], or null if unresolvable
   */
  @Nullable private String[] resolveOrderByToAliasProperty(SQLOrderByItem orderItem) {
    var orderAlias = orderItem.getAlias();
    if (orderAlias == null) {
      return null;
    }

    // Case 1: parsed dot notation — parser splits "message.creationDate" into
    // alias="message" + modifier with suffix="creationDate". Convert modifier
    // to string and verify it's a simple ".propertyName" (no method calls,
    // arrays, or chaining).
    var modifier = orderItem.getModifier();
    if (modifier != null) {
      var modBuilder = new StringBuilder();
      modifier.toString(new HashMap<>(), modBuilder);
      var modStr = modBuilder.toString();
      // Simple suffix starts with '.' followed by an identifier (no further
      // dots, brackets, or parens)
      if (modStr.length() > 1 && modStr.charAt(0) == '.'
          && modStr.indexOf('.', 1) == -1
          && modStr.indexOf('(') == -1
          && modStr.indexOf('[') == -1) {
        return new String[] {orderAlias, modStr.substring(1)};
      }
      // Complex modifier — cannot resolve
      return null;
    }

    // Case 2: projection alias resolution
    if (returnAliases != null && returnItems != null) {
      for (int i = 0; i < returnAliases.size(); i++) {
        var retAlias = returnAliases.get(i);
        if (retAlias != null && retAlias.getStringValue().equals(orderAlias)) {
          var expr = returnItems.get(i);
          var exprBuilder = new StringBuilder();
          expr.toString(new HashMap<>(), exprBuilder);
          var exprStr = exprBuilder.toString();
          var exprDot = exprStr.indexOf('.');
          if (exprDot > 0 && exprDot < exprStr.length() - 1
              && exprStr.indexOf('.', exprDot + 1) == -1) {
            return new String[] {
                exprStr.substring(0, exprDot),
                exprStr.substring(exprDot + 1)
            };
          }
          break;
        }
      }
    }

    return null;
  }

  /**
   * Plan-time cost check for FILTERED modes. Uses estimated cardinality
   * and default fan-out to predict whether the index scan is likely to
   * outperform load-all-and-sort. If even optimistic estimates say "no",
   * reject the optimization to avoid paying upstream materialization cost.
   */
  private boolean isFilteredScanLikelyWorthwhile(
      String sourceAlias,
      Index matchedIndex,
      SQLOrderByItem orderItem,
      Map<String, Long> estimatedRootEntries,
      DatabaseSessionEmbedded session,
      CommandContext context) {
    long sourceEstimate =
        estimatedRootEntries.getOrDefault(sourceAlias, THRESHOLD);
    long indexSize = matchedIndex.size(session);
    // Use optimistic fan-out estimate: max of defaultFanOut and
    // indexSize/sourceEstimate. This avoids rejecting too aggressively
    // when sourceEstimate is low but the actual edges-per-source is high.
    // Plan-time should be a loose gate — runtime cost model refines.
    int defaultFanOut =
        GlobalConfiguration.QUERY_STATS_DEFAULT_FAN_OUT.getValueAsInteger();
    long fanOutEstimate = Math.max(defaultFanOut,
        indexSize / Math.max(sourceEstimate, 1));
    int estimatedEdges = (int) Math.min(
        sourceEstimate * fanOutEstimate, Integer.MAX_VALUE);

    long skipSize = skip != null && skip.getValue(context) >= 0
        ? skip.getValue(context) : 0;
    long limitSize = limit != null && limit.getValue(context) >= 0
        ? limit.getValue(context) : -1;
    long queryLimit = limitSize >= 0 ? skipSize + limitSize : -1;
    var asc = SQLOrderByItem.ASC.equals(orderItem.getType());

    var costs = IndexOrderedCostModel.computeCosts(
        estimatedEdges, indexSize, queryLimit,
        matchedIndex.getHistogram(session), asc);
    return costs != null && costs.costUnionScan() < costs.costLoadSort();
  }

  /**
   * Checks whether the source alias is the target of any edge scheduled before
   * the matched edge. When true, the source is implicitly constrained by those
   * earlier traversals, and UNFILTERED modes (which only check source class)
   * are unsafe — they would include vertices not reachable from the pattern root.
   */
  private static boolean isTargetOfEarlierEdge(
      String sourceAlias,
      List<EdgeTraversal> sortedEdges,
      EdgeTraversal matchedEdge) {
    for (var edge : sortedEdges) {
      if (edge.edge == matchedEdge.edge) {
        break;
      }
      var target = edge.out ? edge.edge.in.alias : edge.edge.out.alias;
      if (sourceAlias.equals(target)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Checks whether any upstream alias (source alias or any alias bound by
   * earlier edges) is referenced by downstream consumers: RETURN expressions,
   * later edges, or built-in return modes.
   *
   * <p>When true, the BOUND mode must be used to preserve the full upstream
   * row. UNBOUND modes create empty upstream rows, dropping all earlier
   * bindings — this is only safe when no upstream alias is needed downstream.
   */
  private boolean isUpstreamBindingNeeded(
      String sourceAlias,
      List<EdgeTraversal> sortedEdges,
      EdgeTraversal matchedEdge) {
    // Built-in return modes always need all aliases
    if (returnElements || returnPaths || returnPatterns || returnPathElements) {
      return true;
    }

    // Collect all upstream aliases: the source alias + all aliases bound
    // by edges scheduled before the matched edge (both source and target
    // of each earlier edge).
    var upstreamAliases = new HashSet<String>();
    upstreamAliases.add(sourceAlias);
    for (var edge : sortedEdges) {
      if (edge.edge == matchedEdge.edge) {
        break;
      }
      upstreamAliases.add(edge.out ? edge.edge.out.alias : edge.edge.in.alias);
      upstreamAliases.add(edge.out ? edge.edge.in.alias : edge.edge.out.alias);
    }

    // Check RETURN expressions for any upstream alias reference
    if (returnItems != null) {
      for (var expr : returnItems) {
        var sb = new StringBuilder();
        expr.toString(new HashMap<>(), sb);
        var exprStr = sb.toString();
        for (var alias : upstreamAliases) {
          if (exprStr.equals(alias) || exprStr.startsWith(alias + ".")) {
            return true;
          }
        }
      }
    }

    // Check later edges:
    // (a) if any upstream alias is the starting point of a later edge
    // (b) if any later edge's WHERE clause references $matched — UNBOUND
    //     modes drop upstream aliases from the result row, which means
    //     $matched (set to the current row) would miss those aliases.
    //     This breaks queries like IS7 where a downstream edge uses
    //     $matched.author.@rid but author was bound before the optimized edge.
    var pastMatched = false;
    for (var edge : sortedEdges) {
      if (edge.edge == matchedEdge.edge) {
        pastMatched = true;
        continue;
      }
      if (pastMatched) {
        var laterSource = edge.out ? edge.edge.out.alias : edge.edge.in.alias;
        if (upstreamAliases.contains(laterSource)) {
          return true;
        }
        // Check if the later edge's WHERE filter uses $matched
        var laterItem = edge.edge.item;
        if (laterItem != null && laterItem.getFilter() != null) {
          var laterWhere = laterItem.getFilter().getFilter();
          if (laterWhere != null && laterWhere.getBaseExpression() != null
              && laterWhere.getBaseExpression().varMightBeInUse("$matched")) {
            return true;
          }
        }
      }
    }

    return false;
  }

  /**
   * For each alias in `aliasesToPrefetch`, creates a {@link MatchPrefetchStep} that
   * eagerly loads all matching records into the execution context under a well-known key.
   * Subsequent {@link MatchFirstStep}s will read from this cache instead of re-scanning.
   */
  private void addPrefetchSteps(
      SelectExecutionPlan result,
      Set<String> aliasesToPrefetch,
      CommandContext context,
      boolean profilingEnabled) {
    for (var alias : aliasesToPrefetch) {
      var targetClass = aliasClasses.get(alias);
      var targetRid = aliasRids.get(alias);
      var filter = aliasFilters.get(alias);
      var prefetchStm =
          createSelectStatement(targetClass, targetRid, filter);

      var step =
          new MatchPrefetchStep(
              context,
              prefetchStm.createExecutionPlan(context, profilingEnabled),
              alias,
              profilingEnabled);
      result.chain(step);
    }
  }

  /**
   * Builds a synthetic `SELECT` statement that scans a class, a single RID, or applies a
   * `WHERE` filter. This is used to generate the initial record source for
   * {@link MatchFirstStep} and {@link MatchPrefetchStep}.
   */
  private static SQLSelectStatement createSelectStatement(
      String targetClass, SQLRid targetRid, SQLWhereClause filter) {
    var prefetchStm = new SQLSelectStatement(-1);
    prefetchStm.setWhereClause(filter);
    var from = new SQLFromClause(-1);
    var fromItem = new SQLFromItem(-1);
    if (targetRid != null) {
      fromItem.setRids(Collections.singletonList(targetRid));
    } else if (targetClass != null) {
      fromItem.setIdentifier(new SQLIdentifier(targetClass));
    }
    from.setItem(fromItem);
    prefetchStm.setTarget(from);
    return prefetchStm;
  }

  /**
   * Lazily builds the internal pattern graph and extracts per-alias metadata.
   *
   * <p>Steps performed:
   * 1. Assign auto-generated aliases to any unnamed pattern nodes.
   * 2. Convert each {@link SQLMatchExpression} into {@link PatternNode}/{@link PatternEdge}
   *    pairs inside the {@link Pattern} graph.
   * 3. Collect per-alias `WHERE` filters, class constraints, and RID constraints,
   *    merging constraints when the same alias appears in multiple expressions.
   * 4. Rebind the merged filters back onto the original expressions so that subsequent
   *    traversal steps see the consolidated predicates.
   *
   * <p>This method is idempotent — it returns immediately if already called.
   */
  private void buildPatterns(CommandContext ctx) {
    if (this.pattern != null) {
      return;
    }
    List<SQLMatchExpression> allPatterns = new ArrayList<>();
    allPatterns.addAll(this.matchExpressions);
    allPatterns.addAll(this.notMatchExpressions);

    assignDefaultAliases(allPatterns);

    pattern = new Pattern();
    for (var expr : this.matchExpressions) {
      pattern.addExpression(expr);
    }

    Map<String, SQLWhereClause> aliasFilters = new LinkedHashMap<>();
    Map<String, String> aliasClasses = new LinkedHashMap<>();
    Map<String, String> aliasCollections = new LinkedHashMap<>();
    Map<String, SQLRid> aliasRids = new LinkedHashMap<>();
    // Collect aliases from patterns that contain a while condition.
    // Class inference on these (and patterns sharing aliases with them)
    // must be skipped — inferred classes change cost estimates which can
    // reorder the schedule, causing while/where recursive steps to be
    // traversed in the wrong direction.
    var whileAliases = collectAliasesFromWhilePatterns(this.matchExpressions);
    for (var expr : this.matchExpressions) {
      boolean skipInference = sharesAliases(expr, whileAliases);
      addAliases(expr, aliasFilters, aliasClasses, aliasCollections, aliasRids,
          ctx, skipInference);
    }

    this.aliasFilters = aliasFilters;
    this.aliasClasses = aliasClasses;
    this.aliasRids = aliasRids;

    rebindFilters(aliasFilters);
  }

  /**
   * After per-alias filters have been merged in {@link #buildPatterns}, this method
   * pushes the consolidated `WHERE` clause back into each {@link SQLMatchFilter} inside
   * the original match expressions, so the traversal steps see the unified predicate.
   */
  private void rebindFilters(Map<String, SQLWhereClause> aliasFilters) {
    for (var expression : matchExpressions) {
      var newFilter = aliasFilters.get(expression.getOrigin().getAlias());
      expression.getOrigin().setFilter(newFilter);

      for (var item : expression.getItems()) {
        newFilter = aliasFilters.get(item.getFilter().getAlias());
        item.getFilter().setFilter(newFilter);
      }
    }
  }

  /**
   * Collects all aliases that appear in patterns containing a {@code while}
   * condition. Used to determine which patterns must skip class inference.
   */
  private static Set<String> collectAliasesFromWhilePatterns(
      List<SQLMatchExpression> expressions) {
    var result = new HashSet<String>();
    for (var expr : expressions) {
      boolean hasWhile = false;
      for (var item : expr.getItems()) {
        if (item.getFilter() != null
            && item.getFilter().getWhileCondition() != null) {
          hasWhile = true;
          break;
        }
      }
      if (hasWhile) {
        // Collect origin alias
        if (expr.getOrigin() != null && expr.getOrigin().getAlias() != null) {
          result.add(expr.getOrigin().getAlias());
        }
        // Collect all item aliases
        for (var item : expr.getItems()) {
          if (item.getFilter() != null && item.getFilter().getAlias() != null) {
            result.add(item.getFilter().getAlias());
          }
        }
      }
    }
    return result;
  }

  /**
   * Returns true if the expression shares any alias with the given set.
   * Used to detect patterns connected to while-containing patterns via
   * shared aliases (e.g. {@code {as: post}} appearing in both patterns).
   */
  private static boolean sharesAliases(
      SQLMatchExpression expr, Set<String> aliases) {
    if (aliases.isEmpty()) {
      return false;
    }
    if (expr.getOrigin() != null && expr.getOrigin().getAlias() != null
        && aliases.contains(expr.getOrigin().getAlias())) {
      return true;
    }
    for (var item : expr.getItems()) {
      if (item.getFilter() != null && item.getFilter().getAlias() != null
          && aliases.contains(item.getFilter().getAlias())) {
        return true;
      }
    }
    return false;
  }

  /**
   * Extracts alias metadata (filters, class, collection, RID) from a single match
   * expression and merges them into the accumulation maps.
   */
  private static void addAliases(
      SQLMatchExpression expr,
      Map<String, SQLWhereClause> aliasFilters,
      Map<String, String> aliasClasses,
      Map<String, String> aliasCollections,
      Map<String, SQLRid> aliasRids,
      CommandContext context,
      boolean skipClassInference) {
    addAliases(expr.getOrigin(), aliasFilters, aliasClasses, aliasCollections, aliasRids, context);

    for (var item : expr.getItems()) {
      if (item.getFilter() != null) {
        addAliases(item.getFilter(), aliasFilters, aliasClasses, aliasCollections, aliasRids,
            context);
        if (!skipClassInference) {
          // Infer target class from edge LINK schema when no explicit class
          // is set. For out('CONTAINER_OF'), the target vertices are the "in"
          // endpoint of the CONTAINER_OF edge class; if that endpoint declares
          // LINK Message, the target class is Message.
          var alias = item.getFilter().getAlias();
          if (alias != null && !aliasClasses.containsKey(alias)) {
            var inferred = inferClassFromEdgeSchema(item.getMethod(), context);
            if (inferred != null) {
              aliasClasses.put(alias, inferred);
              logger.debug(
                  "MATCH class inference: alias '{}' -> class '{}' "
                      + "(from edge LINK schema)",
                  alias, inferred);
            }
          }
        }
      }
    }
  }

  /**
   * Infers the target vertex class from the edge schema LINK declarations.
   * For {@code out('X')}, the target is the "in" endpoint of edge class X;
   * for {@code in('X')}, the target is the "out" endpoint.
   *
   * @return the linked class name, or {@code null} if it cannot be inferred
   */
  @Nullable static String inferClassFromEdgeSchema(
      @Nullable SQLMethodCall method, CommandContext context) {
    if (method == null) {
      return null;
    }
    var dirName = method.getMethodNameString();
    if (dirName == null) {
      return null;
    }
    dirName = dirName.toLowerCase(Locale.ROOT);
    if (!"in".equals(dirName) && !"out".equals(dirName)) {
      return null;
    }

    if (method.getParams() == null || method.getParams().isEmpty()) {
      return null;
    }
    String edgeClassName;
    try {
      var value = method.getParams().getFirst()
          .execute((Result) null, new BasicCommandContext());
      if (!(value instanceof String s) || s.isEmpty()) {
        return null;
      }
      edgeClassName = s;
    } catch (RuntimeException e) {
      return null;
    }

    var session = context.getDatabaseSession();
    var schema = session.getMetadata().getImmutableSchemaSnapshot();
    var edgeClass = schema.getClassInternal(edgeClassName);
    if (edgeClass == null) {
      return null;
    }
    // out('X') targets the "in" side; in('X') targets the "out" side
    var targetPropName = "out".equals(dirName) ? "in" : "out";
    var prop = edgeClass.getPropertyInternal(targetPropName);
    if (prop == null || prop.getLinkedClass() == null) {
      return null;
    }
    assert prop.getLinkedClass().getName() != null
        && !prop.getLinkedClass().getName().isEmpty()
        : "inferClassFromEdgeSchema: linked class has null/empty name for edge "
            + edgeClassName;
    return prop.getLinkedClass().getName();
  }

  /**
   * Merges a single {@link SQLMatchFilter}'s metadata into the accumulation maps.
   *
   * <p>- **Filters**: all `WHERE` predicates for the same alias are AND-ed together into a
   *   single {@link SQLAndBlock}.
   * - **Classes**: if two expressions constrain the same alias to different classes, the
   *   method keeps the more specific sub-class, or throws if the classes are unrelated.
   * - **Collections / RIDs**: duplicates are validated for equality; mismatches throw.
   *
   * @throws CommandExecutionException if the same alias is constrained to unrelated
   *         classes, conflicting collections, or conflicting RIDs
   */
  private static void addAliases(
      SQLMatchFilter matchFilter,
      Map<String, SQLWhereClause> aliasFilters,
      Map<String, String> aliasClasses,
      Map<String, String> aliasCollections,
      Map<String, SQLRid> aliasRids,
      CommandContext context) {
    var alias = matchFilter.getAlias();
    var filter = matchFilter.getFilter();
    if (alias != null) {
      if (filter != null && filter.getBaseExpression() != null) {
        var previousFilter = aliasFilters.get(alias);
        if (previousFilter == null) {
          previousFilter = new SQLWhereClause(-1);
          previousFilter.setBaseExpression(new SQLAndBlock(-1));
          aliasFilters.put(alias, previousFilter);
        }
        var filterBlock = (SQLAndBlock) previousFilter.getBaseExpression();
        if (filter.getBaseExpression() != null) {
          filterBlock.getSubBlocks().add(filter.getBaseExpression());
        }
      }

      var clazz = matchFilter.getClassName(context);
      if (clazz != null) {
        var previousClass = aliasClasses.get(alias);
        if (previousClass == null) {
          aliasClasses.put(alias, clazz);
        } else {
          var lower = getLowerSubclass(context.getDatabaseSession(), clazz, previousClass);
          if (lower == null) {
            throw new CommandExecutionException(context.getDatabaseSession(),
                "classes defined for alias "
                    + alias
                    + " ("
                    + clazz
                    + ", "
                    + previousClass
                    + ") are not in the same hierarchy");
          }
          aliasClasses.put(alias, lower);
        }
      }

      var collectionName = matchFilter.getCollectionName(context);
      if (collectionName != null) {
        var previousCollection = aliasCollections.get(alias);
        if (previousCollection == null) {
          aliasCollections.put(alias, collectionName);
        } else if (!previousCollection.equalsIgnoreCase(collectionName)) {
          throw new CommandExecutionException(context.getDatabaseSession(),
              "Invalid expression for alias "
                  + alias
                  + " cannot be of both collections "
                  + previousCollection
                  + " and "
                  + collectionName);
        }
      }

      var rid = matchFilter.getRid(context);
      if (rid != null) {
        var previousRid = aliasRids.get(alias);
        if (previousRid == null) {
          aliasRids.put(alias, rid);
        } else if (!previousRid.equals(rid)) {
          throw new CommandExecutionException(context.getDatabaseSession(),
              "Invalid expression for alias "
                  + alias
                  + " cannot be of both RIDs "
                  + previousRid
                  + " and "
                  + rid);
        }
      }
    }
  }

  /**
   * Returns the more specific of two class names if one is a subclass of the other,
   * or `null` if they are unrelated in the class hierarchy.
   */
  @Nullable private static String getLowerSubclass(
      DatabaseSessionEmbedded db, String className1, String className2) {
    Schema schema = db.getMetadata().getSchema();
    var class1 = schema.getClass(className1);
    var class2 = schema.getClass(className2);
    if (class1.isSubClassOf(class2)) {
      return class1.getName();
    }
    if (class2.isSubClassOf(class1)) {
      return class2.getName();
    }
    return null;
  }

  /**
   * Assigns auto-generated aliases (prefixed with {@link #DEFAULT_ALIAS_PREFIX}) to
   * pattern nodes that the user did not name explicitly. This ensures every node in the
   * pattern graph has a unique alias, which simplifies downstream processing (edge
   * creation, filter merging, result projection).
   */
  private static void assignDefaultAliases(List<SQLMatchExpression> matchExpressions) {
    var counter = 0;
    for (var expression : matchExpressions) {
      if (expression.getOrigin().getAlias() == null) {
        expression.getOrigin().setAlias(DEFAULT_ALIAS_PREFIX + counter++);
      }

      for (var item : expression.getItems()) {
        if (item.getFilter() == null) {
          item.setFilter(new SQLMatchFilter(-1));
        }
        if (item.getFilter().getAlias() == null) {
          item.getFilter().setAlias(DEFAULT_ALIAS_PREFIX + counter++);
        }
      }
    }
  }

  /**
   * Estimates the number of records each aliased root node will produce. These estimates
   * drive two optimizations:
   *
   * <p>- **Prefetching**: aliases below {@link #THRESHOLD} records are loaded eagerly.
   * - **Root selection**: the topological scheduler starts from the cheapest root.
   *
   * <p>Estimation strategy per alias:
   * - RID constraint → exactly 1 record.
   * - Class constraint with `WHERE` → uses the filter's own
   *   {@link SQLWhereClause#estimate} method (which may use index statistics).
   * - Class constraint without filter → uses the class's record count.
   * - No constraint → omitted from the map (the alias is not a root candidate).
   *
   * @return a map from alias name to estimated record count
   * @throws CommandExecutionException if a referenced class does not exist in the schema
   */
  static Map<String, Long> estimateRootEntries(
      Map<String, String> aliasClasses,
      Map<String, SQLRid> aliasRids,
      Map<String, SQLWhereClause> aliasFilters,
      CommandContext ctx) {
    Set<String> allAliases = new LinkedHashSet<>();
    allAliases.addAll(aliasClasses.keySet());
    allAliases.addAll(aliasFilters.keySet());
    allAliases.addAll(aliasRids.keySet());

    var db = ctx.getDatabaseSession();
    var schema = db.getMetadata().getImmutableSchemaSnapshot();

    Map<String, Long> result = new LinkedHashMap<>();
    for (var alias : allAliases) {
      var rid = aliasRids.get(alias);
      if (rid != null) {
        result.put(alias, 1L);
        continue;
      }

      var className = aliasClasses.get(alias);

      if (className == null) {
        continue;
      }

      if (!schema.existsClass(className)) {
        throw new CommandExecutionException(ctx.getDatabaseSession(),
            "class not defined: " + className);
      }
      var oClass = schema.getClassInternal(className);
      var classCount = oClass.approximateCount(ctx.getDatabaseSession());
      long upperBound;
      var filter = aliasFilters.get(alias);
      if (filter != null) {
        // A WHERE filter always reduces or equals the full class scan.
        // The estimate() heuristic may return a higher value (e.g.
        // count/2 > count for another class), so we cap it to ensure
        // filtered nodes are always preferred over unfiltered ones.
        upperBound = Math.min(filter.estimate(oClass, THRESHOLD, ctx), classCount);
      } else {
        // No WHERE filter — full class scan. Add +1 bias so that a
        // filtered node with the same class count is preferred.
        upperBound = classCount + 1;
      }
      result.put(alias, upperBound);
    }

    return result;
  }

}
