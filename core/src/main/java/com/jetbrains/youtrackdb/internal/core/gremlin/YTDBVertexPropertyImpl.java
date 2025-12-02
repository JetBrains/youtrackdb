package com.jetbrains.youtrackdb.internal.core.gremlin;

import com.jetbrains.youtrackdb.api.gremlin.YTDBGraph;
import com.jetbrains.youtrackdb.api.gremlin.YTDBVertexPropertyId;
import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBProperty;
import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBVertex;
import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBVertexProperty;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl.PropertyValidationMode;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Property;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;
import org.apache.tinkerpop.gremlin.structure.util.ElementHelper;

public class YTDBVertexPropertyImpl<V> extends YTDBPropertyImpl<V> implements
    YTDBVertexProperty<V> {

  public YTDBVertexPropertyImpl(
      String key,
      @Nullable V value,
      @Nullable PropertyType propertyType,
      YTDBVertex vertex
  ) {
    super(key, value, propertyType, (YTDBElementImpl) vertex);
  }

  @Override
  public YTDBVertexPropertyId id() {
    return new YTDBVertexPropertyId(element.id(), key());
  }

  @Override
  public <U> YTDBProperty<U> property(String key, U value) {
    if (T.id.getAccessor().equals(key)) {
      throw VertexProperty.Exceptions.userSuppliedIdsNotSupported();
    }

    var graph = (YTDBGraphInternal) graph();
    graph.tx().readWrite();
    var metadata = getMetadataEntity();

    metadata.setProperty(key, value);
    return new YTDBVertexPropertyProperty<>(key, value, metadata.getPropertyType(key), this);
  }

  @Override
  public <U> Iterator<Property<U>> properties(String... propertyKeys) {
    final var graph = (YTDBGraphInternal) graph();
    graph.tx().readWrite();
    if (!hasMetadataDocument()) {
      return Collections.emptyIterator();
    }

    final var metadataEntity = getMetadataEntity();
    final var properties = metadataEntity.toMap(false);
    final var keys = new HashSet<>(Arrays.asList(propertyKeys));

    var entries =
        StreamUtils.asStream(properties.entrySet().iterator());
    if (!keys.isEmpty()) {
      entries = entries.filter(entry -> keys.contains(entry.getKey()));
    }

    @SuppressWarnings("unchecked")
    Stream<Property<U>> propertyStream =
        entries
            .filter(entry -> !entry.getKey().startsWith("@rid"))
            .map(entry -> new YTDBVertexPropertyProperty<>(
                entry.getKey(),
                (U) entry.getValue(),
                metadataEntity.getPropertyType(entry.getKey()),
                this
            ));
    return propertyStream.iterator();
  }

  @Override
  public boolean hasProperty(String key) {
    var graph = (YTDBGraphInternal) graph();
    graph.tx().readWrite();
    if (!hasMetadataDocument()) {
      return false;
    }
    return getMetadataEntity().hasProperty(key);
  }

  @Override
  public boolean removeProperty(String key) {
    var graph = (YTDBGraphInternal) graph();
    graph.tx().readWrite();
    if (!hasMetadataDocument()) {
      return false;
    }
    return getMetadataEntity().removeProperty(key);
  }

  private boolean hasMetadataDocument() {
    return element.getRawEntity().getProperty(metadataKey()) != null;
  }

  public void removeMetadata(String key) {
    var metadata = getMetadataEntity();
    metadata.removeProperty(key);

    if (metadata.getPropertyNames().isEmpty()) {
      ((EntityImpl) element.getRawEntity())
          .removePropertyInternal(metadataKey(), PropertyValidationMode.ALLOW_METADATA);
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
      var vertexEntity = ((EntityImpl) element.getRawEntity());
      vertexEntity.setPropertyInternal(metadataKey(), metadata, null, null,
          PropertyValidationMode.ALLOW_METADATA);
    }

    return metadata;
  }

  @Override
  public void remove() {
    super.remove();
    ((EntityImpl) element.getRawEntity())
        .removePropertyInternal(metadataKey(), PropertyValidationMode.ALLOW_METADATA);
  }

  private String metadataKey() {
    return "~meta_" + key;
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
