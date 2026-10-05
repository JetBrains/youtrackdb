package com.jetbrains.youtrackdb.internal.core.query;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
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

public interface BasicResultSet<R extends BasicResult> extends Spliterator<R>, Iterator<R>,
    AutoCloseable {

  @Override
  boolean hasNext();

  @Override
  R next();

  @Override
  default void remove() {
    throw new UnsupportedOperationException();
  }

  @Override
  void close();

  @Nullable DatabaseSessionEmbedded getBoundToSession();

  /**
   * Returns the result set as a stream. IMPORTANT: the stream consumes the result set!
   */
  @Nonnull
  default Stream<R> stream() {
    return StreamSupport.stream(this, false).onClose(this::close);
  }

  /** Reads all rows and closes this result set, also if reading fails. */
  @Nonnull
  default List<R> toList() {
    try (var rows = stream()) {
      return rows.toList();
    }
  }

  @Nullable default <O> O findFirst(@Nonnull Function<R, O> function) {
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

  default R findFirst() {
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

  @Nullable default <O> O findFirstOrNull(@Nonnull Function<R, O> function) {
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

  @Nullable default R findFirstOrNull() {
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

  /**
   * Detaches the rows from the underlying session and returns them as a list.
   * Closes this result set, also if reading or detaching fails.
   */
  @Nonnull
  default List<R> detach() {
    try (var rows = stream()) {
      //noinspection unchecked
      return rows.map(r -> (R) r.detach()).toList();
    }
  }

  @Nonnull
  default Stream<R> detachedStream() {
    return StreamSupport.stream(
        new Spliterator<R>() {
          @Override
          public boolean tryAdvance(Consumer<? super R> action) {
            while (hasNext()) {
              var nextElem = next();
              if (nextElem != null) {
                //noinspection unchecked
                action.accept((R) nextElem.detach());
                return true;
              }
            }
            return false;
          }

          @Nullable @Override
          public Spliterator<R> trySplit() {
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

  /** Reads detached rows and closes this result set, also if reading or detaching fails. */
  @Nonnull
  default List<R> toDetachedList() {
    try (var rows = detachedStream()) {
      return rows.toList();
    }
  }

  @Override
  void forEachRemaining(@Nonnull Consumer<? super R> action);

  boolean isClosed();
}
