package com.jetbrains.youtrackdb.internal.core.gql.executor.resultset;

import java.util.Iterator;
import java.util.Objects;
import java.util.function.Function;
import org.apache.tinkerpop.gremlin.structure.util.CloseableIterator;
import org.jspecify.annotations.Nullable;

/// Stream of GQL execution results.
///
/// Extends TinkerPop's CloseableIterator for compatibility with the TinkerPop ecosystem.
///
/// Result type depends on query:
/// - With alias (MATCH (a:Person)): Map<String, Object> with bindings {"a": vertex}
/// - Without alias (MATCH (:Person)): just the Vertex (no side effects)
@SuppressWarnings("unused")
public interface GqlExecutionStream extends CloseableIterator<Object> {

  /// Create an empty stream.
  @SuppressWarnings("SameReturnValue")
  static GqlExecutionStream empty() {
    return EmptyGqlExecutionStream.INSTANCE;
  }

  /// Create a stream from an iterator (no mapping).
  static GqlExecutionStream fromIterator(Iterator<?> iterator) {
    return new IteratorGqlExecutionStream<>(Objects.requireNonNull(iterator));
  }

  /// Create a stream from an iterator with mapping function.
  static <T> GqlExecutionStream fromIterator(@Nullable Iterator<T> iterator,
      Function<T, ?> mapper) {
    return new IteratorGqlExecutionStream<>(Objects.requireNonNull(iterator),
        Objects.requireNonNull(mapper));
  }

  default GqlExecutionStream flatMap(Function<Object, GqlExecutionStream> mapper) {
    return new FlatMapGqlExecutionStream(this, Objects.requireNonNull(mapper));
  }
}
