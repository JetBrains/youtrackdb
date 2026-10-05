package com.jetbrains.youtrackdb.internal.core.gremlin.service;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.gql.executor.GqlExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.gql.executor.resultset.GqlExecutionStream;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import com.jetbrains.youtrackdb.internal.core.gremlin.sqlcommand.GremlinResultMapper;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchema;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.query.StreamQueryHandle;
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
  private StreamQueryHandle handle;

  GqlResultIterator(GqlExecutionStream stream, GqlExecutionPlan plan,
      YTDBGraphInternal graph, ImmutableSchema schema) {
    this.stream = stream;
    this.plan = plan;
    this.graph = graph;
    this.schema = schema;
  }

  /** Publish only after this iterator owns all resources needed by registry-driven closure. */
  void register(DatabaseSessionEmbedded session) {
    handle = new StreamQueryHandle(session, this::close, () -> plan.prettyPrint(0, 2));
    handle.register();
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
    } catch (RuntimeException | Error e) {
      closeAfterFailure(e);
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
    } catch (RuntimeException | Error e) {
      closeAfterFailure(e);
      throw e;
    }
  }

  /** Keep execution or registration failure primary, including fatal unchecked failures. */
  void closeAfterFailure(Throwable failure) {
    try {
      close();
    } catch (RuntimeException | Error cleanup) {
      if (cleanup != failure) {
        failure.addSuppressed(cleanup);
      }
    }
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    // Mark terminal before cleanup, including re-entrant closure from registry publication.
    closed = true;
    Throwable failure = null;
    try {
      Objects.requireNonNull(stream).close();
    } catch (RuntimeException | Error e) {
      failure = e;
    }
    try {
      Objects.requireNonNull(plan).close();
    } catch (RuntimeException | Error e) {
      failure = collectFailure(failure, e);
    } finally {
      try {
        if (handle != null) {
          handle.retire();
        }
      } catch (RuntimeException | Error e) {
        failure = collectFailure(failure, e);
      }
    }
    if (failure instanceof Error error) {
      throw error;
    }
    if (failure != null) {
      throw (RuntimeException) failure;
    }
  }

  private static Throwable collectFailure(Throwable first, Throwable next) {
    if (first == null) {
      return next;
    }
    if (first != next) {
      first.addSuppressed(next);
    }
    return first;
  }
}
