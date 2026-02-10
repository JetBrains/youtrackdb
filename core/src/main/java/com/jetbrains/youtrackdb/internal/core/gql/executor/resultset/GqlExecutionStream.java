package com.jetbrains.youtrackdb.internal.core.gql.executor.resultset;

import java.util.Iterator;
import org.apache.groovy.internal.util.Function;
import org.apache.tinkerpop.gremlin.structure.util.CloseableIterator;

/// Stream of GQL execution results.
///
/// Extends TinkerPop's CloseableIterator for compatibility with the TinkerPop ecosystem.
///
/// Result type depends on query:
/// - With alias (MATCH (a:Person)): Map<String, Object> with bindings {"a": vertex}
/// - Without alias (MATCH (:Person)): just the Vertex (no side effects)
@SuppressWarnings("unused")
public interface GqlExecutionStream extends CloseableIterator<Object>, Iterable<Object> {

  /// Create an empty stream.
  @SuppressWarnings("SameReturnValue")
  static GqlExecutionStream empty() {
    return EmptyGqlExecutionStream.INSTANCE;
  }

  /// Create a stream from an iterator (no mapping).
  static GqlExecutionStream fromIterator(Iterator<?> iterator) {
    return new IteratorGqlExecutionStream<>(iterator);
  }

  /// Create a stream from an iterator with mapping function.
  static <T> GqlExecutionStream fromIterator(Iterator<T> iterator,
      java.util.function.Function<T, ?> mapper) {
    return new IteratorGqlExecutionStream<>(iterator, mapper);
  }

  default GqlExecutionStream flatMap(Function<Object, GqlExecutionStream> mapper) {
    return new FlatMapGqlExecutionStream(this, mapper);
  }

  @Override
  default Iterator<Object> iterator() {
    return this;
  }
}
