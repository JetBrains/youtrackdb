package com.jetbrains.youtrack.db.internal.common.profiler.metrics;

/**
 * A metric that holds a single value of type T.
 */
public interface Gauge<T> extends Metric<T> {

  void setValue(T value);

  Gauge<?> NOOP = new Gauge<>() {
    @Override
    public void setValue(Object value) {
      // do nothing
    }

    @Override
    public Object getValue() {
      return null;
    }
  };

  static <T> Gauge<T> create() {
    return new Impl<>();
  }

  @SuppressWarnings("unchecked")
  static <T> Gauge<T> noop() {
    return (Gauge<T>) NOOP;
  }

  class Impl<T> implements Gauge<T> {

    private volatile T value;

    public T getValue() {
      return value;
    }

    public void setValue(T value) {
      this.value = value;
    }
  }
}
