package com.jetbrains.youtrackdb.internal.core.gremlin;

import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBProperty;
import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBVertex;
import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBVertexProperty;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.structure.Property;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;

/// Factory for creating TinkerPop [[Property]]s. Having this abstraction allows us to express
/// common property-related logic without code duplication and in a type-safe manner.
///
/// @see com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBElement
public interface YTDBPropertyFactory<V, P extends Property<V>> {

  P empty();

  P create(
      String key,
      @Nullable V value,
      @Nullable PropertyType type,
      YTDBElementImpl element
  );

  YTDBPropertyFactory<Object, ? extends YTDBProperty<Object>> PROPERTY = new YTDBPropertyFactory<>() {
    @Override
    public YTDBProperty<Object> empty() {
      return YTDBProperty.empty();
    }

    @Override
    public YTDBProperty<Object> create(String key, Object value, PropertyType type,
        YTDBElementImpl element) {
      return new YTDBPropertyImpl<>(key, value, type, element);
    }
  };

  YTDBPropertyFactory<Object, ? extends YTDBVertexProperty<Object>> VERTEX_PROPERTY = new YTDBPropertyFactory<>() {
    @Override
    public YTDBVertexProperty<Object> empty() {
      return YTDBVertexProperty.empty();
    }

    @Override
    public YTDBVertexProperty<Object> create(String key, Object value, PropertyType type,
        YTDBElementImpl element) {
      return new YTDBVertexPropertyImpl<>(key, value, type, ((YTDBVertex) element));
    }
  };

  @SuppressWarnings("unchecked")
  static <V> YTDBPropertyFactory<V, ? extends YTDBProperty<V>> ytdbProps() {
    return (YTDBPropertyFactory<V, ? extends YTDBProperty<V>>) PROPERTY;
  }

  @SuppressWarnings("unchecked")
  static <V> YTDBPropertyFactory<V, ? extends Property<V>> stdProps() {
    return (YTDBPropertyFactory<V, ? extends Property<V>>) PROPERTY;
  }

  @SuppressWarnings("unchecked")
  static <V> YTDBPropertyFactory<V, ? extends YTDBVertexProperty<V>> ytdbVectorProps() {
    return (YTDBPropertyFactory<V, ? extends YTDBVertexProperty<V>>) VERTEX_PROPERTY;
  }

  @SuppressWarnings("unchecked")
  static <V> YTDBPropertyFactory<V, ? extends VertexProperty<V>> stdVectorProps() {
    return (YTDBPropertyFactory<V, ? extends VertexProperty<V>>) VERTEX_PROPERTY;
  }
}
