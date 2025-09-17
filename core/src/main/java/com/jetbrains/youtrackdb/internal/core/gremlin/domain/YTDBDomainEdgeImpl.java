package com.jetbrains.youtrackdb.internal.core.gremlin.domain;

import com.jetbrains.youtrackdb.api.gremlin.YTDBGraph;

import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBDomainVertex;
import com.jetbrains.youtrackdb.internal.core.gremlin.domain.schema.YTDBSchemaClassOutTokenInternal;
import java.util.ArrayList;
import java.util.Iterator;
import javax.annotation.Nonnull;
import org.apache.commons.collections4.IteratorUtils;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Property;
import org.apache.tinkerpop.gremlin.structure.Vertex;

public class YTDBDomainEdgeImpl implements Edge {
  private final @Nonnull YTDBGraph graph;

  private final @Nonnull YTDBDomainVertex from;
  private final @Nonnull YTDBDomainVertex to;

  private final @Nonnull YTDBSchemaClassOutTokenInternal outToken;


  public YTDBDomainEdgeImpl(@Nonnull YTDBGraph graph, @Nonnull YTDBDomainVertex from,
      @Nonnull YTDBDomainVertex ytdbDomainVertex, YTDBSchemaClassOutTokenInternal outToken) {
    this.graph = graph;
    this.from = from;
    to = ytdbDomainVertex;
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
  public Graph graph() {
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
