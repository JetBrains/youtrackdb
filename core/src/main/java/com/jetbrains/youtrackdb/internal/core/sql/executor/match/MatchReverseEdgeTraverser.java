package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.internal.common.collection.MultiValue;
import com.jetbrains.youtrackdb.internal.common.io.IOUtils;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchPathItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLRid;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;

/**
 * Edge traverser that walks a pattern edge in the **reverse** direction.
 * <p>
 * When the topological scheduler decides to traverse an edge from `edge.in` to `edge.out`
 * (i.e., backwards relative to the syntactic direction), {@link MatchStep} instantiates
 * this class instead of the base {@link MatchEdgeTraverser}.
 * <p>
 * ### Key differences from the forward traverser
 * <p>
 * | Aspect                | Forward (`MatchEdgeTraverser`)     | Reverse (`MatchReverseEdgeTraverser`) |
 * |-----------------------|-----------------------------------|---------------------------------------|
 * | Starting alias        | `edge.out.alias`                   | `edge.in.alias`                        |
 * | Endpoint alias        | `edge.in.alias`                    | `edge.out.alias`                       |
 * | Traversal method      | `item.getMethod().execute()`       | `item.getMethod().executeReverse()`    |
 * | Target class/RID/filter | From item's filter               | From `EdgeTraversal.leftClass/Rid/Filter` |
 * <p>
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
    assert lastUpstreamRecord != null : "upstream record must not be null";
    // Swap source/target aliases relative to the syntactic direction
    this.startingPointAlias = edge.edge.in.alias;
    this.endPointAlias = edge.edge.out.alias;
    assert startingPointAlias != null : "starting point alias must not be null";
    assert endPointAlias != null : "endpoint alias must not be null";
  }

  /** Uses the planner-provided left-class constraint (the original source node's class). */
  @Override
  protected String targetClassName(SQLMatchPathItem item, CommandContext iCommandContext) {
    return edge.getLeftClass();
  }

  /** Uses the planner-provided left-RID constraint (the original source node's RID). */
  @Override
  protected SQLRid targetRid(SQLMatchPathItem item, CommandContext iCommandContext) {
    return edge.getLeftRid();
  }

  /** Uses the planner-provided left-filter (the original source node's WHERE clause). */
  @Override
  protected SQLWhereClause getTargetFilter(SQLMatchPathItem item) {
    return edge.getLeftFilter();
  }

  /**
   * Calls `executeReverse()` on the path item's method instead of `execute()`,
   * effectively walking the edge in the opposite direction (e.g. `out()` becomes an
   * incoming traversal). Delegates to {@link #applyPreFilter} for adaptive
   * RidSet resolution using the actual link bag size.
   */
  @Override
  protected ExecutionStream traversePatternEdge(
      Result startingPoint, CommandContext iCommandContext) {
    assert startingPoint != null : "starting point must not be null";
    var qR = this.item.getMethod().executeReverse(startingPoint, iCommandContext);

    qR = applyPreFilter(qR, iCommandContext);

    var stream = toExecutionStream(qR, iCommandContext.getDatabaseSession());
    String method = item.getMethod().getMethodNameString();
    if (("outE".equalsIgnoreCase(method) || "inE".equalsIgnoreCase(method)
        || "bothE".equalsIgnoreCase(method)) && !item.getMethod().getParams().isEmpty()) {
      // Reversing inV/outV discovers edges without the preceding outE/inE label. The reverse
      // endpoint method ignores labels, so enforce that constraint on the starting edge here.
      // Evaluate labels on each original source, just as the forward edge method does.
      return stream.filter((source, ctx) -> matchesEdgeLabel(startingPoint, source, ctx)
          ? source : null);
    }
    return stream;
  }

  private boolean matchesEdgeLabel(Result startingEdge, Result source, CommandContext ctx) {
    var previous = ctx.getSystemVariable(CommandContext.VAR_CURRENT);
    try {
      ctx.setSystemVariable(CommandContext.VAR_CURRENT, source);
      // Resolve every parameter before testing any label. Forward navigation also evaluates
      // later parameters when the first label matches or is null.
      var values = item.getMethod().getParams().stream()
          .map(expression -> expression.execute(source, ctx)).toArray();
      if (values[0] == null) {
        return true;
      }
      var labels = MultiValue.array(values, String.class, IOUtils::getStringContent);
      var schema = ctx.getDatabaseSession().getMetadata().getImmutableSchemaSnapshot();
      for (var label : labels) {
        var clazz = schema.getClass(label);
        if (clazz != null && matchesClass(ctx, clazz.getName(), startingEdge)) {
          return true;
        }
      }
      return false;
    } finally {
      ctx.setSystemVariable(CommandContext.VAR_CURRENT, previous);
    }
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
