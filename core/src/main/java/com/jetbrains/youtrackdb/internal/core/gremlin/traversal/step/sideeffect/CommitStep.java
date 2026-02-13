package com.jetbrains.youtrackdb.internal.core.gremlin.traversal.step.sideeffect;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import java.util.NoSuchElementException;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.AbstractStep;

/**
 * A blocking step that commits the current transaction.
 * The input values are passed through unchanged.
 *
 * TODO: After commit, elements should be detached (converted to read-only snapshots).
 * This requires investigation of the correct TinkerPop API for detaching.
 */
public class CommitStep<S> extends AbstractStep<S, S> {

  private boolean committed = false;

  public CommitStep(Traversal.Admin traversal) {
    super(traversal);
  }

  @Override
  protected Traverser.Admin<S> processNextStart() throws NoSuchElementException {
    // Commit transaction once before processing first element
    if (!committed) {
      commitTransaction();
      committed = true;
    }

    // Pass through the traverser unchanged, or throw if no more traversers
    // TODO: Detach elements after commit
    if (this.starts.hasNext()) {
      return this.starts.next();
    } else {
      throw fastNoSuchElement();
    }
  }
  
  private NoSuchElementException fastNoSuchElement() {
    // Use reflection to get FastNoSuchElementException if available
    try {
      var clazz = Class.forName("org.apache.tinkerpop.gremlin.process.traversal.util.FastNoSuchElementException");
      var method = clazz.getMethod("instance");
      return (NoSuchElementException) method.invoke(null);
    } catch (Exception e) {
      return new NoSuchElementException();
    }
  }

  private void commitTransaction() {
    var session = getDatabaseSession();
    if (session.isTxActive()) {
      session.commit();
    }
  }

  private DatabaseSessionEmbedded getDatabaseSession() {
    var graph = (YTDBGraphInternal) this.traversal.getGraph().orElseThrow();
    var graphTx = graph.tx();
    return graphTx.getDatabaseSession();
  }

  @Override
  public CommitStep<S> clone() {
    var cloned = (CommitStep<S>) super.clone();
    cloned.committed = false;
    return cloned;
  }

  @Override
  public void reset() {
    super.reset();
    this.committed = false;
  }
}
