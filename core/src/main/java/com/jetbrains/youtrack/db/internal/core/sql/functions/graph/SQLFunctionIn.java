package com.jetbrains.youtrack.db.internal.core.sql.functions.graph;

import com.jetbrains.youtrack.db.api.record.Direction;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.Vertex;
import com.jetbrains.youtrack.db.internal.common.collection.MultiCollectionIterator;
import com.jetbrains.youtrack.db.internal.common.util.Sizeable;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.index.CompositeKey;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.record.impl.Relation;
import java.util.Collections;
import java.util.Iterator;
import java.util.stream.Collectors;
import javax.annotation.Nullable;


public class SQLFunctionIn extends SQLFunctionMoveFiltered {
  public static final String NAME = "in";

  public SQLFunctionIn() {
    super(NAME, 0, -1);
  }

  @Override
  protected Object move(
      final DatabaseSessionInternal graph, final Identifiable record, final String[] labels) {
    return v2v(graph, record, Direction.IN, labels);
  }

  @Override
  protected Object move(DatabaseSessionInternal db,
      Relation<?> bidirectionalLink, String[] labels) {
    throw new UnsupportedOperationException(
        "Function in is not supported for bidirectional links");
  }

  protected Object move(
      final DatabaseSessionInternal graph,
      final Identifiable iRecord,
      final String[] iLabels,
      Iterable<Identifiable> iPossibleResults) {
    if (iPossibleResults == null) {
      return v2v(graph, iRecord, Direction.IN, iLabels);
    }

    if (!iPossibleResults.iterator().hasNext()) {
      return Collections.emptyList();
    }

    var edges = v2e(graph, iRecord, Direction.IN, iLabels);
    if (edges instanceof Sizeable sizeable && sizeable.isSizeable()) {
      var size = sizeable.size();
      if (size > supernodeThreshold) {
        var result = fetchFromIndex(graph, iRecord, iPossibleResults, iLabels);
        if (result != null) {
          return result;
        }
      }
    }

    return v2v(graph, iRecord, Direction.IN, iLabels);
  }

  @Nullable
  private static Iterator<Vertex> fetchFromIndex(
      DatabaseSessionInternal session,
      Identifiable iFrom,
      Iterable<Identifiable> to,
      String[] iEdgeTypes) {
    String edgeClassName;
    if (iEdgeTypes == null) {
      edgeClassName = "E";
    } else if (iEdgeTypes.length == 1) {
      edgeClassName = iEdgeTypes[0];
    } else {
      return null;
    }
    var edgeClass =
        session
            .getMetadata()
            .getImmutableSchemaSnapshot()
            .getClassInternal(edgeClassName);
    if (edgeClass == null) {
      return null;
    }
    var indexes = edgeClass.getInvolvedIndexesInternal(session, "in", "out");
    if (indexes == null || indexes.isEmpty()) {
      return null;
    }
    var index = indexes.iterator().next();

    var result = new MultiCollectionIterator<Vertex>();
    for (var identifiable : to) {
      var key = new CompositeKey(iFrom, identifiable);
      try (var stream = index
          .getRids(session, key)) {
        result.add(
            stream
                .map((edge) -> {
                  var transaction = session.getActiveTransaction();
                  EntityImpl entity = transaction.load(edge);
                  return entity.getProperty("out");
                })
                .collect(Collectors.toSet()));
      }
    }

    return result;
  }
}
