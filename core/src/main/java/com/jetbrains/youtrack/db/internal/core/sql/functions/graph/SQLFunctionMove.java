package com.jetbrains.youtrack.db.internal.core.sql.functions.graph;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.Direction;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.Vertex;
import com.jetbrains.youtrack.db.internal.common.collection.MultiValue;
import com.jetbrains.youtrack.db.internal.common.io.IOUtils;
import com.jetbrains.youtrack.db.internal.common.util.CallableFunction;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.record.impl.Relation;
import com.jetbrains.youtrack.db.internal.core.sql.SQLEngine;
import com.jetbrains.youtrack.db.internal.core.sql.functions.SQLFunctionConfigurableAbstract;
import java.util.ArrayList;
import javax.annotation.Nullable;

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
      final DatabaseSessionEmbedded db, final Identifiable record, final String[] labels);

  protected abstract Object move(
      final DatabaseSessionEmbedded db, final Relation<?> bidirectionalLink,
      final String[] labels);

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
        iArgument -> {
          if (iArgument instanceof Relation<?> bidirectionalLink) {
            return move(db, bidirectionalLink, labels);
          } else if (iArgument instanceof Identifiable identifiable) {
            return move(db, identifiable, labels);
          } else {
            throw new IllegalArgumentException(
                "Invalid argument type: " + iArgument.getClass().getName());
          }
        },
        iThis,
        iContext);
  }

  @Nullable
  protected static Object v2v(
      final DatabaseSessionInternal graph,
      final Identifiable iRecord,
      final Direction iDirection,
      final String[] iLabels) {
    if (iRecord != null) {
      try {
        var transaction = graph.getActiveTransaction();
        var rec = (EntityImpl) transaction.loadEntity(iRecord);
        if (rec.isEdge()) {
          return null;
        } else {
          return rec.getEntities(iDirection, iLabels);
        }
      } catch (RecordNotFoundException rnf) {
        return null;
      }
    } else {
      return null;
    }
  }

  @Nullable
  protected static Object v2e(
      final DatabaseSession graph,
      final Identifiable iRecord,
      final Direction iDirection,
      final String[] iLabels) {
    if (iRecord != null) {
      try {
        var transaction = graph.getActiveTransaction();
        var rec = (EntityImpl) transaction.loadEntity(iRecord);
        if (!rec.isEdge()) {
          return rec.getBidirectionalLinks(iDirection, iLabels);
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

  @Nullable
  protected static Object e2v(
      final DatabaseSession graph,
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

  @Nullable
  protected static Object e2v(
      final Relation<?> bidirectionalLink,
      final Direction iDirection) {
    if (bidirectionalLink != null) {
      try {
        if (iDirection == Direction.BOTH) {
          var results = new ArrayList<Entity>(2);
          results.add(bidirectionalLink.getEntity(Direction.OUT));
          results.add(bidirectionalLink.getEntity(Direction.IN));
          return results;
        }
        return bidirectionalLink.getEntity(iDirection);
      } catch (RecordNotFoundException e) {
        return null;
      }
    } else {
      return null;
    }
  }
}
