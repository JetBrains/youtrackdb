package com.jetbrains.youtrack.db.internal.core.sql.functions.graph;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.Direction;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.common.collection.MultiValue;
import com.jetbrains.youtrack.db.internal.common.io.IOUtils;
import com.jetbrains.youtrack.db.internal.common.util.CallableFunction;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.sql.SQLEngine;
import com.jetbrains.youtrack.db.internal.core.sql.functions.SQLFunctionConfigurableAbstract;
import java.util.ArrayList;
import java.util.List;

/**
 *
 */
public abstract class SQLFunctionMove extends SQLFunctionConfigurableAbstract {

  public static final String NAME = "move";

  public SQLFunctionMove() {
    super(NAME, 1, 2);
  }

  public SQLFunctionMove(final String iName, final int iMin, final int iMax) {
    super(iName, iMin, iMax);
  }

  protected abstract Object move(
      final DatabaseSessionInternal db, final Identifiable iRecord, final String[] iLabels);

  public String getSyntax(DatabaseSession session) {
    return "Syntax error: " + name + "([<labels>])";
  }

  public Object execute(
      final Object iThis,
      final Result iCurrentRecord,
      final Object iCurrentResult,
      final Object[] iParameters,
      final CommandContext iContext) {

    var db = iContext.getDatabaseSession();
    final String[] labels;
    if (iParameters != null && iParameters.length > 0 && iParameters[0] != null) {
      labels =
          MultiValue.array(
              iParameters,
              String.class,
              new CallableFunction<Object, Object>() {

                @Override
                public Object call(final Object iArgument) {
                  return IOUtils.getStringContent(iArgument);
                }
              });
    } else {
      labels = null;
    }

    return SQLEngine.foreachRecord(
        iArgument -> move(db, iArgument, labels),
        iThis,
        iContext);
  }

  protected static Object v2v(
      final DatabaseSessionInternal graph,
      final Identifiable iRecord,
      final Direction iDirection,
      final String[] iLabels) {
    if (iRecord != null) {
      try {
        var transaction = graph.getActiveTransaction();
        Entity rec = transaction.load(iRecord);
        if (rec.isVertex()) {
          return rec.asVertex().getVertices(iDirection, iLabels);
        } else {
          return null;
        }
      } catch (RecordNotFoundException rnf) {
        return null;
      }
    } else {
      return null;
    }
  }

  protected static Object v2e(
      final DatabaseSession graph,
      final Identifiable iRecord,
      final Direction iDirection,
      final String[] iLabels) {
    if (iRecord != null) {
      try {
        var transaction = graph.getActiveTransaction();
        Entity rec = transaction.load(iRecord);
        if (rec.isVertex()) {
          return rec.asVertex().getEdges(iDirection, iLabels);
        } else {
          return null;
        }
      } catch (RecordNotFoundException rnf) {
        return null;
      }
    } else {
      return null;
    }
  }

  protected static Object e2v(
      final DatabaseSession graph,
      final Identifiable iRecord,
      final Direction iDirection,
      final String[] iLabels) {
    if (iRecord != null) {

      try {
        var transaction = graph.getActiveTransaction();
        Entity rec = transaction.load(iRecord);
        if (rec.isEdge()) {
          if (iDirection == Direction.BOTH) {
            List results = new ArrayList();
            results.add(rec.asEdge().getVertex(Direction.OUT));
            results.add(rec.asEdge().getVertex(Direction.IN));
            return results;
          }
          return rec.asEdge().getVertex(iDirection);
        } else {
          return null;
        }
      } catch (RecordNotFoundException e) {
        return null;
      }
    } else {
      return null;
    }
  }
}
