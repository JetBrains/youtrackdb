package com.jetbrains.youtrackdb.internal.core.gremlin.gremlintest.scenarios;

import com.jetbrains.youtrackdb.api.gremlin.YTDBGraph;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import org.apache.tinkerpop.gremlin.AbstractGremlinTest;

public class YTDBAbstractGremlinTest extends AbstractGremlinTest {

  public YTDBGraph graph() {
    return (YTDBGraph) graph;
  }

  public YTDBGraphTraversalSource g() {
    return (YTDBGraphTraversalSource) g;
  }
}
