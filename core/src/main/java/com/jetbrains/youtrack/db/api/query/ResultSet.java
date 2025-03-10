package com.jetbrains.youtrack.db.api.query;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.record.Edge;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.record.Vertex;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 *
 */
public interface ResultSet extends Spliterator<Result>, Iterator<Result>, AutoCloseable {

  @Override
  boolean hasNext();

  @Override
  Result next();

  default void remove() {
    throw new UnsupportedOperationException();
  }

  void close();

  @Nullable
  ExecutionPlan getExecutionPlan();

  Map<String, Long> getQueryStats();

  @Nullable
  DatabaseSession getBoundToSession();

  default void reset() {
    throw new UnsupportedOperationException("Implement RESET on " + getClass().getSimpleName());
  }

  default boolean tryAdvance(Consumer<? super Result> action) {
    if (hasNext()) {
      action.accept(next());
      return true;
    }
    return false;
  }

  default void forEachRemaining(Consumer<? super Result> action) {
    Spliterator.super.forEachRemaining(action);
  }

  default ResultSet trySplit() {
    return null;
  }

  default long estimateSize() {
    return Long.MAX_VALUE;
  }

  default int characteristics() {
    return ORDERED;
  }

  /**
   * Returns the result set as a stream. IMPORTANT: the stream consumes the result set!
   */
  default Stream<Result> stream() {
    return StreamSupport.stream(this, false).onClose(this::close);
  }

  default List<Result> toList() {
    return stream().toList();
  }

  @Nonnull
  default <R> R findFirst(@Nonnull Function<Result, R> function) {
    try {
      if (hasNext()) {
        return function.apply(next());
      } else {
        throw new NoSuchElementException();
      }
    } finally {
      close();
    }
  }

  default <R> R findFirstOrNull(@Nonnull Function<Result, R> function) {
    try {
      if (hasNext()) {
        return function.apply(next());
      } else {
        return null;
      }
    } finally {
      close();
    }
  }

  default <R> R findFirstEntity(@Nonnull Function<Entity, R> function) {
    try {
      if (hasNext()) {
        return function.apply(next().castToEntity());
      } else {
        throw new NoSuchElementException();
      }
    } finally {
      close();
    }
  }

  default <R> R findFirstEntityOrNull(@Nonnull Function<Entity, R> function) {
    try {
      if (hasNext()) {
        var entity = next().asEntity();

        if (entity != null) {
          return function.apply(entity);
        }

        return null;
      } else {
        throw null;
      }
    } finally {
      close();
    }
  }

  default <R> R findFirstVertex(@Nonnull Function<Vertex, R> function) {
    try {
      if (hasNext()) {
        return function.apply(next().castToVertex());
      } else {
        throw new NoSuchElementException();
      }
    } finally {
      close();
    }
  }

  default <R> R findFirstVertexOrNull(@Nonnull Function<Vertex, R> function) {
    try {
      if (hasNext()) {
        var vertex = next().asVertex();
        if (vertex != null) {
          return function.apply(vertex);
        }

        return null;
      } else {
        throw null;
      }
    } finally {
      close();
    }
  }

  default <R> R findFirstEdge(@Nonnull Function<Edge, R> function) {
    try {
      if (hasNext()) {
        return function.apply(next().castToEdge());
      } else {
        throw new NoSuchElementException();
      }
    } finally {
      close();
    }
  }

  default <R> R findFirstEdgeOrNull(@Nonnull Function<Edge, R> function) {
    try {
      if (hasNext()) {
        var edge = next().asEdge();
        if (edge != null) {
          return function.apply(edge);
        }

        return null;
      } else {
        throw null;
      }
    } finally {
      close();
    }
  }

  default <R> R findFirstStateFullEdge(@Nonnull Function<Edge, R> function) {
    try {
      if (hasNext()) {
        return function.apply(next().castToStatefulEdge());
      } else {
        throw new NoSuchElementException();
      }
    } finally {
      close();
    }
  }

