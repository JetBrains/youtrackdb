package com.jetbrains.youtrackdb.api.gremlin;

import java.lang.Override;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.util.DefaultTraversal;
import org.apache.tinkerpop.gremlin.structure.Graph;

public class DefaultYTDBGraphTraversal<S, E> extends DefaultTraversal<S, E> implements YTDBGraphTraversal<S, E> {
  public DefaultYTDBGraphTraversal() {
    super();
  }

  public DefaultYTDBGraphTraversal(Graph graph) {
    super(graph);
  }

  public DefaultYTDBGraphTraversal(YTDBGraphTraversalSource traversalSource) {
    super(traversalSource);
  }

  public DefaultYTDBGraphTraversal(YTDBGraphTraversalSource traversalSource,
      GraphTraversal.Admin traversal) {
    super(traversalSource, traversal.asAdmin());
  }

  @Override
  public YTDBGraphTraversal<S, E> iterate() {
    return (YTDBGraphTraversal) super.iterate();
  }

  @Override
  public GraphTraversal.Admin<S, E> asAdmin() {
    return (GraphTraversal.Admin) super.asAdmin();
  }

  @Override
  public DefaultYTDBGraphTraversal<S, E> clone() {
    return (DefaultYTDBGraphTraversal) super.clone();
  }
}
