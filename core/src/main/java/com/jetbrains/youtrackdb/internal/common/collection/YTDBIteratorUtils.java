package com.jetbrains.youtrackdb.internal.common.collection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.process.traversal.util.FastNoSuchElementException;
import org.apache.tinkerpop.gremlin.structure.util.CloseableIterator;
import org.apache.tinkerpop.gremlin.util.iterator.MultiIterator;

public final class YTDBIteratorUtils {

  private YTDBIteratorUtils() {
  }

  public static <S, E> CloseableIterator<E> flatMap(final Iterator<S> iterator,
      final Function<S, Iterator<E>> function) {
    return new CloseableIterator<>() {

      private Iterator<E> currentIterator = Collections.emptyIterator();

      @Override
      public boolean hasNext() {
        if (this.currentIterator.hasNext()) {
          return true;
        } else {
          while (iterator.hasNext()) {
            this.currentIterator = function.apply(iterator.next());
            if (this.currentIterator.hasNext()) {
              return true;
            }
          }
        }
        return false;
      }

      @Override
      public void remove() {
        iterator.remove();
      }

      @Override
      public E next() {
        if (this.hasNext()) {
          return this.currentIterator.next();
        } else {
          throw FastNoSuchElementException.instance();
        }
      }

      @Override
      public void close() {
        CloseableIterator.closeIterator(iterator);
      }
    };
  }

  public static <S, E> CloseableIterator<E> map(final Iterator<S> iterator,
      final Function<S, E> function) {
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

  public static <S> CloseableIterator<S> filter(final Iterator<S> iterator,
      final Predicate<S> predicate) {
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

  public static <T> CloseableIterator<T> unmodifiableIterator(final Iterator<T> iterator) {
    return new CloseableIterator<>() {
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

  public static long count(final Iterator<?> iterator) {
    long ix = 0;
    for (; iterator.hasNext(); ++ix) {
      iterator.next();
    }
    CloseableIterator.closeIterator(iterator);
    return ix;
  }

  @SafeVarargs
  public static <S> Iterator<S> concat(final Iterator<S>... iterators) {
    final var iterator = new MultiIterator<S>();

    for (final var itty : iterators) {
      iterator.addIterator(itty);
    }

    return iterator;
  }

  public static <T> CloseableIterator<T> mergeSortedIterators(final Iterator<T> firstIterator,
      Iterator<T> secondIterator, Comparator<T> comparator) {
    return new SortedCompositeIterator<>(firstIterator, secondIterator, comparator);
  }

  private static final class SortedCompositeIterator<T> implements CloseableIterator<T> {

    private final Iterator<T> firstIterator;
    @Nonnull
    private final Iterator<T> secondIterator;

    private T firstValue;
    private T secondValue;

    @Nullable
    private final Comparator<? super T> comparator;

    private SortedCompositeIterator(
        @Nonnull
        Iterator<T> firstIterator,
        @Nonnull
        Iterator<T> secondIterator,
        @Nullable
        Comparator<? super T> comparator) {
      this.firstIterator = firstIterator;
      this.secondIterator = secondIterator;
      this.comparator = comparator;
    }

    @Override
    public boolean hasNext() {
      if (firstValue == null) {
        if (firstIterator.hasNext()) {
          firstValue = firstIterator.next();
        }
      }

      if (secondValue == null) {
        if (secondIterator.hasNext()) {
          secondValue = secondIterator.next();
        }
      }

      return firstValue != null || secondValue != null;
    }

    @Override
    public T next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }

      T result;
      if (secondValue == null) {
        result = firstValue;
        firstValue = null;
      } else if (firstValue == null) {
        result = secondValue;
        secondValue = null;
      } else {
        int res;
        if (comparator != null) {
          res = comparator.compare(firstValue, secondValue);
        } else if (firstValue instanceof Comparable<?>
            && secondValue instanceof Comparable<?>) {
          //noinspection unchecked
          res = ((Comparable<T>) firstValue).compareTo(secondValue);
        } else {
          throw new IllegalArgumentException(
              "Cannot compare values : " + firstValue + " and " + secondValue);
        }

        if (res == 0) {
          if (firstValue.equals(secondValue)) {
            result = firstValue;
            firstValue = null;
            secondValue = null;
          } else {
            result = firstValue;
            firstValue = null;
          }
        } else if (res < 0) {
          result = firstValue;
          firstValue = null;
        } else {
          result = secondValue;
          secondValue = null;
        }
      }

      return result;

    }

    @Override
    public void close() {
      if (firstIterator instanceof CloseableIterator<?> a
          && secondIterator instanceof CloseableIterator<?> b) {
        try {
          a.close();
        } catch (Exception e1) {
          try {
            b.close();
          } catch (Exception e2) {
            try {
              e1.addSuppressed(e2);
            } catch (Exception throwable) {
              throw new RuntimeException(throwable);
            }
          }
          throw e1;
        }
        b.close();
      } else if (firstIterator instanceof CloseableIterator<?> a) {
        a.close();
      } else if (secondIterator instanceof CloseableIterator<?> b) {
        b.close();
      }
    }
  }
}
