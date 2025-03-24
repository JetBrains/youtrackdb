package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLMatchPathItem;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLRid;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLWhereClause;

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
      Identifiable startingPoint, CommandContext iCommandContext) {

    var qR = this.item.getMethod().executeReverse(startingPoint, iCommandContext);
    if (qR == null) {
      return ExecutionStream.empty();
    }
    if (qR instanceof ResultInternal) {
      return ExecutionStream.singleton((ResultInternal) qR);
    }
    if (qR instanceof Identifiable) {
      return ExecutionStream.singleton(
          new ResultInternal(iCommandContext.getDatabaseSession(), (Identifiable) qR));
    }
    if (qR instanceof Iterable iterable) {
      return ExecutionStream.iterator(iterable.iterator());
    }
    return ExecutionStream.empty();
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
