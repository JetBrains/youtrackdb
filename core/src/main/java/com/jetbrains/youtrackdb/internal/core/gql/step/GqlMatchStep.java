package com.jetbrains.youtrackdb.internal.core.gql.step;

import com.jetbrains.youtrackdb.internal.core.gql.executor.GqlExecutionContext;
import com.jetbrains.youtrackdb.internal.core.gql.executor.GqlExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import java.util.Map;
import java.util.NoSuchElementException;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.Traverser.Admin;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.AbstractStep;
import org.apache.tinkerpop.gremlin.process.traversal.util.FastNoSuchElementException;
import org.apache.tinkerpop.gremlin.structure.util.CloseableIterator;

/// TinkerPop step that executes a GQL MATCH clause.
///
/// Follows AbstractStep#processNextStart pattern: holds an iterator over results,
/// each call to processNextStart returns a traverser generated from iterator.next().
///
/// For `MATCH (a:Label)`:
/// - Uses GqlExecutionPlan with GqlFetchFromClassStep
/// - Each result is a Map<String, Object> with {"a": vertex}
public class GqlMatchStep extends AbstractStep<Map<String, Object>, Map<String, Object>> {

  private final GqlExecutionPlan executionPlan;
  private final String alias;
  private final String label;

  /// Iterator over GQL result maps. Lazy-initialized in processNextStart.
  private CloseableIterator<Map<String, Object>> iterator = CloseableIterator.empty();
  private boolean started = false;

  public GqlMatchStep(
      Traversal.Admin<?, ?> traversal,
      GqlExecutionPlan executionPlan,
      String alias,
      String label) {
    super(traversal);
    this.executionPlan = executionPlan;
    this.alias = alias;
    this.label = label;
  }

  private CloseableIterator<Map<String, Object>> startExecution() {
    var graph = (YTDBGraphInternal) this.traversal.getGraph().orElseThrow();
    var graphTx = graph.tx();
    graphTx.readWrite();

    var session = graphTx.getDatabaseSession();
    var ctx = new GqlExecutionContext(graph, session);

    return executionPlan.start(ctx);
  }

  public GqlExecutionPlan getExecutionPlan() {
    return executionPlan;
  }

  public String getAlias() {
    return alias;
  }

  public String getLabel() {
    return label;
  }

  @Override
  protected Admin<Map<String, Object>> processNextStart() throws NoSuchElementException {
    if (!started) {
      started = true;
      iterator = startExecution();
    }

    if (iterator.hasNext()) {
      return this.getTraversal().getTraverserGenerator()
          .generate(this.iterator.next(), this, 1L);
    }

    throw FastNoSuchElementException.instance();
  }

  @Override
  public GqlMatchStep clone() {
    var clone = (GqlMatchStep) super.clone();
    clone.iterator = CloseableIterator.empty();
    clone.started = false;
    return clone;
  }

  @Override
  public void reset() {
    super.reset();
    CloseableIterator.closeIterator(iterator);
    iterator = CloseableIterator.empty();
    started = false;
    executionPlan.reset();
  }
}
