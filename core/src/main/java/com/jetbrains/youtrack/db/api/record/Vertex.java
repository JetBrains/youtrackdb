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
import java.util.Set;

/**
 * Vertex interface represents a vertex in a graph database. Unlike non-typed record it treats some
 * property names differently, namely properties with names starting with prefixes
 * {@code #DIRECTION_IN_PREFIX} and {@code #DIRECTION_OUT_PREFIX} are considered as booked and
 * should not be used by users directly.
 */
@SuppressWarnings({"UnusedReturnValue", "unused"})
public interface Vertex extends Entity {

  /**
   * The name of the class of the vertex record
   */
  String CLASS_NAME = SchemaClass.VERTEX_CLASS_NAME;

  /**
   * A constant variable representing the prefix used for outbound edges in a graph.
   */
  String DIRECTION_OUT_PREFIX = "out_";

  /**
   * A constant variable representing the prefix used for inbound edges in a graph.
   */
  String DIRECTION_IN_PREFIX = "in_";

  /**
   * Returns the names of the edges connected to the vertex in both directions. It is identical to
   * the call : {@code getEdgeNames(Direction.BOTH)}
   *
   * @return a set of strings containing the names of the edges
   */
  Set<String> getEdgeNames();

  /**
   * Returns the names of the edges connected to the vertex, filtered by the given direction.
   *
   * @param direction the direction of the edges to retrieve (IN, OUT, or BOTH)
   * @return a Set of String containing the names of the edges
   */
  Set<String> getEdgeNames(Direction direction);

  /**
   * Retrieves all edges connected to the vertex in the given direction.
   *
   * @param direction the direction of the edges to retrieve (OUT, IN, or BOTH)
   * @return an iterable collection of edges connected to the vertex
   */
  Iterable<Edge> getEdges(Direction direction);

  /**
   * Retrieves all edges connected to the vertex in the given direction and with the specified
   * label(s).
   *
   * @param direction the direction of the edges to retrieve (OUT, IN, or BOTH)
   * @param label     the label(s) of the edges to retrieve
   * @return an iterable collection of edges connected to the vertex
   */
  Iterable<Edge> getEdges(Direction direction, String... label);

  /**
   * Retrieves all edges connected to the vertex in the given direction and with the specified
   * label(s).
   *
   * @param direction the direction of the edges to retrieve (OUT, IN, or BOTH)
   * @param label     the label(s) of the edges to retrieve
   * @return an iterable collection of edges connected to the vertex
   */
  Iterable<Edge> getEdges(Direction direction, SchemaClass... label);

  /**
   * Retrieves all vertices connected to the current vertex in the specified direction.
   *
   * @param direction the direction of the vertices to retrieve (OUT, IN, or BOTH)
   * @return an iterable collection of vertices connected to the current vertex
   */
  Iterable<Vertex> getVertices(Direction direction);

  /**
   * Returns the vertices connected to the current vertex in the specified direction and with the
   * specified label(s).
   *
   * @param direction the direction of the vertices to retrieve (OUT, IN, or BOTH)
   * @param label     the label(s) of the vertices to retrieve
   * @return an iterable collection of vertices connected to the current vertex
   */
  Iterable<Vertex> getVertices(Direction direction, String... label);

  /**
   * Retrieves all vertices connected to the current vertex in the specified direction and with the
   * specified label(s).
   *
   * @param direction the direction of the vertices to retrieve (OUT, IN, or BOTH)
   * @param label     the label(s) of the vertices to retrieve
   * @return an iterable collection of vertices connected to the current vertex
   */
  Iterable<Vertex> getVertices(Direction direction, SchemaClass... label);

  /**
   * Adds a stateful edge between the current vertex and the specified vertex. Edge will be created
   * without any specific label. It is recommended to use labeled edges instead.
   *
   * @param to the vertex to which the edge is connected
   * @return the created edge
   * @see com.jetbrains.youtrack.db.api.schema.Schema#createEdgeClass(String)
   */
  StatefulEdge addStateFulEdge(Vertex to);


