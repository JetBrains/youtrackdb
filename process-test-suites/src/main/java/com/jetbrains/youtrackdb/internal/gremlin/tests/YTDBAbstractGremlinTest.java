package com.jetbrains.youtrackdb.internal.gremlin.tests;

import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import org.apache.tinkerpop.gremlin.AbstractGremlinTest;

public class YTDBAbstractGremlinTest extends AbstractGremlinTest {

  public YTDBGraphTraversalSource g() {
    return (YTDBGraphTraversalSource) g;
  }
}
