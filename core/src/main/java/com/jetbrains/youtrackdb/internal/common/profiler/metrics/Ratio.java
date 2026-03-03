package com.jetbrains.youtrackdb.internal.common.profiler.metrics;

import com.jetbrains.youtrackdb.internal.common.profiler.Ticker;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.Meter.Mode;
import org.apache.commons.lang3.Validate;

/**
 * A metric that calculates the ratio of successful events to the total number of events.
 */
public interface Ratio extends Metric<Double> {

  void record(long success, long total);

  default void record(boolean success) {
    record(success ? 1 : 0, 1);
  }

  double getRatio();

  @Override
  default Double getValue() {
    return getRatio();
  }

  static Ratio create(
      Ticker ticker,
      TimeInterval flushRate,
      TimeInterval collectionPeriod
  ) {
    return create(ticker, flushRate, collectionPeriod, 1.0);
  }

  static Ratio create(
      Ticker ticker,
      TimeInterval flushRate,
      TimeInterval collectionPeriod,
      double coefficient
  ) {
    return create(ticker, flushRate, collectionPeriod, coefficient,
        Meter.DEFAULT_TICK_CHECK_INTERVAL);
  }

  // Exposed for tests to control tick check interval for deterministic flushing.
  // Not part of the public API — this interface is in the internal package.
  static Ratio create(
      Ticker ticker,
      TimeInterval flushRate,
      TimeInterval collectionPeriod,
      double coefficient,
      int tickCheckInterval
  ) {
    return new Impl(
        ticker,
        flushRate.toNanos() / ticker.getGranularity(),
        collectionPeriod.toNanos() / ticker.getGranularity(),
        coefficient,
        tickCheckInterval
    );
  }


  Ratio NOOP = new Ratio() {
    @Override
    public void record(long success, long total) {
      // empty
    }

    @Override
    public double getRatio() {
      return 0.0;
    }
  };


  class Impl implements Ratio {

    private final Meter meter;
    private final double coefficient;

    public Impl(Ticker ticker, long flushRateTicks, long periodTicks, double coefficient) {
      this(ticker, flushRateTicks, periodTicks, coefficient, Meter.DEFAULT_TICK_CHECK_INTERVAL);
    }

    Impl(Ticker ticker, long flushRateTicks, long periodTicks, double coefficient,
        int tickCheckInterval) {
      this.coefficient = coefficient;
      this.meter = new Meter(ticker, Mode.SUCCESS_RATIO, flushRateTicks, periodTicks,
          tickCheckInterval);
    }

    @Override
    public void record(long success, long total) {
      Validate.isTrue(success >= 0, "Success count must be non-negative");
      Validate.isTrue(total > 0, "Total count must be positive");
      Validate.isTrue(success <= total, "Success count must be less or equal to total count");
      meter.record(success, total);
    }

    @Override
    public double getRatio() {
      final var rate = meter.getRate();
      return rate.leftLong() == 0 ?
          0.0 :
          coefficient * rate.leftLong() / rate.rightLong();
    }
  }
}
