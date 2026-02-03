package com.jetbrains.youtrackdb.internal.core.gql.executor.resultset;

import java.util.Iterator;
import java.util.Map;
import org.apache.tinkerpop.gremlin.structure.util.CloseableIterator;

/// Stream of GQL execution results.
///
/// Extends TinkerPop's CloseableIterator for compatibility with the TinkerPop ecosystem.
/// Each result is a Map<String, Object> containing variable bindings.
/// For example, for `MATCH (a:Person)`, each result contains {"a": vertex}.
public interface GqlExecutionStream extends CloseableIterator<Map<String, Object>> {

  /// Create an empty stream.
  static GqlExecutionStream empty() {
    return EmptyGqlExecutionStream.INSTANCE;
  }

  /// Create a stream from an iterator of maps.
  static GqlExecutionStream fromIterator(Iterator<Map<String, Object>> iterator) {
    return new IteratorGqlExecutionStream(iterator);
  }
}
