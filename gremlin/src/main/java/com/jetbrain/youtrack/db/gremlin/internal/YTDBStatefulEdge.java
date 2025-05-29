package com.jetbrain.youtrack.db.gremlin.internal;


import com.jetbrain.youtrack.db.gremlin.api.YTDBGraph;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import com.jetbrains.youtrack.db.api.record.StatefulEdge;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Property;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.util.StringFactory;

public final class YTDBStatefulEdge extends YTDBElement implements Edge {

  private static final List<String> INTERNAL_FIELDS = Arrays.asList("@rid", "@class",
      com.jetbrains.youtrack.db.api.record.Edge.DIRECTION_IN,
      com.jetbrains.youtrack.db.api.record.Edge.DIRECTION_OUT);

  public YTDBStatefulEdge(YTDBGraphInternal graph, StatefulEdge ytdbEdge) {
    super(graph, ytdbEdge);
  }

  @Override
  public Iterator<Vertex> vertices(Direction direction) {
    this.graph.tx().readWrite();
    return switch (direction) {
      case OUT -> graph.vertices(getRawEntity().asEdge().getFrom());
      case IN -> graph.vertices(getRawEntity().asEdge().getTo());
      case BOTH -> {
        var edge = getRawEntity().asEdge();
        yield graph.vertices(edge.getFrom(), edge.getTo());
      }
    };
  }

  @Override
  public <V> Iterator<Property<V>> properties(final String... propertyKeys) {
    Iterator<? extends Property<V>> properties = super.properties(propertyKeys);
    return StreamUtils.asStream(properties)
        .filter(p -> !INTERNAL_FIELDS.contains(p.key()))
        .map(p -> (Property<V>) p)
        .iterator();
  }


  @Override
  public String toString() {
    return StringFactory.edgeString(this);
  }
}
