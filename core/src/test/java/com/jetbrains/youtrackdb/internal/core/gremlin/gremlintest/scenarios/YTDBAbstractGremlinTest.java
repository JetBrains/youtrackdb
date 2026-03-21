package com.jetbrains.youtrackdb.internal.core.gremlin.gremlintest.scenarios;

import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraph;
import org.apache.tinkerpop.gremlin.AbstractGremlinTest;
import org.junit.experimental.categories.Category;

@Category(SequentialTest.class)
public class YTDBAbstractGremlinTest extends AbstractGremlinTest {

  public YTDBGraph graph() {
    return (YTDBGraph) graph;
  }

  public YTDBGraphTraversalSource g() {
    return (YTDBGraphTraversalSource) g;
  }
}
