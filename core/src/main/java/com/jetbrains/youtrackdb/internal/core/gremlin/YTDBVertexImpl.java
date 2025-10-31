package com.jetbrains.youtrackdb.internal.core.gremlin;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.isNullOrEmpty;

import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBEdge;
import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBVertexProperty;
import com.jetbrains.youtrackdb.api.record.RID;
import java.util.Iterator;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Graph.Hidden;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;
import org.apache.tinkerpop.gremlin.structure.VertexProperty.Cardinality;
import org.apache.tinkerpop.gremlin.structure.util.ElementHelper;
import org.apache.tinkerpop.gremlin.structure.util.StringFactory;

public final class YTDBVertexImpl extends YTDBElementImpl implements YTDBVertexInternal {

  public YTDBVertexImpl(
      final YTDBGraphInternal graph,
      final com.jetbrains.youtrackdb.api.record.Vertex rawElement
  ) {
    super(graph, rawElement);
  }

  public YTDBVertexImpl(final YTDBGraphInternal graph, final RID vertexId) {
    super(graph, vertexId);
  }

  @Override
  public <V> YTDBVertexProperty<V> property(final String key, final V value) {
    return writeProperty(YTDBPropertyFactory.ytdbVectorProps(), key, value);
  }

  @Override
  public <V> YTDBVertexProperty<V> property(String key) {
    return readProperty(YTDBPropertyFactory.<V>ytdbVectorProps(), key);
  }

  @Override
  public <V> Iterator<VertexProperty<V>> properties(final String... propertyKeys) {
    return readProperties(YTDBPropertyFactory.stdVectorProps(), propertyKeys
    );
  }

  @Override
  public <V> YTDBVertexProperty<V> property(
      final String key, final V value, final Object... keyValues) {

    if (ElementHelper.getIdValue(keyValues).isPresent()) {
      throw VertexProperty.Exceptions.userSuppliedIdsNotSupported();
    }
    ElementHelper.legalPropertyKeyValueArray(keyValues);

    var vertexProperty = this.property(key, value);
    ElementHelper.attachProperties(vertexProperty, keyValues);
    return vertexProperty;
  }

  @Override
  public <V> YTDBVertexProperty<V> property(
      final Cardinality cardinality,
      final String key,
      final V value,
      final Object... keyValues) {
    return this.property(key, value, keyValues);
  }

  @Override
  public Iterator<Vertex> vertices(
      final Direction direction,
      final String... labels) {
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
  public Iterator<Edge> edges(final Direction direction, String... edgeLabels) {
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
            .map(e -> new YTDBStatefulStatefulEdgeImpl(graph, e.asStatefulEdge()));

    return edgeStream.collect(Collectors.toList()).iterator();
  }

  @Override
  public YTDBEdge addEdge(String label, Vertex inVertex, Object... keyValues) {
    if (inVertex == null) {
      throw new IllegalArgumentException("destination vertex is null");
    }

    checkArgument(!isNullOrEmpty(label), "label is invalid");

    ElementHelper.legalPropertyKeyValueArray(keyValues);
    if (ElementHelper.getIdValue(keyValues).isPresent()) {
      throw Vertex.Exceptions.userSuppliedIdsNotSupported();
    }
    if (Hidden.isHidden(label)) {
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
            com.jetbrains.youtrackdb.api.record.Edge.CLASS_NAME);
        schemaCopy.getOrCreateClass(label, edgeCls);
      }
    }

    var vertex = getRawEntity().asVertex();
    var ytdbEdge = vertex.addStateFulEdge(
        ((YTDBElementImpl) inVertex).getRawEntity().asVertex(),
        label);
    var edge = new YTDBStatefulStatefulEdgeImpl(graph, ytdbEdge);
    ElementHelper.attachProperties(edge, keyValues);

    return edge;
  }

  @Override
  public String toString() {
    return StringFactory.vertexString(this);
  }

  @Override
  public com.jetbrains.youtrackdb.api.record.Vertex getRawEntity() {
    return super.getRawEntity().asVertex();
  }
}