  /**
   * Adds an edge between the current vertex and the specified vertex. If label of this edge is
   * related to abstract class it will be created as lightweight edge, otherwise as stateful edge.
   *
   * @param to    the vertex to which the edge is connected
   * @param label the label of the edge (optional)
   * @return the created edge
   * @see com.jetbrains.youtrack.db.api.schema.Schema#createEdgeClass(String)
   * @see com.jetbrains.youtrack.db.api.schema.Schema#createLightweightEdgeClass(String)
   */
  Edge addEdge(Vertex to, String label);

  /**
   * Adds a stateful edge between the current vertex and the specified vertex with the given label.
   * Label of this edge should be related to  non-abstract class (class with given name should
   * exist).
   *
   * @param to    the vertex to which the edge is connected
   * @param label the label of the edge
   * @return the created edge
   * @see com.jetbrains.youtrack.db.api.schema.Schema#createEdgeClass(String)
   */
  StatefulEdge addStateFulEdge(Vertex to, String label);

  /**
   * Adds a lightweight edge (one that does not require associated record) between the current
   * vertex and the specified vertex. Label of this edge should be related to abstract class (class
   * with given name should exist).
   *
   * @param to    the vertex to which the edge is connected
   * @param label the label of the edge (optional)
   * @return the created edge
   * @see com.jetbrains.youtrack.db.api.schema.Schema#createLightweightEdgeClass(String)
   */
  Edge addLightWeightEdge(Vertex to, String label);

  /**
   * Adds an edge between the current vertex and the specified vertex with the given label. If label
   * of this edge is related to abstract class it will be created as lightweight edge, otherwise as
   * stateful edge.
   *
   * @param to    the vertex to which the edge is connected
   * @param label the label of the edge
   * @return the created edge
   * @see com.jetbrains.youtrack.db.api.schema.Schema#createEdgeClass(String)
   * @see com.jetbrains.youtrack.db.api.schema.Schema#createLightweightEdgeClass(String)
   */
  Edge addEdge(Vertex to, SchemaClass label);

  /**
   * Adds a stateful edge between the current vertex and the specified vertex with the given label.
   * Label of this edge should be related to non-abstract class (class with given name should
   * exist).
   *
   * @param to    the vertex to which the edge is connected
   * @param label the label of the edge
   * @return the created edge
   */
  StatefulEdge addStateFulEdge(Vertex to, SchemaClass label);

  /**
   * Adds a lightweight edge (one that does not require associated record) between the current
   * vertex and the specified vertex with the given label. Label of this edge should be related to
   * abstract class.
   *
   * @param to    the vertex to which the edge is connected
   * @param label the label of the edge
   * @return the created edge
   * @see com.jetbrains.youtrack.db.api.schema.Schema#createLightweightEdgeClass(String)
   */
  Edge addLightWeightEdge(Vertex to, SchemaClass label);

  /**
   * Removes all edges connected to the vertex in the given direction.
   *
   * @param direction the direction of the edges to remove (OUT, IN, or BOTH)
   * @param labels    the labels of the edges to remove
   */
  default void removeEdges(Direction direction, SchemaClass... labels) {
    var edges = getEdges(direction, labels);

    for (var edge : edges) {
      edge.delete();
    }
  }

  /**
   * Removes all edges connected to the vertex in the given direction.
   *
   * @param direction the direction of the edges to remove (OUT, IN, or BOTH)
   * @param labels    the labels of the edges to remove
   */
  default void removeEdges(Direction direction, String... labels) {
    var edges = getEdges(direction, labels);

    for (var edge : edges) {
      edge.delete();
    }
  }

  /**
   * Deletes the current vertex.
   */
  void delete();


  /**
   * Returns the name of the field used to store the link to the edge record.
   *
   * @param direction the direction of the edge
   * @param className the name of the edge class
   * @return the name of the field used to store the direct link to the edge
   */
  static String getEdgeLinkFieldName(final Direction direction, final String className) {
    if (direction == null || direction == Direction.BOTH) {
      throw new IllegalArgumentException("Direction not valid");
    }

    // PREFIX "out_" or "in_" TO THE FIELD NAME
    final var prefix = direction == Direction.OUT ? DIRECTION_OUT_PREFIX : DIRECTION_IN_PREFIX;
    if (className == null || className.isEmpty() || className.equals(Edge.CLASS_NAME)) {
      return prefix;
    }

    return prefix + className;
  }
}
