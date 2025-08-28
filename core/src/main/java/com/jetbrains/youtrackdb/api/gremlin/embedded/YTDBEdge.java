package com.jetbrains.youtrackdb.api.gremlin.embedded;

import org.apache.tinkerpop.gremlin.structure.Edge;

public interface YTDBEdge extends Edge, YTDBElement {
  @Override
  default YTDBVertex outVertex() {
    return (YTDBVertex) Edge.super.outVertex();
  }

  @Override
  default YTDBVertex inVertex() {
    return (YTDBVertex) Edge.super.inVertex();
  }
}
