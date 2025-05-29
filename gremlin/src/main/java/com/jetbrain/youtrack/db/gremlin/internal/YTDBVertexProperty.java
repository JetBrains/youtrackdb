package com.jetbrain.youtrack.db.gremlin.internal;

import com.jetbrains.youtrack.db.api.record.Entity;

import java.util.*;
import java.util.stream.Stream;

import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Property;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;
import org.apache.tinkerpop.gremlin.structure.util.ElementHelper;

public class YTDBVertexProperty<V> extends YTDBProperty<V> implements VertexProperty<V> {

  public YTDBVertexProperty(Property<V> property, YTDBVertex vertex) {
    super(property.key(), property.value(), vertex);
  }

  public YTDBVertexProperty(String key, V value, YTDBVertex vertex) {
    super(key, value, vertex);
  }

  @Override
  public String id() {
    return String.format("%s_%s", element.id(), key());
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

    Map<String, Object> properties = getMetadataEntity().toMap();
    HashSet<String> keys = new HashSet<>(Arrays.asList(propertyKeys));

    Stream<Map.Entry<String, Object>> entries =
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
      var session = graph.getUnderlyingSession();
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
  public Vertex element() {
    return (Vertex) element;
  }
}
