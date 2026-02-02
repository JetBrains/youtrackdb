package com.jetbrains.youtrackdb.internal.core.gql.executor.resultset;

import java.util.Iterator;
import java.util.Map;
import org.apache.tinkerpop.gremlin.structure.util.CloseableIterator;

/// Stream backed by an iterator.
public final class IteratorGqlExecutionStream implements GqlExecutionStream {

  private final Iterator<Map<String, Object>> iterator;

  public IteratorGqlExecutionStream(Iterator<Map<String, Object>> iterator) {
    this.iterator = iterator;
  }

  @Override
  public boolean hasNext() {
    return iterator.hasNext();
  }

  @Override
  public Map<String, Object> next() {
    return iterator.next();
  }

  @Override
  public void close() {
    CloseableIterator.closeIterator(iterator);
  }
}
