package com.jetbrains.youtrack.db.internal.core.gremlin;

import static com.google.common.collect.Lists.newArrayList;

import java.util.Iterator;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class StreamUtils {

  public static <T> Stream<T> asStream(Iterator<T> sourceIterator) {
    return asStream(sourceIterator, false);
  }

  public static <T> Stream<T> asStream(Iterator<T> sourceIterator, boolean parallel) {
    Iterable<T> iterable = () -> sourceIterator;
    return StreamSupport.stream(iterable.spliterator(), parallel).onClose(() -> {
      if (sourceIterator instanceof AutoCloseable autoCloseable) {
        try {
          autoCloseable.close();
        } catch (Exception e) {
          throw new IllegalStateException("Error during closing of the iterator in stream.", e);
        }
      }
    });
  }

  public static Stream<String> asStream(String[] fieldNames) {
    return newArrayList(fieldNames).stream();
  }
}
