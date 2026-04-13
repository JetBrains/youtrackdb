package com.jetbrains.youtrackdb.internal.common.profiler.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.sql.executor.TraversalPreFilterHelper;
import org.junit.After;
import org.junit.Test;

/**
 * Verifies the CoreMetrics definitions added for pre-filter live cost
 * calibration (Track 5): metric types, GLOBAL_METRICS registration,
 * and the config entry for explicit load-to-scan ratio override.
 */
public class PrefilterMetricsDefinitionTest {

  @After
  public void tearDown() {
    GlobalConfiguration.QUERY_PREFILTER_LOAD_TO_SCAN_RATIO.resetToDefault();
  }

  // ---- Metric type assertions ----

  /**
   * PREFILTER_SCAN_NANOS must resolve to a live (non-NOOP) TimeRate
   * metric instance from the registry.
   */
  @Test
  public void scanNanosIsLiveTimeRate() {
    var registry = new MetricsRegistry(new StubTicker(1));
    TimeRate metric = registry.globalMetric(CoreMetrics.PREFILTER_SCAN_NANOS);
    assertThat(metric).isInstanceOf(TimeRate.Impl.class);
  }

  /**
   * PREFILTER_SCAN_ENTRIES must resolve to a live (non-NOOP) TimeRate
   * metric instance from the registry.
   */
  @Test
  public void scanEntriesIsLiveTimeRate() {
    var registry = new MetricsRegistry(new StubTicker(1));
    TimeRate metric = registry.globalMetric(CoreMetrics.PREFILTER_SCAN_ENTRIES);
    assertThat(metric).isInstanceOf(TimeRate.Impl.class);
  }

  /**
   * PREFILTER_EFFECTIVENESS must resolve to a live (non-NOOP) Ratio
   * metric instance from the registry.
   */
  @Test
  public void effectivenessIsLiveRatio() {
    var registry = new MetricsRegistry(new StubTicker(1));
    Ratio metric = registry.globalMetric(CoreMetrics.PREFILTER_EFFECTIVENESS);
    assertThat(metric).isInstanceOf(Ratio.Impl.class);
  }

  // ---- GLOBAL_METRICS registration ----

  /**
   * GLOBAL_METRICS must contain exactly the 5 expected metrics —
   * the 2 originals plus the 3 new pre-filter metrics. This catches
   * both accidental additions and accidental removals.
   */
  @Test
  public void globalMetricsContainsExactlyExpectedMetrics() {
    assertThat(CoreMetrics.GLOBAL_METRICS).containsExactlyInAnyOrder(
        CoreMetrics.FILE_EVICTION_RATE,
        CoreMetrics.CACHE_HIT_RATIO,
        CoreMetrics.PREFILTER_SCAN_NANOS,
        CoreMetrics.PREFILTER_SCAN_ENTRIES,
        CoreMetrics.PREFILTER_EFFECTIVENESS);
  }

  // ---- Config entry assertions ----

  /** Default value of LOAD_TO_SCAN_RATIO is -1.0 (sentinel for auto-compute). */
  @Test
  public void loadToScanRatioDefaultIsSentinel() {
    assertThat(GlobalConfiguration.QUERY_PREFILTER_LOAD_TO_SCAN_RATIO
        .getValueAsDouble()).isEqualTo(-1.0);
  }

  /**
   * When not explicitly set, {@code configuredLoadToScanRatio()} returns
   * -1.0 (meaning auto-compute from live metrics).
   */
  @Test
  public void configuredLoadToScanRatioDefaultReturnsNegative() {
    assertThat(TraversalPreFilterHelper.configuredLoadToScanRatio())
        .isEqualTo(-1.0);
  }

  /**
   * When explicitly set to a positive value, the accessor returns
   * that value directly.
   */
  @Test
  public void configuredLoadToScanRatioExplicitOverride() {
    GlobalConfiguration.QUERY_PREFILTER_LOAD_TO_SCAN_RATIO.setValue(50.0);
    assertThat(TraversalPreFilterHelper.configuredLoadToScanRatio())
        .isEqualTo(50.0);
  }

  /**
   * The smallest positive double (Double.MIN_VALUE) is still positive
   * and must be returned by the accessor.
   */
  @Test
  public void configuredLoadToScanRatioSmallestPositive() {
    GlobalConfiguration.QUERY_PREFILTER_LOAD_TO_SCAN_RATIO
        .setValue(Double.MIN_VALUE);
    assertThat(TraversalPreFilterHelper.configuredLoadToScanRatio())
        .isEqualTo(Double.MIN_VALUE);
  }

  /**
   * When explicitly set to zero or negative, the accessor treats it
   * as auto-compute and returns -1.0.
   */
  @Test
  public void configuredLoadToScanRatioIgnoresNonPositive() {
    GlobalConfiguration.QUERY_PREFILTER_LOAD_TO_SCAN_RATIO.setValue(0.0);
    assertThat(TraversalPreFilterHelper.configuredLoadToScanRatio())
        .isEqualTo(-1.0);

    GlobalConfiguration.QUERY_PREFILTER_LOAD_TO_SCAN_RATIO.setValue(-5.0);
    assertThat(TraversalPreFilterHelper.configuredLoadToScanRatio())
        .isEqualTo(-1.0);
  }

  /**
   * When explicitly set to the sentinel value -1.0 itself, the accessor
   * must still return -1.0 (auto-compute), not treat it as an override.
   */
  @Test
  public void configuredLoadToScanRatioExplicitSentinelValue() {
    GlobalConfiguration.QUERY_PREFILTER_LOAD_TO_SCAN_RATIO.setValue(-1.0);
    assertThat(TraversalPreFilterHelper.configuredLoadToScanRatio())
        .isEqualTo(-1.0);
  }

  // ---- NOOP fallback assertions ----

  /**
   * TimeRate.NOOP must silently accept recordings without crashing.
   * This verifies the scan metric fallback path is safe when the
   * engine's MetricsRegistry is unavailable.
   */
  @Test
  public void timeRateNoopSilentlyDiscards() {
    TimeRate.NOOP.record(12345);
    assertThat(TimeRate.NOOP.getRate()).isEqualTo(0.0);
  }

  /**
   * Ratio.NOOP must silently accept recordings without crashing.
   * This verifies the effectiveness metric fallback path is safe
   * when the engine's MetricsRegistry is unavailable.
   */
  @Test
  public void ratioNoopSilentlyDiscards() {
    Ratio.NOOP.record(50, 100);
    assertThat(Ratio.NOOP.getRatio()).isEqualTo(0.0);
  }
}
