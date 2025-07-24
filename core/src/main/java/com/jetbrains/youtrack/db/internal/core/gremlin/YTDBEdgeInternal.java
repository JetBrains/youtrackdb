package com.jetbrains.youtrack.db.internal.core.gremlin;

import com.jetbrains.youtrack.db.api.gremlin.embedded.YTDBEdge;
import com.jetbrains.youtrack.db.api.record.Edge;
import com.jetbrains.youtrack.db.api.record.StatefulEdge;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Vertex;

public interface YTDBEdgeInternal extends YTDBEdge {
  List<String> INTERNAL_FIELDS = Arrays.asList("@rid", "@class",
      Edge.DIRECTION_IN, Edge.DIRECTION_OUT);

  @Override
  default Iterator<Vertex> vertices(Direction direction) {
    var graph = (YTDBGraphInternal) graph();

    graph.tx().readWrite();
    return switch (direction) {
      case Direction.OUT -> graph.vertices(getRawEntity().asEdge().getFrom());
      case Direction.IN -> graph.vertices(getRawEntity().asEdge().getTo());
      case Direction.BOTH -> {
        var edge = getRawEntity().asEdge();
        yield graph.vertices(edge.getFrom(), edge.getTo());
      }
    };
  }

  StatefulEdge getRawEntity();
}
