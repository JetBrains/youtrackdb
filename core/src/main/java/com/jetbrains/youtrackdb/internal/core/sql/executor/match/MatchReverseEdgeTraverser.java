package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Relation;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchPathItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLRid;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;

/**
 * Edge traverser that walks a pattern edge in the **reverse** direction.
 *
 * When the topological scheduler decides to traverse an edge from `edge.in` to `edge.out`
 * (i.e., backwards relative to the syntactic direction), {@link MatchStep} instantiates
 * this class instead of the base {@link MatchEdgeTraverser}.
 *
 * ### Key differences from the forward traverser
 *
 * | Aspect                | Forward (`MatchEdgeTraverser`)     | Reverse (`MatchReverseEdgeTraverser`) |
 * |-----------------------|-----------------------------------|---------------------------------------|
 * | Starting alias        | `edge.out.alias`                   | `edge.in.alias`                        |
 * | Endpoint alias        | `edge.in.alias`                    | `edge.out.alias`                       |
 * | Traversal method      | `item.getMethod().execute()`       | `item.getMethod().executeReverse()`    |
 * | Target class/RID/filter | From item's filter               | From `EdgeTraversal.leftClass/Rid/Filter` |
 *
 * The "left" constraints (class, RID, WHERE) are set on the {@link EdgeTraversal} by
 * the planner — they represent the **original source node's** constraints which, in
 * reverse mode, become the target to validate against.
 *
 * @see MatchEdgeTraverser
 * @see MatchStep#createTraverser
 */
public class MatchReverseEdgeTraverser extends MatchEdgeTraverser {

  /** In reverse mode, we start from the syntactic *target* (`edge.in`). */
  private final String startingPointAlias;

  /** In reverse mode, we end at the syntactic *source* (`edge.out`). */
  private final String endPointAlias;

  public MatchReverseEdgeTraverser(Result lastUpstreamRecord, EdgeTraversal edge) {
    super(lastUpstreamRecord, edge);
    // Swap source/target aliases relative to the syntactic direction
    this.startingPointAlias = edge.edge.in.alias;
    this.endPointAlias = edge.edge.out.alias;
  }

  /** Uses the planner-provided left-class constraint (the original source node's class). */
  protected String targetClassName(SQLMatchPathItem item, CommandContext iCommandContext) {
    return edge.getLeftClass();
  }

  /** Uses the planner-provided left-RID constraint (the original source node's RID). */
  protected SQLRid targetRid(SQLMatchPathItem item, CommandContext iCommandContext) {
    return edge.getLeftRid();
  }

  /** Uses the planner-provided left-filter (the original source node's WHERE clause). */
  protected SQLWhereClause getTargetFilter(SQLMatchPathItem item) {
    return edge.getLeftFilter();
  }

  /**
   * Calls `executeReverse()` on the path item's method instead of `execute()`,
   * effectively walking the edge in the opposite direction (e.g. `out()` becomes an
   * incoming traversal).
   */
  @Override
  protected ExecutionStream traversePatternEdge(
      Result startingPoint, CommandContext iCommandContext) {

    var qR = this.item.getMethod().executeReverse(startingPoint, iCommandContext);
    return switch (qR) {
      case null -> ExecutionStream.empty();
      case ResultInternal resultInternal -> ExecutionStream.singleton(resultInternal);
      case Identifiable identifiable -> ExecutionStream.singleton(
          new ResultInternal(iCommandContext.getDatabaseSession(), identifiable));
      case Relation<?> bidirectionalLink -> ExecutionStream.singleton(
          new ResultInternal(iCommandContext.getDatabaseSession(), bidirectionalLink));
      case Iterable<?> iterable -> ExecutionStream.iterator(iterable.iterator());
      default -> ExecutionStream.empty();
    };
  }

  @Override
  protected String getStartingPointAlias() {
    return this.startingPointAlias;
  }

  @Override
  protected String getEndpointAlias() {
    return endPointAlias;
  }
}
