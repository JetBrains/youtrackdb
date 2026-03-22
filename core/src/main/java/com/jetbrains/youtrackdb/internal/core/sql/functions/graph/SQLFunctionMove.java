package com.jetbrains.youtrackdb.internal.core.sql.functions.graph;

import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.internal.common.collection.MultiValue;
import com.jetbrains.youtrackdb.internal.common.io.IOUtils;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Direction;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.SQLEngine;
import com.jetbrains.youtrackdb.internal.core.sql.functions.SQLFunctionConfigurableAbstract;
import java.util.ArrayList;
import javax.annotation.Nullable;

public abstract class SQLFunctionMove extends SQLFunctionConfigurableAbstract {

  public static final String NAME = "move";

  public SQLFunctionMove() {
    super(NAME, 1, 2);
  }

  public SQLFunctionMove(final String iName, final int iMin, final int iMax) {
    super(iName, iMin, iMax);
  }

  protected abstract Object move(
      final DatabaseSessionEmbedded db, final Identifiable record, final String[] labels);

  @Override
  public String getSyntax(DatabaseSessionEmbedded session) {
    return "Syntax error: " + name + "([<labels>])";
  }

  @Override
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
              IOUtils::getStringContent);
    } else {
      labels = null;
    }

    return SQLEngine.foreachRecord(
        iArgument -> {
          if (iArgument instanceof Identifiable identifiable) {
            return move(db, identifiable, labels);
          } else {
            throw new IllegalArgumentException(
                "Invalid argument type: " + iArgument.getClass().getName());
          }
        },
        iThis,
        iContext);
  }

  @Nullable protected static Object v2v(
      final DatabaseSessionEmbedded graph,
      final Identifiable iRecord,
      final Direction iDirection,
      final String[] labels) {
    if (iRecord != null) {
      try {
        var transaction = graph.getActiveTransaction();
        var rec = (EntityImpl) transaction.loadEntity(iRecord);
        if (rec.isEdge()) {
          return null;
        } else if (rec.isVertex()) {
          // Use the optimized getVertices() path which reads adjacent vertices
          // directly from LinkBag secondary RIDs without loading edge records.
          return rec.asVertex().getVertices(iDirection, labels);
        } else {
          // Plain entities are not graph elements — no vertex traversal.
          return null;
        }
      } catch (RecordNotFoundException rnf) {
        return null;
      }
    } else {
      return null;
    }
  }

  @Nullable protected static Object v2e(
      final DatabaseSessionEmbedded graph,
      final Identifiable iRecord,
      final Direction iDirection,
      final String[] labels) {
    if (iRecord != null) {
      try {
        var transaction = graph.getActiveTransaction();
        var rec = (EntityImpl) transaction.loadEntity(iRecord);
        if (rec.isVertex()) {
          return rec.asVertex().getEdges(iDirection, labels);
        } else {
          // Edge records and plain entities have no outgoing edges to traverse.
          return null;
        }
      } catch (RecordNotFoundException rnf) {
        return null;
      }
    } else {
      return null;
    }
  }

  @Nullable protected static Object e2v(
      final DatabaseSessionEmbedded graph,
      final Identifiable iRecord,
      final Direction iDirection) {
    if (iRecord != null) {

      try {
        var transaction = graph.getActiveTransaction();
        Entity rec = transaction.load(iRecord);
        if (rec.isEdge()) {
          if (iDirection == Direction.BOTH) {
            var results = new ArrayList<Vertex>();
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
