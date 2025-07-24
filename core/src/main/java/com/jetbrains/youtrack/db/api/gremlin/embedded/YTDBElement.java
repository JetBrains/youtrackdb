package com.jetbrains.youtrack.db.api.gremlin.embedded;

import com.jetbrains.youtrack.db.api.gremlin.YTDBGraph;
import org.apache.tinkerpop.gremlin.structure.Element;

public interface YTDBElement extends Element {

  @Override
  YTDBGraph graph();
}
