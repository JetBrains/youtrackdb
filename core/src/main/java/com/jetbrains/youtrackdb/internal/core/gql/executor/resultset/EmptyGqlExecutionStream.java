package com.jetbrains.youtrackdb.internal.core.gql.executor.resultset;

import java.util.NoSuchElementException;

/// Empty stream implementation that returns no results.
public final class EmptyGqlExecutionStream implements GqlExecutionStream {

  public static final EmptyGqlExecutionStream INSTANCE = new EmptyGqlExecutionStream();

  private EmptyGqlExecutionStream() {
  }

  @Override
  public boolean hasNext() {
    return false;
  }

  @Override
  public Object next() {
    throw new NoSuchElementException();
  }

  @Override
  public void close() {
    // nothing to close
  }
}
