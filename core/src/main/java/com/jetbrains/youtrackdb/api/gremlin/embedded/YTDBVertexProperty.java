package com.jetbrains.youtrackdb.api.gremlin.embedded;

import com.jetbrains.youtrackdb.api.gremlin.YTDBGraph;
import com.jetbrains.youtrackdb.api.gremlin.YTDBVertexPropertyId;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;

public interface YTDBVertexProperty<V> extends VertexProperty<V>, YTDBElement {

  @Override
  YTDBGraph graph();

  @Override
  YTDBVertexPropertyId id();
}
