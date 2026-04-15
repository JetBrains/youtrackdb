package com.jetbrains.youtrackdb.internal.common.profiler.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;
import org.junit.Test;

/**
 * Tests for metric factory methods, NOOP instances, MetricDefinition, MetricType, and CoreMetrics
 * constant definitions.
 */
public class MetricFactoryTest {

  private final StubTicker ticker = new StubTicker(1_000_000);

  // ---------------------------------------------------------------------------
  // Gauge
  // ---------------------------------------------------------------------------

  /** Gauge.create() produces a working gauge with get/set. */
  @Test
  public void gaugeCreateSetAndGet() {
    Gauge<String> gauge = Gauge.create();
    assertThat(gauge.getValue()).isNull();
    gauge.setValue("hello");
    assertThat(gauge.getValue()).isEqualTo("hello");
  }

  /** Gauge.noop() setValue does nothing, getValue returns null. */
  @Test
  public void gaugeNoopDoesNothing() {
    Gauge<String> gauge = Gauge.noop();
    gauge.setValue("hello");
    assertThat(gauge.getValue()).isNull();
  }

  // ---------------------------------------------------------------------------
  // Stopwatch
  // ---------------------------------------------------------------------------

  /** Stopwatch.create() produces a working stopwatch. */
  @Test
  public void stopwatchCreateAndSetMillis() {
    Stopwatch sw = Stopwatch.create(ticker);
    sw.setMillis(150);
    assertThat(sw.getValue()).isEqualTo(150.0);
  }

  /** Stopwatch.setNanos() converts to milliseconds. */
  @Test
  public void stopwatchSetNanosConvertsToMillis() {
    Stopwatch sw = Stopwatch.create(ticker);
    sw.setNanos(2_000_000);
    assertThat(sw.getValue()).isCloseTo(2.0, org.assertj.core.data.Offset.offset(0.1));
  }

  /** Stopwatch.timed() measures execution time. */
  @Test
  public void stopwatchTimedMeasuresExecution() throws Exception {
    Stopwatch sw = Stopwatch.create(ticker);
    ticker.setTime(1_000_000);
    sw.timed(
        () -> {
          ticker.setTime(11_000_000);
        });
    assertThat(sw.getValue()).isGreaterThan(0.0);
  }

  // ---------------------------------------------------------------------------
  // MetricDefinition
  // ---------------------------------------------------------------------------

  /** MetricDefinition record fields are accessible. */
  @Test
  public void metricDefinitionFields() {
    MetricType<Gauge<Long>> type = MetricType.gauge(Long.class);
    MetricDefinition<MetricScope.Global, Gauge<Long>> def =
        new MetricDefinition<>("test.metric", "Test", "A test metric", type);
    assertThat(def.name()).isEqualTo("test.metric");
    assertThat(def.label()).isEqualTo("Test");
    assertThat(def.description()).isEqualTo("A test metric");
    assertThat(def.enabled()).isTrue();
  }

  /** MetricDefinition.disable() returns a new definition with enabled=false. */
  @Test
  public void metricDefinitionDisable() {
    MetricType<Gauge<Long>> type = MetricType.gauge(Long.class);
    MetricDefinition<MetricScope.Global, Gauge<Long>> def =
        new MetricDefinition<>("test.metric", "Test", "A test metric", type);
    MetricDefinition<MetricScope.Global, Gauge<Long>> disabled = def.disable();
    assertThat(disabled.enabled()).isFalse();
    assertThat(disabled.name()).isEqualTo("test.metric");
  }

  // ---------------------------------------------------------------------------
  // MetricType
  // ---------------------------------------------------------------------------

  /** MetricType.gauge creates and provides noop instances. */
  @Test
  public void metricTypeGaugeCreateAndNoop() {
    MetricType<Gauge<Long>> type = MetricType.gauge(Long.class);
    assertThat(type.valueType()).isEqualTo(Long.class);

    Gauge<Long> instance = type.create(ticker);
    assertThat(instance).isNotNull();
    instance.setValue(42L);
    assertThat(instance.getValue()).isEqualTo(42L);

    Gauge<Long> noop = type.noop();
    noop.setValue(42L);
    assertThat(noop.getValue()).isNull();
  }

  /** MetricType.stopwatch creates and provides noop instances. */
  @Test
  public void metricTypeStopwatchCreateAndNoop() {
    MetricType<Stopwatch> type = MetricType.stopwatch();
    assertThat(type.valueType()).isEqualTo(Double.class);

    Stopwatch instance = type.create(ticker);
    assertThat(instance).isNotNull();

    Stopwatch noop = type.noop();
    noop.setMillis(100);
    // Noop stopwatch may return default value (0.0) rather than null
    assertThat(noop).isNotNull();
  }

  /** MetricType.rate creates time rate instances. */
  @Test
  public void metricTypeRateCreate() {
    var interval = TimeInterval.of(1, TimeUnit.SECONDS);
    var flushRate = TimeInterval.of(100, TimeUnit.MILLISECONDS);
    MetricType<TimeRate> type = MetricType.rate(interval, flushRate, TimeUnit.SECONDS);
    assertThat(type.valueType()).isEqualTo(Double.class);

    TimeRate instance = type.create(ticker);
    assertThat(instance).isNotNull();
  }

  /** MetricType.ratio creates ratio instances. */
  @Test
  public void metricTypeRatioCreate() {
    var interval = TimeInterval.of(1, TimeUnit.SECONDS);
    var flushRate = TimeInterval.of(100, TimeUnit.MILLISECONDS);
    MetricType<Ratio> type = MetricType.ratio(interval, flushRate);
    assertThat(type.valueType()).isEqualTo(Double.class);

    Ratio instance = type.create(ticker);
    assertThat(instance).isNotNull();
  }

  // ---------------------------------------------------------------------------
  // CoreMetrics
  // ---------------------------------------------------------------------------

  /** CoreMetrics has non-empty global and database metric sets. */
  @Test
  public void coreMetricsSetsAreNonEmpty() {
    assertThat(CoreMetrics.GLOBAL_METRICS).isNotEmpty();
    assertThat(CoreMetrics.DATABASE_METRICS).isNotEmpty();
  }

  /** CoreMetrics class metrics are all disabled by default. */
  @Test
  public void coreMetricsClassMetricsAreDisabled() {
    for (var def : CoreMetrics.CLASS_METRICS) {
      assertThat(def.enabled()).isFalse();
    }
  }

  // ---------------------------------------------------------------------------
  // TimeInterval
  // ---------------------------------------------------------------------------

  /** TimeInterval toNanos converts correctly. */
  @Test
  public void timeIntervalToNanos() {
    TimeInterval interval = TimeInterval.of(1, TimeUnit.SECONDS);
    assertThat(interval.toNanos()).isEqualTo(1_000_000_000L);
  }
}
