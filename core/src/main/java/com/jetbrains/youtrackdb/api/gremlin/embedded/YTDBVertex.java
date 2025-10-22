package com.jetbrains.youtrackdb.api.gremlin.embedded;

import com.jetbrains.youtrackdb.api.gremlin.YTDBGraph;
import com.jetbrains.youtrackdb.api.record.RID;
import java.util.Iterator;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;
import org.apache.tinkerpop.gremlin.structure.VertexProperty.Cardinality;

public interface YTDBVertex extends Vertex, YTDBElement {

  @Override
  RID id();

  @Override
  YTDBGraph graph();

  @Override
  YTDBEdge addEdge(String label, Vertex inVertex, Object... keyValues);

  @Override
  default <V> YTDBVertexProperty<V> property(String key) {
    final Iterator<VertexProperty<V>> iterator = this.properties(key);
    if (iterator.hasNext()) {
      final var property = (YTDBVertexProperty<V>) iterator.next();
      if (iterator.hasNext())
        throw Vertex.Exceptions.multiplePropertiesExistForProvidedKey(key);
      else
        return property;
    } else {
      return YTDBVertexProperty.empty();
    }
  }

  @Override
  <V> YTDBVertexProperty<V> property(String key, V value);

  @Override
  <V> YTDBVertexProperty<V> property(String key, V value, Object... keyValues);

  @Override
  <V> YTDBVertexProperty<V> property(Cardinality cardinality, String key, V value,
      Object... keyValues);
}