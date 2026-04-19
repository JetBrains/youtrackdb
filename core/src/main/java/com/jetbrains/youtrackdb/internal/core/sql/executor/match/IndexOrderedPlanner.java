package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.parser.Pattern;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLAndBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBaseExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBooleanExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLEqualsOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLLimit;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLNotBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrderBy;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrderByItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLRid;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSkip;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Plan-time detection of the index-ordered MATCH traversal opportunity.
 * <p>
 * Extracted from {@link MatchExecutionPlanner} to keep that class small enough
 * for HotSpot to inline its hot methods (notably {@code createExecutionPlan}).
 * All logic here runs once per query at planning time and is cold-path for
 * benchmarks — moving it out of the planner class restores the JIT inlining
 * budget for the planner's hot path.
 */
final class IndexOrderedPlanner {

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
   * Captures a detected opportunity for index-ordered MATCH traversal.
   * When present, the edge identified by {@code edgeTraversal} will be executed
   * via {@link IndexOrderedEdgeStep} instead of the standard {@link MatchStep},
   * and the ORDER BY step will be suppressed because the index scan already
   * produces results in the requested order.
   */
  record IndexOrderedCandidate(
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
      boolean isEdgeTraversal,
      int downstreamEdgeCount) {
  }

  // Snapshot of planner state needed for detection. Held as fields rather than
  // passed to every method to keep signatures tight and avoid parameter-heap
  // pressure in the hot (well, cold) planning path.
  @Nullable private final Pattern pattern;
  private final Map<String, String> aliasClasses;
  private final Map<String, SQLWhereClause> aliasFilters;
  private final Map<String, SQLRid> aliasRids;
  @Nullable private final SQLOrderBy orderBy;
  @Nullable private final SQLSkip skip;
  @Nullable private final SQLLimit limit;
  @Nullable private final List<SQLExpression> returnItems;
  @Nullable private final List<SQLIdentifier> returnAliases;
  private final boolean returnElements;
  private final boolean returnPaths;
  private final boolean returnPatterns;
  private final boolean returnPathElements;

  IndexOrderedPlanner(
      @Nullable Pattern pattern,
      Map<String, String> aliasClasses,
      Map<String, SQLWhereClause> aliasFilters,
      Map<String, SQLRid> aliasRids,
      @Nullable SQLOrderBy orderBy,
      @Nullable SQLSkip skip,
      @Nullable SQLLimit limit,
      @Nullable List<SQLExpression> returnItems,
      @Nullable List<SQLIdentifier> returnAliases,
      boolean returnElements,
      boolean returnPaths,
      boolean returnPatterns,
      boolean returnPathElements) {
    this.pattern = pattern;
    this.aliasClasses = aliasClasses;
    this.aliasFilters = aliasFilters;
    this.aliasRids = aliasRids;
    this.orderBy = orderBy;
    this.skip = skip;
    this.limit = limit;
    this.returnItems = returnItems;
    this.returnAliases = returnAliases;
    this.returnElements = returnElements;
    this.returnPaths = returnPaths;
    this.returnPatterns = returnPatterns;
    this.returnPathElements = returnPathElements;
  }

