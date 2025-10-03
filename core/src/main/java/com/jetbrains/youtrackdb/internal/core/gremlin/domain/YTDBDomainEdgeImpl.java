package com.jetbrains.youtrackdb.internal.core.gremlin.domain;

import com.jetbrains.youtrackdb.api.gremlin.YTDBGraph;
import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBEdge;
import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBDomainVertex;
import com.jetbrains.youtrackdb.internal.core.gremlin.domain.tokens.YTDBOutTokenInternal;
import java.util.ArrayList;
import java.util.Iterator;
import javax.annotation.Nonnull;
import org.apache.commons.collections4.IteratorUtils;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Property;
import org.apache.tinkerpop.gremlin.structure.Vertex;

public class YTDBDomainEdgeImpl<O extends YTDBDomainVertex, I extends YTDBDomainVertex> implements
    YTDBEdge {
  private final @Nonnull YTDBGraph graph;

  private final @Nonnull O from;
  private final @Nonnull I to;

  private final @Nonnull YTDBOutTokenInternal<O> outToken;


  public YTDBDomainEdgeImpl(@Nonnull YTDBGraph graph, @Nonnull O from,
      @Nonnull I to, @Nonnull YTDBOutTokenInternal<O> outToken) {
    this.graph = graph;
    this.from = from;
    this.to = to;
    this.outToken = outToken;
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
    throw new UnsupportedOperationException("Edges of domain vertices do not have an identity");
  }

  @Override
  public String label() {
    return outToken.name();
  }

  @Override
  public YTDBGraph graph() {
    return graph;
  }

  @Override
  public <V> Property<V> property(String key, V value) {
    throw new UnsupportedOperationException("Edges of domain vertices do not contain properties");
  }

  @Override
  public void remove() {
    outToken.remove(from, to);
  }

  @Override
  public <V> Iterator<Property<V>> properties(String... propertyKeys) {
    return IteratorUtils.emptyIterator();
  }
}
