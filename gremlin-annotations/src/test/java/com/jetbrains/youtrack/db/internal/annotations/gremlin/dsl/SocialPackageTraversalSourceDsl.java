package com.jetbrains.youtrack.db.internal.annotations.gremlin.dsl;

import org.apache.tinkerpop.gremlin.process.remote.RemoteConnection;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategies;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.DefaultGraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.GraphStep;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Vertex;

/**
 * @author Stephen Mallette (http://stephen.genoprime.com)
 */
public class SocialPackageTraversalSourceDsl extends GraphTraversalSource {

  public SocialPackageTraversalSourceDsl(final Graph graph,
      final TraversalStrategies traversalStrategies) {
    super(graph, traversalStrategies);
  }

  public SocialPackageTraversalSourceDsl(final Graph graph) {
    super(graph);
  }

  public SocialPackageTraversalSourceDsl(final RemoteConnection connection) {
    super(connection);
  }

  public GraphTraversal<Vertex, Vertex> persons(String... names) {
    GraphTraversalSource clone = this.clone();

    clone.getBytecode().addStep(GraphTraversal.Symbols.V);
    GraphTraversal<Vertex, Vertex> traversal = new DefaultGraphTraversal<>(clone);
    traversal.asAdmin().addStep(new GraphStep<>(traversal.asAdmin(), Vertex.class, true));

    traversal = traversal.hasLabel("person");
    if (names.length > 0) {
      traversal = traversal.has("name", P.within(names));
    }

    return traversal;
  }

  @Override
  public void close() {
    try {
      super.close();
    } catch (Exception ignored) {
      // do nothing - this is just for testing TINKERPOP-2496
    }
  }
}
