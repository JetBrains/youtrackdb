package com.jetbrains.youtrack.db.internal.core.gremlin;

import com.jetbrains.youtrack.db.api.record.Vertex;
import java.util.Iterator;
import org.apache.tinkerpop.gremlin.structure.Property;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;
import org.apache.tinkerpop.gremlin.structure.util.StringFactory;

public final class YTDBVertexImpl extends YTDBElementImpl implements YTDBVertexInternal {
  public YTDBVertexImpl(final YTDBGraphInternal graph,
      final com.jetbrains.youtrack.db.api.record.Vertex rawElement) {
    super(graph, rawElement);
  }

  @Override
  public <V> VertexProperty<V> property(final String key, final V value) {
    return new YTDBVertexPropertyImpl<>(super.property(key, value), this);
  }

  @Override
  public <V> VertexProperty<V> property(String key) {
    return YTDBVertexInternal.super.property(key);
  }

  @Override
  public <V> Iterator<VertexProperty<V>> properties(final String... propertyKeys) {
    Iterator<? extends Property<V>> properties = super.properties(
        propertyKeys);
    return StreamUtils.asStream(properties)
        .filter(p -> !INTERNAL_FIELDS.contains(p.key()))
        .filter(p -> !p.key().startsWith("_meta_"))
        .map(
            p ->
                (VertexProperty<V>)
                    new YTDBVertexPropertyImpl<>(p.key(), p.value(), (YTDBVertexImpl) p.element()))
        .iterator();
  }

  @Override
  public String toString() {
    return StringFactory.vertexString(this);
  }

  @Override
  public Vertex getRawEntity() {
    return super.getRawEntity().asVertex();
  }
}
