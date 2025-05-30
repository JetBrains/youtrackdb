package com.jetbrain.youtrack.db.gremlin.internal;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.jetbrain.youtrack.db.gremlin.internal.StreamUtils.asStream;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Property;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;
import org.apache.tinkerpop.gremlin.structure.util.ElementHelper;
import org.apache.tinkerpop.gremlin.structure.util.StringFactory;

public final class YTDBVertex extends YTDBElement implements Vertex {

  private static final List<String> INTERNAL_FIELDS = Arrays.asList("@rid", "@class");

  public YTDBVertex(final YTDBGraphInternal graph,
      final com.jetbrains.youtrack.db.api.record.Vertex rawElement) {
    super(graph, rawElement);
  }

  @Override
  public Iterator<Vertex> vertices(final Direction direction, final String... labels) {
    this.graph.tx().readWrite();
    Stream<Vertex> vertexStream =
        asStream(
            getRawEntity().asVertex()
                .getVertices(YTDBGraphUtils.mapDirection(direction), labels)
                .iterator())
            .map(v -> graph.elementFactory().wrapVertex(v));

    return vertexStream.iterator();
  }

  @Override
  public <V> VertexProperty<V> property(String key) {
    graph.tx().readWrite();
    if (key == null || key.isEmpty()) {
      return VertexProperty.empty();
    }

    var entity = getRawEntity();
    if (entity.hasProperty(key) && !INTERNAL_FIELDS.contains(key) &&
        !key.startsWith("_meta_")) {
      return new YTDBVertexProperty<>(key, entity.getProperty(key), this);
    }
    return VertexProperty.empty();
  }

  @Override
  public <V> Iterator<VertexProperty<V>> properties(final String... propertyKeys) {
    Iterator<? extends Property<V>> properties = super.properties(propertyKeys);
    return StreamUtils.asStream(properties)
        .filter(p -> !INTERNAL_FIELDS.contains(p.key()))
        .filter(p -> !p.key().startsWith("_meta_"))
        .map(
            p ->
                (VertexProperty<V>)
                    new YTDBVertexProperty<>(p.key(), p.value(), (YTDBVertex) p.element()))
        .iterator();
  }


  @Override
  public <V> VertexProperty<V> property(final String key, final V value) {
    return new YTDBVertexProperty<>(super.property(key, value), this);
  }

  @Override
  public <V> VertexProperty<V> property(
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
  public <V> VertexProperty<V> property(
      final VertexProperty.Cardinality cardinality,
      final String key,
      final V value,
      final Object... keyValues) {
    return this.property(key, value, keyValues);
  }

  @Override
  public String toString() {
    return StringFactory.vertexString(this);
  }

  @Override
  public Edge addEdge(String label, Vertex inVertex, Object... keyValues) {
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

    var session = graph.getUnderlyingSession();
    var edgeClass = session.getMetadata().getImmutableSchemaSnapshot().getClass(label);
    if (edgeClass == null) {
      if (session.isTxActive()) {
        try (var copy = session.copy()) {
          var schemaCopy = copy.getSchema();
          var edgeCls = schemaCopy.getClass(com.jetbrains.youtrack.db.api.record.Edge.CLASS_NAME);
          schemaCopy.getOrCreateClass(label, edgeCls);
        }
      } else {
        var schema = session.getSchema();
        var edgeCls = schema.getClass(com.jetbrains.youtrack.db.api.record.Edge.CLASS_NAME);
        schema.getOrCreateClass(label, edgeCls);
      }
    }

    this.graph.tx().readWrite();

    var vertex = getRawEntity().asVertex();
    var ytdbEdge = vertex.addStateFulEdge(((YTDBVertex) inVertex).getRawEntity().asVertex(), label);
    var edge = graph.elementFactory().wrapEdge(ytdbEdge);
    edge.property(keyValues);

    return edge;
  }

  @Override
  public Iterator<Edge> edges(final Direction direction, String... edgeLabels) {
    this.graph.tx().readWrite();
    // It should not collect but instead iterating through the relations.
    // But necessary in order to avoid loop in
    // EdgeTest#shouldNotHaveAConcurrentModificationExceptionWhenIteratingAndRemovingAddingEdges
    Stream<Edge> edgeStream =
        asStream(
            getRawEntity().asVertex()
                .getEdges(YTDBGraphUtils.mapDirection(direction), edgeLabels)
                .iterator())
            .filter(e -> e != null && e.getFrom() != null && e.getTo() != null)
            .map(e -> graph.elementFactory().wrapEdge(e.asStatefulEdge()));

    return edgeStream.collect(Collectors.toList()).iterator();
  }
}
