package com.jetbrains.youtrackdb.internal.common.collection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import org.apache.tinkerpop.gremlin.process.traversal.util.FastNoSuchElementException;
import org.apache.tinkerpop.gremlin.structure.util.CloseableIterator;

public final class YTDBIteratorUtils {

  private YTDBIteratorUtils() {
  }

  public static <S, E> Iterator<E> map(final Iterator<S> iterator, final Function<S, E> function) {
    return new CloseableIterator<>() {
      @Override
      public boolean hasNext() {
        return iterator.hasNext();
      }

      @Override
      public void remove() {
        iterator.remove();
      }

      @Override
      public E next() {
        return function.apply(iterator.next());
      }

      @Override
      public void close() {
        CloseableIterator.closeIterator(iterator);
      }
    };
  }

  public static <S> Iterator<S> filter(final Iterator<S> iterator, final Predicate<S> predicate) {
    return new CloseableIterator<>() {
      S nextResult = null;

      @Override
      public boolean hasNext() {
        if (null != this.nextResult) {
          return true;
        } else {
          advance();
          return null != this.nextResult;
        }
      }

      @Override
      public void remove() {
        iterator.remove();
      }

      @Override
      public S next() {
        try {
          if (null != this.nextResult) {
            return this.nextResult;
          } else {
            advance();
            if (null != this.nextResult) {
              return this.nextResult;
            } else {
              throw FastNoSuchElementException.instance();
            }
          }
        } finally {
          this.nextResult = null;
        }
      }

      @Override
      public void close() {
        CloseableIterator.closeIterator(iterator);
      }

      private void advance() {
        this.nextResult = null;
        while (iterator.hasNext()) {
          final var s = iterator.next();
          if (predicate.test(s)) {
            this.nextResult = s;
            return;
          }
        }
      }
    };
  }

  public static <T> Iterator<T> unmodifiableIterator(final Iterator<T> iterator) {
    return new CloseableIterator<T>() {
      @Override
      public boolean hasNext() {
        return iterator.hasNext();
      }

      @Override
      public void remove() {
        throw new UnsupportedOperationException("Remove is not supported");
      }

      @Override
      public T next() {
        return iterator.next();
      }

      @Override
      public void close() {
        CloseableIterator.closeIterator(iterator);
      }
    };
  }


  public static <S> Set<S> set(final Iterator<S> iterator) {
    return fill(iterator, new HashSet<>());
  }

  public static <S extends Collection<T>, T> S fill(final Iterator<T> iterator,
      final S collection) {
    while (iterator.hasNext()) {
      collection.add(iterator.next());
    }
    CloseableIterator.closeIterator(iterator);
    return collection;
  }

  public static <S> List<S> list(final Iterator<S> iterator) {
    return fill(iterator, new ArrayList<>());
  }

  public static <T> boolean anyMatch(final Iterator<T> iterator, final Predicate<T> predicate) {
    try {
      while (iterator.hasNext()) {
        if (predicate.test(iterator.next())) {
          return true;
        }
      }
      return false;
    } finally {
      CloseableIterator.closeIterator(iterator);
    }
  }

}