  default <R> R findFirstSateFullEdgeOrNull(@Nonnull Function<Edge, R> function) {
    try {
      if (hasNext()) {
        var edge = next().asStatefulEdge();
        if (edge != null) {
          return function.apply(edge);
        }

        return null;
      } else {
        throw null;
      }
    } finally {
      close();
    }
  }


  /**
   * Detaches the result set from the underlying session and returns the results as a list.
   */
  default List<Result> detach() {
    return stream().map(Result::detach).toList();
  }

  /**
   * Returns the result set as a stream of elements (filters only the results that are elements -
   * where the isEntity() method returns true). IMPORTANT: the stream consumes the result set!
   */
  default Stream<Entity> entityStream() {
    return StreamSupport.stream(
            new Spliterator<Entity>() {
              @Override
              public boolean tryAdvance(Consumer<? super Entity> action) {
                while (hasNext()) {
                  var elem = next();
                  if (elem.isEntity()) {
                    action.accept(elem.castToEntity());
                    return true;
                  }
                }
                return false;
              }

              @Override
              public Spliterator<Entity> trySplit() {
                return null;
              }

              @Override
              public long estimateSize() {
                return Long.MAX_VALUE;
              }

              @Override
              public int characteristics() {
                return ORDERED;
              }
            },
            false)
        .onClose(this::close);
  }

  default List<Entity> toEntityList() {
    return entityStream().toList();
  }

  /**
   * Returns the result set as a stream of vertices (filters only the results that are vertices -
   * where the isVertex() method returns true). IMPORTANT: the stream consumes the result set!
   */
  default Stream<Vertex> vertexStream() {
    return StreamSupport.stream(
            new Spliterator<Vertex>() {
              @Override
              public boolean tryAdvance(Consumer<? super Vertex> action) {
                while (hasNext()) {
                  var elem = next();
                  if (elem.isVertex()) {
                    action.accept(elem.castToVertex());
                    return true;
                  }
                }
                return false;
              }

              @Override
              public Spliterator<Vertex> trySplit() {
                return null;
              }

              @Override
              public long estimateSize() {
                return Long.MAX_VALUE;
              }

              @Override
              public int characteristics() {
                return ORDERED;
              }
            },
            false)
        .onClose(this::close);
  }

  default List<Vertex> toVertexList() {
    return vertexStream().toList();
  }

  default Stream<RID> ridStream() {
    return StreamSupport.stream(
            new Spliterator<RID>() {
              @Override
              public boolean tryAdvance(Consumer<? super RID> action) {
                while (hasNext()) {
                  var elem = next();
                  if (elem.isRecord()) {
                    action.accept(elem.getIdentity());
                    return true;
                  }
                }
                return false;
              }

              @Override
              public Spliterator<RID> trySplit() {
                return null;
              }

              @Override
              public long estimateSize() {
                return Long.MAX_VALUE;
              }

              @Override
              public int characteristics() {
                return ORDERED;
              }
            },
            false)
        .onClose(this::close);
  }

  default List<RID> toRidList() {
    return ridStream().toList();
  }

  /**
   * Returns the result set as a stream of vertices (filters only the results that are edges - where
   * the isEdge() method returns true). IMPORTANT: the stream consumes the result set!
   */
  default Stream<Edge> edgeStream() {
    return StreamSupport.stream(
            new Spliterator<Edge>() {
              @Override
              public boolean tryAdvance(Consumer<? super Edge> action) {
                while (hasNext()) {
                  var nextElem = next();
                  if (nextElem != null && nextElem.isStatefulEdge()) {
                    action.accept(nextElem.castToStatefulEdge());
                    return true;
                  }
                }
                return false;
              }

              @Override
              public Spliterator<Edge> trySplit() {
                return null;
              }

              @Override
              public long estimateSize() {
                return Long.MAX_VALUE;
              }

              @Override
              public int characteristics() {
                return ORDERED;
              }
            },
            false)
        .onClose(this::close);
  }

  default List<Edge> toEdgeList() {
    return edgeStream().toList();
  }
}

