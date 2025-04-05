/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrack.db.internal.core.record.impl;

import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.Direction;
import com.jetbrains.youtrack.db.api.record.Edge;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.Vertex;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.common.util.Pair;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseRecordThreadLocal;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.iterator.LazyWrapperIterator;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaImmutableClass;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import java.util.Iterator;

/**
 *
 */
public class EdgeIterator extends LazyWrapperIterator<Edge> {

  private final Vertex sourceVertex;
  private final Vertex targetVertex;
  private final Pair<Direction, String> connection;
  private final String[] labels;

  public EdgeIterator(
      final Vertex iSourceVertex,
      final Object iMultiValue,
      final Iterator<?> iterator,
      final Pair<Direction, String> connection,
      final String[] iLabels,
      final int iSize) {
    this(iSourceVertex, null, iMultiValue, iterator, connection, iLabels, iSize);
  }

  public EdgeIterator(
      final Vertex iSourceVertex,
      final Vertex iTargetVertex,
      final Object iMultiValue,
      final Iterator<?> iterator,
      final Pair<Direction, String> connection,
      final String[] iLabels,
      final int iSize) {
    super(iterator, iSize, iMultiValue);
    this.sourceVertex = iSourceVertex;
    this.targetVertex = iTargetVertex;
    this.connection = connection;
    this.labels = iLabels;
  }

  public Edge createGraphElement(final Object iObject) {
    if (iObject instanceof Entity && ((Entity) iObject).isEdge()) {
      return ((Entity) iObject).asEdge().get();
    }

    final Identifiable rec = (Identifiable) iObject;

    if (rec == null) {
      // SKIP IT
      return null;
    }

    final DBRecord record;
    try {
      record = rec.getRecord();
    } catch (RecordNotFoundException rnf) {
      // SKIP IT
      LogManager.instance().warn(this, "Record (%s) is null", rec);
      return null;
    }

    if (!(record instanceof Entity value)) {
      // SKIP IT
      LogManager.instance()
          .warn(
              this,
              "Found a record (%s) that is not an edge. Source vertex : %s, Target vertex : %s,"
                  + " Database : %s",
              rec,
              sourceVertex != null ? sourceVertex.getIdentity() : null,
              targetVertex != null ? targetVertex.getIdentity() : null,
              ((RecordAbstract) record).getSession().getURL());
      return null;
    }

    final Edge edge;
    if (value.isVertex()) {
      // DIRECT VERTEX, CREATE DUMMY EDGE
      DatabaseSessionInternal db = DatabaseRecordThreadLocal.instance().getIfDefined();
      SchemaImmutableClass clazz = null;
      if (db != null && connection.getValue() != null) {
        clazz =
            (SchemaImmutableClass)
                db.getMetadata().getImmutableSchemaSnapshot().getClass(connection.getValue());
      }
      if (connection.getKey() == Direction.OUT) {
        edge =
            new EdgeDelegate(
                this.sourceVertex, value.asVertex().get(), clazz, connection.getValue());
      } else {
        edge =
            new EdgeDelegate(
                value.asVertex().get(), this.sourceVertex, clazz, connection.getValue());
      }
    } else if (value.isEdge()) {
      // EDGE
      edge = value.asEdge().get();
    } else {
      throw new IllegalStateException(
          "Invalid content found while iterating edges, value '" + value + "' is not an edge");
    }

    return edge;
  }

  @Override
  public Edge next() {
    return createGraphElement(super.next());
  }

  @Override
  public boolean filter(final Edge iObject) {
    if (targetVertex != null
        && !targetVertex.equals(iObject.getVertex(connection.getKey().opposite()))) {
      return false;
    }

    return iObject.isLabeled(labels);
  }

  @Override
  public boolean canUseMultiValueDirectly() {
    return true;
  }
}
