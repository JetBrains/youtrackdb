package com.jetbrains.youtrackdb.api.gremlin.embedded;

import com.jetbrains.youtrackdb.api.gremlin.YTDBVertexPropertyId;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBEmptyVertexProperty;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;

public interface YTDBVertexProperty<V> extends VertexProperty<V>, YTDBProperty<V>, YTDBElement {
  static <V> YTDBVertexProperty<V> empty() {
    return YTDBEmptyVertexProperty.instance();
  }

  @Override
  YTDBVertexPropertyId id();

  @Override
  YTDBVertex element();
}
