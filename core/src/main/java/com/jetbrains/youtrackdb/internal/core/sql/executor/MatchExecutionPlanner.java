package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.util.PairLongObject;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
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
import java.util.Collections;
import java.util.HashMap;
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
 *
 */
public class MatchExecutionPlanner {

  static final String DEFAULT_ALIAS_PREFIX = "$YOUTRACKDB_DEFAULT_ALIAS_";

  protected List<SQLMatchExpression> matchExpressions;
  protected List<SQLMatchExpression> notMatchExpressions;
  protected List<SQLExpression> returnItems;
  protected List<SQLIdentifier> returnAliases;
  protected List<SQLNestedProjection> returnNestedProjections;
  private boolean returnElements = false;
  private boolean returnPaths = false;
  private boolean returnPatterns = false;
  private boolean returnPathElements = false;
  private boolean returnDistinct = false;
  protected SQLSkip skip;
  private final SQLGroupBy groupBy;
  private final SQLOrderBy orderBy;
  private final SQLUnwind unwind;
  protected SQLLimit limit;

  // post-parsing
  private Pattern pattern;
  private List<Pattern> subPatterns;
  private Map<String, SQLWhereClause> aliasFilters;
  private Map<String, String> aliasClasses;
  private Map<String, SQLRid> aliasRids;
  private boolean foundOptional = false;
  private static final long THRESHOLD = 100;

