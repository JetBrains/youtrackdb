package com.jetbrains.youtrackdb.api.gremlin.embedded;

import com.jetbrains.youtrackdb.api.gremlin.YTDBGraph;
import org.apache.tinkerpop.gremlin.structure.Element;

public interface YTDBElement extends Element {

  @Override
  YTDBGraph graph();
}
