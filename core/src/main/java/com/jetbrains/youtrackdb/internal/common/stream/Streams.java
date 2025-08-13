package com.jetbrains.youtrackdb.internal.common.stream;

import java.util.Comparator;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.stream.BaseStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class Streams {

  public static <T> Stream<T> mergeSortedSpliterators(
      @Nonnull Stream<T> streamOne, @Nonnull Stream<T> streamTwo,
      @Nullable Comparator<? super T> comparator) {
    final var spliterator =
        new SortedStreamSpliterator<>(streamOne.spliterator(), streamTwo.spliterator(),
            comparator);
    @SuppressWarnings("resource") final var stream = StreamSupport.stream(spliterator, false);
    return stream.onClose(composedClose(streamOne, streamTwo));
  }

  private static final class SortedStreamSpliterator<T> implements Spliterator<T>, Consumer<T> {

    private boolean firstStream;

    @Nonnull
    private final Spliterator<T> firstSpliterator;
    @Nonnull
    private final Spliterator<T> secondSpliterator;

    private T firstValue;
    private T secondValue;

    @Nullable
    private final Comparator<? super T> comparator;

    private SortedStreamSpliterator(
        @Nonnull
        Spliterator<T> firstSpliterator,
        @Nonnull
        Spliterator<T> secondSpliterator,
        @Nullable
        Comparator<? super T> comparator) {
      this.firstSpliterator = firstSpliterator;
      this.secondSpliterator = secondSpliterator;
      this.comparator = comparator;
    }

    @Override
    public boolean tryAdvance(Consumer<? super T> action) {
      if (firstValue == null) {
        firstStream = true;
        if (!firstSpliterator.tryAdvance(this)) {
          firstValue = null;
        }
      }

      if (secondValue == null) {
        firstStream = false;
        if (!secondSpliterator.tryAdvance(this)) {
          secondValue = null;
        }
      }

      if (firstValue == null && secondValue == null) {
        return false;
      }

      if (secondValue == null) {
        action.accept(firstValue);
        firstValue = null;
        return true;
      }

      if (firstValue == null) {
        action.accept(secondValue);
        secondValue = null;
        return true;
      }

      int res;
      if (comparator != null) {
        res = comparator.compare(firstValue, secondValue);
        if (res == 0) {
          if (firstValue.equals(secondValue)) {
            action.accept(firstValue);

            firstValue = null;
            secondValue = null;

            return true;
          } else {
            action.accept(firstValue);

            firstValue = null;
            return true;
          }
        }
      } else if (firstValue instanceof Comparable<?>
          && secondValue instanceof Comparable<?>) {
        //noinspection unchecked
        res = ((Comparable<T>) firstValue).compareTo(secondValue);
      } else {
        throw new IllegalArgumentException(
            "Cannot compare values : " + firstValue + " and " + secondValue);
      }

      if (res < 0) {
        action.accept(firstValue);
        firstValue = null;
        return true;
      }

      action.accept(secondValue);
      secondValue = null;

      return true;
    }

    @Nullable
    @Override
    public Spliterator<T> trySplit() {
      return null;
    }

    @Override
    public long estimateSize() {
      return Long.MAX_VALUE;
    }

    @Override
    public int characteristics() {
      return NONNULL | SORTED | ORDERED;
    }

    @Override
    @Nullable
    public Comparator<? super T> getComparator() {
      return comparator;
    }

    @Override
    public void accept(T t) {
      if (firstStream) {
        firstValue = t;
      } else {
        secondValue = t;
      }
    }
  }

  private static Runnable composedClose(BaseStream<?, ?> a, BaseStream<?, ?> b) {
    return () -> {
      try {
        a.close();
      } catch (Throwable e1) {
        try {
          b.close();
        } catch (Throwable e2) {
          try {
            e1.addSuppressed(e2);
          } catch (Throwable throwable) {
            if (throwable instanceof Error error) {
              throw error;
            }
            throw new RuntimeException(throwable);
          }
        }
        throw e1;
      }
      b.close();
    };
  }
}
