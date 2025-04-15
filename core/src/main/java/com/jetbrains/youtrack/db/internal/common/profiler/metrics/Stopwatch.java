package com.jetbrains.youtrack.db.internal.common.profiler.metrics;

import com.jetbrains.youtrack.db.internal.common.profiler.Ticker;

/**
 * Special type of Gauge for time measurements.
 */
public interface Stopwatch extends Gauge<Double> {

  @FunctionalInterface
  interface XRunnable<X extends Throwable> {

    void run() throws X;
  }

  long currentApproxNanoTime();

  default <X extends Throwable> void timed(XRunnable<X> runnable) throws X {
    long start = currentApproxNanoTime();
    try {
      runnable.run();
    } finally {
      setNanos(currentApproxNanoTime() - start);
    }
  }

  default void setMillis(long millis) {
    setValue((double) millis);
  }

  default void setNanos(long nanos) {
    setValue((double) nanos / 1_000_000);
  }

  Stopwatch NOOP = new Stopwatch() {
    @Override
    public void setValue(Double value) {
      // do nothing
    }

    @Override
    public long currentApproxNanoTime() {
      return 0;
    }

    @Override
    public Double getValue() {
      return 0.0;
    }

    @Override
    public <X extends Throwable> void timed(XRunnable<X> runnable) throws X {
      runnable.run();
    }
  };

  static Stopwatch create(Ticker ticker) {
    return new Impl(ticker);
  }

  class Impl implements Stopwatch {

    private final Ticker ticker;
    private volatile Double value;

    public Impl(Ticker ticker) {
      this.ticker = ticker;
    }

    @Override
    public long currentApproxNanoTime() {
      return ticker.lastNanoTime();
    }

    public Double getValue() {
      return value;
    }

    public void setValue(Double value) {
      this.value = value;
    }
  }
}
