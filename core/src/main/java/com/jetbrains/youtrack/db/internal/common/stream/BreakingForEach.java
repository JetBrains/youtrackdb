package com.jetbrains.youtrack.db.internal.common.stream;

import java.util.function.BiConsumer;
import java.util.stream.Stream;

public class BreakingForEach {

  public static class Breaker {

    private boolean shouldBreak = false;

    public void stop() {
      shouldBreak = true;
    }
  }

  public static <T> void forEach(Stream<T> stream, BiConsumer<T, Breaker> consumer) {
    var spliterator = stream.spliterator();
    var hadNext = true;
    var breaker = new Breaker();

    while (hadNext && !breaker.shouldBreak) {
      hadNext =
          spliterator.tryAdvance(
              elem -> {
                consumer.accept(elem, breaker);
              });
    }
  }
}
