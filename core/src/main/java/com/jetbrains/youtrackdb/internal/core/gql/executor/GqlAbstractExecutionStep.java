package com.jetbrains.youtrackdb.internal.core.gql.executor;

import com.jetbrains.youtrackdb.internal.core.gql.executor.resultset.GqlExecutionStream;
import javax.annotation.Nullable;

/// Abstract base class for GQL execution steps.
public abstract class GqlAbstractExecutionStep implements GqlExecutionStep {

  @Nullable
  protected GqlExecutionStep prev = null;

  private boolean closed = false;

  @Override
  public void setPrevious(@Nullable GqlExecutionStep step) {
    this.prev = step;
  }

  @Override
  @Nullable
  public GqlExecutionStep getPrevious() {
    return prev;
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    if (prev != null) {
      prev.close();
    }
  }

  @Override
  public void reset() {
    closed = false;
    if (prev != null) {
      prev.reset();
    }
  }

  @Override
  public GqlExecutionStream start(GqlExecutionContext ctx) {
    return internalStart(ctx);
  }

  /// Subclasses implement this to provide the actual execution logic.
  protected abstract GqlExecutionStream internalStart(GqlExecutionContext ctx);
}
