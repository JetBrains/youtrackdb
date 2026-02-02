package com.jetbrains.youtrackdb.internal.core.gql.executor.resultset;

import java.util.Iterator;
import java.util.Map;
import java.util.function.Function;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.util.CloseableIterator;
import org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils;

/// Stream of GQL execution results.
///
/// Extends TinkerPop's CloseableIterator for compatibility with the TinkerPop ecosystem.
/// Each result is a Map<String, Object> containing variable bindings.
/// For example, for `MATCH (a:Person)`, each result contains {"a": vertex}.
public interface GqlExecutionStream extends CloseableIterator<Map<String, Object>> {

  /// Map each result to a new result.
  default GqlExecutionStream map(Function<Map<String, Object>, Map<String, Object>> mapper) {
    return new MappingGqlExecutionStream(this, mapper);
  }

  /// Create an empty stream.
  static GqlExecutionStream empty() {
    return EmptyGqlExecutionStream.INSTANCE;
  }

  /// Create a stream from an iterator of maps.
  static GqlExecutionStream fromIterator(Iterator<Map<String, Object>> iterator) {
    return new IteratorGqlExecutionStream(iterator);
  }

  /// Create a stream from an iterator of vertices with a binding alias.
  static GqlExecutionStream fromVertices(Iterator<? extends Vertex> vertices, String alias) {
    var mapped = IteratorUtils.map(vertices, v -> Map.<String, Object>of(alias, v));
    return new IteratorGqlExecutionStream(mapped);
  }
}
