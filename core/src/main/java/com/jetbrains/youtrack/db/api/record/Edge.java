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
package com.jetbrains.youtrack.db.api.record;

import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface Edge {
  /**
   * The name of the class of the edge record
   */
  String CLASS_NAME = SchemaClass.EDGE_CLASS_NAME;

  /**
   * The name of the property that represents link to the vertex from which the edge is going out.
   * This property is used in the internal representation of the edge and can not be updated
   * directly.
   *
   * <p>You can apply composite unique index to this and {@link #DIRECTION_IN} properties to ensure
   * one-to-one relationship between vertices.
   */
  String DIRECTION_OUT = "out";

  /**
   * The name of the property that represents link to the vertex to which the edge is going in. This
   * property is used in the internal representation of the edge and can not be updated directly.
   *
   * <p>You can apply composite unique index to this and {@link #DIRECTION_OUT} properties to
   * ensure one-to-one relationship between vertices.
   */
  String DIRECTION_IN = "in";

  /**
   * Retrieves the vertex that this edge originates from.
   *
   * @return the vertex that this edge originates from
   */
  @Nullable
  Vertex getFrom();

  /**
   * Retrieves the identifiable object from where this edge originates.
   *
   * @return the identifiable object from where this edge originates
   */
  @Nullable
  Identifiable getFromLink();

  /**
   * Retrieves the vertex that this edge connects to.
   *
   * @return the vertex that this edge connects to
   */
  @Nullable
  Vertex getTo();

  /**
   * Retrieves the identifiable object from where the edge connects to.
   *
   * @return the identifiable object from where the edge connects to
   */
  @Nullable
  Identifiable getToLink();

  /**
   * Checks if the edge is lightweight.
   *
   * @return true if the edge is lightweight, false otherwise.
   */
  boolean isLightweight();

  /**
   * Checks if the edge is stateful.
   *
   * @return true if the edge is stateful, false otherwise.
   */
  default boolean isStateful() {
    return !isLightweight();
  }

  /**
   * Retrieves the vertex connected to this edge in the specified direction.
   *
   * @param dir the direction of the edge (IN or OUT)
   * @return the vertex connected to this edge in the specified direction, or null if no vertex is
   * connected
   */
  @Nullable
  default Vertex getVertex(@Nonnull Direction dir) {
    if (dir == Direction.IN) {
      return getTo();
    } else if (dir == Direction.OUT) {
      return getFrom();
    }
    throw new IllegalArgumentException("Direction not supported: " + dir);
  }

  /**
   * Retrieves the identifiable object of the vertex connected to this edge in the specified
   * direction.
   *
   * @param dir the direction of the edge (IN or OUT)
   * @return the identifiable object of the vertex connected to this edge in the specified
   * direction, or null if no vertex is connected
   */
  @Nullable
  default Identifiable getVertexLink(@Nonnull Direction dir) {
    if (dir == Direction.IN) {
      return getToLink();
    } else if (dir == Direction.OUT) {
      return getFromLink();
    }
    throw new IllegalArgumentException("Direction not supported: " + dir);
  }

  /**
   * Check if the given labels match the labels of the edge.
   *
   * @param labels the labels to check
   * @return true if the labels match, false otherwise
   */
  boolean isLabeled(@Nonnull String[] labels);

  /**
   * Retrieves the schema class associated with this edge.
   *
   * @return the schema class associated with this edge.
   */
  @Nonnull
  SchemaClass getSchemaClass();

  /**
   * Retrieves the class name associated with this edge
   */
  @Nonnull
  String getSchemaClassName();

  /**
   * Casts this edge to a stateful edge if this is a stateful edge. If this is not a stateful edge,
   * an exception is thrown.
   */
  @Nonnull
  StatefulEdge asStatefulEdge();

  /**
   * Casts this edge to a stateful edge if this is a stateful edge. If this is not a stateful edge,
   * null is returned.
   */
  @Nullable
  StatefulEdge asStatefulEdgeOrNull();

  /**
   * Deletes the edge from the graph.
   */
  void delete();

  @Nonnull
  Map<String, Object> toMap();

  @Nonnull
  String toJSON();
}