  /**
   * Checks whether the given edge matches the index-ordered candidate by comparing
   * the underlying PatternEdge identity. The candidate was detected on a probing
   * schedule (separate EdgeTraversal instances), so we compare the wrapped
   * PatternEdge objects which are shared across schedule computations.
   */
  static boolean isIndexOrderedEdge(
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
  @Nullable IndexOrderedCandidate detect(
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
    //     For vertex traversal (.in/.out): reverse field on target vertex is the
    //       opposite direction + edgeClassName (e.g., out_LIKES → in_LIKES).
    //     For edge traversal (.inE/.outE): reverse field on the edge record is
    //       the SAME direction as linkBagDirection — because out_X on a vertex
    //       stores edges whose "out" field points back to that vertex.
    var reverseDirection = "in".equals(linkBagDirection) ? "out" : "in";
    var reverseFieldName = isEdgeTraversal
        ? linkBagDirection
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
    // Single-source is safe when the source is guaranteed to produce exactly 1 row:
    //   (a) explicit RID constraint ({rid: #X:Y}), or
    //   (b) WHERE equality on a UNIQUE-index field (e.g., id = :personId).
    // With class + non-unique WHERE, the estimator may undercount
    // (e.g., LIKE matching multiple rows estimated as 1). In single-source mode,
    // flatMap concatenates per-source results — but OrderByStep is suppressed,
    // so the output would be incorrectly ordered if >1 source rows arrive.
    // Multi-source mode always produces globally sorted results, so it is the
    // safe default whenever the source is not pinned to a single row.
    var sourceHasRidConstraint = aliasRids.get(sourceAlias) != null
        || hasSingleRowGuarantee(
            sourceAlias, aliasClasses, aliasFilters, context);
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

    // 10b. Require LIMIT for single-source and FILTERED multi-source modes.
    // Without LIMIT, all source rows' edges must be scanned regardless of
    // order, so the only saving is sort elision — which for small linkBags
    // is negligible compared to the planner setup + per-source RidSet build
    // + index cursor init overhead (measured ~5-7% CPU regression on IS7
    // where msg is unique-indexed, linkBag ≈ a few replies, no LIMIT).
    // UNFILTERED modes scan the whole index anyway — they stay enabled
    // without LIMIT (see testIndexOrderedMatchNoLimitAllResults,
    // testIndexOrderedMatchUnfilteredBoundNoLimit).
    if (queryLimit < 0
        && (multiSourceMode == null
            || multiSourceMode == MultiSourceMode.FILTERED_BOUND
            || multiSourceMode == MultiSourceMode.FILTERED_UNBOUND)) {
      return null;
    }

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

    // 12. Count downstream edges after the matched edge in the schedule.
    // These edges represent traversal work done PER result row. With index
    // scan + LIMIT, only K rows go through downstream edges; with load-all,
    // all N rows do. This cost difference tips the balance toward index scan
    // when downstream work is significant (e.g., IS2's REPLY_OF chain).
    int downstreamEdgeCount = 0;
    boolean pastMatched = false;
    for (var edge : sortedEdges) {
      if (edge.edge == matchedEdge.edge) {
        pastMatched = true;
        continue;
      }
      if (pastMatched) {
        downstreamEdgeCount++;
      }
    }

    return new IndexOrderedCandidate(
        matchedEdge, sourceAlias, targetAlias, edgeClassName,
        linkBagFieldName, matchedIndex, orderAsc, queryLimit,
        multiSourceMode, reverseFieldName, sourceClassName,
        multiFieldOrderBy, targetFilter, targetClassName, isEdgeTraversal,
        downstreamEdgeCount);
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
    // alias="message" + modifier with suffix="creationDate". Inspect the AST
    // directly to verify it's a simple ".propertyName" (no method calls,
    // arrays, or chaining).
    var modifier = orderItem.getModifier();
    if (modifier != null) {
      var propertyName = modifier.getSimpleSuffixPropertyName();
      if (propertyName != null) {
        return new String[] {orderAlias, propertyName};
      }
      // Complex modifier — cannot resolve
      return null;
    }

    // Case 2: projection alias resolution — inspect the AST directly
    // to extract "alias.property" from the return expression.
    if (returnAliases != null && returnItems != null) {
      for (int i = 0; i < returnAliases.size(); i++) {
        var retAlias = returnAliases.get(i);
        if (retAlias != null && retAlias.getStringValue().equals(orderAlias)) {
          var resolved = resolveSimpleDotExpression(returnItems.get(i));
          if (resolved != null) {
            return resolved;
          }
          break;
        }
      }
    }

    return null;
  }

  /**
   * Inspects an {@link SQLExpression} AST to extract a simple {@code alias.property}
   * pair. Returns a two-element array {@code [alias, property]} if the expression
   * is a simple dot-access ({@code SQLBaseExpression} with a plain identifier and
   * a single-suffix modifier), or {@code null} for anything more complex.
   */
  @Nullable private static String[] resolveSimpleDotExpression(SQLExpression expr) {
    var math = expr.getMathExpression();
    if (!(math instanceof SQLBaseExpression baseExpr)) {
      return null;
    }
    var ident = baseExpr.getIdentifier();
    if (ident == null || ident.getSuffix() == null
        || ident.getSuffix().getIdentifier() == null
        || ident.getLevelZero() != null) {
      return null;
    }
    var mod = baseExpr.getModifier();
    if (mod == null) {
      return null;
    }
    var propertyName = mod.getSimpleSuffixPropertyName();
    if (propertyName == null) {
      return null;
    }
    return new String[] {
        ident.getSuffix().getIdentifier().getStringValue(),
        propertyName
    };
  }

  /**
   * Plan-time cost check for FILTERED modes. Uses estimated cardinality
   * and default fan-out to predict whether the index scan is likely to
   * beat load-and-sort.
   */
  private boolean isFilteredScanLikelyWorthwhile(
      String sourceAlias,
      Index matchedIndex,
      SQLOrderByItem orderItem,
      Map<String, Long> estimatedRootEntries,
      DatabaseSessionEmbedded session,
      CommandContext context) {
    long sourceEstimate =
        estimatedRootEntries.getOrDefault(sourceAlias, MatchExecutionPlanner.THRESHOLD);
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

    // Check RETURN expressions for any upstream alias reference.
    // Uses toString() serialization + prefix matching as a heuristic.
    // False positives are safe (they force the more expensive BOUND mode,
    // not wrong results). Direct AST inspection would be more precise but
    // the RETURN expression AST is complex (supports functions, nested
    // access, arithmetic) — toString() covers all cases with minimal code.
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
   * Checks whether the source alias is guaranteed to produce exactly one row
   * by having a WHERE equality condition on a single-field UNIQUE index.
   * For example, {@code {class: Person, where: (id = :personId)}} with a
   * UNIQUE index on Person.id guarantees one row.
   *
   * <p>This enables single-source index-ordered mode for queries like IS2
   * where the source is identified by a unique key rather than a literal RID.
   */
  private static boolean hasSingleRowGuarantee(
      String alias,
      Map<String, String> aliasClasses,
      Map<String, SQLWhereClause> aliasFilters,
      CommandContext context) {
    var className = aliasClasses.get(alias);
    var filter = aliasFilters.get(alias);
    if (className == null || filter == null) {
      return false;
    }
    var session = context.getDatabaseSession();
    var schema = session.getMetadata().getImmutableSchemaSnapshot();
    var clazz = schema.getClassInternal(className);
    if (clazz == null) {
      return false;
    }

    // Extract equality field names from the WHERE clause.
    // The aliasFilters map stores filters as a SQLWhereClause with an
    // SQLAndBlock base expression containing the individual conditions.
    // Walk the AND block directly to find equality conditions.
    var equalityFields = new HashSet<String>();
    var baseExpr = filter.getBaseExpression();
    if (baseExpr instanceof SQLAndBlock andBlock) {
      for (var sub : andBlock.getSubBlocks()) {
        extractEqualityField(sub, equalityFields);
      }
    } else {
      extractEqualityField(baseExpr, equalityFields);
    }
    if (equalityFields.isEmpty()) {
      return false;
    }

    // Check if any single-field UNIQUE index is fully covered by equality fields.
    for (var idx : clazz.getIndexesInternal()) {
      if (!idx.isUnique()) {
        continue;
      }
      var def = idx.getDefinition();
      if (def == null) {
        continue;
      }
      var props = def.getProperties();
      if (props.isEmpty()) {
        continue;
      }
      if (equalityFields.containsAll(props)) {
        return true;
      }
    }
    return false;
  }

  /**
   * If the expression is a simple {@code field = value} equality (with
   * {@link SQLEqualsOperator}), extracts the field name(s) into the set.
   * Recurses into AND blocks and single-branch OR blocks.
   */
  private static void extractEqualityField(
      SQLBooleanExpression expr, Set<String> fields) {
    if (expr instanceof SQLBinaryCondition cond
        && cond.getOperator() instanceof SQLEqualsOperator) {
      var leftField = extractSimpleFieldName(cond.getLeft());
      if (leftField != null) {
        fields.add(leftField);
      }
      var rightField = extractSimpleFieldName(cond.getRight());
      if (rightField != null) {
        fields.add(rightField);
      }
    } else if (expr instanceof SQLAndBlock andBlock) {
      for (var sub : andBlock.getSubBlocks()) {
        extractEqualityField(sub, fields);
      }
    } else if (expr instanceof SQLOrBlock orBlock) {
      // Single-branch OR is just wrapping — recurse into it
      if (orBlock.getSubBlocks().size() == 1) {
        extractEqualityField(orBlock.getSubBlocks().getFirst(), fields);
      }
    } else if (expr instanceof SQLNotBlock notBlock) {
      // SQLNotBlock with negate=false is just a wrapper — recurse into sub
      if (!notBlock.isNegate()) {
        extractEqualityField(notBlock.getSub(), fields);
      }
      // Actual NOT conditions don't give us equality guarantees
    }
    // For any other expression type (e.g., SQLInCondition, function calls),
    // skip gracefully — no equality field can be extracted.
  }

  /**
   * Extracts a simple field name from an expression like {@code id} or
   * {@code fieldName}. Returns null for anything more complex (functions,
   * dot-access, arithmetic, etc.).
   */
  @Nullable private static String extractSimpleFieldName(SQLExpression expr) {
    var math = expr.getMathExpression();
    if (!(math instanceof SQLBaseExpression baseExpr)) {
      return null;
    }
    // Must have no modifier (no .property, no method call)
    if (baseExpr.getModifier() != null) {
      return null;
    }
    var ident = baseExpr.getIdentifier();
    if (ident == null) {
      return null;
    }
    var suffix = ident.getSuffix();
    if (suffix == null || suffix.getIdentifier() == null) {
      return null;
    }
    // Must not be a special identifier (like @rid, @class, etc.)
    if (ident.getLevelZero() != null) {
      return null;
    }
    return suffix.getIdentifier().getStringValue();
  }
}
