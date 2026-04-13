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

  /** PREFILTER_SCAN_NANOS must be a TimeRate metric. */
  @Test
  public void scanNanosIsTimeRate() {
    var registry = new MetricsRegistry(new StubTicker(1));
    TimeRate metric = registry.globalMetric(CoreMetrics.PREFILTER_SCAN_NANOS);
    assertThat(metric).isNotNull();
    // Fresh metric reports zero rate
    assertThat(metric.getRate()).isEqualTo(0.0);
    // MetricsRegistry is test-scoped — no cleanup needed
  }

  /** PREFILTER_SCAN_ENTRIES must be a TimeRate metric. */
  @Test
  public void scanEntriesIsTimeRate() {
    var registry = new MetricsRegistry(new StubTicker(1));
    TimeRate metric = registry.globalMetric(CoreMetrics.PREFILTER_SCAN_ENTRIES);
    assertThat(metric).isNotNull();
    assertThat(metric.getRate()).isEqualTo(0.0);
    // MetricsRegistry is test-scoped — no cleanup needed
  }

  /** PREFILTER_EFFECTIVENESS must be a Ratio metric with coefficient 100. */
  @Test
  public void effectivenessIsRatio() {
    var registry = new MetricsRegistry(new StubTicker(1));
    Ratio metric = registry.globalMetric(CoreMetrics.PREFILTER_EFFECTIVENESS);
    assertThat(metric).isNotNull();
    // Fresh ratio metric reports zero
    assertThat(metric.getRatio()).isEqualTo(0.0);
    // MetricsRegistry is test-scoped — no cleanup needed
  }

  // ---- GLOBAL_METRICS registration ----

  /** All three new metrics must be included in GLOBAL_METRICS. */
  @Test
  public void globalMetricsIncludesPrefilterMetrics() {
    assertThat(CoreMetrics.GLOBAL_METRICS)
        .contains(CoreMetrics.PREFILTER_SCAN_NANOS)
        .contains(CoreMetrics.PREFILTER_SCAN_ENTRIES)
        .contains(CoreMetrics.PREFILTER_EFFECTIVENESS);
  }

  /** GLOBAL_METRICS must still include the original two metrics. */
  @Test
  public void globalMetricsRetainsOriginalMetrics() {
    assertThat(CoreMetrics.GLOBAL_METRICS)
        .contains(CoreMetrics.FILE_EVICTION_RATE)
        .contains(CoreMetrics.CACHE_HIT_RATIO);
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
   * When explicitly set to a positive value, the accessor returns that value.
   */
  @Test
  public void configuredLoadToScanRatioExplicitOverride() {
    GlobalConfiguration.QUERY_PREFILTER_LOAD_TO_SCAN_RATIO.setValue(50.0);
    assertThat(TraversalPreFilterHelper.configuredLoadToScanRatio())
        .isEqualTo(50.0);
  }

  /**
   * When explicitly set to zero or negative, the accessor ignores it and
   * returns -1.0 (auto-compute).
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
}
