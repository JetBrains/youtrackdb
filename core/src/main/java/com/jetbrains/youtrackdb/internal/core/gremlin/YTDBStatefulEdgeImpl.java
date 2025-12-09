package com.jetbrains.youtrackdb.internal.core.gremlin;

import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBProperty;
import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBStatefulEdge;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.record.StatefulEdge;
import java.util.Iterator;
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
  public <V> YTDBProperty<V> property(final String key, final V value) {
    return writeProperty(YTDBPropertyFactory.ytdbProps(), key, value);
  }

  @Override
  public <V> YTDBProperty<V> property(String key) {
    return readProperty(YTDBPropertyFactory.<V>ytdbProps(), key);
  }

  @Override
  public <V> Iterator<Property<V>> properties(final String... propertyKeys) {
    // unfortunately, we can't return Iterator<YTDBProperty> here, because of
    // the parent interface constraints
    return readProperties(YTDBPropertyFactory.stdProps(), propertyKeys);
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
