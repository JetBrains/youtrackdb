package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.util.PairLongObject;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Direction;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
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
import com.jetbrains.youtrackdb.internal.core.sql.executor.IndexSearchDescriptor;
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
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMathExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMethodCall;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMultiMatchPathItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLNeOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLNestedProjection;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLNotBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLNotInCondition;
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
  boolean returnElements;

  /** `true` when the user wrote `RETURN $paths` — returns full matched paths. */
  boolean returnPaths;

  /** `true` when the user wrote `RETURN $patterns` — returns matched patterns. */
  boolean returnPatterns;

  /** `true` when the user wrote `RETURN $pathElements` — unrolls *all* path nodes. */
  boolean returnPathElements;

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

  /**
   * Aliases whose class was inferred from edge LINK schema rather than
   * explicitly declared. Inferred aliases must NOT outcompete explicit
   * roots during scheduling — a low-cardinality inferred class can cause
   * the scheduler to reverse traversal direction across while steps,
   * producing 0 results. Their estimates are inflated to {@code
   * Long.MAX_VALUE} so they sort last in root selection while remaining
   * available for prefetching and collection-ID filtering.
   */
  private Set<String> inferredWhileExprAliases = Set.of();

  /** Set to `true` if at least one node in the pattern is marked `optional: true`. */
  private boolean foundOptional = false;

  /**
   * Aliases with an estimated record count below this threshold are **prefetched** into
   * memory before the traversal starts. This avoids repeated scans of very small sets
   * during the nested-loop pattern matching.
   */
  static final long THRESHOLD = 100;

  /**
   * Maximum estimated build-side cardinality for which the planner will choose a
   * hash-based join (anti-join, semi-join, inner join) over nested-loop evaluation.
   * If the estimated NOT-pattern result set exceeds this threshold, the planner
   * falls back to {@link FilterNotMatchPatternStep}. Configurable via
   * {@link GlobalConfiguration#QUERY_MATCH_HASH_JOIN_THRESHOLD}.
   */
  static long getHashJoinThreshold() {
    return Math.max(0, GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.getValueAsLong());
  }

  /**
   * Minimum upstream (probe-side) cardinality for hash join to be worthwhile.
   * When set to 0, both the upstream check (Guard 1) and cost-based comparison
   * (Guard 2) are bypassed — only the build-side threshold applies. Configurable
   * via {@link GlobalConfiguration#QUERY_MATCH_HASH_JOIN_UPSTREAM_MIN}.
   */
  static long getHashJoinUpstreamMin() {
    return GlobalConfiguration.QUERY_MATCH_HASH_JOIN_UPSTREAM_MIN.getValueAsLong();
  }

  /**
   * Memory weight ratio of INNER_JOIN vs SEMI_JOIN entries. INNER_JOIN materializes
   * full ResultInternal rows (~100 + aliasCount × 80 bytes each) into a
   * HashMap&lt;JoinKey, List&lt;Result&gt;&gt;, while SEMI_JOIN stores only JoinKey entries
   * in a HashSet (~72 bytes each). The INNER_JOIN is roughly 7× heavier per entry
   * and should use a proportionally tighter threshold.
   */
  private static final int INNER_JOIN_MEMORY_WEIGHT = 7;

  // FANOUT_PER_HOP removed — fan-out is now computed per-edge via
  // EdgeFanOutEstimator.estimateFanOut() using schema statistics
  // (edgeCount / sourceCount). Falls back to GlobalConfiguration
  // .QUERY_STATS_DEFAULT_FAN_OUT when schema metadata is unavailable.

  /** Pattern for detecting $matched.ALIAS.@rid correlation in WHERE clauses. */
  private static final java.util.regex.Pattern MATCHED_RID_PATTERN =
      java.util.regex.Pattern.compile("\\$matched\\.(\\w+)\\.@rid");

  /** Pattern for counting @rid occurrences in a filter string. */
  private static final java.util.regex.Pattern RID_PATTERN =
      java.util.regex.Pattern.compile("@rid");

  /** Pattern for validating edge class names as valid identifiers. */
  private static final java.util.regex.Pattern VALID_EDGE_LABEL =
      java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

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
    // Defensive copy: aliasFilters may be immutable (e.g. Map.of() from GQL).
    // detectNotInAntiJoin() mutates this map to strip NOT IN conditions.
    this.aliasFilters = new HashMap<>(aliasFilters);
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

    // Phase 3: Estimate how many root records each aliased node will produce.
    var estimatedRootEntries =
        estimateRootEntries(aliasClasses, aliasRids, aliasFilters, context);
    // Inflate estimates for inferred-class aliases so they never outcompete
    // explicitly declared roots. A low-cardinality inferred class can cause
    // the scheduler to reverse traversal direction across while steps.
    // The alias stays in the map for prefetching; only root priority changes.
    for (var alias : inferredWhileExprAliases) {
      if (estimatedRootEntries.containsKey(alias)) {
        estimatedRootEntries.put(alias, Long.MAX_VALUE);
      }
    }

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
    IndexOrderedPlanner.IndexOrderedCandidate indexOrderedCandidate = null;
    if (subPatterns.size() == 1 && orderBy != null) {
      var probeEdges = getTopologicalSortedSchedule(
          estimatedRootEntries, pattern, aliasClasses, aliasFilters,
          context.getDatabaseSession());
      indexOrderedCandidate = detectIndexOrderedCandidate(
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

    // Phase 6: Append NOT-pattern filter steps (nested-loop or hash anti-join)
    manageNotPatterns(
        result, pattern, notMatchExpressions, aliasClasses, aliasFilters, aliasRids,
        context, enableProfiling);

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
        // Disabled when RETURN DISTINCT: early termination stops reading
        // when primary key worsens, but the bounded heap may contain
        // duplicates that DistinctStep will remove — producing fewer
        // than LIMIT distinct results. Without the hint, the bounded
        // heap still reads all upstream rows (no early termination),
        // matching the pre-existing behavior.
        SQLOrderByItem primaryHint = null;
        if (indexOrderedCandidate != null
            && indexOrderedCandidate.multiFieldOrderBy()
            && !this.returnDistinct) {
          primaryHint = orderBy.getItems().getFirst();
        }
        // indexOrderedUpstream: OrderByStep checks runtime context variable
        // to pass through when IndexOrderedEdgeStep produces sorted output.
        // Safe with RETURN DISTINCT: DistinctExecutionStep is a streaming
        // filter that preserves input order (RidSet-based dedup), and runs
        // AFTER OrderByStep in the pipeline.
        var indexOrderedUpstream = indexOrderedCandidate != null;
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

      // When index-ordered traversal is active AND no GROUP BY:
      // pass indexOrderedUpstream flag so OrderByStep can detect pre-sorted
      // input at runtime and pass through without sorting.
      // Safe with RETURN DISTINCT: DistinctExecutionStep is a streaming
      // filter that preserves input order and runs after OrderByStep.
      if (indexOrderedCandidate != null
          && this.groupBy == null) {
        info.indexOrderedUpstream = true;
        if (indexOrderedCandidate.multiFieldOrderBy()
            && !this.returnDistinct) {
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
    return filterDependsOnContext(aliasFilters.get(key));
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
   * @param aliasClasses         per-alias class names (needed for hash join build-side)
   * @param aliasFilters         per-alias WHERE clauses
   * @param aliasRids            per-alias RID constraints
   * @param context              the command context
   * @param enableProfiling      whether to enable step profiling
   */
  private static void manageNotPatterns(
      SelectExecutionPlan result,
      Pattern pattern,
      List<SQLMatchExpression> notMatchExpressions,
      Map<String, String> aliasClasses,
      Map<String, SQLWhereClause> aliasFilters,
      Map<String, SQLRid> aliasRids,
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
        // TODO implement this
      }

      // Build the NOT pattern's MatchStep chain (shared by both strategies)
      var lastFilter = exp.getOrigin();
      List<AbstractExecutionStep> matchSteps = new ArrayList<>();
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
        matchSteps.add(step);
        lastFilter = item.getFilter();
      }

      if (canUseHashJoin(exp, aliasClasses, aliasFilters, aliasRids, context)) {
        // Hash anti-join path: materialize NOT sub-pattern, probe per upstream row
        var buildPlan = buildNotPatternPlan(
            exp, matchSteps, aliasClasses, aliasFilters, aliasRids, context, enableProfiling);
        var sharedAliases = findSharedAliases(exp, pattern);
        result.chain(new HashJoinMatchStep(
            context, buildPlan, sharedAliases, JoinMode.ANTI_JOIN, enableProfiling));
      } else {
        // Fallback: nested-loop evaluation via FilterNotMatchPatternStep
        result.chain(new FilterNotMatchPatternStep(matchSteps, context, enableProfiling));
      }
    }
  }

  /**
   * Checks whether a NOT expression depends on the current execution context
   * ({@code $matched} or {@code $parent} references). If any filter in the NOT
   * expression references these variables, the pattern cannot be independently
   * materialized and must use the nested-loop {@link FilterNotMatchPatternStep}.
   *
   * <p>Inspects the origin filter and all intermediate path-item filters, checking
   * each WHERE clause for {@code refersToParent()} and string-level
   * {@code $matched.} references (matching the approach in
   * {@link #dependsOnExecutionContext}).
   *
   * @param exp the NOT match expression to inspect
   * @return {@code true} if any filter depends on execution context
   */
  static boolean notPatternDependsOnMatched(SQLMatchExpression exp) {
    // Check origin filter (currently always null per parser validation in
    // manageNotPatterns, but check defensively in case that constraint is relaxed)
    if (filterDependsOnContext(exp.getOrigin().getFilter())) {
      return true;
    }
    // Check each intermediate path item's filter
    for (var item : exp.getItems()) {
      var filter = item.getFilter();
      if (filter != null && filterDependsOnContext(filter.getFilter())) {
        return true;
      }
    }
    return false;
  }

  /**
   * Checks whether a single WHERE clause references execution context variables.
   */
  private static boolean filterDependsOnContext(@Nullable SQLWhereClause where) {
    if (where == null) {
      return false;
    }
    if (where.refersToParent()) {
      return true;
    }
    return where.toString().toLowerCase(Locale.ROOT).contains("$matched.");
  }

  /**
   * Finds aliases shared between a NOT expression and the positive pattern. These
   * shared aliases form the join key for hash-based evaluation.
   *
   * <p>The origin alias is guaranteed to be shared (enforced by the origin-alias
   * validation in {@code manageNotPatterns}). Additional shared aliases may exist if intermediate path items
   * reference aliases that also appear in the positive pattern.
   *
   * @param exp     the NOT match expression
   * @param pattern the positive pattern
   * @return shared alias names, origin first, then others in NOT-expression traversal order;
   *     never empty
   */
  static List<String> findSharedAliases(SQLMatchExpression exp, Pattern pattern) {
    // Collect all alias names from the NOT expression
    var notAliases = new LinkedHashSet<String>();
    var originAlias = exp.getOrigin().getAlias();
    assert originAlias != null : "NOT expression origin must have an alias";
    notAliases.add(originAlias);
    for (var item : exp.getItems()) {
      var filter = item.getFilter();
      if (filter != null) {
        var alias = filter.getAlias();
        if (alias != null) {
          notAliases.add(alias);
        }
      }
    }

    // Intersect with positive pattern aliases, preserving origin-first order
    var positiveAliases = pattern.aliasToNode.keySet();
    var result = new ArrayList<String>();
    for (var alias : notAliases) {
      if (positiveAliases.contains(alias)) {
        result.add(alias);
      }
    }

    assert !result.isEmpty()
        : "NOT expression must share at least the origin alias with the positive pattern";
    return result;
  }

  /**
   * Estimates the cardinality of a NOT pattern's build side (the materialized result
   * set used for hash-based evaluation). The estimate drives the planner's decision
   * to use hash join vs. nested-loop.
   *
   * <p>Algorithm:
   * <ol>
   *   <li>Get the origin alias's base cardinality via {@link #estimateRootEntries}.
   *       If the origin alias has no estimable class/RID/filter, returns
   *       {@code Long.MAX_VALUE} to force fallback to nested-loop.</li>
   *   <li>For each edge, multiply by schema-based fan-out via
   *       {@link EdgeFanOutEstimator#estimateFanOut}.</li>
   *   <li>For each intermediate filter (non-null WHERE clause), apply 0.5 selectivity.</li>
   *   <li>Cap at {@code Long.MAX_VALUE} to avoid overflow.</li>
   * </ol>
   *
   * @return estimated cardinality, or {@code Long.MAX_VALUE} if not estimable
   */
  static long estimateNotPatternCardinality(
      SQLMatchExpression exp,
      Map<String, String> aliasClasses,
      Map<String, SQLWhereClause> aliasFilters,
      Map<String, SQLRid> aliasRids,
      CommandContext context) {
    var originAlias = exp.getOrigin().getAlias();
    assert originAlias != null : "NOT expression origin must have an alias";

    // If the origin alias has no class, RID, or filter, we can't estimate —
    // return MAX_VALUE to force fallback to nested-loop.
    if (aliasClasses.get(originAlias) == null
        && aliasRids.get(originAlias) == null
        && aliasFilters.get(originAlias) == null) {
      return Long.MAX_VALUE;
    }
    var session = context.getDatabaseSession();
    long estimate = estimateAliasCardinality(
        originAlias, aliasClasses, aliasFilters, aliasRids, context);
    var currentClass = aliasClasses.get(originAlias);
    for (var item : exp.getItems()) {
      // Estimate fan-out from schema statistics (edgeCount / sourceCount)
      // or fall back to default if schema metadata is unavailable
      var method = item.getMethod();
      double fanOut = estimateMethodFanOut(method, currentClass, session);
      long fanOutLong = Math.max(1, Math.round(fanOut));
      if (estimate > Long.MAX_VALUE / fanOutLong) {
        return Long.MAX_VALUE; // overflow guard
      }
      estimate *= fanOutLong;

      // Apply selectivity for intermediate filters — use histogram-based
      // estimation if an index is available, otherwise fall back to default.
      var filter = item.getFilter();
      if (filter != null && filter.getFilter() != null) {
        double selectivity = estimateFilterSelectivity(
            filter.getFilter(), currentClass, context);
        estimate = Math.max(1, Math.round(estimate * selectivity));
      }
      // Track current class for next hop's fan-out estimation
      if (filter != null && filter.getClassName(context) != null) {
        currentClass = filter.getClassName(context);
      }
    }
    return estimate;
  }

  /**
   * Determines whether a NOT expression is eligible for hash-based anti-join evaluation.
   * Returns {@code true} iff all three conditions are met:
   * <ol>
   *   <li>No filter in the NOT expression references {@code $matched} or
   *       {@code $parent}.</li>
   *   <li>The origin alias has a known class in {@code aliasClasses} (needed to
   *       construct the build-side scan).</li>
   *   <li>The estimated build-side cardinality does not exceed
   *       {@link #getHashJoinThreshold()}.</li>
   * </ol>
   */
  static boolean canUseHashJoin(
      SQLMatchExpression exp,
      Map<String, String> aliasClasses,
      Map<String, SQLWhereClause> aliasFilters,
      Map<String, SQLRid> aliasRids,
      CommandContext context) {
    if (notPatternDependsOnMatched(exp)) {
      return false;
    }
    var originAlias = exp.getOrigin().getAlias();
    if (originAlias == null || !aliasClasses.containsKey(originAlias)) {
      return false;
    }
    var estimatedCardinality =
        estimateNotPatternCardinality(exp, aliasClasses, aliasFilters, aliasRids, context);
    return estimatedCardinality <= getHashJoinThreshold();
  }

  /**
   * Determines which pattern aliases are referenced downstream of the MATCH traversal —
   * in the RETURN clause, GROUP BY, ORDER BY, and UNWIND. This is needed to decide if
   * a branch's non-shared aliases can be elided via semi-join (i.e., if they are not
   * referenced downstream, the branch only needs to be probed for existence).
   *
   * <p>If any wildcard return mode is active ({@code $elements}, {@code $paths},
   * {@code $patterns}, {@code $pathElements}), all pattern aliases are implicitly
   * needed and the method returns the full {@code allPatternAliases} set.
   *
   * @param returnItems         expressions in the RETURN clause
   * @param groupBy             GROUP BY clause (nullable)
   * @param orderBy             ORDER BY clause (nullable)
   * @param unwind              UNWIND clause (nullable)
   * @param allPatternAliases   all aliases defined in the pattern graph
   * @return the subset of aliases that are referenced downstream
   */
  Set<String> collectDownstreamAliases(
      List<SQLExpression> returnItems,
      @Nullable SQLGroupBy groupBy,
      @Nullable SQLOrderBy orderBy,
      @Nullable SQLUnwind unwind,
      Set<String> allPatternAliases) {
    // Wildcard return modes implicitly reference all aliases
    if (returnElements || returnPaths || returnPatterns || returnPathElements) {
      return Set.copyOf(allPatternAliases);
    }

    // Defensive: empty RETURN items shouldn't happen, but return all aliases
    if (returnItems == null || returnItems.isEmpty()) {
      return Set.copyOf(allPatternAliases);
    }

    var referenced = new HashSet<String>();

    // Pre-compile word-boundary patterns for all aliases (avoids repeated
    // regex compilation when scanning multiple expressions)
    var compiled = new HashMap<String, java.util.regex.Pattern>();
    for (var alias : allPatternAliases) {
      compiled.put(alias, java.util.regex.Pattern.compile(
          "\\b" + java.util.regex.Pattern.quote(alias) + "\\b"));
    }

    // Scan RETURN expressions
    for (var expr : returnItems) {
      collectAliasesFromText(expr.toString(), allPatternAliases, referenced, compiled);
    }

    // Scan GROUP BY expressions
    if (groupBy != null) {
      for (var expr : groupBy.getItems()) {
        collectAliasesFromText(
            expr.toString(), allPatternAliases, referenced, compiled);
      }
    }

    // Scan ORDER BY items — use getAlias() which holds the expression text
    // (e.g., "friend.name"), not Object.toString() which is not overridden
    if (orderBy != null && orderBy.getItems() != null) {
      for (var item : orderBy.getItems()) {
        var alias = item.getAlias();
        if (alias != null) {
          collectAliasesFromText(alias, allPatternAliases, referenced, compiled);
        }
      }
    }

    // Scan UNWIND identifiers
    if (unwind != null) {
      for (var ident : unwind.getItems()) {
        collectAliasesFromText(
            ident.toString(), allPatternAliases, referenced, compiled);
      }
    }

    return referenced;
  }

  /**
   * Scans a text string for alias references using pre-compiled word-boundary patterns.
   * An alias is considered referenced if it appears as a standalone word (e.g.,
   * {@code friend} or {@code friend.name}, but not as a substring of a longer
   * identifier like {@code friendship}).
   */
  private static void collectAliasesFromText(
      String text,
      Set<String> allPatternAliases,
      Set<String> result,
      Map<String, java.util.regex.Pattern> compiledPatterns) {
    if (text == null || text.isEmpty()) {
      return;
    }
    for (var alias : allPatternAliases) {
      var pattern = compiledPatterns.get(alias);
      if (pattern == null) {
        pattern = java.util.regex.Pattern.compile(
            "\\b" + java.util.regex.Pattern.quote(alias) + "\\b");
      }
      if (pattern.matcher(text).find()) {
        result.add(alias);
      }
    }
  }

  /**
   * Describes a secondary branch in the pattern graph that is eligible for hash join
   * optimization. Depending on whether intermediate aliases are referenced downstream,
   * the branch is executed as a {@link JoinMode#SEMI_JOIN} (existence check only) or
   * {@link JoinMode#INNER_JOIN} (intermediate bindings merged into result rows).
   *
   * @param sharedAliases       aliases shared between the branch and the main path (join keys)
   * @param branchEdges         the edge traversals forming this branch (in schedule order),
   *                            including the consistency-check edge
   * @param intermediateAliases aliases visited exclusively by this branch
   * @param estimatedCardinality estimated number of rows the branch produces
   * @param joinMode            the join mode: SEMI_JOIN if no intermediates are downstream,
   *                            INNER_JOIN if any intermediate is referenced downstream
   * @param scanAlias           the alias to scan from in the build-side plan — must have a
   *                            known class or RID in {@code aliasClasses}/{@code aliasRids}
   */
  record HashJoinBranch(
      List<String> sharedAliases,
      List<EdgeTraversal> branchEdges,
      Set<String> intermediateAliases,
      long estimatedCardinality,
      JoinMode joinMode,
      String scanAlias) {
  }

  /**
   * Identifies secondary branches in the pattern graph that are eligible for hash join
   * optimization (semi-join or inner join). A hash join branch is a contiguous sub-sequence
   * of scheduled edges that:
   * <ul>
   *   <li>ends with a <b>consistency-check edge</b> — an edge whose target alias was already
   *       visited before this edge (both endpoints known, cost 0 in the scheduler)</li>
   *   <li>no filter on any branch node depends on {@code $matched} or {@code $parent}</li>
   *   <li>estimated cardinality does not exceed {@link #getHashJoinThreshold()}</li>
   *   <li>no intermediate alias uses an auto-generated name ({@link #DEFAULT_ALIAS_PREFIX})</li>
   * </ul>
   *
   * <p>Branches are classified as {@link JoinMode#SEMI_JOIN} when no intermediate alias
   * is referenced downstream, or {@link JoinMode#INNER_JOIN} when at least one intermediate
   * alias is referenced downstream (requiring build-side row merge into the result).
   *
   * @param scheduledEdges     the edge schedule from {@code getTopologicalSortedSchedule()}
   * @param downstreamAliases  aliases referenced downstream of the MATCH traversal
   * @param aliasClasses       per-alias class names
   * @param aliasFilters       per-alias WHERE clauses
   * @param aliasRids          per-alias RID constraints
   * @param context            the command context
   * @return list of eligible hash join branches (may be empty)
   */
  static List<HashJoinBranch> identifyHashJoinBranches(
      List<EdgeTraversal> scheduledEdges,
      Set<String> downstreamAliases,
      Map<String, String> aliasClasses,
      Map<String, SQLWhereClause> aliasFilters,
      Map<String, SQLRid> aliasRids,
      CommandContext context) {
    if (scheduledEdges.size() < 2) {
      // Need at least 2 edges: one branch edge + one consistency-check edge
      return List.of();
    }

    // Build a per-edge visited snapshot: for each edge index i, we know which
    // aliases were visited by edges [0..i-1]. This lets traceBackwardBranch
    // distinguish "main path" aliases from "branch" aliases.
    var visited = new LinkedHashSet<String>();
    visited.add(sourceAlias(scheduledEdges.get(0)));

    // visitedBefore[i] = set of aliases visited before edge i is processed
    @SuppressWarnings("unchecked")
    var visitedBefore = new Set[scheduledEdges.size()];
    for (int i = 0; i < scheduledEdges.size(); i++) {
      visitedBefore[i] = Set.copyOf(visited);
      visited.add(sourceAlias(scheduledEdges.get(i)));
      visited.add(targetAlias(scheduledEdges.get(i)));
    }

    var result = new ArrayList<HashJoinBranch>();
    // Track claimed edges to prevent overlapping branches from sharing edges.
    // If two branches share an edge, the same edge would be skipped once in the
    // main loop but included in two build plans, leading to duplicated traversal.
    var claimedEdges = new HashSet<EdgeTraversal>();

    for (int i = 0; i < scheduledEdges.size(); i++) {
      var target = targetAlias(scheduledEdges.get(i));
      @SuppressWarnings("unchecked")
      Set<String> beforeThis = visitedBefore[i];

      if (beforeThis.contains(target)) {
        // This is a consistency-check edge — target was already visited.
        var branch = traceBackwardBranch(
            scheduledEdges, i, visitedBefore, downstreamAliases,
            aliasClasses, aliasFilters, aliasRids, context);
        if (branch != null) {
          // Discard branch if any of its edges overlap with an already-claimed branch
          boolean overlaps = false;
          for (var edge : branch.branchEdges()) {
            if (claimedEdges.contains(edge)) {
              overlaps = true;
              break;
            }
          }
          if (!overlaps) {
            claimedEdges.addAll(branch.branchEdges());
            result.add(branch);
          }
        }
      }
    }

    return result;
  }

  /**
   * Returns the source alias of an edge traversal (the alias that is already matched).
   */
  private static String sourceAlias(EdgeTraversal edge) {
    return edge.out ? edge.edge.out.alias : edge.edge.in.alias;
  }

  /**
   * Returns the target alias of an edge traversal (the alias being traversed to).
   */
  private static String targetAlias(EdgeTraversal edge) {
    return edge.out ? edge.edge.in.alias : edge.edge.out.alias;
  }

  /**
   * Traces backward from a consistency-check edge at position {@code checkIdx} to find
   * the branch's edges. The branch is the contiguous sub-sequence of edges ending at the
   * consistency-check edge, starting from a "branch root" shared alias. Branches with
   * downstream intermediates are classified as {@link JoinMode#INNER_JOIN} (build-side
   * row merge); branches without are classified as {@link JoinMode#SEMI_JOIN}.
   *
   * @return a {@link HashJoinBranch} if eligible, or {@code null} if not
   */
  @Nullable private static HashJoinBranch traceBackwardBranch(
      List<EdgeTraversal> scheduledEdges,
      int checkIdx,
      Set<String>[] visitedBefore,
      Set<String> downstreamAliases,
      Map<String, String> aliasClasses,
      Map<String, SQLWhereClause> aliasFilters,
      Map<String, SQLRid> aliasRids,
      CommandContext context) {
    var checkEdge = scheduledEdges.get(checkIdx);
    var checkTarget = targetAlias(checkEdge);
    var checkSource = sourceAlias(checkEdge);

    // Phase 1: Walk backward to collect branch edges and intermediate aliases
    var trace = traceBackwardEdges(
        scheduledEdges, checkIdx, visitedBefore, checkSource, checkTarget);
    if (trace == null) {
      return null;
    }

    // Phase 2: Eligibility checks (optional nodes, context dependencies,
    // external $matched dependencies on intermediates)
    var joinMode = checkBranchEligibility(
        trace.branchEdges, trace.intermediateAliases, scheduledEdges,
        downstreamAliases, aliasFilters);
    if (joinMode == null) {
      return null;
    }

    // Also check shared aliases (checkTarget, checkSource, branchRoot) — their
    // filters are used in the build-side plan's scan or leftFilter, and $matched
    // is not populated in the isolated context.
    if (filterDependsOnContext(aliasFilters.get(checkTarget))
        || filterDependsOnContext(aliasFilters.get(checkSource))
        || filterDependsOnContext(aliasFilters.get(trace.branchRoot))) {
      return null;
    }

    // Phase 3: Cardinality estimation and cost-based guards
    long cardinality = estimateBranchCardinality(
        trace.branchRoot, trace.branchEdges, aliasClasses, aliasFilters,
        aliasRids, context);
    long threshold = getHashJoinThreshold();
    if (cardinality > threshold) {
      return null;
    }

    // INNER_JOIN materializes full ResultInternal rows (~7× heavier per entry
    // than SEMI_JOIN's lightweight JoinKey entries). Apply a tighter threshold.
    if (joinMode == JoinMode.INNER_JOIN
        && cardinality > threshold / INNER_JOIN_MEMORY_WEIGHT) {
      return null;
    }

    // Guards 1 & 2 are only active when upstreamMin > 0. Setting upstreamMin to 0
    // bypasses both guards — only the build-side threshold applies.
    long upstreamMin = getHashJoinUpstreamMin();
    if (upstreamMin > 0) {
      // Guard 1: Skip hash join when the upstream (probe side) is small.
      long upstreamCardinality = estimateUpstreamCardinality(
          scheduledEdges, checkIdx, trace.branchEdges,
          aliasClasses, aliasFilters, aliasRids, context);
      if (upstreamCardinality < upstreamMin) {
        return null;
      }

      // Guard 2: Cost-based comparison.
      //   hashJoinCost  = build_cardinality + upstream
      //   nestedLoopCost = upstream × branchFanOut × numHops
      double branchFanOut = estimateBranchFanOut(
          trace.branchEdges, trace.branchRoot, aliasClasses, aliasFilters,
          context);
      int numBranchHops = Math.max(1, trace.branchEdges.size() - 1);
      double nestedLoopCost = upstreamCardinality * branchFanOut * numBranchHops;
      double hashJoinCost = (double) cardinality + upstreamCardinality;
      if (hashJoinCost >= nestedLoopCost) {
        return null;
      }
    }

    // Shared aliases: the branch root and the check edge's non-intermediate endpoint
    var otherShared = trace.intermediateAliases.contains(checkSource)
        ? checkTarget : checkSource;
    var sharedAliases = trace.branchRoot.equals(otherShared)
        ? List.of(trace.branchRoot) : List.of(otherShared, trace.branchRoot);

    // The build plan needs a scan alias with a known class or RID.
    var scanAlias = findScanAlias(
        otherShared, trace.branchRoot, trace.intermediateAliases, aliasClasses,
        aliasRids);
    if (scanAlias == null) {
      return null;
    }

    return new HashJoinBranch(
        sharedAliases, trace.branchEdges, trace.intermediateAliases, cardinality,
        joinMode, scanAlias);
  }

  /** Intermediate result from backward edge tracing in {@link #traceBackwardBranch}. */
  private record BranchTrace(
      List<EdgeTraversal> branchEdges,
      Set<String> intermediateAliases,
      String branchRoot) {
  }

  /**
   * Walks backward from the consistency-check edge at {@code checkIdx}, collecting
   * consecutive edges whose target was not yet visited when that edge was processed
   * (i.e., edges that introduced new intermediate aliases). Stops when the branch
   * root (an already-visited source alias) is found.
   *
   * @return the traced branch edges, intermediate aliases, and branch root; or
   *         {@code null} if no valid branch was found
   */
  @SuppressWarnings("unchecked")
  @Nullable private static BranchTrace traceBackwardEdges(
      List<EdgeTraversal> scheduledEdges,
      int checkIdx,
      Set<String>[] visitedBefore,
      String checkSource,
      String checkTarget) {
    var checkEdge = scheduledEdges.get(checkIdx);
    var branchEdges = new ArrayList<EdgeTraversal>();
    branchEdges.add(checkEdge);
    var intermediateAliases = new HashSet<String>();

    String currentAlias = null;
    for (int j = checkIdx - 1; j >= 0; j--) {
      var prevEdge = scheduledEdges.get(j);
      var prevTarget = targetAlias(prevEdge);
      var prevSource = sourceAlias(prevEdge);
      Set<String> visitedBeforeJ = visitedBefore[j];

      // This edge introduced prevTarget if prevTarget was NOT in visitedBefore[j]
      if (!visitedBeforeJ.contains(prevTarget)) {
        if (currentAlias == null) {
          // First branch edge: must connect to checkSource or checkTarget
          if (prevTarget.equals(checkSource) || prevTarget.equals(checkTarget)) {
            intermediateAliases.add(prevTarget);
            branchEdges.add(0, prevEdge);
            currentAlias = prevSource;
            if (visitedBeforeJ.contains(prevSource)) {
              break;
            }
          }
        } else if (prevTarget.equals(currentAlias)) {
          // Continues the branch chain
          intermediateAliases.add(prevTarget);
          branchEdges.add(0, prevEdge);
          currentAlias = prevSource;
          if (visitedBeforeJ.contains(prevSource)) {
            break;
          }
        }
      }
    }

    if (intermediateAliases.isEmpty() || currentAlias == null
        || branchEdges.size() < 2) {
      return null;
    }
    return new BranchTrace(branchEdges, intermediateAliases, currentAlias);
  }

  /**
   * Checks whether a traced branch is eligible for hash join optimization.
   * Rejects branches with optional nodes, auto-generated internal aliases,
   * context-dependent filters ({@code $matched}/{@code $parent}), or external
   * edges that depend on branch intermediates via {@code $matched}.
   *
   * @return the {@link JoinMode} if eligible, or {@code null} if not
   */
  @Nullable private static JoinMode checkBranchEligibility(
      List<EdgeTraversal> branchEdges,
      Set<String> intermediateAliases,
      List<EdgeTraversal> scheduledEdges,
      Set<String> downstreamAliases,
      Map<String, SQLWhereClause> aliasFilters) {
    // Check for auto-generated internal aliases and classify join mode
    boolean hasDownstreamIntermediate = false;
    for (var alias : intermediateAliases) {
      if (alias.startsWith(DEFAULT_ALIAS_PREFIX)) {
        return null;
      }
      if (downstreamAliases.contains(alias)) {
        hasDownstreamIntermediate = true;
      }
    }
    var joinMode = hasDownstreamIntermediate
        ? JoinMode.INNER_JOIN : JoinMode.SEMI_JOIN;

    // Check that no branch edge involves an optional node
    for (var edgeT : branchEdges) {
      if (edgeT.edge.out.isOptionalNode() || edgeT.edge.in.isOptionalNode()) {
        return null;
      }
    }

    // Check that no branch node's filter depends on $matched/$parent
    for (var alias : intermediateAliases) {
      if (filterDependsOnContext(aliasFilters.get(alias))) {
        return null;
      }
    }

    // Check that no NON-BRANCH edge references a branch intermediate via $matched.
    // If an edge outside the branch depends on an intermediate, moving the branch
    // to the end would break execution (the alias wouldn't be bound).
    var branchEdgeSet = new HashSet<>(branchEdges);
    for (var scheduled : scheduledEdges) {
      if (branchEdgeSet.contains(scheduled)) {
        continue;
      }
      var outFilter = aliasFilters.get(scheduled.edge.out.alias);
      var inFilter = aliasFilters.get(scheduled.edge.in.alias);
      var outStr = outFilter != null ? outFilter.toString() : null;
      var inStr = inFilter != null ? inFilter.toString() : null;
      for (var interAlias : intermediateAliases) {
        if (outStr != null && outStr.contains("$matched." + interAlias)) {
          return null;
        }
        if (inStr != null && inStr.contains("$matched." + interAlias)) {
          return null;
        }
      }
    }

    return joinMode;
  }

  /**
   * Estimates the cardinality of a hash join branch. Starts from the branch root's
   * estimated record count and multiplies by schema-based fan-out per edge
   * (via {@link EdgeFanOutEstimator}), applying 0.5 selectivity for WHERE filters.
   */
  private static long estimateBranchCardinality(
      String branchRoot,
      List<EdgeTraversal> branchEdges,
      Map<String, String> aliasClasses,
      Map<String, SQLWhereClause> aliasFilters,
      Map<String, SQLRid> aliasRids,
      CommandContext context) {
    var session = context.getDatabaseSession();
    // Start with branch root cardinality
    long rows = estimateAliasCardinality(
        branchRoot, aliasClasses, aliasFilters, aliasRids, context);

    // Skip the last edge (consistency-check edge) — it doesn't expand cardinality,
    // it's a filter verifying the target alias matches an already-visited node (cost 0).
    int edgeCount = Math.max(0, branchEdges.size() - 1);
    var currentClass = aliasClasses.get(branchRoot);
    for (int i = 0; i < edgeCount; i++) {
      var edgeT = branchEdges.get(i);
      var method = edgeT.edge.item != null ? edgeT.edge.item.getMethod() : null;
      double fanOut = estimateMethodFanOut(method, currentClass, session);
      long fanOutLong = Math.max(1, Math.round(fanOut));
      if (rows > Long.MAX_VALUE / fanOutLong) {
        return Long.MAX_VALUE; // overflow guard
      }
      rows *= fanOutLong;
      var target = targetAlias(edgeT);
      // Apply selectivity — histogram-based if index available, default otherwise
      var targetFilter = aliasFilters.get(target);
      if (targetFilter != null) {
        double selectivity = estimateFilterSelectivity(
            targetFilter, currentClass, context);
        rows = Math.max(1, Math.round(rows * selectivity));
      }
      // Track current class for next hop
      var targetClass = aliasClasses.get(target);
      if (targetClass != null) {
        currentClass = targetClass;
      }
    }

    return rows;
  }

  /**
   * Estimates the upstream (probe-side) cardinality at the point where a hash join
   * branch diverges from the main path. Walks the main-path edges from the scan root
   * up to (but not including) the branch edges, multiplying by fan-out and selectivity.
   *
   * <p>Main-path edges are all edges in {@code scheduledEdges[0..checkIdx-1]} that are
   * NOT in {@code branchEdges}. The upstream cardinality is:
   * <pre>
   *   rootCardinality × Π(fanOut_i × selectivity_i) for each main-path edge i
   * </pre>
   *
   * @param scheduledEdges full edge schedule
   * @param checkIdx       index of the consistency-check edge
   * @param branchEdges    edges belonging to the hash join branch
   * @return estimated upstream row count, or {@link Long#MAX_VALUE} if not estimable
   */
  private static long estimateUpstreamCardinality(
      List<EdgeTraversal> scheduledEdges,
      int checkIdx,
      List<EdgeTraversal> branchEdges,
      Map<String, String> aliasClasses,
      Map<String, SQLWhereClause> aliasFilters,
      Map<String, SQLRid> aliasRids,
      CommandContext context) {
    var session = context.getDatabaseSession();
    var branchEdgeSet = new HashSet<>(branchEdges);

    // Find the scan root alias (source of the first scheduled edge)
    var rootAlias = sourceAlias(scheduledEdges.get(0));
    long rows = estimateAliasCardinality(
        rootAlias, aliasClasses, aliasFilters, aliasRids, context);

    var currentClass = aliasClasses.get(rootAlias);
    for (int i = 0; i < checkIdx; i++) {
      var edgeT = scheduledEdges.get(i);
      if (branchEdgeSet.contains(edgeT)) {
        continue; // Skip branch edges — they don't contribute to upstream
      }
      var method = edgeT.edge.item != null ? edgeT.edge.item.getMethod() : null;
      double fanOut = estimateMethodFanOut(method, currentClass, session);
      long fanOutLong = Math.max(1, Math.round(fanOut));
      if (rows > Long.MAX_VALUE / fanOutLong) {
        return Long.MAX_VALUE;
      }
      rows *= fanOutLong;
      var target = targetAlias(edgeT);
      var targetFilter = aliasFilters.get(target);
      if (targetFilter != null) {
        double selectivity = estimateFilterSelectivity(
            targetFilter, currentClass, context);
        rows = Math.max(1, Math.round(rows * selectivity));
      }
      var targetClass = aliasClasses.get(target);
      if (targetClass != null) {
        currentClass = targetClass;
      }
    }
    return rows;
  }

  /**
   * Estimates the per-row branch fan-out — the average number of rows a single upstream
   * row produces when traversing the branch via nested-loop. This is the product of
   * fanOut × selectivity for each branch edge, excluding the last consistency-check edge
   * (which is a free RID equality check, cost 0).
   *
   * @return the estimated per-row fan-out (≥ 1.0)
   */
  private static double estimateBranchFanOut(
      List<EdgeTraversal> branchEdges,
      String branchRoot,
      Map<String, String> aliasClasses,
      Map<String, SQLWhereClause> aliasFilters,
      CommandContext context) {
    var session = context.getDatabaseSession();
    // Exclude the last edge (consistency-check) — it's a free RID match
    int edgeCount = Math.max(0, branchEdges.size() - 1);
    double fanOut = 1.0;
    var currentClass = aliasClasses.get(branchRoot);
    for (int i = 0; i < edgeCount; i++) {
      var edgeT = branchEdges.get(i);
      var method = edgeT.edge.item != null ? edgeT.edge.item.getMethod() : null;
      fanOut *= estimateMethodFanOut(method, currentClass, session);
      var target = targetAlias(edgeT);
      var targetFilter = aliasFilters.get(target);
      if (targetFilter != null) {
        double selectivity = estimateFilterSelectivity(
            targetFilter, currentClass, context);
        fanOut *= selectivity;
      }
      var targetClass = aliasClasses.get(target);
      if (targetClass != null) {
        currentClass = targetClass;
      }
    }
    return Math.max(1.0, fanOut);
  }

  /**
   * Estimates the cardinality of a single alias — the number of records it can produce.
   * Delegates to {@link #estimateRootEntries} with single-alias maps, matching the
   * approach used in {@link #estimateNotPatternCardinality}.
   */
  private static long estimateAliasCardinality(
      String alias,
      Map<String, String> aliasClasses,
      Map<String, SQLWhereClause> aliasFilters,
      Map<String, SQLRid> aliasRids,
      CommandContext context) {
    var cls = aliasClasses.get(alias);
    var filter = aliasFilters.get(alias);
    var rid = aliasRids.get(alias);
    var singleClasses = cls != null ? Map.of(alias, cls) : Map.<String, String>of();
    var singleFilters = filter != null ? Map.of(alias, filter) : Map.<String, SQLWhereClause>of();
    var singleRids = rid != null ? Map.of(alias, rid) : Map.<String, SQLRid>of();

    var rootEstimates = estimateRootEntries(singleClasses, singleRids, singleFilters, context);
    var count = rootEstimates.get(alias);
    return count != null ? Math.max(1, count) : THRESHOLD;
  }

  /**
   * Selects the best alias to scan from in the build-side plan. Tries shared aliases
   * first ({@code otherShared}, then {@code branchRoot}), then intermediates. Returns
   * the first alias that has a known class or RID, or {@code null} if none qualifies.
   */
  @Nullable private static String findScanAlias(
      String otherShared,
      String branchRoot,
      Set<String> intermediateAliases,
      Map<String, String> aliasClasses,
      Map<String, SQLRid> aliasRids) {
    // Prefer shared aliases — they anchor the build plan at a join endpoint
    if (aliasClasses.get(otherShared) != null || aliasRids.get(otherShared) != null) {
      return otherShared;
    }
    if (aliasClasses.get(branchRoot) != null || aliasRids.get(branchRoot) != null) {
      return branchRoot;
    }
    // Fall back to intermediates (relevant for INNER_JOIN where intermediates have classes)
    for (var alias : intermediateAliases) {
      if (aliasClasses.get(alias) != null || aliasRids.get(alias) != null) {
        return alias;
      }
    }
    return null;
  }

  /**
   * Constructs the build-side {@link SelectExecutionPlan} for a NOT pattern's hash
   * anti-join. The plan scans the origin alias's class and chains the NOT pattern's
   * {@link MatchStep}s to traverse the negative edges.
   *
   * @param exp              the NOT match expression
   * @param matchSteps       the pre-built MatchStep chain for the NOT pattern's edges
   * @param aliasClasses     per-alias class names
   * @param aliasFilters     per-alias WHERE clauses
   * @param aliasRids        per-alias RID constraints
   * @param context          the command context
   * @param enableProfiling  whether to enable step profiling
   * @return a complete build-side execution plan
   */
  private static SelectExecutionPlan buildNotPatternPlan(
      SQLMatchExpression exp,
      List<AbstractExecutionStep> matchSteps,
      Map<String, String> aliasClasses,
      Map<String, SQLWhereClause> aliasFilters,
      Map<String, SQLRid> aliasRids,
      CommandContext context,
      boolean enableProfiling) {
    var originAlias = exp.getOrigin().getAlias();
    var originClass = aliasClasses.get(originAlias);
    var originRid = aliasRids.get(originAlias);
    var originFilter = aliasFilters.get(originAlias);

    // Build the origin scan: SELECT FROM <class> [WHERE ...]
    // Copy the WHERE clause to prevent mutable filter corruption (same as
    // buildHashJoinBranchPlan and addStepsFor).
    var select = createSelectStatement(
        originClass, originRid, originFilter == null ? null : originFilter.copy());

    // Create PatternNode for the origin alias
    var originNode = new PatternNode();
    originNode.alias = originAlias;

    var buildPlan = new SelectExecutionPlan(context);
    buildPlan.chain(new MatchFirstStep(
        context, originNode, select.createExecutionPlan(context, enableProfiling),
        enableProfiling));

    // Chain the NOT pattern's MatchSteps
    for (var step : matchSteps) {
      buildPlan.chain(step);
    }

    return buildPlan;
  }

  /**
   * Constructs the build-side {@link SelectExecutionPlan} for a hash join branch.
   * The plan scans from {@link HashJoinBranch#scanAlias()} (the first shared or
   * intermediate alias with a known class) and chains {@link MatchStep}s for all
   * branch edges, building a directed path through the branch's aliases.
   *
   * @param branch          the branch descriptor
   * @param context         the command context
   * @param profilingEnabled whether to enable step profiling
   * @return a complete build-side execution plan for the branch, or null if path is incomplete
   */
  private SelectExecutionPlan buildHashJoinBranchPlan(
      HashJoinBranch branch,
      CommandContext context,
      boolean profilingEnabled) {
    // The build plan scans from the branch's scan alias (determined by
    // traceBackwardBranch — the first shared/intermediate alias with a known
    // class) and traverses through all branch edges to produce rows containing
    // all branch aliases.
    var scanAlias = branch.scanAlias();
    var scanClass = aliasClasses.get(scanAlias);
    var scanRid = aliasRids.get(scanAlias);
    var scanFilter = aliasFilters.get(scanAlias);

    // Copy the WHERE clause to prevent mutable state from the main plan's
    // execution corrupting the build-side filter (matches addStepsFor behavior).
    var select = createSelectStatement(
        scanClass, scanRid, scanFilter == null ? null : scanFilter.copy());
    var scanNode = new PatternNode();
    scanNode.alias = scanAlias;

    // Use a sub-context with parent linkage for the SELECT sub-plan, matching
    // the pattern in addStepsFor(). Without parent linkage, variable lookups
    // (e.g., input parameters) would fail in the sub-plan's steps.
    var subCtx = new BasicCommandContext();
    subCtx.setParentWithoutOverridingChild(context);

    var buildPlan = new SelectExecutionPlan(context);
    buildPlan.chain(new MatchFirstStep(
        context, scanNode, select.createExecutionPlan(subCtx, profilingEnabled),
        profilingEnabled));

    // Build a directed path from scanAlias through intermediates to the other
    // shared alias (branchRoot). For each branch edge, determine the correct
    // traversal direction so the path connects.
    var edges = branch.branchEdges();
    var current = scanAlias;
    // We need to traverse branch edges in an order that forms a path from
    // scanAlias. Try each unused edge that has 'current' as one endpoint.
    var used = new boolean[edges.size()];
    for (int step = 0; step < edges.size(); step++) {
      boolean found = false;
      for (int j = 0; j < edges.size(); j++) {
        if (used[j]) {
          continue;
        }
        var orig = edges.get(j);
        var outAlias = orig.edge.out.alias;
        var inAlias = orig.edge.in.alias;
        if (outAlias.equals(current)) {
          // Forward traversal: current → inAlias
          var traversal = new EdgeTraversal(orig.edge, true);
          // Left constraints = edge.out node's constraints (same as createPlanForPattern)
          traversal.setLeftClass(aliasClasses.get(outAlias));
          traversal.setLeftFilter(aliasFilters.get(outAlias));
          traversal.setLeftRid(aliasRids.get(outAlias));
          buildPlan.chain(new MatchStep(context, traversal, profilingEnabled));
          current = inAlias;
          used[j] = true;
          found = true;
          break;
        } else if (inAlias.equals(current)) {
          // Reverse traversal: current → outAlias
          var traversal = new EdgeTraversal(orig.edge, false);
          // Left constraints = edge.out node's constraints, NOT the current (in) node.
          // MatchReverseEdgeTraverser uses leftClass/leftFilter/leftRid as TARGET
          // constraints — the target in reverse mode is edge.out.
          traversal.setLeftClass(aliasClasses.get(outAlias));
          traversal.setLeftFilter(aliasFilters.get(outAlias));
          traversal.setLeftRid(aliasRids.get(outAlias));
          buildPlan.chain(new MatchStep(context, traversal, profilingEnabled));
          current = outAlias;
          used[j] = true;
          found = true;
          break;
        }
      }
      if (!found) {
        break; // Can't continue the path
      }
    }

    // Verify all edges were used — if the path is incomplete, the build plan
    // would produce incorrect results (missing alias bindings for the join key).
    for (boolean u : used) {
      if (!u) {
        // Incomplete path — fall back by returning null. The caller must check.
        return null;
      }
    }

    return buildPlan;
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
      @Nullable IndexOrderedPlanner.IndexOrderedCandidate candidate,
      boolean profilingEnabled) {
    var plan = new SelectExecutionPlan(context);
    var sortedEdges = getTopologicalSortedSchedule(estimatedRootEntries, pattern,
        aliasClasses, aliasFilters, context.getDatabaseSession());

    var first = true;
    if (!sortedEdges.isEmpty()) {
      optimizeScheduleWithIntersections(sortedEdges, context);

      // Re-bind filters after optimization: detectNotInAntiJoin() may have
      // stripped NOT IN conditions from aliasFilters, so we must push the
      // updated filters to the match expression AST nodes. Without this,
      // the MatchStep would still evaluate the original un-stripped filter.
      rebindFilters(aliasFilters);

      // Annotate each edge traversal with the source node's class/RID/filter
      // constraints (post-optimization, so stripped filters are reflected).
      // MatchReverseEdgeTraverser uses these when traversing in reverse.
      for (var edge : sortedEdges) {
        if (edge.edge.out.alias != null) {
          edge.setLeftClass(aliasClasses.get(edge.edge.out.alias));
          edge.setLeftRid(aliasRids.get(edge.edge.out.alias));
          edge.setLeftFilter(aliasFilters.get(edge.edge.out.alias));
        }
      }

      attachCollectionIdFilters(sortedEdges, context);

      // Hash join optimization: detect secondary branches that can be evaluated as
      // build-side hash joins (semi-join if intermediates not downstream, inner join
      // if intermediates are referenced downstream).
      var downstreamAliases = collectDownstreamAliases(
          returnItems, groupBy, orderBy, unwind, pattern.aliasToNode.keySet());
      var hashJoinBranches = identifyHashJoinBranches(
          sortedEdges, downstreamAliases, aliasClasses, aliasFilters, aliasRids, context);

      // Collect edges that belong to hash join branches — skip them in the main loop.
      // Guards:
      // - If ALL edges would be claimed, the main plan would have no source step.
      // - If the FIRST edge is claimed, the main plan loses its scan root and
      //   subsequent edges may get an incorrect MatchFirstStep.
      var branchEdgeSet = new HashSet<PatternEdge>();
      for (var branch : hashJoinBranches) {
        for (var branchEdge : branch.branchEdges()) {
          branchEdgeSet.add(branchEdge.edge);
        }
      }
      if (branchEdgeSet.size() >= sortedEdges.size()
          || branchEdgeSet.contains(sortedEdges.getFirst().edge)) {
        // All edges claimed or first edge claimed — fall back to normal execution
        branchEdgeSet.clear();
        hashJoinBranches = List.of();
      }

      for (var edge : sortedEdges) {
        if (branchEdgeSet.contains(edge.edge)) {
          continue; // Skip edges handled by hash join
        }
        addStepsFor(plan, edge, context, first, candidate, profilingEnabled);
        first = false;
      }

      // Append HashJoinMatchSteps for each hash join branch
      for (var branch : hashJoinBranches) {
        var branchPlan = buildHashJoinBranchPlan(branch, context, profilingEnabled);
        if (branchPlan == null) {
          // Build plan construction failed (incomplete path) — re-add the branch's
          // edges back into the main plan by not skipping them. Since we already
          // skipped them above, we need to add them now.
          for (var branchEdge : branch.branchEdges()) {
            addStepsFor(plan, branchEdge, context, first, null, profilingEnabled);
            first = false;
          }
        } else {
          plan.chain(new HashJoinMatchStep(
              context, branchPlan, branch.sharedAliases(),
              branch.joinMode(), profilingEnabled));
        }
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
   * Estimates the fan-out for a single edge traversal using schema statistics
   * via {@link EdgeFanOutEstimator}. Falls back to
   * {@link EdgeFanOutEstimator#defaultFanOut()} if schema metadata is
   * unavailable.
   *
   * @param method         the edge's traversal method (e.g., out('KNOWS'))
   * @param sourceClassName class of the vertex we traverse FROM, or null
   * @param session        database session for schema access
   * @return estimated fan-out (avg neighbors per source vertex)
   */
  static double estimateMethodFanOut(
      SQLMethodCall method,
      @Nullable String sourceClassName,
      DatabaseSessionEmbedded session) {
    if (method == null) {
      return EdgeFanOutEstimator.defaultFanOut();
    }
    Direction direction = parseDirection(method.getMethodNameString());
    if (direction == null) {
      return EdgeFanOutEstimator.defaultFanOut();
    }
    String edgeClassName = extractEdgeClassName(method);
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
    return EdgeFanOutEstimator.estimateFanOut(
        session, edgeClassName, sourceClassName, direction,
        outVertexClass, inVertexClass);
  }

  /**
   * Estimates the selectivity of a WHERE clause for cardinality estimation.
   * Uses the existing {@link TraversalPreFilterHelper#findIndexForFilter} +
   * {@link IndexSearchDescriptor} pipeline to get histogram-based estimates
   * when an index exists. Falls back to
   * {@link SelectivityEstimator#defaultSelectivity()} otherwise.
   *
   * @param where      the WHERE clause to estimate
   * @param className  the class the filter applies to, or null
   * @param ctx        the command context
   * @return selectivity in (0.0, 1.0]
   */
  private static double estimateFilterSelectivity(
      @Nullable SQLWhereClause where,
      @Nullable String className,
      CommandContext ctx) {
    if (where == null || className == null) {
      return SelectivityEstimator.defaultSelectivity();
    }
    // Try to find an index that covers this filter and use its statistics
    var indexDesc = TraversalPreFilterHelper.findIndexForFilter(where, className, ctx);
    if (indexDesc != null) {
      var session = ctx.getDatabaseSession();
      var stats = indexDesc.getIndex().getStatistics(session);
      if (stats != null && stats.totalCount() > 0) {
        var histogram = indexDesc.getIndex().getHistogram(session);
        // Extract the leading condition and estimate selectivity via histogram
        var baseExpr = where.getBaseExpression();
        if (baseExpr instanceof SQLBinaryCondition bc
            && bc.getRight().isEarlyCalculated(ctx)) {
          var value = bc.getRight().execute((Result) null, ctx);
          if (value != null) {
            double sel = SelectivityEstimator.estimateForOperator(
                bc.getOperator(), stats, histogram, value);
            if (sel >= 0) {
              return Math.max(0.001, sel); // clamp to avoid zero
            }
          }
        }
      }
    }
    return SelectivityEstimator.defaultSelectivity();
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
      if (value instanceof String s && !s.isEmpty()) {
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

    // Track aliases that are bound (visited) before each edge. Built
    // incrementally: the source alias is added at the start of each
    // iteration (it was bound by a preceding edge or is the root), and
    // the target alias is added at the end (it becomes bound during this
    // edge's execution). This ensures that semi-join candidacy checks
    // only see aliases that are actually available at execution time.
    Set<String> boundAliases = new HashSet<>();
    for (var j = 0; j < schedule.size(); j++) {
      var edgeJ = schedule.get(j);
      var sourceAliasJ = edgeJ.out ? edgeJ.edge.out.alias : edgeJ.edge.in.alias;
      var targetAliasJ = edgeJ.out ? edgeJ.edge.in.alias : edgeJ.edge.out.alias;

      // Source alias is bound before this edge executes (it is either
      // the root alias or the target of a preceding edge).
      if (sourceAliasJ != null) {
        boundAliases.add(sourceAliasJ);
      }
      if (targetAliasJ == null) {
        continue;
      }

      var targetFilter = aliasFilters.get(targetAliasJ);
      if (targetFilter == null) {
        boundAliases.add(targetAliasJ);
        continue;
      }

      // --- RID equality detection ---
      // Check if target filter contains @rid = <expr>.
      // Uses findRidEquality() (non-destructive) instead of
      // extractAndRemoveRidEquality() to avoid mutating the filter.
      var ridExpr = targetFilter.findRidEquality();
      if (ridExpr != null) {
        var involvedAliases = ridExpr.getMatchPatternInvolvedAliases();
        if (involvedAliases != null && !involvedAliases.isEmpty()) {
          // Back-reference: @rid = $matched.X.@rid → EdgeRidLookup
          var edgeClass = getEdgeClassName(edgeJ);
          var edgeDirection = getEdgeDirection(edgeJ);
          var collectEdgeRids = false;

          // .inV()/.outV() steps have no edge class — propagate from the
          // preceding .outE('CLASS')/.inE('CLASS') step if present.
          // The preceding edge iterates edge RIDs, so collectEdgeRids=true.
          if (edgeClass == null && j > 0) {
            var prevEdge = schedule.get(j - 1);
            var prevMethodName = getMethodName(prevEdge);
            if ("oute".equals(prevMethodName) || "ine".equals(prevMethodName)) {
              edgeClass = getEdgeClassName(prevEdge);
              // Normalize direction: "oute" -> "out", "ine" -> "in"
              edgeDirection = getEdgeDirection(prevEdge);
              if (edgeDirection != null && edgeDirection.endsWith("e")) {
                edgeDirection =
                    edgeDirection.substring(0, edgeDirection.length() - 1);
              }
              collectEdgeRids = true;
            }
          }

          if (edgeClass != null && edgeDirection != null) {

            // --- Semi-join candidacy check (Pattern A) ---
            // Only when the edge class belongs to the current edge (not
            // propagated from a preceding outE/inE). When collectEdgeRids
            // is true, the class/direction came from the previous edge —
            // Pattern B detection handles that case below.
            //
            // Any residual WHERE terms on the target alias (beyond the
            // {@code @rid = $matched.X.@rid} equality) are extracted here
            // and passed to {@link BackRefHashJoinStep} for post-load
            // evaluation. Pattern A is rejected only when the residual
            // cannot be extracted safely — either because the RID equality
            // is nested too deep for the flat-block extractor, or because
            // the residual references {@code $matched}/{@code $currentMatch}
            // which build phase cannot resolve.
            var residualExtraction = extractTargetResidual(targetFilter);
            if (!collectEdgeRids
                && !edgeJ.edge.in.isOptionalNode()
                && isSemiJoinCandidate(edgeDirection, involvedAliases,
                    boundAliases)
                && residualExtraction.safe()) {
              var backRefAlias = involvedAliases.getFirst();
              var descriptor = new SingleEdgeSemiJoin(
                  edgeClass, edgeDirection, ridExpr,
                  sourceAliasJ, backRefAlias, targetAliasJ,
                  residualExtraction.residual());
              edgeJ.setSemiJoinDescriptor(descriptor);
              logger.debug(
                  "MATCH pre-filter: BackRefHashJoin on edge[{}] "
                      + "({}({}) semi-join via $matched.{})",
                  j, edgeDirection, edgeClass, backRefAlias);
            } else {
              // Fallback: attach EdgeRidLookup on the producing edge
              var producingEdgeIdx = targetAliasToEdgeIndex.get(sourceAliasJ);
              if (producingEdgeIdx != null) {
                var edgeI = schedule.get(producingEdgeIdx);
                edgeI.addIntersectionDescriptor(
                    new RidFilterDescriptor.EdgeRidLookup(
                        edgeClass, edgeDirection, ridExpr, collectEdgeRids));
                logger.debug(
                    "MATCH pre-filter: EdgeRidLookup on edge[{}] "
                        + "({}({}) back-ref from alias '{}', edgeRids={})",
                    producingEdgeIdx, edgeDirection, edgeClass, targetAliasJ,
                    collectEdgeRids);
              }
              // --- Pattern B: outE('E').inV() chain semi-join ---
              // When edge class was propagated from the preceding edge, also
              // try chain semi-join which collapses both edges into one step.
              if (collectEdgeRids) {
                tryAttachChainSemiJoin(
                    schedule, j, edgeJ, involvedAliases, ridExpr,
                    targetAliasJ, boundAliases, ctx);
              }
            }
          } else if (j > 0) {
            // --- Pattern B: outE('E').inV() chain semi-join ---
            // edge_j is .inV() (no edge class/direction). Check if the
            // preceding edge is .outE('E') or .inE('E') with a recognized
            // edge class. If so, collapse both into a ChainSemiJoin.
            tryAttachChainSemiJoin(
                schedule, j, edgeJ, involvedAliases, ridExpr,
                targetAliasJ, boundAliases, ctx);
          }
        } else {
          // Literal or parameter RID: @rid = #12:0 or @rid = :param
          // → DirectRid singleton set for zero-waste link bag filtering.
          // A singleton RID cannot benefit from further index intersection,
          // so skip the index detection below.
          edgeJ.addIntersectionDescriptor(
              new RidFilterDescriptor.DirectRid(ridExpr));
          logger.debug(
              "MATCH pre-filter: DirectRid on edge[{}] for alias '{}'",
              j, targetAliasJ);
          boundAliases.add(targetAliasJ);
          continue;
        }
      }

      // --- Pattern D: NOT IN anti-semi-join detection ---
      // Check if the target's WHERE clause contains
      // $currentMatch NOT IN $matched.X.out('E')
      // Pattern D detection follows below
      if (edgeJ.getSemiJoinDescriptor() == null) {
        var antiDesc = detectNotInAntiJoin(
            targetFilter, targetAliasJ, boundAliases);
        if (antiDesc != null) {
          edgeJ.setSemiJoinDescriptor(antiDesc);
          logger.debug(
              "MATCH pre-filter: AntiSemiJoin on edge[{}] "
                  + "(NOT IN $matched.{}.{}('{}'))",
              j, antiDesc.anchorAlias(),
              antiDesc.traversalDirection(), antiDesc.traversalEdgeClass());
        }
      }

      // --- Index pre-filter detection ---
      var targetClass = aliasClasses.get(targetAliasJ);
      if (targetClass == null) {
        boundAliases.add(targetAliasJ);
        continue;
      }

      // Split the filter: only the non-$matched part can use an index.
      SQLWhereClause indexableFilter = targetFilter;
      var matchedSplit = targetFilter.splitByMatchedReference();
      if (matchedSplit != null) {
        indexableFilter = matchedSplit.nonMatchedReferencing();
      }
      if (indexableFilter == null) {
        boundAliases.add(targetAliasJ);
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

      // Target alias becomes bound after this edge executes
      boundAliases.add(targetAliasJ);
    }
  }

  /**
   * Checks if a back-reference edge qualifies for a semi-join hash table
   * optimization (Pattern A). The edge must be a vertex-level traversal
   * ({@code out('E')} or {@code in('E')}, not {@code outE('E')} or
   * {@code inE('E')}), and the back-referenced alias must be already bound
   * earlier in the schedule.
   *
   * @param edgeDirection    the traversal direction (e.g., "out", "in", "oute")
   * @param involvedAliases  the aliases referenced by the back-ref expression
   * @param boundAliases     all aliases bound (visited) in the schedule
   * @return true if the edge is a semi-join candidate
   */
  private static boolean isSemiJoinCandidate(
      String edgeDirection,
      List<String> involvedAliases,
      Set<String> boundAliases) {
    // Only vertex-level traversals qualify (not outE/inE/bothE/both)
    if (!"out".equals(edgeDirection) && !"in".equals(edgeDirection)) {
      return false;
    }
    // The back-ref must reference exactly one alias
    if (involvedAliases.size() != 1) {
      return false;
    }
    // The back-referenced alias must be already bound in the schedule
    var backRefAlias = involvedAliases.getFirst();
    if (!boundAliases.contains(backRefAlias)) {
      return false;
    }
    // Check threshold is enabled (0 disables hash join)
    var threshold = getHashJoinThreshold();
    return threshold > 0;
  }

  /**
   * Structural decomposition of a {@code $matched.<alias>.<out|in>('E')}
   * traversal expression — the RHS shape required by Pattern D anti-joins.
   */
  private record MatchedTraversal(
      String anchorAlias, String direction, String edgeClass) {
  }

  /**
   * Extracts {@link MatchedTraversal} from an RHS AST node or returns
   * {@code null} when the shape does not match. Walks the AST directly
   * instead of parsing {@link SQLMathExpression#toString}, which would
   * rely on the generated parser's serialization format staying stable
   * and would mis-handle back-quoted identifiers, bound parameters, and
   * edge-direction variants like {@code outE}.
   */
  @Nullable private static MatchedTraversal extractMatchedTraversal(
      @Nullable SQLMathExpression rhs) {
    if (!(rhs instanceof SQLBaseExpression base)) {
      return null;
    }
    var identifier = base.getIdentifier();
    if (identifier == null || !"$matched".equals(identifier.toString())) {
      return null;
    }
    var firstMod = base.getModifier();
    if (firstMod == null) {
      return null;
    }
    // First segment must be `.X` (suffix identifier, no method call).
    var suffix = firstMod.getSuffix();
    if (suffix == null || suffix.getIdentifier() == null
        || firstMod.getMethodCall() != null) {
      return null;
    }
    var anchorAlias = suffix.getIdentifier().getStringValue();
    if (anchorAlias == null) {
      return null;
    }
    // Second segment must be `.<dir>(<edgeClass>)` — a method call.
    var secondMod = firstMod.getNext();
    if (secondMod == null) {
      return null;
    }
    var method = secondMod.getMethodCall();
    if (method == null) {
      return null;
    }
    // There must not be further modifiers (reject `.out('E').somethingElse`).
    if (secondMod.getNext() != null) {
      return null;
    }
    var methodName = method.getMethodNameString();
    if (methodName == null) {
      return null;
    }
    // Vertex-level traversals only: out / in. Reject outE, inE, bothV, etc.
    var direction = methodName.toLowerCase(Locale.ROOT);
    if (!"out".equals(direction) && !"in".equals(direction)) {
      return null;
    }
    var params = method.getParams();
    if (params == null || params.size() != 1) {
      return null;
    }
    var paramMath = params.getFirst().getMathExpression();
    if (!(paramMath instanceof SQLBaseExpression paramBase)) {
      return null;
    }
    // Only accept a plain string literal for the edge class — reject bound
    // parameters (:edge), identifiers, and compound expressions whose
    // value cannot be determined at plan time.
    var edgeClass = paramBase.getStringLiteralValue();
    if (edgeClass == null) {
      return null;
    }
    return new MatchedTraversal(anchorAlias, direction, edgeClass);
  }

  /**
   * Detects Pattern D: a {@code $currentMatch NOT IN $matched.X.out('E')}
   * condition in the target node's WHERE clause. If found, removes the
   * NOT IN condition from the WHERE clause and returns an
   * {@link AntiSemiJoin} descriptor. Any remaining conditions in the AND
   * block stay as residual filter on the {@link MatchEdgeTraverser}.
   *
   * @param targetFilter  the WHERE clause on the target node
   * @param targetAlias   the alias of the target node
   * @param boundAliases  all aliases bound in the schedule
   * @return an AntiSemiJoin descriptor, or null if the pattern is not found
   */
  @Nullable private AntiSemiJoin detectNotInAntiJoin(
      SQLWhereClause targetFilter,
      String targetAlias,
      Set<String> boundAliases) {
    var threshold = getHashJoinThreshold();
    if (threshold <= 0) {
      return null;
    }

    var baseExpr = targetFilter.getBaseExpression();
    if (baseExpr == null) {
      return null;
    }

    // Descend through the transparent wrappers MATCH adds around filters
    // (addAliases wraps original WHERE in an outer AND, the grammar in turn
    // wraps AND blocks in single-element ORs) to the AND block whose
    // sub-blocks are the user's visible conjuncts. Without this, a compound
    // WHERE like "NOT IN AND name='n4'" sits two wrappers deep — iterating
    // the top-level AND's single sub-block (an OR wrapping the inner AND
    // with two terms) never reaches the NOT IN.
    var andBlock = findConjunctsAnd(baseExpr);
    if (andBlock == null) {
      return null;
    }
    var andSubBlocks = andBlock.getSubBlocks();
    if (andSubBlocks == null || andSubBlocks.isEmpty()) {
      return null;
    }
    // Scan for SQLNotInCondition nodes whose LHS is $currentMatch and whose
    // RHS matches $matched.X.out('E') or $matched.X.in('E'). The grammar
    // wraps conditions in multiple transparent layers (OrBlock → AndBlock →
    // NotBlock → ConditionBlock). We unwrap single-element wrappers before
    // checking the inner type, then inspect the LHS/RHS AST nodes directly.
    for (int i = 0; i < andSubBlocks.size(); i++) {
      var sub = andSubBlocks.get(i);
      var inner = unwrapToNotInCondition(sub);
      if (inner == null) {
        continue;
      }

      // Check LHS is $currentMatch via the AST node
      var lhs = inner.getLeft();
      if (lhs == null || !"$currentMatch".equals(lhs.toString().trim())) {
        continue;
      }

      // Check RHS is $matched.X.out('E') or $matched.X.in('E') by walking
      // the AST structure directly — immune to toString() format drift,
      // back-quoted identifiers, and bound parameters for the edge class.
      var traversal = extractMatchedTraversal(inner.getRightMathExpression());
      if (traversal == null) {
        continue;
      }

      // Anchor alias must be already bound
      if (!boundAliases.contains(traversal.anchorAlias())) {
        continue;
      }

      // Strip the NOT IN from the target alias's WHERE clause so that the
      // preceding MatchStep does not re-evaluate it per row (the expensive
      // O(degree) link bag traversal). The stripped condition is stored in
      // the AntiSemiJoin descriptor for runtime fallback: if the hash table
      // build fails, BackRefHashJoinStep evaluates it per row.
      //
      // Both sides use independent AST copies: buildWhereWithoutTerm already
      // deep-copies the sub-blocks it keeps, and we deep-copy {@code sub}
      // before stashing it in the descriptor. The original andBlock is kept
      // intact by the planner (rebindFilters, etc.) and cached plans may
      // re-execute with re-bound parameters — any shared sub-block reference
      // would let an AST rewrite on one side corrupt the other.
      var strippedFilter =
          SQLWhereClause.buildWhereWithoutTerm(andBlock, i);
      if (strippedFilter != null) {
        aliasFilters.put(targetAlias, strippedFilter);
      } else {
        // NOT IN was the only condition — remove the filter entirely
        aliasFilters.remove(targetAlias);
      }

      return new AntiSemiJoin(
          traversal.anchorAlias(), traversal.edgeClass(),
          traversal.direction(), targetAlias, sub.copy());
    }
    return null;
  }

  /**
   * Descends through single-element {@link SQLOrBlock} and {@link SQLAndBlock}
   * wrappers to find the AND block whose sub-blocks are the user's top-level
   * conjuncts.
   *
   * <p>The MATCH planner wraps each alias's WHERE clause in at least one
   * extra AND block (see {@code addAliases}), and the grammar itself wraps
   * the user's AND inside a single-element OR. For a compound WHERE like
   * {@code NOT IN AND name='n4'} the resulting structure is
   * {@code AND[OR[AND[<notIn>, <name='n4'>]]]} — the inner AND holds the
   * actual conjuncts. Returns {@code null} when a multi-branch OR is
   * encountered (that would require disjunctive processing) or when no AND
   * is reachable.
   */
  @Nullable private static SQLAndBlock findConjunctsAnd(
      SQLBooleanExpression expr) {
    SQLAndBlock lastAnd = null;
    var current = expr;
    while (true) {
      if (current instanceof SQLOrBlock or) {
        var subs = or.getSubBlocks();
        if (subs == null || subs.size() != 1) {
          return null;
        }
        current = subs.getFirst();
        continue;
      }
      if (current instanceof SQLAndBlock and) {
        lastAnd = and;
        var subs = and.getSubBlocks();
        if (subs == null || subs.isEmpty()) {
          return and;
        }
        // If this AND already has multiple conjuncts, it IS the user's list.
        if (subs.size() > 1) {
          return and;
        }
        // Single-element AND may be a wrapper around another AND/OR layer.
        var only = subs.getFirst();
        if (only instanceof SQLOrBlock || only instanceof SQLAndBlock) {
          current = only;
          continue;
        }
        // The lone sub-block is a leaf condition — this AND is the deepest
        // one reachable and holds that single conjunct.
        return and;
      }
      // Not an AND/OR at the top — return the deepest AND we've seen.
      return lastAnd;
    }
  }

  /**
   * Unwraps the transparent AST wrappers the grammar produces around condition
   * blocks (OrBlock → AndBlock → NotBlock → ConditionBlock) to reach the inner
   * {@link SQLNotInCondition}, if present. Returns {@code null} if the expression
   * is not a NOT IN condition or if any wrapper is non-transparent (e.g., OR with
   * multiple branches, or NOT with {@code negate=true}).
   */
  @Nullable private static SQLNotInCondition unwrapToNotInCondition(
      SQLBooleanExpression expr) {
    var inner = expr;
    // Unwrap single-element SQLOrBlock
    if (inner instanceof SQLOrBlock or && or.getSubBlocks() != null
        && or.getSubBlocks().size() == 1) {
      inner = or.getSubBlocks().getFirst();
    }
    // Unwrap single-element SQLAndBlock
    if (inner instanceof SQLAndBlock and && and.getSubBlocks() != null
        && and.getSubBlocks().size() == 1) {
      inner = and.getSubBlocks().getFirst();
    }
    // Unwrap transparent SQLNotBlock (negate=false)
    if (inner instanceof SQLNotBlock not && !not.isNegate()) {
      inner = not.getSub();
    }
    return inner instanceof SQLNotInCondition notIn ? notIn : null;
  }

  /**
   * Detects Pattern B: an {@code .outE('E').inV()} chain where the
   * {@code .inV()} target has a back-reference {@code @rid = $matched.X.@rid}.
   * Returns a {@link ChainSemiJoin} descriptor if the pattern qualifies,
   * or {@code null} otherwise.
   *
   * <p>The edge class and direction are extracted from edge_j-1 (the
   * {@code .outE('E')} edge), not from edge_j ({@code .inV()}).
   */
  @Nullable private ChainSemiJoin detectChainSemiJoin(
      List<EdgeTraversal> schedule,
      int j,
      List<String> involvedAliases,
      SQLExpression ridExpr,
      String targetAliasJ,
      Set<String> boundAliases,
      CommandContext ctx) {
    // The back-ref must reference exactly one alias that is already bound
    if (involvedAliases.size() != 1) {
      return null;
    }
    var backRefAlias = involvedAliases.getFirst();
    if (!boundAliases.contains(backRefAlias)) {
      return null;
    }
    var threshold = getHashJoinThreshold();
    if (threshold <= 0) {
      return null;
    }

    var edgeJ = schedule.get(j);
    // Mirror the Pattern A guard: an optional target node must pass through
    // rows with nulls when no match is found, which BackRefHashJoinStep does
    // not implement. Worse, addStepsFor dispatches on the optional branch
    // before consulting getSemiJoinDescriptor() — so if Pattern B fired here
    // the predecessor edge would be marked consumed and silently dropped
    // from the plan, producing wrong results.
    if (edgeJ.edge.in.isOptionalNode() || edgeJ.edge.out.isOptionalNode()) {
      return null;
    }

    // Check preceding edge (j-1) is an edge-level traversal (outE/inE)
    var edgePrev = schedule.get(j - 1);
    // The intermediate alias (edgePrev endpoint) must also be non-optional:
    // it would otherwise be "skipped" in the traversal yet still bound by
    // the collapsed BackRefHashJoinStep, diverging from the documented
    // semantics of optional nodes.
    if (edgePrev.edge.in.isOptionalNode() || edgePrev.edge.out.isOptionalNode()) {
      return null;
    }
    var prevDirection = getEdgeDirection(edgePrev);
    if (prevDirection == null) {
      return null;
    }
    // Must be edge-level: "oute" or "ine"
    if (!"oute".equals(prevDirection) && !"ine".equals(prevDirection)) {
      return null;
    }
    var prevEdgeClass = getEdgeClassName(edgePrev);
    if (prevEdgeClass == null) {
      return null;
    }

    // Extract aliases. The source alias is the source of edge_j-1 (outE),
    // not edge_j (inV). The intermediate alias is the target of edge_j-1.
    var sourceAlias = edgePrev.out
        ? edgePrev.edge.out.alias : edgePrev.edge.in.alias;
    var intermediateAlias = edgePrev.out
        ? edgePrev.edge.in.alias : edgePrev.edge.out.alias;

    // Map oute→out, ine→in for the reverse link bag direction
    var direction = prevDirection.startsWith("out") ? "out" : "in";

    // The intermediate edge may have a WHERE filter. Correctness requires
    // that every edge added to the hash table actually passes the whole
    // WHERE clause — the consumed predecessor's MatchStep, which would
    // normally evaluate it, is skipped in the collapsed plan.
    //
    // Strategy (both optional, both applied in BackRefHashJoinStep):
    //   1. indexFilter — RidSet intersection, rejects non-candidate edges
    //      without loading them. Partial cover is fine here: its only role
    //      is pre-filtering for performance.
    //   2. edgeFilter — the complete WHERE clause, re-evaluated on every
    //      loaded edge. Authoritative correctness check; catches any terms
    //      the index didn't cover.
    //
    // Rejection case kept at plan time:
    //   * Filter references $matched / $currentMatch — build phase has no
    //     per-row scope for those variables, so runtime evaluation would
    //     see stale values. Safer to fall back to the standard chain.
    //
    // Multi-branch OR in the intermediate filter is not rejected here: any
    // terms findIndexForFilter cannot cover are re-verified post-load by
    // edgeFilter on every loaded edge, so correctness does not depend on
    // indexability.
    var intermediateFilter = aliasFilters.get(intermediateAlias);
    IndexSearchDescriptor indexFilter = null;
    SQLWhereClause edgeFilter = null;
    if (intermediateFilter != null) {
      var baseExpr = intermediateFilter.getBaseExpression();
      if (baseExpr != null) {
        var refAliases = baseExpr.getMatchPatternInvolvedAliases();
        if (refAliases != null && !refAliases.isEmpty()) {
          // Filter uses $matched.<alias> — cannot evaluate at build phase.
          return null;
        }
      }
      if (refersToCurrentMatch(intermediateFilter)) {
        return null;
      }
      // Deep-copy the filter so the ChainSemiJoin descriptor owns its own
      // AST subtree, independent of aliasFilters. The planner may still
      // rewrite the original (rebindFilters, etc.) and cached plans may
      // re-execute with re-bound parameters — shared references would let
      // an AST rewrite on one side corrupt the other.
      edgeFilter = intermediateFilter.copy();

      var intermediateClass = aliasClasses.get(intermediateAlias);
      if (intermediateClass != null) {
        indexFilter = TraversalPreFilterHelper.findIndexForFilter(
            intermediateFilter, intermediateClass, ctx);
      }
      // indexFilter may be null (no index) or a partial cover — both are OK
      // because edgeFilter will re-verify every edge post-load.
    }

    return new ChainSemiJoin(
        prevEdgeClass, direction, ridExpr,
        sourceAlias, backRefAlias, intermediateAlias,
        targetAliasJ, indexFilter, edgeFilter);
  }

  /**
   * Returns {@code true} if the given WHERE clause references the
   * {@code $currentMatch} identifier anywhere in its AST.
   *
   * <p>Uses a hybrid strategy: recurse structurally through block types
   * ({@link SQLOrBlock}, {@link SQLAndBlock}, {@link SQLNotBlock}) and
   * fall back to a quote-aware token scan for anything else — leaf
   * condition types (binary / NOT IN / BETWEEN / ...) plus opaque
   * wrappers whose inner structure is not publicly accessible. The
   * token scan skips text enclosed in single or double quotes, so a
   * literal like {@code name = '$currentMatchThing'} no longer
   * false-positives and forces the Pattern A / B optimization to bail
   * unnecessarily.
   *
   * <p>The identifier check is anchored at a word boundary (the next
   * character must not be an identifier part), so {@code $currentMatchX}
   * — a hypothetical unrelated variable — does not match either.
   */
  private static boolean refersToCurrentMatch(SQLWhereClause filter) {
    var base = filter.getBaseExpression();
    return base != null && refersToCurrentMatch(base);
  }

  private static boolean refersToCurrentMatch(SQLBooleanExpression expr) {
    if (expr instanceof SQLOrBlock or) {
      var subs = or.getSubBlocks();
      if (subs != null) {
        for (var sub : subs) {
          if (refersToCurrentMatch(sub)) {
            return true;
          }
        }
      }
      return false;
    }
    if (expr instanceof SQLAndBlock and) {
      var subs = and.getSubBlocks();
      if (subs != null) {
        for (var sub : subs) {
          if (refersToCurrentMatch(sub)) {
            return true;
          }
        }
      }
      return false;
    }
    if (expr instanceof SQLNotBlock not) {
      return not.getSub() != null && refersToCurrentMatch(not.getSub());
    }
    return leafContainsCurrentMatchIdentifier(expr.toString());
  }

  /**
   * Returns {@code true} if {@code serialized} contains the token
   * {@code $currentMatch} outside of any single- or double-quoted string
   * literal, at a word boundary. The serialized form of a leaf boolean
   * expression is re-entrant (its toString is a valid YQL fragment), so
   * string literals use standard SQL quoting and every {@code $}-prefixed
   * identifier is emitted literally.
   */
  private static boolean leafContainsCurrentMatchIdentifier(String serialized) {
    if (serialized == null) {
      return false;
    }
    var len = serialized.length();
    var token = "$currentMatch";
    var inSingle = false;
    var inDouble = false;
    for (var i = 0; i < len; i++) {
      var c = serialized.charAt(i);
      if (inSingle) {
        if (c == '\\' && i + 1 < len) {
          i++;
        } else if (c == '\'') {
          inSingle = false;
        }
        continue;
      }
      if (inDouble) {
        if (c == '\\' && i + 1 < len) {
          i++;
        } else if (c == '"') {
          inDouble = false;
        }
        continue;
      }
      if (c == '\'') {
        inSingle = true;
        continue;
      }
      if (c == '"') {
        inDouble = true;
        continue;
      }
      if (c == '$' && serialized.regionMatches(i, token, 0, token.length())) {
        var end = i + token.length();
        if (end >= len || !isIdentifierPart(serialized.charAt(end))) {
          return true;
        }
        i = end - 1;
      }
    }
    return false;
  }

  private static boolean isIdentifierPart(char c) {
    return Character.isLetterOrDigit(c) || c == '_';
  }

  /**
   * Result of splitting a Pattern A target WHERE into the hash-joinable
   * {@code @rid} equality and everything else.
   *
   * @param safe     {@code true} when Pattern A may fire — either the target
   *                 has no residual, or the residual has been cleanly
   *                 extracted and does not reference any build-phase-unsafe
   *                 variables
   * @param residual the residual to re-evaluate on the loaded target entity,
   *                 or {@code null} when there is nothing to re-evaluate
   */
  record ResidualExtraction(boolean safe, @Nullable SQLWhereClause residual) {
  }

  private static final ResidualExtraction RESIDUAL_SAFE_NONE =
      new ResidualExtraction(true, null);
  private static final ResidualExtraction RESIDUAL_UNSAFE =
      new ResidualExtraction(false, null);

  /**
   * Extracts the non-{@code @rid} residual of a Pattern A target WHERE clause.
   * Returns {@link #RESIDUAL_UNSAFE} when the clause cannot be flattened to
   * a single conjunction (e.g. multi-branch OR) or when the residual
   * references {@code $matched}/{@code $currentMatch} — those variables have
   * no defined value at hash-build time, so evaluating them per loaded
   * target would read stale context state.
   *
   * <p>Handles the MATCH planner's typical double-wrapping
   * ({@code AND[OR[AND[@rid, ...]]]}) by flattening all transparent
   * single-element OR/AND wrappers before locating the {@code @rid} term,
   * matching the recursive behavior of {@link SQLWhereClause#findRidEquality}.
   */
  private static ResidualExtraction extractTargetResidual(
      @Nullable SQLWhereClause targetFilter) {
    if (targetFilter == null) {
      return RESIDUAL_SAFE_NONE;
    }
    var base = targetFilter.getBaseExpression();
    if (base == null) {
      return RESIDUAL_SAFE_NONE;
    }
    var atoms = new ArrayList<SQLBooleanExpression>();
    if (!flattenConjunction(base, atoms)) {
      return RESIDUAL_UNSAFE;
    }
    var residualAtoms = new ArrayList<SQLBooleanExpression>(atoms.size());
    var foundRid = false;
    for (var atom : atoms) {
      if (!foundRid && isRidEqualityAtom(atom)) {
        foundRid = true;
      } else {
        residualAtoms.add(atom);
      }
    }
    if (!foundRid) {
      return RESIDUAL_UNSAFE;
    }
    if (residualAtoms.isEmpty()) {
      return RESIDUAL_SAFE_NONE;
    }
    // Deep-copy each atom so the residual owns its own AST subtree, independent
    // of the original targetFilter. The original stays in place for other
    // planner passes (rebindFilters, index detection) and the residual is
    // stashed on the SingleEdgeSemiJoin descriptor, which may be re-used when
    // a cached plan re-executes with re-bound parameters — shared references
    // would let an AST rewrite on one side corrupt the other. Mirrors the
    // policy applied in SQLWhereClause.buildWhereWithoutTerm.
    var newAnd = new SQLAndBlock(-1);
    for (var atom : residualAtoms) {
      newAnd.getSubBlocks().add(atom.copy());
    }
    var newOr = new SQLOrBlock(-1);
    newOr.getSubBlocks().add(newAnd);
    var residual = new SQLWhereClause(-1);
    residual.setBaseExpression(newOr);

    var refs = newAnd.getMatchPatternInvolvedAliases();
    if (refs != null && !refs.isEmpty()) {
      return RESIDUAL_UNSAFE;
    }
    if (refersToCurrentMatch(residual)) {
      return RESIDUAL_UNSAFE;
    }
    return new ResidualExtraction(true, residual);
  }

  /**
   * Flattens a conjunction expression, peeling single-element OR/AND
   * wrappers recursively and collecting leaf boolean terms into
   * {@code atoms}. Returns {@code false} when a multi-branch OR is
   * encountered (not a pure conjunction) or the expression is otherwise
   * not flattenable.
   */
  private static boolean flattenConjunction(
      SQLBooleanExpression expr, List<SQLBooleanExpression> atoms) {
    if (expr instanceof SQLOrBlock or) {
      if (or.getSubBlocks().size() != 1) {
        return false;
      }
      return flattenConjunction(or.getSubBlocks().getFirst(), atoms);
    }
    if (expr instanceof SQLAndBlock and) {
      for (var sub : and.getSubBlocks()) {
        if (!flattenConjunction(sub, atoms)) {
          return false;
        }
      }
      return true;
    }
    atoms.add(expr);
    return true;
  }

  /**
   * Returns {@code true} if the given already-flattened atomic term is a
   * {@code @rid = <expr>} equality. Wraps the atom in a single-term WHERE
   * clause and delegates to {@link SQLWhereClause#findRidEquality} to avoid
   * duplicating the RID-matching logic.
   */
  private static boolean isRidEqualityAtom(SQLBooleanExpression atom) {
    var tmpAnd = new SQLAndBlock(-1);
    tmpAnd.getSubBlocks().add(atom);
    var tmpWhere = new SQLWhereClause(-1);
    tmpWhere.setBaseExpression(tmpAnd);
    return tmpWhere.findRidEquality() != null;
  }

  /**
   * Attempts to detect and attach a Pattern B (ChainSemiJoin) descriptor on
   * {@code edgeJ}. If detection succeeds, the predecessor edge is marked as
   * consumed so {@code addStepsFor()} skips it.
   */
  private void tryAttachChainSemiJoin(
      List<EdgeTraversal> schedule,
      int j,
      EdgeTraversal edgeJ,
      List<String> involvedAliases,
      SQLExpression ridExpr,
      String targetAliasJ,
      Set<String> boundAliases,
      CommandContext ctx) {
    var chainDesc = detectChainSemiJoin(
        schedule, j, involvedAliases, ridExpr, targetAliasJ,
        boundAliases, ctx);
    if (chainDesc != null) {
      var consumed = schedule.get(j - 1);
      edgeJ.setSemiJoinDescriptor(chainDesc);
      consumed.setConsumed(true);
      edgeJ.setConsumedPredecessor(consumed);
      logger.debug(
          "MATCH pre-filter: ChainSemiJoin on edge[{},{}] "
              + "({}({}) chain semi-join via $matched.{})",
          j - 1, j, chainDesc.direction(), chainDesc.edgeClass(),
          chainDesc.backRefAlias());
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
   * Detects whether an optional edge with a {@code $matched.X.@rid} correlation
   * can be replaced with a correlated hash lookup. The pattern is:
   * {@code .out('LABEL'){where: (@rid = $matched.ALIAS.@rid), optional: true}}
   *
   * @return descriptor or null if the pattern is not detected
   */
  record CorrelatedOptionalDesc(
      String correlatedAlias, String probeAlias, String targetAlias,
      String edgeLabel, boolean edgeOut) {
  }

  @Nullable private CorrelatedOptionalDesc detectCorrelatedOptionalJoin(EdgeTraversal edge) {
    // Must be optional
    if (!edge.edge.in.isOptionalNode()) {
      return null;
    }

    var filter = edge.edge.item.getFilter();
    if (filter == null) {
      return null;
    }

    // No WHILE or maxDepth
    if (filter.getWhileCondition() != null || filter.getMaxDepth() != null) {
      return null;
    }

    // Must have a WHERE filter
    var whereClause = filter.getFilter();
    if (whereClause == null) {
      return null;
    }

    // The WHERE filter must be a simple RID correlation: @rid = $matched.X.@rid
    // Use regex to extract the correlated alias robustly (handles whitespace,
    // both operand orders, and rejects complex multi-condition filters).
    var filterStr = whereClause.toString();
    var matcher = MATCHED_RID_PATTERN.matcher(filterStr);
    if (!matcher.find()) {
      return null;
    }
    var correlatedAlias = matcher.group(1);

    // Reject if there are multiple $matched references (complex filter)
    if (matcher.find()) {
      return null;
    }

    // Verify the filter also references @rid on the other side (simple equality)
    // Count @rid occurrences — must be exactly 2 (one for each side of =)
    long ridCount = RID_PATTERN.matcher(filterStr).results().count();
    if (ridCount != 2) {
      return null;
    }

    // The correlated alias must already be visited (it's in aliasClasses or known)
    if (aliasClasses.get(correlatedAlias) == null
        && aliasRids.get(correlatedAlias) == null
        && !pattern.aliasToNode.containsKey(correlatedAlias)) {
      return null;
    }

    // Edge must be a simple directional method
    var edgeLabel = getEdgeClassName(edge);
    if (edgeLabel == null) {
      return null;
    }
    var direction = getEdgeDirection(edge);
    if (direction == null || (!"out".equals(direction) && !"in".equals(direction))) {
      return null;
    }

    var probeAlias = edge.out ? edge.edge.out.alias : edge.edge.in.alias;
    var targetAlias = edge.out ? edge.edge.in.alias : edge.edge.out.alias;

    return new CorrelatedOptionalDesc(
        correlatedAlias, probeAlias, targetAlias, edgeLabel, "out".equals(direction));
  }

  /**
   * Detects whether a WHILE edge can be replaced with an inverted reachability
   * hash filter. The pattern is: unconditional WHILE ({@code while: (true)})
   * with a simple WHERE filter, no $matched dependency, no depth/path alias,
   * and a simple directional edge method (out/in with single label).
   *
   * @return {@code true} if the edge qualifies for inverted-WHILE optimization
   */
  private boolean canUseInvertedWhileJoin(EdgeTraversal edge) {
    var filter = edge.edge.item.getFilter();
    if (filter == null) {
      return false;
    }

    // Must have a WHILE condition
    var whileCondition = filter.getWhileCondition();
    if (whileCondition == null) {
      return false;
    }

    // WHILE must be unconditional: (true). Check via the base expression.
    var baseExpr = whileCondition.getBaseExpression();
    if (baseExpr == null) {
      return false;
    }
    // The WHILE(true) condition parses as a SQLBooleanExpression wrapping "true"
    var baseStr = baseExpr.toString().strip().toLowerCase(Locale.ROOT);
    if (!baseStr.equals("true") && !baseStr.equals("(true)")) {
      return false;
    }

    // Must have a WHERE filter on the target node
    var targetFilter = filter.getFilter();
    if (targetFilter == null) {
      return false;
    }

    // No $matched/$parent dependency
    if (filterDependsOnContext(targetFilter)) {
      return false;
    }

    // No depth or path alias (user doesn't need traversal metadata)
    if (filter.getDepthAlias() != null || filter.getPathAlias() != null) {
      return false;
    }

    // The source alias (probe side) must be known — it's the alias whose RID
    // we probe against the reachable set. The target alias doesn't need a class
    // constraint; we find the anchor vertex via the WHERE filter by scanning the
    // source alias's connected class or the entire edge's target class.
    // For now, we need at least the edge label to determine the traversal.

    // Edge must be a simple directional method (out/in with single label)
    var edgeLabel = getEdgeClassName(edge);
    if (edgeLabel == null) {
      return false;
    }

    var direction = getEdgeDirection(edge);
    return "out".equals(direction) || "in".equals(direction);
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
      // Strip surrounding quotes if present (defensive — execute() typically
      // returns the unquoted string, but some AST paths may retain quotes)
      var label = s;
      if (label.length() >= 2
          && ((label.charAt(0) == '\'' && label.charAt(label.length() - 1) == '\'')
              || (label.charAt(0) == '"' && label.charAt(label.length() - 1) == '"'))) {
        label = label.substring(1, label.length() - 1);
      }
      // Validate: edge class names must be valid identifiers. Reject anything
      // containing characters that could break SQL string interpolation (e.g.,
      // single quotes from escaped literals in the MATCH parser).
      if (!VALID_EDGE_LABEL.matcher(label).matches()) {
        return null;
      }
      return label;
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
   * Returns the lowercased method name for the given edge traversal
   * (e.g. {@code "out"}, {@code "oute"}, {@code "inv"}).
   */
  @Nullable static String getMethodName(EdgeTraversal et) {
    var method = et.edge.item.getMethod();
    if (method == null) {
      return null;
    }
    var name = method.getMethodNameString();
    return name != null ? name.toLowerCase(Locale.ROOT) : null;
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
      @Nullable IndexOrderedPlanner.IndexOrderedCandidate candidate,
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
    // Skip edges consumed by a ChainSemiJoin on the next edge — the
    // BackRefHashJoinStep on the next edge covers both.
    if (edge.isConsumed()) {
      return;
    }
    if (edge.edge.in.isOptionalNode()) {
      // Check if this optional edge can be replaced with a correlated hash lookup
      var correlatedDesc = detectCorrelatedOptionalJoin(edge);
      if (correlatedDesc != null) {
        // Correlated optional hash join: pre-materialize neighbor set, probe per row
        plan.chain(new CorrelatedOptionalHashJoinStep(
            context,
            correlatedDesc.correlatedAlias(),
            correlatedDesc.probeAlias(),
            correlatedDesc.targetAlias(),
            correlatedDesc.edgeLabel(),
            correlatedDesc.edgeOut(),
            profilingEnabled));
        // Still set foundOptional so RemoveEmptyOptionalsStep is present
        // (no-op for our null values, but needed for other optional nodes)
        foundOptional = true;
      } else {
        foundOptional = true;
        plan.chain(new OptionalMatchStep(context, edge, profilingEnabled));
      }
    } else if (canUseInvertedWhileJoin(edge)) {
      // Replace WHILE recursion with pre-materialized reachability hash filter
      var targetAlias = edge.out ? edge.edge.in.alias : edge.edge.out.alias;
      var probeAlias = edge.out ? edge.edge.out.alias : edge.edge.in.alias;
      var rawFilter = edge.edge.item.getFilter().getFilter();
      var targetFilter = rawFilter != null ? rawFilter.copy() : null;
      var edgeLabel = getEdgeClassName(edge);
      var edgeDirection = "out".equals(getEdgeDirection(edge));
      // Anchor class from target alias — the WHERE filter applies to the target,
      // not the probe. If the alias has no explicit class (common for WHILE
      // targets where class inference is skipped), infer from edge LINK schema.
      // For out('IS_SUBCLASS_OF') this infers TagClass from the edge's "in" LINK.
      var anchorClass = aliasClasses.get(targetAlias);
      if (anchorClass == null) {
        anchorClass = inferClassFromEdgeSchema(
            edge.edge.item.getMethod(), null, context);
      }
      if (anchorClass != null) {
        plan.chain(new InvertedWhileHashJoinStep(
            context, anchorClass, targetFilter, edgeLabel, edgeDirection,
            probeAlias, targetAlias, edge, profilingEnabled));
      } else {
        // Cannot determine anchor class — fall back to standard WHILE traversal
        // to avoid catastrophic full V scan
        plan.chain(new MatchStep(context, edge, profilingEnabled));
      }
    } else if (edge.getSemiJoinDescriptor() instanceof AntiSemiJoin) {
      // Pattern D: normal traversal + anti-join filter. The NOT IN condition
      // was stripped from the WHERE clause at plan time so the MatchStep
      // traverses without it. The BackRefHashJoinStep filters results against
      // the exclusion set; on build failure it evaluates the stored NOT IN
      // condition per row as a correctness fallback.
      plan.chain(new MatchStep(context, edge, profilingEnabled));
      plan.chain(new BackRefHashJoinStep(
          context, edge.getSemiJoinDescriptor(), null, null, profilingEnabled));
    } else if (edge.getSemiJoinDescriptor() != null) {
      // Back-reference semi-join (Pattern A/B): replace per-row link bag
      // traversal with a one-time hash table build + O(1) probe. The
      // EdgeTraversal is passed for runtime fallback if the build fails.
      // For Pattern B (ChainSemiJoin), the consumed predecessor edge is
      // also passed so the fallback can traverse both edges sequentially.
      plan.chain(new BackRefHashJoinStep(
          context, edge.getSemiJoinDescriptor(), edge,
          edge.getConsumedPredecessor(), profilingEnabled));
    } else if (candidate != null
        && IndexOrderedPlanner.isIndexOrderedEdge(candidate, edge)) {
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
          candidate.downstreamEdgeCount(),
          profilingEnabled));
    } else {
      plan.chain(new MatchStep(context, edge, profilingEnabled));
    }
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
    // Collect aliases that are directly part of a while-condition's recursive
    // zone (origin + while-item).  Class inference on these specific aliases
    // must be skipped — inferred classes change cost estimates which can
    // reorder the schedule, causing while/where recursive steps to be
    // traversed in the wrong direction.  Non-recursive aliases in the same
    // expression (downstream of the while) are safe to infer.
    var whileAliases = collectAliasesFromWhilePatterns(this.matchExpressions);
    var inferredAliases = new HashSet<String>();
    for (var expr : this.matchExpressions) {
      addAliases(expr, aliasFilters, aliasClasses, aliasCollections, aliasRids,
          ctx, whileAliases, inferredAliases);
    }

    this.aliasFilters = aliasFilters;
    this.aliasClasses = aliasClasses;
    this.aliasRids = aliasRids;
    this.inferredWhileExprAliases = inferredAliases;

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
      for (var item : expr.getItems()) {
        if (item.getFilter() != null
            && item.getFilter().getWhileCondition() != null) {
          // Only the origin alias and the while-item's own alias are in the
          // recursive zone.  Downstream items (after the while in the pattern
          // chain) are not recursive and can safely have class inference —
          // their inferred classes do not affect while-traversal direction.
          if (expr.getOrigin() != null && expr.getOrigin().getAlias() != null) {
            result.add(expr.getOrigin().getAlias());
          }
          if (item.getFilter().getAlias() != null) {
            result.add(item.getFilter().getAlias());
          }
        }
      }
    }
    return result;
  }

  /**
   * Extracts alias metadata (filters, class, collection, RID) from a single match
   * expression and merges them into the accumulation maps.
   *
   * <p>Tracks a {@code currentEdgeClass} state across items: {@code outE('X')}/{@code inE('X')}
   * set it to {@code X}; {@code inV()}/{@code outV()} consume it for vertex class inference
   * then reset it; all other methods reset it to {@code null}.
   */
  // Visible for testing
  static void addAliases(
      SQLMatchExpression expr,
      Map<String, SQLWhereClause> aliasFilters,
      Map<String, String> aliasClasses,
      Map<String, String> aliasCollections,
      Map<String, SQLRid> aliasRids,
      CommandContext context,
      Set<String> whileAliases,
      Set<String> inferredWhileExprAliases) {
    addAliases(expr.getOrigin(), aliasFilters, aliasClasses, aliasCollections, aliasRids, context);

    // Track the edge class set by the most recent outE/inE item, so that a
    // following inV/outV can look up the linked vertex class from the edge schema.
    // Reset after inV/outV consumes it, or when a non-edge-method item is seen.
    @Nullable String currentEdgeClass = null;

    for (var item : expr.getItems()) {
      if (item.getFilter() != null) {
        addAliases(item.getFilter(), aliasFilters, aliasClasses, aliasCollections, aliasRids,
            context);

        // Skip class inference only for aliases in the recursive zone
        // (origin + while-item).  Downstream aliases are safe to infer.
        var alias = item.getFilter().getAlias();
        boolean skipThisItem = alias != null && whileAliases.contains(alias);

        if (!skipThisItem) {
          // Determine the method name for edge-class state tracking.
          var method = item.getMethod();
          var methodName = method != null ? method.getMethodNameString() : null;
          var methodLower = methodName != null
              ? methodName.toLowerCase(Locale.ROOT) : null;

          // Update currentEdgeClass state based on the method type.
          // inV/outV is deferred: it consumes currentEdgeClass after inference below.
          var isInVOrOutV = "inv".equals(methodLower) || "outv".equals(methodLower);
          if ("oute".equals(methodLower) || "ine".equals(methodLower)) {
            currentEdgeClass = extractEdgeClassName(method);
          } else if (!isInVOrOutV) {
            // Any other method (out, in, both, bothE, etc.) resets the state.
            currentEdgeClass = null;
          }

          // Infer target class from edge LINK schema when no explicit class
          // is set. Handles both vertex-to-vertex traversals (out/in) and
          // edge-method traversals (outE/inE/inV/outV).
          if (alias != null && !aliasClasses.containsKey(alias)) {
            var inferred = inferClassFromEdgeSchema(method, currentEdgeClass, context);
            if (inferred != null) {
              aliasClasses.put(alias, inferred);
              inferredWhileExprAliases.add(alias);
              logger.debug(
                  "MATCH class inference: alias '{}' -> class '{}' "
                      + "(from edge LINK schema)",
                  alias, inferred);
            }
          }

          // Reset currentEdgeClass after inV/outV consumes it.
          if (isInVOrOutV) {
            currentEdgeClass = null;
          }
        } else {
          // While-alias: reset edge class state since we skip inference.
          currentEdgeClass = null;
        }
      }
    }
  }

  /**
   * Infers the alias class from the edge schema LINK declarations.
   *
   * <p>Handles six method types:
   * <ul>
   *   <li>{@code out('X')} / {@code in('X')}: target is the opposite endpoint
   *       of edge class X (vertex class)</li>
   *   <li>{@code outE('X')} / {@code inE('X')}: alias class is X itself
   *       (the edge class)</li>
   *   <li>{@code inV()} / {@code outV()}: alias class is the linked vertex
   *       class from the preceding edge's LINK schema ({@code currentEdgeClass})</li>
   * </ul>
   *
   * @param currentEdgeClass the edge class set by a preceding {@code outE}/{@code inE},
   *     or {@code null} if none
   * @return the inferred class name, or {@code null} if it cannot be inferred
   */
  @Nullable static String inferClassFromEdgeSchema(
      @Nullable SQLMethodCall method, @Nullable String currentEdgeClass,
      CommandContext context) {
    if (method == null) {
      return null;
    }
    var dirName = method.getMethodNameString();
    if (dirName == null) {
      return null;
    }
    dirName = dirName.toLowerCase(Locale.ROOT);

    // outE('X') / inE('X'): the edge class itself is the alias class
    if ("oute".equals(dirName) || "ine".equals(dirName)) {
      return extractEdgeClassName(method);
    }

    // inV() / outV(): look up the linked vertex class from the preceding edge.
    // inV() reads the "in" property; outV() reads the "out" property.
    if ("inv".equals(dirName) || "outv".equals(dirName)) {
      if (currentEdgeClass == null) {
        return null;
      }
      var prop = "inv".equals(dirName) ? "in" : "out";
      return lookupLinkedVertexClass(currentEdgeClass, prop, context);
    }

    // out('X') / in('X'): infer the target vertex class from the edge LINK schema
    if (!"in".equals(dirName) && !"out".equals(dirName)) {
      return null;
    }

    var edgeClassName = extractEdgeClassName(method);
    if (edgeClassName == null) {
      return null;
    }

    // out('X') targets the "in" side; in('X') targets the "out" side
    var targetPropName = "out".equals(dirName) ? "in" : "out";
    return lookupLinkedVertexClass(edgeClassName, targetPropName, context);
  }

  /**
   * Looks up the linked vertex class from an edge class's LINK property.
   *
   * @param edgeClassName the edge class to look up
   * @param propName the property name to read — must be {@code "in"} or {@code "out"}
   * @return the linked class name, or {@code null} if not found
   */
  @Nullable private static String lookupLinkedVertexClass(
      String edgeClassName, String propName, CommandContext context) {
    var session = context.getDatabaseSession();
    var schema = session.getMetadata().getImmutableSchemaSnapshot();
    var edgeClass = schema.getClassInternal(edgeClassName);
    if (edgeClass == null) {
      return null;
    }
    var prop = edgeClass.getPropertyInternal(propName);
    if (prop == null || prop.getLinkedClass() == null) {
      return null;
    }
    assert prop.getLinkedClass().getName() != null
        && !prop.getLinkedClass().getName().isEmpty()
        : "lookupLinkedVertexClass: linked class has null/empty name for edge "
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
   * Thin adapter that constructs {@link IndexOrderedPlanner} from this planner's
   * state and runs detection. Kept as a separate method so the planner
   * construction's bytecode lives here (cold path) and does not bloat
   * {@link #createExecutionPlan}, which HotSpot benefits from keeping small.
   */
  @Nullable private IndexOrderedPlanner.IndexOrderedCandidate detectIndexOrderedCandidate(
      List<EdgeTraversal> sortedEdges,
      CommandContext context,
      Map<String, Long> estimatedRootEntries) {
    return new IndexOrderedPlanner(
        pattern, aliasClasses, aliasFilters, aliasRids,
        orderBy, skip, limit, returnItems, returnAliases,
        returnElements, returnPaths, returnPatterns, returnPathElements)
        .detect(sortedEdges, context, estimatedRootEntries);
  }

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
