package com.jetbrains.youtrackdb.api.gremlin;

import com.jetbrains.youtrackdb.api.gremlin.tokens.YTDBQueryConfigParam;
import org.apache.tinkerpop.gremlin.process.remote.RemoteConnection;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategies;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.structure.Graph;

public class YTDBGraphTraversalSourceDSL extends GraphTraversalSource {

  public YTDBGraphTraversalSourceDSL(Graph graph,
      TraversalStrategies traversalStrategies) {
    super(graph, traversalStrategies);
  }

  public YTDBGraphTraversalSourceDSL(Graph graph) {
    super(graph);
  }

  public YTDBGraphTraversalSourceDSL(
      RemoteConnection connection) {
    super(connection);
  }

  public YTDBGraphTraversalSource with(final YTDBQueryConfigParam key, final Object value) {
    if (!key.type().isInstance(value)) {
      throw new IllegalArgumentException("The provided value " + value + " is not an instance of "
          + key.type().getSimpleName());
    }
    return (YTDBGraphTraversalSource) with(key.name(), value);
  }

  public YTDBGraphTraversalSource with(final YTDBQueryConfigParam key) {
    return with(key, true);
  }
}
