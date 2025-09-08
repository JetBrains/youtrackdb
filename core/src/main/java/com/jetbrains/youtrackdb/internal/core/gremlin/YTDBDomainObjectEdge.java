package com.jetbrains.youtrackdb.internal.core.gremlin;

import com.jetbrains.youtrackdb.api.gremlin.YTDBGraph;
import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBDomainObject;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import java.util.ArrayList;
import java.util.Iterator;
import javax.annotation.Nonnull;
import org.apache.commons.collections4.IteratorUtils;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Property;
import org.apache.tinkerpop.gremlin.structure.Vertex;

public class YTDBDomainObjectEdge implements Edge {

  private final @Nonnull YTDBGraph graph;
  private final @Nonnull YTDBDomainObject from;
  private final @Nonnull YTDBDomainObject to;
  private final @Nonnull String label;
  private RecordIdInternal recordId;

  public YTDBDomainObjectEdge(@Nonnull YTDBGraph graph, @Nonnull YTDBDomainObject from,
      @Nonnull YTDBDomainObject ytdbDomainObject, @Nonnull String label) {
    this.graph = graph;
    this.from = from;
    to = ytdbDomainObject;
    this.label = label;
  }

  @Override
  public Iterator<Vertex> vertices(Direction direction) {
    if (direction == Direction.IN) {
      return IteratorUtils.singletonIterator(from);
    } else if (direction == Direction.OUT) {
      return IteratorUtils.singletonIterator(to);
    }
    assert direction == Direction.BOTH;

    var edges = new ArrayList<Vertex>();
    edges.add(from);
    edges.add(to);

    return edges.iterator();
  }

  @Override
  public Object id() {
    if (recordId == null) {
      recordId = RecordIdInternal.tempRecordId();
    }

    return recordId;
  }

  @Override
  public String label() {
    return label;
  }

  @Override
  public Graph graph() {
    return graph;
  }

  @Override
  public <V> Property<V> property(String key, V value) {
    throw new UnsupportedOperationException("Edge of domain objects do not contain properties");
  }

  @Override
  public void remove() {
    throw new UnsupportedOperationException("Edges of domain objects cannot be removed");
  }

  @Override
  public <V> Iterator<Property<V>> properties(String... propertyKeys) {
    return IteratorUtils.emptyIterator();
  }
}
