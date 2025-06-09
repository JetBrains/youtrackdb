package com.jetbrains.youtrack.db.internal.core.gremlin;

import com.jetbrains.youtrack.db.api.gremlin.YTDBStatefulEdge;
import com.jetbrains.youtrack.db.api.record.StatefulEdge;
import java.util.Iterator;
import org.apache.tinkerpop.gremlin.structure.Property;
import org.apache.tinkerpop.gremlin.structure.util.StringFactory;

public class YTDBStatefulEdgeWrapper extends YTDBElementWrapper implements YTDBEdgeInternal,
    YTDBStatefulEdge {

  public YTDBStatefulEdgeWrapper(YTDBGraphInternal graph,
      StatefulEdge rawEntity) {
    super(graph, rawEntity);
  }

  @Override
  public <V> Iterator<Property<V>> properties(final String... propertyKeys) {
    Iterator<? extends Property<V>> properties = (super.properties(
        propertyKeys));
    return StreamUtils.asStream(properties)
        .filter(p -> !INTERNAL_FIELDS.contains(p.key()))
        .map(p -> (Property<V>) p)
        .iterator();
  }

  @Override
  public String toString() {
    return StringFactory.edgeString(this);
  }

  @Override
  public StatefulEdge getRawEntity() {
    return super.getRawEntity().asStatefulEdge();
  }

  @Override
  public StatefulEdge getUnderlyingEdge() {
    return getRawEntity();
  }
}
