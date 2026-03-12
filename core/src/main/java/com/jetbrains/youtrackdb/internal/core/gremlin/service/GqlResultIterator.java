package com.jetbrains.youtrackdb.internal.core.gremlin.service;

import com.jetbrains.youtrackdb.internal.core.gql.executor.GqlExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.gql.executor.resultset.GqlExecutionStream;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import com.jetbrains.youtrackdb.internal.core.gremlin.sqlcommand.GremlinResultMapper;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchema;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import java.util.NoSuchElementException;
import java.util.Objects;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.structure.util.CloseableIterator;

/// Streaming iterator that wraps GqlExecutionStream for lazy result consumption.
/// Converts internal Result rows to Gremlin types (vertex, edge, Map) via
/// the shared [GremlinResultMapper] used by both GQL and YQL services.
@SuppressWarnings("DuplicatedCode")
final class GqlResultIterator implements CloseableIterator<Object> {

  private final GqlExecutionStream stream;
  private final GqlExecutionPlan plan;
  private final YTDBGraphInternal graph;
  private final ImmutableSchema schema;
  private boolean closed;

  GqlResultIterator(GqlExecutionStream stream, GqlExecutionPlan plan,
      YTDBGraphInternal graph, ImmutableSchema schema) {
    this.stream = stream;
    this.plan = plan;
    this.graph = graph;
    this.schema = schema;
  }

  @Override
  public boolean hasNext() {
    if (closed) {
      return false;
    }
    try {
      final var hasNext = Objects.requireNonNull(stream).hasNext();
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
      throw new NoSuchElementException();
    }
    try {
      var raw = Objects.requireNonNull(stream).next();
      if (raw instanceof Result result) {
        return GremlinResultMapper.toGremlinValue(graph, schema, result);
      }
      return raw;
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
    try {
      Objects.requireNonNull(stream).close();
    } finally {
      Objects.requireNonNull(plan).close();
    }
  }
}
