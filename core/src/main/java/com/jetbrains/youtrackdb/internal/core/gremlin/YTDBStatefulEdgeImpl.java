package com.jetbrains.youtrackdb.internal.core.gremlin;

import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBStatefulEdge;
import com.jetbrains.youtrackdb.api.record.Edge;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.api.record.StatefulEdge;
import java.util.Iterator;
import java.util.List;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Property;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.util.StringFactory;

public final class YTDBStatefulEdgeImpl extends YTDBElementImpl implements YTDBEdgeInternal,
    YTDBStatefulEdge {

  public YTDBStatefulEdgeImpl(YTDBGraphInternal graph, StatefulEdge ytdbEdge) {
    super(graph, ytdbEdge);
  }

  public YTDBStatefulEdgeImpl(YTDBGraphInternal graph, RID ytdbEdgeRid) {
    super(graph, ytdbEdgeRid);
  }

  @Override
  public <V> Property<V> property(final String key, final V value) {
    return writeProperty(YTDBPropertyFactory.propFactory(), key, value);
  }

  @Override
  public <V> Property<V> property(String key) {
    return readProperty(YTDBPropertyFactory.propFactory(), key);
  }

  @Override
  public <V> Iterator<Property<V>> properties(final String... propertyKeys) {
    return readProperties(YTDBPropertyFactory.propFactory(), propertyKeys);
  }

  @Override
  public Iterator<Vertex> vertices(Direction direction) {
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

  @Override
  public String toString() {
    return StringFactory.edgeString(this);
  }

  @Override
  public StatefulEdge getRawEntity() {
    return super.getRawEntity().asStatefulEdge();
  }
}
