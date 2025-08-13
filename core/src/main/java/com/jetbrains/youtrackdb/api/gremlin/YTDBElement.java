package com.jetbrains.youtrackdb.api.gremlin;

import org.apache.tinkerpop.gremlin.structure.Element;

public interface YTDBElement extends Element {
  @Override
  YTDBGraph graph();
}
