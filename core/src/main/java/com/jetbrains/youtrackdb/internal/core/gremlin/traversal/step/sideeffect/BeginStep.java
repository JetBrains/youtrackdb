package com.jetbrains.youtrackdb.internal.core.gremlin.traversal.step.sideeffect;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import java.util.NoSuchElementException;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.AbstractStep;

/**
 * A non-blocking step that begins a new transaction.
 * This step passes through the input value unchanged.
 */
public class BeginStep<S> extends AbstractStep<S, S> {

  public BeginStep(Traversal.Admin traversal) {
    super(traversal);
  }

  @Override
  protected Traverser.Admin<S> processNextStart() throws NoSuchElementException {
    var traverser = this.starts.next();

    // Begin transaction if not already started
    var session = getDatabaseSession();
    if (!session.isTxActive()) {
      session.begin();
    }

    return traverser;
  }

  private DatabaseSessionEmbedded getDatabaseSession() {
    var graph = (YTDBGraphInternal) this.traversal.getGraph().orElseThrow();
    var graphTx = graph.tx();
    return graphTx.getDatabaseSession();
  }

  @Override
  public BeginStep<S> clone() {
    return (BeginStep<S>) super.clone();
  }
}
