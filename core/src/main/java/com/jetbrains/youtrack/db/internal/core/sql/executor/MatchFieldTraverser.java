package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.Identifiable;
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

    if (qR == null) {
      return ExecutionStream.empty();
    }
    if (qR instanceof Identifiable) {
      return ExecutionStream.singleton(new ResultInternal(
          iCommandContext.getDatabaseSession(), (Identifiable) qR));
    }
    if (qR instanceof Iterable) {
      return ExecutionStream.iterator(((Iterable) qR).iterator());
    }
    return ExecutionStream.empty();
  }
}
