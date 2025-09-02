package com.jetbrains.youtrack.db.api.gremlin;

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
}
