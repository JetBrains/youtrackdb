package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.internal.core.sql.parser.Pattern;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchPathItem;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Represents a **node** (vertex) in the MATCH pattern graph.
 * <p>
 * Each node corresponds to a `{...}` block in the `MATCH` statement and is uniquely
 * identified by its {@link #alias}. Nodes are connected to one another through
 * {@link PatternEdge}s which represent the traversal steps (e.g. `.out()`, `.in()`,
 * `.both()`, field accesses).
 * <p>
 * ### Example
 * <p>
 * For the query:
 * ```sql
 * MATCH {class: Person, as: p}.out('Knows'){as: f}.out('Lives'){as: c}
 * ```
 * <p>
 * Three `PatternNode` instances are created, linked by two `PatternEdge`s:
 *
 * <pre>
 *   PatternNode("p")           PatternNode("f")           PatternNode("c")
 *     out: [edge1]               out: [edge2]               out: []
 *     in:  []                    in:  [edge1]               in:  [edge2]
 *           │                          │                          │
 *           └── edge1.out=p ──→ edge1.in=f                       │
 *                                      └── edge2.out=f ──→ edge2.in=c
 * </pre>
 * <p>
 * ### Graph structure
 * <p>
 * - {@link #out} — edges **leaving** this node (this node is the source / `edge.out`).
 * - {@link #in}  — edges **arriving** at this node (this node is the target / `edge.in`).
 *
 * The `out`/`in` naming follows the direction declared in the `MATCH` expression, **not**
 * the actual traversal direction chosen by the scheduler. The scheduler may traverse an
 * edge in reverse (see {@link EdgeTraversal#out}).
 *
 * @see PatternEdge
 * @see Pattern
 * @see MatchExecutionPlanner
 */
public class PatternNode {

  /** The unique alias for this node, e.g. `"p"` in `{as: p}`. */
  public String alias;

  /** Outgoing pattern edges (this node → neighbor). Insertion-ordered. */
  public Set<PatternEdge> out = new LinkedHashSet<>();

  /** Incoming pattern edges (neighbor → this node). Insertion-ordered. */
  public Set<PatternEdge> in = new LinkedHashSet<>();

  /**
   * When `true`, this node is marked `optional: true` in the MATCH pattern, meaning
   * the traversal may produce `null` for this alias without discarding the entire row.
   */
  public boolean optional = false;

  /**
   * Creates a directed edge from this node to `to` using the given path item and
   * registers it in both nodes' adjacency sets.
   *
   * @param item the parsed path item that describes the traversal (method, filter, etc.)
   * @param to   the target node
   * @return always `1` — the number of edges added (used by {@link Pattern} to track
   *         the total edge count)
   */
  public int addEdge(SQLMatchPathItem item, PatternNode to) {
    assert item != null : "path item must not be null";
    assert to != null : "target node must not be null";
    var edge = new PatternEdge();
    edge.item = item;
    edge.out = this;
    edge.in = to;
    this.out.add(edge);
    to.in.add(edge);
    return 1;
  }

  /**
   * @return `true` if this node was declared with `optional: true`
   */
  public boolean isOptionalNode() {
    return optional;
  }

  /**
   * Creates a shallow copy of this node. The copy shares the same edge **items** and
   * target nodes — only the {@code out} edge set is re-created via {@link #addEdge}.
   * <p>
   * The {@code in} set of the copy is **not** populated because incoming edges are owned
   * by their source nodes. When copying a full {@link Pattern}, the caller must rebuild
   * the {@code in} sets by iterating over all nodes' {@code out} edges — which
   * {@link #addEdge} already does for the target node. Therefore, when copying an
   * entire graph node-by-node, the {@code in} sets are reconstructed automatically as
   * each source node's edges are re-added. For standalone single-node copies (e.g. in
   * {@link MatchFirstStep#copy}), the {@code in} set is not needed because the copy
   * is used only to carry the alias and optional flag.
   */
  public PatternNode copy() {
    var copy = new PatternNode();
    copy.alias = alias;
    copy.optional = optional;

    for (var edge : out) {
      copy.addEdge(edge.item, edge.in);
    }

    return copy;
  }
}
