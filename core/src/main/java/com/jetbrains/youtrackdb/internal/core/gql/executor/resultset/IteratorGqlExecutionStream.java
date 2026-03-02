package com.jetbrains.youtrackdb.internal.core.gql.executor.resultset;

import java.util.Iterator;
import java.util.Objects;
import java.util.function.Function;
import javax.annotation.Nullable;

/// Stream backed by an iterator with optional mapping.
///
/// Properly closes the underlying iterator if it implements AutoCloseable.
/// Uses a closed boolean guard to ensure close() is idempotent, preventing
/// multiple close() calls (from hasNext() exhaustion and error handling).
@SuppressWarnings("DuplicatedCode")
public final class IteratorGqlExecutionStream<T> implements GqlExecutionStream {

  private final Iterator<T> source;
  private final Function<T, ?> mapper;
  private boolean closed;

  /// Create a stream that passes elements through unchanged.
  public IteratorGqlExecutionStream(Iterator<?> source) {
    this(Objects.requireNonNull(source), Function.identity());
  }

  /// Create a stream that maps elements using the given function.
  @SuppressWarnings("unchecked")
  public <S> IteratorGqlExecutionStream(Iterator<S> source, Function<S, ?> mapper) {
    this.source = (Iterator<T>) source;
    this.mapper = (Function<T, ?>) mapper;
  }

  @Override
  public boolean hasNext() {
    if (closed) {
      return false;
    }
    try {
      final var hasNext = Objects.requireNonNull(source).hasNext();
      if (!hasNext) {
        close();
      }
      return hasNext;
    } catch (Exception e) {
      close();
      throw e;
    }
  }

  @Override
  public @Nullable Object next() {
    if (closed) {
      throw new java.util.NoSuchElementException();
    }
    try {
      return Objects.requireNonNull(mapper).apply(Objects.requireNonNull(source).next());
    } catch (Exception e) {
      close();
      throw e;
    }
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    if (source instanceof AutoCloseable closeable) {
      try {
        closeable.close();
      } catch (Exception e) {
        throw new RuntimeException("Failed to close iterator", e);
      }
    }
  }
}
