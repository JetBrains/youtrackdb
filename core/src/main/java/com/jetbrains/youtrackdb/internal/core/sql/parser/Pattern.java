package com.jetbrains.youtrackdb.internal.core.sql.parser;

import com.jetbrains.youtrackdb.internal.core.exception.CommandSQLParsingException;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.PatternEdge;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.PatternNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * An in-memory **graph** representation of a MATCH query's pattern.
 *
 * A `Pattern` is built from one or more {@link SQLMatchExpression}s during the
 * planning phase (see {@code MatchExecutionPlanner#buildPatterns()}).
 * Each node in the graph corresponds to an aliased `{…}` block in the MATCH statement,
 * and each edge corresponds to a traversal step (`.out()`, `.in()`, `.both()`, field
 * access, etc.).
 *
 * ### Structure
 *
 * - **Nodes** are stored in {@link #aliasToNode}, keyed by their unique alias.
 * - **Edges** are stored inside the nodes' `out` and `in` adjacency sets
 *   ({@link PatternNode#out}, {@link PatternNode#in}).
 * - {@link #numOfEdges} tracks the total number of distinct edges in the graph.
 *
 * ### Lifecycle
 *
 * 1. The planner creates an empty `Pattern` and populates it by calling
 *    {@link #addExpression} for each positive `MATCH` expression.
 * 2. {@link #validate()} is called to enforce structural constraints (e.g. optional
 *    nodes must be terminal).
 * 3. {@link #getDisjointPatterns()} splits the graph into connected components for
 *    independent planning.
 *
 * @see PatternNode
 * @see PatternEdge
 * @see com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchExecutionPlanner
 */
public class Pattern {

  /**
   * Maps each alias to its {@link PatternNode}. Insertion order is preserved to
   * provide deterministic iteration during planning.
   */
  public Map<String, PatternNode> aliasToNode = new LinkedHashMap<String, PatternNode>();

  /** Total number of directed edges in the pattern graph. */
  public int numOfEdges = 0;

  /**
   * Adds a single MATCH expression to the pattern graph. The expression is a chain of
   * nodes connected by edges: `{origin} -> edge -> {node1} -> edge -> {node2} -> …`.
   *
   * For each node in the chain, a {@link PatternNode} is created (or reused if the
   * alias already exists). For each edge, a {@link PatternEdge} is created linking the
   * consecutive pair of nodes.
   *
   * @param expression the parsed MATCH expression to incorporate
   */
  public void addExpression(SQLMatchExpression expression) {
    var originNode = getOrCreateNode(expression.origin);

    for (var item : expression.items) {
      var nextAlias = item.filter.getAlias();
      var nextNode = getOrCreateNode(item.filter);

      numOfEdges += originNode.addEdge(item, nextNode);
      originNode = nextNode;
    }
  }

  /**
   * Retrieves or creates the {@link PatternNode} for the given filter's alias. If the
   * filter is marked `optional`, the node's `optional` flag is set to `true`.
   */
  private PatternNode getOrCreateNode(SQLMatchFilter origin) {
    var originNode = get(origin.getAlias());
    if (originNode == null) {
      originNode = new PatternNode();
      originNode.alias = origin.getAlias();
      aliasToNode.put(originNode.alias, originNode);
    }
    if (origin.isOptional()) {
      originNode.optional = true;
    }
    return originNode;
  }

  /**
   * @param alias the alias to look up
   * @return the corresponding node, or `null` if no node with that alias exists
   */
  public PatternNode get(String alias) {
    return aliasToNode.get(alias);
  }

  public int getNumOfEdges() {
    return numOfEdges;
  }

  /**
   * Validates the structural constraints of the pattern graph.
   *
   * Current constraints for **optional nodes**:
   * - Must be **right-terminal**: no outgoing edges (they must be the end of a path).
   * - Must have at least one incoming edge (they cannot be isolated).
   *
   * @throws CommandSQLParsingException if any constraint is violated
   */
  public void validate() {
    for (var node : this.aliasToNode.values()) {
      if (node.isOptionalNode()) {
        if (node.out.size() > 0) {
          throw new CommandSQLParsingException(
              "In current MATCH version, optional nodes are allowed only on right terminal nodes,"
                  + " eg. {} --> {optional:true} is allowed, {optional:true} <-- {} is not. ");
        }
        if (node.in.size() == 0) {
          throw new CommandSQLParsingException(
              "In current MATCH version, optional nodes must have at least one incoming pattern"
                  + " edge");
        }
      }
    }
  }

  /**
   * Splits this pattern graph into its **connected components** (disjoint sub-patterns).
   *
   * A MATCH query can contain multiple disconnected sub-graphs. For example:
   * ```sql
   * MATCH {as: a}.out(){as: b}, {as: x}.out(){as: y}
   * ```
   * produces two connected components: `{a, b}` and `{x, y}`.
   *
   * Each component is returned as a separate `Pattern` instance. The planner uses these
   * to generate independent execution plans that are later joined via a
   * {@link com.jetbrains.youtrackdb.internal.core.sql.executor.CartesianProductStep}.
   *
   * The algorithm uses a flood-fill approach: pick an unvisited node, explore all
   * reachable nodes through both incoming and outgoing edges, and collect them into
   * a new `Pattern`.
   *
   * <pre>
   * Example flood-fill on a pattern with two disconnected sub-graphs:
   *
   *   Input pattern:   (a)──→(b)──→(c)    (x)──→(y)
   *
   *   Iteration 1: pick (a), flood-fill → {a, b, c} → Pattern 1
   *   Iteration 2: pick (x), flood-fill → {x, y}    → Pattern 2
   *
   *   Output: [Pattern1{a,b,c}, Pattern2{x,y}]
   * </pre>
   *
   * @return list of connected components, each as a separate Pattern
   */
  public List<Pattern> getDisjointPatterns() {
    // Build a reverse map (node → alias) using identity semantics to handle duplicate aliases
    Map<PatternNode, String> reverseMap = new IdentityHashMap<>();
    reverseMap.putAll(
        this.aliasToNode.entrySet().stream()
            .collect(Collectors.toMap(x -> x.getValue(), x -> x.getKey())));

    List<Pattern> result = new ArrayList<>();
    while (!reverseMap.isEmpty()) {
      var pattern = new Pattern();
      result.add(pattern);

      // Start BFS from an arbitrary unvisited node
      var nextNode = reverseMap.entrySet().iterator().next();
      Set<PatternNode> toVisit = new HashSet<>();
      toVisit.add(nextNode.getKey());

      // Flood-fill: visit all nodes reachable via edges in either direction
      while (toVisit.size() > 0) {
        var currentNode = toVisit.iterator().next();
        toVisit.remove(currentNode);
        if (reverseMap.containsKey(currentNode)) {
          pattern.aliasToNode.put(reverseMap.get(currentNode), currentNode);
          reverseMap.remove(currentNode);
          // Expand outgoing neighbors
          for (var x : currentNode.out) {
            toVisit.add(x.in);
          }
          // Expand incoming neighbors
          for (var x : currentNode.in) {
            toVisit.add(x.out);
          }
        }
      }
      pattern.recalculateNumOfEdges();
    }
    return result;
  }

  /**
   * Recomputes {@link #numOfEdges} by counting distinct edge instances across all nodes.
   * Uses an {@link IdentityHashMap} to ensure each edge is counted exactly once even if
   * it appears in both a node's `out` and its neighbor's `in` set.
   */
  private void recalculateNumOfEdges() {
    Map<PatternEdge, PatternEdge> edges = new IdentityHashMap<>();
    for (var node : this.aliasToNode.values()) {
      for (var edge : node.out) {
        edges.put(edge, edge);
      }
      for (var edge : node.in) {
        edges.put(edge, edge);
      }
    }
    this.numOfEdges = edges.size();
  }

  public Map<String, PatternNode> getAliasToNode() {
    return aliasToNode;
  }

  public void setAliasToNode(Map<String, PatternNode> aliasToNode) {
    this.aliasToNode = aliasToNode;
  }

  public void setNumOfEdges(int numOfEdges) {
    this.numOfEdges = numOfEdges;
  }
}
