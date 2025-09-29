package com.jetbrains.youtrackdb.internal.common.collection;

import java.util.Iterator;
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
}
