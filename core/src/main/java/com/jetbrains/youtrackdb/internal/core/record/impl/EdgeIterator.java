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
package com.jetbrains.youtrackdb.internal.core.record.impl;

import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.api.record.Direction;
import com.jetbrains.youtrackdb.api.record.Entity;
import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.api.record.Vertex;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.common.util.Pair;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassSnapshot;
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
      SchemaClassSnapshot clazz = null;
      if (connection.getValue() != null) {
        clazz =
            (SchemaClassSnapshot)
                session.getMetadata().getFastImmutableSchema()
                    .getClass(connection.getValue());
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
