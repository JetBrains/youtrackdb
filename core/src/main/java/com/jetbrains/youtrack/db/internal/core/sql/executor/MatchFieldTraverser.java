package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.Relation;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLFieldMatchPathItem;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLMatchPathItem;

public class MatchFieldTraverser extends MatchEdgeTraverser {

  public MatchFieldTraverser(Result lastUpstreamRecord, EdgeTraversal edge) {
    super(lastUpstreamRecord, edge);
  }

  public MatchFieldTraverser(Result lastUpstreamRecord, SQLMatchPathItem item) {
    super(lastUpstreamRecord, item);
  }

  @Override
  protected ExecutionStream traversePatternEdge(
      Result startingPoint, CommandContext iCommandContext) {
    var prevCurrent = iCommandContext.getVariable("$current");
    iCommandContext.setVariable("$current", startingPoint);
    Object qR;
    try {
      // TODO check possible results!
      qR = ((SQLFieldMatchPathItem) this.item).getExp().execute(startingPoint, iCommandContext);
    } finally {
      iCommandContext.setVariable("$current", prevCurrent);
    }

    return switch (qR) {
      case null -> ExecutionStream.empty();
      case Identifiable identifiable -> ExecutionStream.singleton(new ResultInternal(
          iCommandContext.getDatabaseSession(), identifiable));
      case Relation<?> relation -> ExecutionStream.singleton(
          new ResultInternal(iCommandContext.getDatabaseSession(), relation));
      case Iterable<?> iterable -> ExecutionStream.iterator(iterable.iterator());
      default -> ExecutionStream.empty();
    };
  }
}
