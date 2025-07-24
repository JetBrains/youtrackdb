package com.jetbrains.youtrack.db.internal.core.gremlin;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.isNullOrEmpty;

import com.jetbrains.youtrack.db.api.gremlin.embedded.YTDBEdge;
import com.jetbrains.youtrack.db.api.gremlin.embedded.YTDBVertex;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;
import org.apache.tinkerpop.gremlin.structure.util.ElementHelper;


public interface YTDBVertexInternal extends YTDBVertex {

  List<String> INTERNAL_FIELDS = Arrays.asList("@rid", "@class");

  @Override
  default Iterator<Vertex> vertices(final Direction direction, final String... labels) {
    var graph = (YTDBGraphInternal) graph();
    graph.tx().readWrite();
    Stream<Vertex> vertexStream =
        StreamUtils.asStream(
                getRawEntity().asVertex()
                    .getVertices(YTDBGraphUtils.mapDirection(direction), labels)
                    .iterator())
            .map(v -> new YTDBVertexImpl(graph, v));

    return vertexStream.iterator();
  }

  @Override
  default <V> VertexProperty<V> property(String key) {
    var graph = (YTDBGraphInternal) graph();
    graph.tx().readWrite();
    if (key == null || key.isEmpty()) {
      return VertexProperty.empty();
    }

    var entity = getRawEntity();
    if (entity.hasProperty(key) && !INTERNAL_FIELDS.contains(key) &&
        !key.startsWith("_meta_")) {
      return new YTDBVertexPropertyImpl<>(key, entity.getProperty(key), this);
    }

    return VertexProperty.empty();
  }


  @Override
  default <V> VertexProperty<V> property(
      final String key, final V value, final Object... keyValues) {
    var vertexProperty = this.property(key, value);

    if (ElementHelper.getIdValue(keyValues).isPresent()) {
      throw VertexProperty.Exceptions.userSuppliedIdsNotSupported();
    }

    ElementHelper.legalPropertyKeyValueArray(keyValues);
    ElementHelper.attachProperties(vertexProperty, keyValues);
    return vertexProperty;
  }

  @Override
  default <V> VertexProperty<V> property(
      final VertexProperty.Cardinality cardinality,
      final String key,
      final V value,
      final Object... keyValues) {
    return this.property(key, value, keyValues);
  }

  @Override
  default YTDBEdge addEdge(String label, Vertex inVertex, Object... keyValues) {
    if (inVertex == null) {
      throw new IllegalArgumentException("destination vertex is null");
    }

    checkArgument(!isNullOrEmpty(label), "label is invalid");

    ElementHelper.legalPropertyKeyValueArray(keyValues);
    if (ElementHelper.getIdValue(keyValues).isPresent()) {
      throw Vertex.Exceptions.userSuppliedIdsNotSupported();
    }
    if (Graph.Hidden.isHidden(label)) {
      throw Element.Exceptions.labelCanNotBeAHiddenKey(label);
    }

    var graph = (YTDBGraphInternal) graph();
    var tx = graph.tx();
    tx.readWrite();

    var session = tx.getDatabaseSession();

    var edgeClass = session.getMetadata().getImmutableSchemaSnapshot().getClass(label);
    if (edgeClass == null) {
      try (var copy = session.copy()) {
        var schemaCopy = copy.getSchema();
        var edgeCls = schemaCopy.getClass(
            com.jetbrains.youtrack.db.api.record.Edge.CLASS_NAME);
        schemaCopy.getOrCreateClass(label, edgeCls);
      }
    }

    var vertex = getRawEntity().asVertex();
    var ytdbEdge = vertex.addStateFulEdge(
        ((YTDBElementImpl) inVertex).getRawEntity().asVertex(),
        label);
    var edge = new YTDBStatefulEdgeImpl(graph, ytdbEdge);
    edge.property(keyValues);

    return edge;
  }

  @Override
  default Iterator<Edge> edges(final Direction direction, String... edgeLabels) {
    var graph = (YTDBGraphInternal) graph();
    graph.tx().readWrite();
    // It should not collect but instead iterating through the relations.
    // But necessary in order to avoid loop in
    // EdgeTest#shouldNotHaveAConcurrentModificationExceptionWhenIteratingAndRemovingAddingEdges
    Stream<Edge> edgeStream =
        StreamUtils.asStream(
                getRawEntity().asVertex()
                    .getEdges(YTDBGraphUtils.mapDirection(direction), edgeLabels)
                    .iterator())
            .filter(e -> e != null && e.isStateful() && e.getFrom() != null && e.getTo() != null)
            .map(e -> new YTDBStatefulEdgeImpl(graph, e.asStatefulEdge()));

    return edgeStream.collect(Collectors.toList()).iterator();
  }


  com.jetbrains.youtrack.db.api.record.Vertex getRawEntity();
}
