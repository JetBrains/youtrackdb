package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Relation;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchPathItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLRid;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;

/**
 *
 */
public class MatchReverseEdgeTraverser extends MatchEdgeTraverser {

  private final String startingPointAlias;
  private final String endPointAlias;

  public MatchReverseEdgeTraverser(Result lastUpstreamRecord, EdgeTraversal edge) {
    super(lastUpstreamRecord, edge);
    this.startingPointAlias = edge.edge.in.alias;
    this.endPointAlias = edge.edge.out.alias;
  }

  protected String targetClassName(SQLMatchPathItem item, CommandContext iCommandContext) {
    return edge.getLeftClass();
  }

  protected SQLRid targetRid(SQLMatchPathItem item, CommandContext iCommandContext) {
    return edge.getLeftRid();
  }

  protected SQLWhereClause getTargetFilter(SQLMatchPathItem item) {
    return edge.getLeftFilter();
  }

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
