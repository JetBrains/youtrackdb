package com.jetbrains.youtrackdb.internal.core.gql.executor.resultset;

import java.util.Objects;
import java.util.function.Function;
import org.apache.tinkerpop.gremlin.structure.util.CloseableIterator;
import javax.annotation.Nullable;

public class FlatMapGqlExecutionStream implements GqlExecutionStream {

  private final CloseableIterator<Object> upstream;
  private final Function<Object, GqlExecutionStream> mapper;
  private @Nullable GqlExecutionStream currentChildStream = null;

  public FlatMapGqlExecutionStream(CloseableIterator<Object> upstream,
      Function<Object, GqlExecutionStream> mapper) {
    this.upstream = upstream;
    this.mapper = mapper;
  }

  @Override
  public boolean hasNext() {
    while (currentChildStream == null || !currentChildStream.hasNext()) {
      if (!Objects.requireNonNull(upstream).hasNext()) {
        upstream.close();
        if (currentChildStream != null) {
          currentChildStream.close();
          currentChildStream = null;
        }
        return false;
      }

      if (currentChildStream != null) {
        currentChildStream.close();
      }

      currentChildStream = Objects.requireNonNull(mapper).apply(upstream.next());
    }
    return true;
  }

  @Override
  public @Nullable Object next() {
    if (!hasNext()) {
      throw new java.util.NoSuchElementException();
    }
    return Objects.requireNonNull(currentChildStream).next();
  }

  @Override
  public void close() {
    if (currentChildStream != null) {
      try {
        currentChildStream.close();
      } catch (Exception e) {
        throw new RuntimeException("Failed to close child stream", e);
      }
    }
    if (upstream != null) {
      try {
        upstream.close();
      } catch (Exception e) {
        throw new RuntimeException("Failed to close upstream iterator", e);
      }
    }
  }
}
