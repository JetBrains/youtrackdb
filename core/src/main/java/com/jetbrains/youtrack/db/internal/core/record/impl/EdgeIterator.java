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
import com.jetbrains.youtrack.db.api.record.Direction;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.Vertex;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.common.util.Pair;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaImmutableClass;
import java.util.Iterator;
import javax.annotation.Nonnull;

/**
 *
 */
public class EdgeIterator extends RelationsIteratorAbstract<Vertex, EdgeInternal> {

  public EdgeIterator(
      @Nonnull final Vertex iSourceVertex,
      final Object iMultiValue,
      final Iterator<? extends Identifiable> iterator,
      final Pair<Direction, String> connection,
      final String[] iLabels,
      final int iSize, @Nonnull DatabaseSessionInternal session) {
    super(iSourceVertex, iMultiValue, iterator, connection, iLabels, iSize, session);
  }


  @Override
  protected EdgeInternal createBidirectionalLink(Identifiable identifiable) {
    if (identifiable == null) {
      return null;
    }

    if (identifiable instanceof Entity entity && entity.isStatefulEdge()) {
      return (EdgeInternal) entity.asStatefulEdge();
    }

    final Entity entity;
    try {
      var transaction = session.getActiveTransaction();
      entity = transaction.loadEntity(identifiable);
    } catch (RecordNotFoundException rnf) {
      // SKIP IT
      LogManager.instance().warn(this, "Record (%s) is null", identifiable);
      return null;
    }

    final EdgeInternal edge;
    if (entity.isVertex()) {
      // DIRECT VERTEX, CREATE DUMMY EDGE
      SchemaImmutableClass clazz = null;
      if (connection.getValue() != null) {
        clazz =
            (SchemaImmutableClass)
                session.getMetadata().getImmutableSchemaSnapshot().getClass(connection.getValue());
      }
      if (connection.getKey() == Direction.OUT) {
        edge =
            new EdgeImpl(session,
                this.sourceEntity, entity.asVertex(), clazz);
      } else {
        edge =
            new EdgeImpl(session,
                entity.asVertex(), this.sourceEntity, clazz);
      }
    } else if (entity.isStatefulEdge()) {
      // EDGE
      edge = (EdgeInternal) entity.asStatefulEdge();
    } else {
      throw new IllegalStateException(
          "Invalid content found while iterating edges, value '" + entity + "' is not an edge");
    }

    return edge;
  }
}
