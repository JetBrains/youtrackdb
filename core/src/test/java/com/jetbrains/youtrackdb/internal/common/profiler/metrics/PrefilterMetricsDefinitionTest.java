package com.jetbrains.youtrackdb.internal.common.profiler.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.sql.executor.TraversalPreFilterHelper;
import org.junit.After;
import org.junit.Test;

/**
 * Verifies the CoreMetrics definitions used by pre-filter observability:
 * metric type, GLOBAL_METRICS registration, and the optional config entry
 * for explicit load-to-scan ratio override.
 */
public class PrefilterMetricsDefinitionTest {

  @After
  public void tearDown() {
    GlobalConfiguration.QUERY_PREFILTER_LOAD_TO_SCAN_RATIO.resetToDefault();
  }

  // ---- Metric type assertions ----

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
   * GLOBAL_METRICS must contain exactly the 2 originals plus
   * PREFILTER_EFFECTIVENESS. This catches both accidental additions and
   * accidental removals.
   */
  @Test
  public void globalMetricsContainsExactlyExpectedMetrics() {
    assertThat(CoreMetrics.GLOBAL_METRICS).containsExactlyInAnyOrder(
        CoreMetrics.FILE_EVICTION_RATE,
        CoreMetrics.CACHE_HIT_RATIO,
        CoreMetrics.PREFILTER_EFFECTIVENESS);
  }

  // ---- Config entry assertions ----

  /** Default value of LOAD_TO_SCAN_RATIO is 100.0 (SSD-calibrated). */
  @Test
  public void loadToScanRatioDefaultIsOneHundred() {
    assertThat(GlobalConfiguration.QUERY_PREFILTER_LOAD_TO_SCAN_RATIO
        .getValueAsDouble()).isEqualTo(100.0);
  }

  /**
   * When not explicitly set, {@code configuredLoadToScanRatio()} returns
   * the positive default value verbatim (no sentinel fallback).
   */
  @Test
  public void configuredLoadToScanRatioDefaultReturnsHundred() {
    assertThat(TraversalPreFilterHelper.configuredLoadToScanRatio())
        .isEqualTo(100.0);
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
   * as "not set" and returns -1.0.
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
   * must still return -1.0, not treat it as an override.
   */
  @Test
  public void configuredLoadToScanRatioExplicitSentinelValue() {
    GlobalConfiguration.QUERY_PREFILTER_LOAD_TO_SCAN_RATIO.setValue(-1.0);
    assertThat(TraversalPreFilterHelper.configuredLoadToScanRatio())
        .isEqualTo(-1.0);
  }

  /**
   * Infinity must be treated as "not set" to prevent assertion failures
   * in computeMinNeighborsForBuild (which requires finite input).
   */
  @Test
  public void configuredLoadToScanRatioInfinityTreatedAsUnset() {
    GlobalConfiguration.QUERY_PREFILTER_LOAD_TO_SCAN_RATIO
        .setValue(Double.POSITIVE_INFINITY);
    assertThat(TraversalPreFilterHelper.configuredLoadToScanRatio())
        .isEqualTo(-1.0);
  }

  /**
   * NaN must be treated as "not set".
   */
  @Test
  public void configuredLoadToScanRatioNaNTreatedAsUnset() {
    GlobalConfiguration.QUERY_PREFILTER_LOAD_TO_SCAN_RATIO
        .setValue(Double.NaN);
    assertThat(TraversalPreFilterHelper.configuredLoadToScanRatio())
        .isEqualTo(-1.0);
  }

  // ---- NOOP fallback assertions ----

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
