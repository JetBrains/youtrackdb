package com.jetbrains.youtrackdb.internal.core.sql.functions.graph;

import com.jetbrains.youtrackdb.api.record.Direction;
import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.api.record.Relation;
import com.jetbrains.youtrackdb.api.record.Vertex;
import com.jetbrains.youtrackdb.internal.common.collection.MultiCollectionIterator;
import com.jetbrains.youtrackdb.internal.common.collection.YTDBIteratorUtils;
import com.jetbrains.youtrackdb.internal.common.util.Sizeable;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.index.CompositeKey;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import javax.annotation.Nullable;


public class SQLFunctionIn extends SQLFunctionMoveFiltered implements SQLGraphNavigationFunction {

  public static final String NAME = "in";

  public SQLFunctionIn() {
    super(NAME, 0, -1);
  }

  @Override
  protected Object move(
      final DatabaseSessionEmbedded graph, final Identifiable record, final String[] labels) {
    return v2v(graph, record, Direction.IN, labels);
  }

  @Override
  protected Object move(DatabaseSessionEmbedded db,
      Relation<?> bidirectionalLink, String[] labels) {
    throw new UnsupportedOperationException(
        "Function in is not supported for bidirectional links");
  }

  @Override
  protected Object move(
      final DatabaseSessionEmbedded graph,
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
      DatabaseSessionEmbedded session,
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
            .getFastImmutableSchema()
            .getClass(edgeClassName);
    if (edgeClass == null) {
      return null;
    }
    var indexes = edgeClass.getInvolvedIndexes("in", "out");
    if (indexes == null || indexes.isEmpty()) {
      return null;
    }
    var index = indexes.iterator().next();

    var result = new MultiCollectionIterator<Vertex>();
    for (var identifiable : to) {
      var key = new CompositeKey(iFrom, identifiable);
      try (var iterator = index
          .getRids(session, key)) {
        result.add(
            YTDBIteratorUtils.set(YTDBIteratorUtils.map(iterator,
                (edge) -> {
                  var transaction = session.getActiveTransaction();
                  EntityImpl entity = transaction.load(edge);
                  return entity.getProperty("out");
                })
            )
        );
      }
    }

    return result;
  }

  @Nullable
  @Override
  public Collection<String> propertyNamesForIndexCandidates(String[] labels,
      ImmutableSchemaClass schemaClass,
      boolean polymorphic, DatabaseSessionEmbedded session) {
    return SQLGraphNavigationFunction.propertiesForV2VNavigation(schemaClass, session,
        Direction.IN,
        labels);
  }
}
