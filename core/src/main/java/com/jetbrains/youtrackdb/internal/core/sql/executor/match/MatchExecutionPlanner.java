package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.internal.common.util.PairLongObject;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.sql.executor.AbstractExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.CartesianProductStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.DistinctExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.EmptyStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ExecutionStepInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.LimitExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.OrderByStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ProjectionCalculationStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.QueryPlanningInfo;
import com.jetbrains.youtrackdb.internal.core.sql.executor.SelectExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.SelectExecutionPlanner;
import com.jetbrains.youtrackdb.internal.core.sql.executor.SkipExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.UnwindStep;
import com.jetbrains.youtrackdb.internal.core.sql.parser.Pattern;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLAndBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLFromClause;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLFromItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLGroupBy;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLLimit;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchFilter;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMultiMatchPathItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLNestedProjection;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrderBy;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLProjection;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLProjectionItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLRid;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSelectStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSkip;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLUnwind;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Collections;
import java.util.HashMap;
import java.util.Objects;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

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

  /**
   * Prefix prepended to auto-generated aliases for pattern nodes that the user did not
   * name explicitly. Properties whose name starts with this prefix are treated as
   * **internal** and are stripped from the final result set by
   * {@link ReturnMatchPatternsStep} and {@link ReturnMatchElementsStep}.
   */
  static final String DEFAULT_ALIAS_PREFIX = "$YOUTRACKDB_DEFAULT_ALIAS_";

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
   * Creates a planner by **deep-copying** every mutable component from the parsed
   * statement so that planning can freely mutate state (e.g. assign default aliases,
   * merge filters) without affecting the original AST.
   *
   * @param stm the parsed `MATCH` statement AST node
   */
  public MatchExecutionPlanner(SQLMatchStatement stm) {
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
   * @return the assembled execution plan, ready to be {@linkplain InternalExecutionPlan#start()
   *         started}
   */
  public InternalExecutionPlan createExecutionPlan(
      CommandContext context, boolean enableProfiling) {

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

    // Phase 4: Prefetch small alias sets into the context variable map (see class Javadoc)
    addPrefetchSteps(result, aliasesToPrefetch, context, enableProfiling);

    // Phase 5: Topological scheduling + step generation for each connected component
    if (subPatterns.size() > 1) {
      // Multiple disjoint sub-patterns → Cartesian product of their independent results
      var step = new CartesianProductStep(context, enableProfiling);
      for (var subPattern : subPatterns) {
        step.addSubPlan(
            createPlanForPattern(
                subPattern, context, estimatedRootEntries, aliasesToPrefetch, enableProfiling));
      }
      result.chain(step);
    } else {
      // Single connected pattern → inline the steps directly into the main plan
      var plan =
          createPlanForPattern(
              pattern, context, estimatedRootEntries, aliasesToPrefetch, enableProfiling);
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

      if (this.orderBy != null) {
        result.chain(new OrderByStep(orderBy, context, -1, enableProfiling));
      }

      if (this.unwind != null) {
        result.chain(new UnwindStep(unwind, context, enableProfiling));
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

      SelectExecutionPlanner.optimizeQuery(info, context);
      SelectExecutionPlanner.handleProjectionsBlock(result, info, context, enableProfiling);
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
      boolean profilingEnabled) {
    var plan = new SelectExecutionPlan(context);
    var sortedEdges = getTopologicalSortedSchedule(estimatedRootEntries, pattern,
        context.getDatabaseSession());

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
        addStepsFor(plan, edge, context, first, profilingEnabled);
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
      Map<String, Long> estimatedRootEntries, Pattern pattern, DatabaseSessionEmbedded session) {
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
          startingNode, visitedNodes, visitedEdges, remainingDependencies, resultingSchedule);
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
      List<EdgeTraversal> resultingSchedule) {
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

    Map<PatternEdge, Boolean> edges = new LinkedHashMap<>();
    for (var outEdge : startNode.out) {
      edges.put(outEdge, true);
    }
    for (var inEdge : startNode.in) {
      if (inEdge.item.isBidirectional()) {
        edges.put(inEdge, false);
      }
    }

    for (var edgeData : edges.entrySet()) {
      var edge = edgeData.getKey();
      boolean isOutbound = edgeData.getValue();
      var neighboringNode = isOutbound ? edge.in : edge.out;

      if (!remainingDependencies.get(neighboringNode.alias).isEmpty()) {
        // Unsatisfied dependencies, ignore this neighboring node.
        continue;
      }

      if (visitedNodes.contains(neighboringNode)) {
        if (!visitedEdges.contains(edge)) {
          // If we are executing in this block, we are in the following situation:
          // - the startNode has not been visited yet;
          // - it has a neighboringNode that has already been visited;
          // - the edge between the startNode and the neighboringNode has not been scheduled yet.
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
        }
      } else if (!startNode.optional || isOptionalChain(startNode, edge, neighboringNode)) {
        // If the neighboring node wasn't visited, we don't expand the optional node into it, hence
        // the above check.
        // Instead, we'll allow the neighboring node to add the edge we failed to visit, via the
        // above block.
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
            neighboringNode, visitedNodes, visitedEdges, remainingDependencies, resultingSchedule);
      }
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
    for (var expr : this.matchExpressions) {
      addAliases(expr, aliasFilters, aliasClasses, aliasCollections, aliasRids, ctx);
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
   * Extracts alias metadata (filters, class, collection, RID) from a single match
   * expression and merges them into the accumulation maps.
   */
  private static void addAliases(
      SQLMatchExpression expr,
      Map<String, SQLWhereClause> aliasFilters,
      Map<String, String> aliasClasses,
      Map<String, String> aliasCollections,
      Map<String, SQLRid> aliasRids,
      CommandContext context) {
    addAliases(expr.getOrigin(), aliasFilters, aliasClasses, aliasCollections, aliasRids, context);
    for (var item : expr.getItems()) {
      if (item.getFilter() != null) {
        addAliases(item.getFilter(), aliasFilters, aliasClasses, aliasCollections, aliasRids, context);
      }
    }
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
  @Nullable
  private static String getLowerSubclass(
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
  private static Map<String, Long> estimateRootEntries(
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
      long upperBound;
      var filter = aliasFilters.get(alias);
      if (filter != null) {
        upperBound = filter.estimate(oClass, THRESHOLD, ctx);
      } else {
        upperBound = oClass.count(ctx.getDatabaseSession());
      }
      result.put(alias, upperBound);
    }

    return result;
  }
}
