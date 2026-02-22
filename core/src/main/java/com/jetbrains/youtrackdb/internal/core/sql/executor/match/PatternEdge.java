package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchPathItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchStatement;

/**
 * Represents a **directed edge** in the MATCH pattern graph.
 * <p>
 * Each edge corresponds to a traversal step between two pattern nodes — for example,
 * `.out('Knows')` or `.in('Lives')`. The direction stored here (`out` → `in`) reflects
 * the **syntactic** direction in the `MATCH` expression, which may differ from the
 * actual runtime traversal direction chosen by the scheduler (see
 * {@link EdgeTraversal#out}).
 *
 * <pre>
 *   PatternNode("p")  ──PatternEdge──→  PatternNode("f")
 *        ↑ out                               ↑ in
 *     (source)                             (target)
 * </pre>
 * <p>
 * ### Fields
 *
 * | Field   | Meaning                                       |
 * |---------|-----------------------------------------------|
 * | `out`   | The **source** node (left-hand side / origin)  |
 * | `in`    | The **target** node (right-hand side / dest.)  |
 * | `item`  | The parsed path item holding the method call,  |
 * |         | filter, `WHILE` condition, etc.                |
 *
 * @see PatternNode
 * @see EdgeTraversal
 * @see MatchStep
 */
public class PatternEdge {

  /** The **source** node from which this edge originates (left-hand side). */
  public PatternNode out;

  /** The **target** node to which this edge points (right-hand side). */
  public PatternNode in;

  /**
   * The parsed AST path item that describes *how* to traverse this edge — including
   * the method (e.g. `out()`, `in()`, `both()`), optional edge-type label, filter
   * conditions, `WHILE` clauses, and depth limits.
   */
  public SQLMatchPathItem item;

  /**
   * Executes the traversal defined by {@link #item}, starting from the given record
   * and returning all reachable target records.
   *
   * @param matchContext      legacy match context (carries previously matched aliases)
   * @param iCommandContext   the command execution context
   * @param startingPoint     the record to traverse from
   * @param depth             the current recursion depth (used by `WHILE` traversals)
   * @return an iterable over the records reachable via this edge
   */
  public Iterable<Identifiable> executeTraversal(
      SQLMatchStatement.MatchContext matchContext,
      CommandContext iCommandContext,
      Identifiable startingPoint,
      int depth) {
    return item.executeTraversal(matchContext, iCommandContext, startingPoint, depth);
  }

  @Override
  public String toString() {
    return "{as: " + in.alias + "}" + item.toString();
  }
}
