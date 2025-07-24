package com.jetbrains.youtrack.db.internal.core.gremlin;

import com.jetbrains.youtrack.db.api.gremlin.YTDBGraph;
import com.jetbrains.youtrack.db.api.gremlin.YTDBVertexPropertyId;
import com.jetbrains.youtrack.db.api.gremlin.embedded.YTDBVertex;
import com.jetbrains.youtrack.db.api.record.Entity;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.stream.Stream;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Property;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;
import org.apache.tinkerpop.gremlin.structure.util.ElementHelper;

public class YTDBVertexPropertyImpl<V> extends YTDBPropertyImpl<V> implements
    VertexProperty<V> {

  public YTDBVertexPropertyImpl(Property<V> property, Vertex vertex) {
    super(property.key(), property.value(), (YTDBElementImpl) vertex);
  }

  public YTDBVertexPropertyImpl(String key, V value, Vertex vertex) {
    super(key, value, (YTDBElementImpl) vertex);
  }

  @Override
  public YTDBVertexPropertyId id() {
    return new YTDBVertexPropertyId(element.id(), key());
  }

  @Override
  public <U> Property<U> property(String key, U value) {
    if (T.id.getAccessor().equals(key)) {
      throw VertexProperty.Exceptions.userSuppliedIdsNotSupported();
    }

    var metadata = getMetadataEntity();

    metadata.setProperty(key, value);
    return new YTDBVertexPropertyProperty<>(key, value, this);
  }

  @Override
  public <U> Iterator<Property<U>> properties(String... propertyKeys) {
    if (!hasMetadataDocument()) {
      return Collections.emptyIterator();
    }

    var properties = getMetadataEntity().toMap(false);
    var keys = new HashSet<>(Arrays.asList(propertyKeys));

    var entries =
        StreamUtils.asStream(properties.entrySet().iterator());
    if (!keys.isEmpty()) {
      entries = entries.filter(entry -> keys.contains(entry.getKey()));
    }

    @SuppressWarnings("unchecked")
    Stream<Property<U>> propertyStream =
        entries
            .filter(entry -> !entry.getKey().startsWith("@rid"))
            .map(
                entry ->
                    new YTDBVertexPropertyProperty<>(entry.getKey(), (U) entry.getValue(), this));
    return propertyStream.iterator();
  }

  private boolean hasMetadataDocument() {
    return element.getRawEntity().getProperty(metadataKey()) != null;
  }

  public void removeMetadata(String key) {
    var metadata = getMetadataEntity();
    metadata.removeProperty(key);

    if (metadata.getPropertyNames().isEmpty()) {
      element.getRawEntity().removeProperty(metadataKey());
    }
  }

  Entity getMetadataEntity() {
    var metadata = element.getRawEntity().getEntity(metadataKey());

    if (metadata == null) {
      var graph = element.getGraph();
      var graphTx = graph.tx();
      var session = graphTx.getDatabaseSession();
      var tx = session.getActiveTransaction();

      metadata = tx.newEmbeddedEntity();
      var vertexEntity = element.getRawEntity();
      vertexEntity.setProperty(metadataKey(), metadata);
    }

    return metadata;
  }

  @Override
  public void remove() {
    super.remove();
    element.getRawEntity().removeProperty(metadataKey());
  }

  private String metadataKey() {
    return "_meta_" + key;
  }

  @SuppressWarnings("EqualsDoesntCheckParameterClass")
  @Override
  public boolean equals(final Object object) {
    return ElementHelper.areEqual(this, object);
  }

  @Override
  public int hashCode() {
    return ElementHelper.hashCode((Element) this);
  }

  @Override
  public YTDBVertex element() {
    return (YTDBVertex) element;
  }

  @Override
  public YTDBGraph graph() {
    return element.graph();
  }

}
