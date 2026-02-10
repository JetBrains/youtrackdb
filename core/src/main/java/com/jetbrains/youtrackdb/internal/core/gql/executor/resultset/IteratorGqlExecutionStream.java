package com.jetbrains.youtrackdb.internal.core.gql.executor.resultset;

import java.util.Iterator;
import java.util.function.Function;

/// Stream backed by an iterator with optional mapping.
///
/// Properly closes the underlying iterator if it implements AutoCloseable.
public final class IteratorGqlExecutionStream<T> implements GqlExecutionStream {

  private final Iterator<T> source;
  private final Function<T, ?> mapper;

  /// Create a stream that passes elements through unchanged.
  public IteratorGqlExecutionStream(Iterator<?> source) {
    this(source, Function.identity());
  }

  /// Create a stream that maps elements using the given function.
  @SuppressWarnings("unchecked")
  public <S> IteratorGqlExecutionStream(Iterator<S> source, Function<S, ?> mapper) {
    this.source = (Iterator<T>) source;
    this.mapper = (Function<T, ?>) mapper;
  }

  @Override
  public boolean hasNext() {
    final var hasNext = source.hasNext();
    if (!hasNext) {
      close();
    }
    return hasNext;
  }

  @Override
  public Object next() {
    return mapper.apply(source.next());
  }

  @Override
  public void close() {
    if (source instanceof AutoCloseable closeable) {
      try {
        closeable.close();
      } catch (Exception e) {
        throw new RuntimeException("Failed to close iterator", e);
      }
    }
  }
}
