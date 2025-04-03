package com.jetbrains.youtrack.db.internal.core.sql.functions.graph;

import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.common.collection.MultiValue;
import com.jetbrains.youtrack.db.internal.common.io.IOUtils;
import com.jetbrains.youtrack.db.internal.common.util.CallableFunction;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.Relation;
import com.jetbrains.youtrack.db.internal.core.sql.SQLEngine;
import com.jetbrains.youtrack.db.internal.core.sql.functions.SQLFunctionFiltered;

/**
 *
 */
public abstract class SQLFunctionMoveFiltered extends SQLFunctionMove
    implements SQLFunctionFiltered {

  protected static int supernodeThreshold = 1000; // move to some configuration

  public SQLFunctionMoveFiltered() {
    super(NAME, 1, 2);
  }

  public SQLFunctionMoveFiltered(final String iName, final int iMin, final int iMax) {
    super(iName, iMin, iMax);
  }

  @Override
  public Object execute(
      final Object current,
      final Identifiable iCurrentRecord,
      final Object iCurrentResult,
      final Object[] iParameters,
      final Iterable<Identifiable> possibleResults,
      final CommandContext context) {
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
        argument -> {
          if (argument instanceof Identifiable identifiable) {
            return move(context.getDatabaseSession(), identifiable, labels, possibleResults);
          }
          if (argument instanceof Relation<?> bidirectionalLink) {
            return move(context.getDatabaseSession(), bidirectionalLink, labels);
          }
          throw new IllegalArgumentException(
              "Unsupported argument type: " + argument.getClass().getName());
        }, current, context);
  }

  protected abstract Object move(
      DatabaseSessionInternal graph,
      Identifiable iArgument,
      String[] labels,
      Iterable<Identifiable> iPossibleResults);
}
