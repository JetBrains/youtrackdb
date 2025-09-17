package com.jetbrains.youtrackdb.internal.core.gremlin;

import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBVertex;
import org.apache.tinkerpop.gremlin.structure.Property;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;

public interface YTDBPropertyFactory<V, P extends Property<V>> {

  P empty();

  P create(String key, V value, YTDBElementImpl element);

  YTDBPropertyFactory<Object, ? extends Property<Object>> PROPERTY = new YTDBPropertyFactory<>() {
    @Override
    public Property<Object> empty() {
      return Property.empty();
    }

    @Override
    public Property<Object> create(String key, Object value, YTDBElementImpl element) {
      return new YTDBPropertyImpl<>(key, value, element);
    }
  };

  YTDBPropertyFactory<Object, ? extends VertexProperty<Object>> VERTEX_PROPERTY = new YTDBPropertyFactory<>() {
    @Override
    public VertexProperty<Object> empty() {
      return VertexProperty.empty();
    }

    @Override
    public VertexProperty<Object> create(String key, Object value, YTDBElementImpl element) {
      return new YTDBVertexPropertyImpl<>(key, value, ((YTDBVertex) element));
    }
  };

  @SuppressWarnings("unchecked")
  static <V> YTDBPropertyFactory<V, ? extends Property<V>> propFactory() {
    return (YTDBPropertyFactory<V, ? extends Property<V>>) PROPERTY;
  }

  @SuppressWarnings("unchecked")
  static <V> YTDBPropertyFactory<V, ? extends VertexProperty<V>> vertexPropFactory() {
    return (YTDBPropertyFactory<V, ? extends VertexProperty<V>>) VERTEX_PROPERTY;
  }
}