  public MatchExecutionPlanner(SQLMatchStatement stm) {
    this.matchExpressions =
        stm.getMatchExpressions().stream().map(SQLMatchExpression::copy)
            .collect(Collectors.toList());
    this.notMatchExpressions =
        stm.getNotMatchExpressions().stream().map(SQLMatchExpression::copy)
            .collect(Collectors.toList());
    this.returnItems =
        stm.getReturnItems().stream().map(SQLExpression::copy).collect(Collectors.toList());
    //noinspection ReturnOfNull
    this.returnAliases =
        stm.getReturnAliases().stream()
            .map(x -> x == null ? null : x.copy())
            .collect(Collectors.toList());
    //noinspection ReturnOfNull
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

  public InternalExecutionPlan createExecutionPlan(
      CommandContext context, boolean enableProfiling) {

    buildPatterns(context);
    splitDisjointPatterns(context);

    var result = new SelectExecutionPlan(context);
    var estimatedRootEntries =
        estimateRootEntries(aliasClasses, aliasRids, aliasFilters, context);
    var aliasesToPrefetch =
        estimatedRootEntries.entrySet().stream()
            .filter(x -> x.getValue() < THRESHOLD)
            .filter(x -> !dependsOnExecutionContext(x.getKey()))
            .map(Entry::getKey)
            .collect(Collectors.toSet());
    for (var entry : estimatedRootEntries.entrySet()) {
      if (entry.getValue() == 0L && !isOptional(entry.getKey())) {
        result.chain(new EmptyStep(context, enableProfiling));
        return result;
      }
    }

    addPrefetchSteps(result, aliasesToPrefetch, context, enableProfiling);

    if (subPatterns.size() > 1) {
      var step = new CartesianProductStep(context, enableProfiling);
      for (var subPattern : subPatterns) {
        step.addSubPlan(
            createPlanForPattern(
                subPattern, context, estimatedRootEntries, aliasesToPrefetch, enableProfiling));
      }
      result.chain(step);
    } else {
      var plan =
          createPlanForPattern(
              pattern, context, estimatedRootEntries, aliasesToPrefetch, enableProfiling);
      for (var step : plan.getSteps()) {
        result.chain((ExecutionStepInternal) step);
      }
    }

    manageNotPatterns(result, pattern, notMatchExpressions, context, enableProfiling);

    if (foundOptional) {
      result.chain(new RemoveEmptyOptionalsStep(context, enableProfiling));
    }

    if (returnElements || returnPaths || returnPatterns || returnPathElements) {
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

  private boolean dependsOnExecutionContext(String key) {
    var filter = aliasFilters.get(key);
    if (filter == null) {
      return false;
    }
    if (filter.refersToParent()) {
      return true;
    }
    return filter.toString().toLowerCase().contains("$matched.");
  }

  private boolean isOptional(String key) {
    var node = this.pattern.aliasToNode.get(key);
    return node != null && node.isOptionalNode();
  }

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
      var node = pattern.getAliasToNode().values().iterator().next();
      if (prefetchedAliases.contains(node.alias)) {
        // from prefetch
        plan.chain(new MatchFirstStep(context, node, profilingEnabled));
      } else {
        // from actual execution plan
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
   * sort edges in the order they will be matched
   */
  private List<EdgeTraversal> getTopologicalSortedSchedule(
      Map<String, Long> estimatedRootEntries, Pattern pattern, DatabaseSessionInternal session) {
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
      remainingStarts.add(item.value());
    }
    // Add all the remaining aliases after all the suggested start points.
    remainingStarts.addAll(pattern.aliasToNode.keySet());

    while (resultingSchedule.size() < pattern.numOfEdges) {
      // Start a new depth-first pass, adding all nodes with satisfied dependencies.
      // 1. Find a starting vertex for the depth-first pass.
      PatternNode startingNode = null;
      List<String> startsToRemove = new ArrayList<String>();
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

    Map<PatternEdge, Boolean> edges = new LinkedHashMap<PatternEdge, Boolean>();
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
          // The isOutbound value shows us whether the edge is outbound from the point of view of
          // the startNode.
          // However, if there are edges to the startNode, we must visit the startNode from an
          // already-visited
          // neighbor, to preserve the validity of the traversal. Therefore, we negate the value of
          // isOutbound
          // to ensure that the edge is always scheduled in the direction from the already-visited
          // neighbor
          // toward the startNode. Notably, this is also the case when evaluating "optional" nodes
          // -- we always
          // visit the optional node from its non-optional and already-visited neighbor.
          //
          // The only exception to the above is when we have edges with "while" conditions. We are
          // not allowed
          // to flip their directionality, so we leave them as-is.
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
   * Calculate the set of dependency aliases for each alias in the pattern.
   * @return map of alias to the set of aliases it depends on
   */
  private Map<String, Set<String>> getDependencies(Pattern pattern) {
    Map<String, Set<String>> result = new HashMap<String, Set<String>>();

    for (var node : pattern.aliasToNode.values()) {
      Set<String> currentDependencies = new HashSet<String>();

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

  private void splitDisjointPatterns(CommandContext context) {
    if (this.subPatterns != null) {
      return;
    }

    this.subPatterns = pattern.getDisjointPatterns();
  }

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

  @Nullable
  private static String getLowerSubclass(
      DatabaseSessionInternal db, String className1, String className2) {
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
   * assigns default aliases to pattern nodes that do not have an explicit alias
   */
  private static void assignDefaultAliases(List<SQLMatchExpression> matchExpressions) {
    var counter = 0;
    for (var expression : matchExpressions) {
      if (expression.getOrigin().getAlias() == null) {
        expression.getOrigin().setAlias(DEFAULT_ALIAS_PREFIX + (counter++));
      }

      for (var item : expression.getItems()) {
        if (item.getFilter() == null) {
          item.setFilter(new SQLMatchFilter(-1));
        }
        if (item.getFilter().getAlias() == null) {
          item.getFilter().setAlias(DEFAULT_ALIAS_PREFIX + (counter++));
        }
      }
    }
  }

  private static Map<String, Long> estimateRootEntries(
      Map<String, String> aliasClasses,
      Map<String, SQLRid> aliasRids,
      Map<String, SQLWhereClause> aliasFilters,
      CommandContext ctx) {
    Set<String> allAliases = new LinkedHashSet<String>();
    allAliases.addAll(aliasClasses.keySet());
    allAliases.addAll(aliasFilters.keySet());
    allAliases.addAll(aliasRids.keySet());

    var db = ctx.getDatabaseSession();
    var schema = db.getMetadata().getImmutableSchemaSnapshot();

    Map<String, Long> result = new LinkedHashMap<String, Long>();
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
