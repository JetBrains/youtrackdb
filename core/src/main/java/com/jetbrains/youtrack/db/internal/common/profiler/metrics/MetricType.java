package com.jetbrains.youtrack.db.internal.common.profiler.metrics;

import com.jetbrains.youtrack.db.internal.common.profiler.Ticker;
import java.util.concurrent.TimeUnit;

/**
 * A metric type: gauge, stopwatch, time-rate, ratio. {@code MetricType} represents a specific type
 * of metric and holds its specific configuration parameters.
 */
public interface MetricType<M extends Metric<?>> {

  M create(Ticker ticker);

  M noop();

  Class<?> valueType();

  class GaugeType<T> implements MetricType<Gauge<T>> {

    private final Class<T> valueType;

    public GaugeType(Class<T> valueType) {
      this.valueType = valueType;
    }

    @Override
    public Gauge<T> create(Ticker ticker) {
      return Gauge.create();
    }

    @Override
    public Gauge<T> noop() {
      return Gauge.noop();
    }

    @Override
    public Class<?> valueType() {
      return valueType;
    }
  }

  class StopwatchType implements MetricType<Stopwatch> {

    @Override
    public Stopwatch create(Ticker ticker) {
      return Stopwatch.create(ticker);
    }

    @Override
    public Stopwatch noop() {
      return Stopwatch.NOOP;
    }

    @Override
    public Class<?> valueType() {
      return Double.class;
    }
  }

  class TimeRateType implements MetricType<TimeRate> {

    private final TimeInterval collectPeriod;
    private final TimeInterval flushRate;
    private final TimeUnit resolution;

    public TimeRateType(TimeInterval collectInterval, TimeInterval flushRate, TimeUnit resolution) {
      this.collectPeriod = collectInterval;
      this.flushRate = flushRate;
      this.resolution = resolution;
    }

    @Override
    public TimeRate create(Ticker ticker) {
      return TimeRate.create(ticker, flushRate, collectPeriod, resolution);
    }

    @Override
    public TimeRate noop() {
      return TimeRate.NOOP;
    }

    @Override
    public Class<?> valueType() {
      return Double.class;
    }
  }

  class RatioType implements MetricType<Ratio> {

    private final TimeInterval collectPeriod;
    private final TimeInterval flushRate;
    private final double coefficient;

    public RatioType(TimeInterval collectPeriod, TimeInterval flushRate, double coefficient) {
      this.collectPeriod = collectPeriod;
      this.flushRate = flushRate;
      this.coefficient = coefficient;
    }

    @Override
    public Ratio create(Ticker ticker) {
      return Ratio.create(ticker, flushRate, collectPeriod, coefficient);
    }

    @Override
    public Ratio noop() {
      return Ratio.NOOP;
    }

    @Override
    public Class<?> valueType() {
      return Double.class;
    }
  }

  static <T> GaugeType<T> gauge(Class<T> valueType) {
    return new GaugeType<>(valueType);
  }

  static StopwatchType stopwatch() {
    return new StopwatchType();
  }

  static TimeRateType rate(TimeInterval interval, TimeInterval flushRate, TimeUnit resolution) {
    return new TimeRateType(interval, flushRate, resolution);
  }

  static RatioType ratio(TimeInterval interval, TimeInterval flushRate) {
    return new RatioType(interval, flushRate, 1.0);
  }

  static RatioType ratio(TimeInterval interval, TimeInterval flushRate, double coefficient) {
    return new RatioType(interval, flushRate, coefficient);
  }

}
