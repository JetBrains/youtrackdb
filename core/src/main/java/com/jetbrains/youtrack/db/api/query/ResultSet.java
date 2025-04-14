package com.jetbrains.youtrack.db.api.query;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.record.Edge;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.record.StatefulEdge;
import com.jetbrains.youtrack.db.api.record.Vertex;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface ResultSet extends Spliterator<Result>, Iterator<Result>, AutoCloseable {

  @Override
  boolean hasNext();

  @Override
  Result next();

  @Override
  default void remove() {
    throw new UnsupportedOperationException();
  }

  @Override
  void close();

  @Nullable
  ExecutionPlan getExecutionPlan();

  @Nullable
  DatabaseSession getBoundToSession();

  /**
   * Returns the result set as a stream. IMPORTANT: the stream consumes the result set!
   */
  @Nonnull
  default Stream<Result> stream() {
    return StreamSupport.stream(this, false).onClose(this::close);
  }

  @Nonnull
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

  @Nonnull
  default Result findFirst() {
    try {
      if (hasNext()) {
        return next();
      } else {
        throw new NoSuchElementException();
      }
    } finally {
      close();
    }
  }

  @Nullable
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

  @Nullable
  default Result findFirstOrNull() {
    try {
      if (hasNext()) {
        return next();
      } else {
        return null;
      }
    } finally {
      close();
    }
  }

  @Nonnull
  default Entity findFirstEntity() {
    try {
      if (hasNext()) {
        return next().asEntity();
      } else {
        throw new NoSuchElementException();
      }
    } finally {
      close();
    }
  }

  @Nonnull
  default <R> R findFirstEntity(@Nonnull Function<Entity, R> function) {
    try {
      if (hasNext()) {
        return function.apply(next().asEntity());
      } else {
        throw new NoSuchElementException();
      }
    } finally {
      close();
    }
  }

  @Nullable
  default <R> R findFirstEntityOrNull(@Nonnull Function<Entity, R> function) {
    try {
      if (hasNext()) {
        var entity = next().asEntityOrNull();

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

  @Nonnull
  default <R> R findFirstVertex(@Nonnull Function<Vertex, R> function) {
    try {
      if (hasNext()) {
        return function.apply(next().asVertex());
      } else {
        throw new NoSuchElementException();
      }
    } finally {
      close();
    }
  }

  @Nonnull
  default Vertex findFirstVertex() {
    try {
      if (hasNext()) {
        return next().asVertex();
      } else {
        throw new NoSuchElementException();
      }
    } finally {
      close();
    }
  }

  @Nullable
  default <R> R findFirstVertexOrNull(@Nonnull Function<Vertex, R> function) {
    try {
      if (hasNext()) {
        var vertex = next().asVertexOrNull();
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

  @Nonnull
  default Edge findFirstEdge() {
    try {
      if (hasNext()) {
        return next().asEdge();
      } else {
        throw new NoSuchElementException();
      }
    } finally {
      close();
    }
  }

  @Nonnull
  default <R> R findFirstEdge(@Nonnull Function<Edge, R> function) {
    try {
      if (hasNext()) {
        return function.apply(next().asEdge());
      } else {
        throw new NoSuchElementException();
      }
    } finally {
      close();
    }
  }


  @Nullable
  default Edge findFirstEdgeOrNull() {
    try {
      if (hasNext()) {
        return next().asEdgeOrNull();
      } else {
        return null;
      }
    } finally {
      close();
    }
  }

  @Nullable
  default <R> R findFirstEdgeOrNull(@Nonnull Function<Edge, R> function) {
    try {
      if (hasNext()) {
        var edge = next().asEdgeOrNull();
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

  @Nonnull
  default <R> R findFirstStateFullEdge(@Nonnull Function<Edge, R> function) {
    try {
      if (hasNext()) {
        return function.apply(next().asStatefulEdge());
      } else {
        throw new NoSuchElementException();
      }
    } finally {
      close();
    }
  }

  @Nullable
  default <R> R findFirstSateFullEdgeOrNull(@Nonnull Function<Edge, R> function) {
    try {
      if (hasNext()) {
        var edge = next().asStatefulEdgeOrNull();
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
  @Nonnull
  default List<Result> detach() {
    return stream().map(Result::detach).toList();
  }

  /**
   * Returns the result set as a stream of elements (filters only the results that are elements -
   * where the isEntity() method returns true). IMPORTANT: the stream consumes the result set!
   */
  @Nonnull
  default Stream<Entity> entityStream() {
    return StreamSupport.stream(
            new Spliterator<Entity>() {
              @Override
              public boolean tryAdvance(Consumer<? super Entity> action) {
                while (hasNext()) {
                  var elem = next();
                  if (elem != null) {
                    action.accept(elem.asEntity());
                    return true;
                  }
                }
                return false;
              }

              @Override
              @Nullable
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


  default void forEachEntity(@Nonnull Consumer<? super Entity> action) {
    entityStream().forEach(action);
  }

  @Nonnull
  default List<Entity> toEntityList() {
    return entityStream().toList();
  }

  /**
   * Returns the result set as a stream of vertices (filters only the results that are vertices -
   * where the isVertex() method returns true). IMPORTANT: the stream consumes the result set!
   */
  @Nonnull
  default Stream<Vertex> vertexStream() {
    return StreamSupport.stream(
            new Spliterator<Vertex>() {
              @Override
              public boolean tryAdvance(Consumer<? super Vertex> action) {
                while (hasNext()) {
                  var elem = next();

                  if (elem != null) {
                    action.accept(elem.asVertex());
                    return true;
                  }

                }
                return false;
              }

              @Override
              @Nullable
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

  default void forEachVertex(@Nonnull Consumer<? super Vertex> action) {
    vertexStream().forEach(action);
  }

  @Nonnull
  default List<Vertex> vertexList() {
    return vertexStream().toList();
  }

  @Nonnull
  default Stream<RID> ridStream() {
    return StreamSupport.stream(
            new Spliterator<RID>() {
              @Override
              public boolean tryAdvance(Consumer<? super RID> action) {
                while (hasNext()) {
                  var elem = next();

                  if (elem == null) {
                    continue;
                  }

                  if (elem.isRecord()) {
                    action.accept(elem.getIdentity());
                    return true;
                  } else {
                    throw new IllegalStateException(elem + " is not a record");
                  }
                }

                return false;
              }

              @Override
              @Nullable
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

  @Nonnull
  default List<RID> toRidList() {
    return ridStream().toList();
  }

  /**
   * Returns the result set as a stream of vertices (filters only the results that are edges - where
   * the isEdge() method returns true). IMPORTANT: the stream consumes the result set!
   */
  @Nonnull
  default Stream<StatefulEdge> statefulEdgeStream() {
    return StreamSupport.stream(
            new Spliterator<StatefulEdge>() {
              @Override
              public boolean tryAdvance(Consumer<? super StatefulEdge> action) {
                while (hasNext()) {
                  var nextElem = next();
                  if (nextElem != null) {
                    action.accept(nextElem.asStatefulEdge());
                    return true;
                  }
                }
                return false;
              }

              @Nullable
              @Override
              public Spliterator<StatefulEdge> trySplit() {
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

  default void forEachStatefulEdge(@Nonnull Consumer<? super StatefulEdge> action) {
    statefulEdgeStream().forEach(action);
  }

  @Nonnull
  default List<StatefulEdge> toStatefulEdgeList() {
    return statefulEdgeStream().toList();
  }

  @Nonnull
  default Stream<Edge> edgeStream() {
    return StreamSupport.stream(
            new Spliterator<Edge>() {
              @Override
              public boolean tryAdvance(Consumer<? super Edge> action) {
                while (hasNext()) {
                  var nextElem = next();
                  if (nextElem != null) {
                    action.accept(nextElem.asStatefulEdge());
                    return true;
                  }
                }
                return false;
              }

              @Nullable
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

  default void forEachEdge(Consumer<? super Edge> action) {
    edgeStream().forEach(action);
  }

  @Nonnull
  default List<Edge> toEdgeList() {
    return edgeStream().toList();
  }


  @Nonnull
  default Stream<Result> detachedStream() {
    return StreamSupport.stream(
            new Spliterator<Result>() {
              @Override
              public boolean tryAdvance(Consumer<? super Result> action) {
                while (hasNext()) {
                  var nextElem = next();
                  if (nextElem != null) {
                    action.accept(nextElem.detach());
                    return true;
                  }
                }
                return false;
              }

              @Nullable
              @Override
              public Spliterator<Result> trySplit() {
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

  @Nonnull
  default List<Result> toDetachedList() {
    return detachedStream().toList();
  }

  @Override
  void forEachRemaining(@Nonnull Consumer<? super Result> action);
}

