package com.jetbrains.youtrackdb.internal.core.gql.executor.resultset;

import java.util.Iterator;
import org.apache.groovy.internal.util.Function;

public class FlatMapGqlExecutionStream implements GqlExecutionStream {

  private final Iterator<Object> upstream;
  private final Function<Object, GqlExecutionStream> mapper;
  private GqlExecutionStream currentChildStream = null;

  public FlatMapGqlExecutionStream(Iterator<Object> upstream,
      Function<Object, GqlExecutionStream> mapper) {
    this.upstream = upstream;
    this.mapper = mapper;
  }

  @Override
  public boolean hasNext() {
    while (currentChildStream == null || !currentChildStream.hasNext()) {
      if (!upstream.hasNext()) {
        currentChildStream.close();
        return false;
      }

      if (currentChildStream != null)
        currentChildStream.close();

      currentChildStream = mapper.apply(upstream.next());
    }
    return true;
  }

  @Override
  public Object next() {
    if (!hasNext()) throw new java.util.NoSuchElementException();
    return currentChildStream.next();
  }

  @Override
  public void close() {
    if (currentChildStream != null)
      currentChildStream.close();
    if (upstream instanceof AutoCloseable) {
      try {
        ((AutoCloseable) upstream).close();
      } catch (Exception ignored) {
      }
    }
  }
}
