package com.jetbrains.youtrackdb.internal.core.sql.functions.graph;

import com.jetbrains.youtrackdb.api.record.Direction;
import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.api.record.Relation;
import com.jetbrains.youtrackdb.api.record.Vertex;
import com.jetbrains.youtrackdb.internal.common.collection.MultiCollectionIterator;
import com.jetbrains.youtrackdb.internal.common.util.Sizeable;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.index.CompositeKey;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.Collection;
import java.util.Collections;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 *
 */
public class SQLFunctionOut extends SQLFunctionMoveFiltered implements SQLGraphNavigationFunction {

  public static final String NAME = "out";

  public SQLFunctionOut() {
    super(NAME, 0, -1);
  }

  @Override
  protected Object move(
      final DatabaseSessionEmbedded graph, final Identifiable record, final String[] labels) {
    return v2v(graph, record, Direction.OUT, labels);
  }

  @Override
  protected Object move(DatabaseSessionEmbedded db,
      Relation<?> bidirectionalRelation, String[] labels) {
    throw new UnsupportedOperationException(
        "Function 'out' is not supported for bidirectional links");
  }

  @Override
  protected Object move(
      final DatabaseSessionEmbedded graph,
      final Identifiable iRecord,
      final String[] iLabels,
      Iterable<Identifiable> iPossibleResults) {
    if (iPossibleResults == null) {
      return v2v(graph, iRecord, Direction.OUT, iLabels);
    }

    if (!iPossibleResults.iterator().hasNext()) {
      return Collections.emptyList();
    }

    var edges = v2e(graph, iRecord, Direction.OUT, iLabels);
    if (edges instanceof Sizeable sizeable && sizeable.isSizeable()) {
      var size = sizeable.size();
      if (size > supernodeThreshold) {
        var result = fetchFromIndex(graph, iRecord, iPossibleResults, iLabels);
        if (result != null) {
          return result;
        }
      }
    }

    return v2v(graph, iRecord, Direction.OUT, iLabels);
  }

  @Nullable
  private static Object fetchFromIndex(
      DatabaseSessionEmbedded session,
      Identifiable iFrom,
      Iterable<Identifiable> iTo,
      String[] iEdgeTypes) {
    String edgeClassName = null;
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
            .getFastImmutableSchema(session)
            .getClassInternal(edgeClassName);
    if (edgeClass == null) {
      return null;
    }
    var indexes = edgeClass.getInvolvedIndexesInternal(session, "out", "in");
    if (indexes == null || indexes.isEmpty()) {
      return null;
    }
    var index = indexes.iterator().next();

    var result = new MultiCollectionIterator<Vertex>();
    for (var to : iTo) {
      final var key = new CompositeKey(iFrom, to);
      try (var stream = index
          .getRids(session, key)) {
        result.add(
            stream
                .map((rid) -> {
                  var transaction = session.getActiveTransaction();
                  EntityImpl entity = transaction.load(rid);
                  return entity.getProperty("in");
                })
                .collect(Collectors.toSet()));
      }
    }

    return result;
  }

  @Nullable
  @Override
  public Collection<String> propertyNamesForIndexCandidates(String[] labels,
      SchemaClass schemaClass,
      boolean polymorphic, DatabaseSessionEmbedded session) {
    return SQLGraphNavigationFunction.propertiesForV2VNavigation(schemaClass, session,
        Direction.OUT,
        labels);
  }
}
