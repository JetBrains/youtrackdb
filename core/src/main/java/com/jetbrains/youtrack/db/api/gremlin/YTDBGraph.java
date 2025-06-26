package com.jetbrains.youtrack.db.api.gremlin;

import com.jetbrains.youtrack.db.internal.core.gremlin.YTDBGraphFactory;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.util.GraphFactoryClass;

@GraphFactoryClass(YTDBGraphFactory.class)
@Graph.OptIn(Graph.OptIn.SUITE_STRUCTURE_STANDARD)
@Graph.OptIn(Graph.OptIn.SUITE_PROCESS_STANDARD)
public interface YTDBGraph extends Graph {

  @Override
  YTDBVertex addVertex(Object... keyValues);

  @Override
  YTDBVertex addVertex(String label);

  boolean isSingleThreaded();
}
