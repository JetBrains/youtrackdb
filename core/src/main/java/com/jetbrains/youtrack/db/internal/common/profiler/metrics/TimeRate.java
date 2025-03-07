package com.jetbrains.youtrack.db.internal.common.profiler.metrics;

import com.jetbrains.youtrack.db.internal.common.profiler.Ticker;
import com.jetbrains.youtrack.db.internal.common.profiler.metrics.Meter.Mode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.TimeUnit;

/**
 * A metric that calculates the rate of events per unit of time in a given time interval.
 */
public interface TimeRate extends Metric<Double> {

  void record(long value);

  default void record() {
    record(1);
  }

  double getRate();

  @Override
  default Double getValue() {
    return getRate();
  }

  TimeRate NOOP = new TimeRate() {
    @Override
    public void record(long value) {
      // do nothing
    }

    @Override
    public double getRate() {
      return 0.0;
    }
  };

  static TimeRate create(
      Ticker ticker,
      TimeInterval flushRate,
      TimeInterval collectionPeriod,
      TimeUnit resolution
  ) {

    // makes sense to check that the configured flushRate and interval are multiples of ticker.granularity?
    return new Impl(
        ticker,
        flushRate.toNanos() / ticker.getGranularity(),
        collectionPeriod.toNanos() / ticker.getGranularity(),
        resolution
    );
  }

  class Impl implements TimeRate {

    private final Meter meter;
    private final BigDecimal resolution;

    public Impl(
        Ticker ticker,
        long flushRateTicks,
        long periodTicks,
        TimeUnit resolution
    ) {
      this.meter = new Meter(ticker, Mode.TIME_RATE, flushRateTicks, periodTicks);
      this.resolution = BigDecimal.valueOf(resolution.toNanos(1));
    }

    @Override
    public void record(long value) {
      meter.record(value, 0);
    }

    @Override
    public double getRate() {
      final var rate = meter.getRate();
      if (rate.leftLong() == 0) {
        return 0.0;
      }
      return BigDecimal.valueOf(rate.leftLong())
          .multiply(resolution)
          .divide(BigDecimal.valueOf(rate.rightLong()), 6, RoundingMode.HALF_UP)
          .doubleValue();
    }
  }
}
