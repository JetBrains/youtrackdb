package com.jetbrains.youtrackdb.internal.core.gremlin.traversal.step.sideeffect;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import java.util.NoSuchElementException;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.AbstractStep;
import org.apache.tinkerpop.gremlin.process.traversal.util.FastNoSuchElementException;

/**
 * A terminal step that rolls back the current transaction and ends traversal execution.
 * This step returns no elements and discards all changes made in the transaction.
 */
public class RollbackStep<S> extends AbstractStep<S, S> {

  private boolean rolledBack = false;

  public RollbackStep(Traversal.Admin traversal) {
    super(traversal);
  }

  @Override
  protected Traverser.Admin<S> processNextStart() throws NoSuchElementException {
    // Rollback transaction once, then always throw FastNoSuchElementException
    if (!rolledBack) {
      rollbackTransaction();
      rolledBack = true;
    }

    // Terminal step - return nothing
    throw FastNoSuchElementException.instance();
  }

  private void rollbackTransaction() {
    var session = getDatabaseSession();
    if (session.isTxActive()) {
      session.rollback();
    }
  }

  private DatabaseSessionEmbedded getDatabaseSession() {
    var graph = (YTDBGraphInternal) this.traversal.getGraph().orElseThrow();
    var graphTx = graph.tx();
    return graphTx.getDatabaseSession();
  }

  @Override
  public RollbackStep<S> clone() {
    var cloned = (RollbackStep<S>) super.clone();
    cloned.rolledBack = false;
    return cloned;
  }

  @Override
  public void reset() {
    super.reset();
    this.rolledBack = false;
  }
}
