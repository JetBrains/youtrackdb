package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.common.profiler.metrics.CoreMetrics;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.MetricsRegistry;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.Ratio;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.StubTicker;
import org.junit.Test;

/**
 * Unit tests for {@link GremlinTranslationMetrics}: lifetime counters and shares, shape-frequency
 * ranking, the soft cap on tracked shapes, and the four CoreMetrics Ratio sinks.
 */
public class GremlinTranslationMetricsTest {

  /** Success, decline, and error each advance their own counter and the shared attempt total. */
  @Test
  public void recordOutcomes_advanceLifetimeCountersAndShares() {
    var metrics = new GremlinTranslationMetrics((MetricsRegistry) null);

    metrics.recordSuccess();
    metrics.recordDecline("[GraphStep, LambdaMapStep]");
    metrics.recordDecline("[GraphStep, RepeatStep]");
    metrics.recordError("[GraphStep, HasStep]");

    assertThat(metrics.getSuccesses()).isEqualTo(1);
    assertThat(metrics.getDeclines()).isEqualTo(2);
    assertThat(metrics.getErrors()).isEqualTo(1);
    assertThat(metrics.getAttempts()).isEqualTo(4);
    assertThat(metrics.successShare()).isCloseTo(0.25, within(1e-9));
    assertThat(metrics.declineShare()).isCloseTo(0.5, within(1e-9));
    assertThat(metrics.errorShare()).isCloseTo(0.25, within(1e-9));
  }

  /** Every outcome advances all four denominators and exactly one numerator. */
  @Test
  public void fourOutcomesFeedAllRatioDenominators() {
    var registry = mock(MetricsRegistry.class);
    var success = mock(Ratio.class);
    var decline = mock(Ratio.class);
    var error = mock(Ratio.class);
    var nativeChoice = mock(Ratio.class);
    when(registry.globalMetric(CoreMetrics.GREMLIN_TRANSLATION_SUCCESS_RATIO)).thenReturn(success);
    when(registry.globalMetric(CoreMetrics.GREMLIN_TRANSLATION_DECLINE_RATIO)).thenReturn(decline);
    when(registry.globalMetric(CoreMetrics.GREMLIN_TRANSLATION_ERROR_RATIO)).thenReturn(error);
    when(registry.globalMetric(CoreMetrics.GREMLIN_TRANSLATION_RULE_NATIVE_RATIO))
        .thenReturn(nativeChoice);
    var metrics = new GremlinTranslationMetrics(registry);
    metrics.recordSuccess();
    metrics.recordDecline("unsupported");
    metrics.recordError("failed");
    metrics.recordRuleNative();
    for (var ratio : java.util.List.of(success, decline, error, nativeChoice)) {
      verify(ratio).record(1, 1);
      verify(ratio, times(3)).record(0, 1);
    }
  }

  /** topDeclinedShapes ranks by count descending and breaks ties by shape string. */
  @Test
  public void topDeclinedShapes_ranksByCount() {
    var metrics = new GremlinTranslationMetrics((MetricsRegistry) null);
    metrics.recordDecline("[GraphStep, RepeatStep]");
    metrics.recordDecline("[GraphStep, LambdaMapStep]");
    metrics.recordDecline("[GraphStep, RepeatStep]");
    metrics.recordDecline("[GraphStep, RepeatStep]");

    assertThat(metrics.topDeclinedShapes(2))
        .containsExactly(
            new GremlinTranslationMetrics.ShapeCount("[GraphStep, RepeatStep]", 3),
            new GremlinTranslationMetrics.ShapeCount("[GraphStep, LambdaMapStep]", 1));
  }

  /**
   * When the shape map is at capacity, new distinct shapes are not retained, but the decline
   * counter still advances so totals stay honest.
   */
  @Test
  public void trackShape_stopsAddingKeysAtCap() {
    var metrics = new GremlinTranslationMetrics((MetricsRegistry) null);
    for (int i = 0; i < GremlinTranslationMetrics.MAX_TRACKED_SHAPES; i++) {
      metrics.recordDecline("[GraphStep, Extra" + i + "]");
    }
    metrics.recordDecline("[GraphStep, OverflowShape]");

    assertThat(metrics.getDeclines())
        .isEqualTo(GremlinTranslationMetrics.MAX_TRACKED_SHAPES + 1L);
    assertThat(metrics.topDeclinedShapes(Integer.MAX_VALUE))
        .hasSize(GremlinTranslationMetrics.MAX_TRACKED_SHAPES)
        .noneMatch(s -> s.shape().contains("OverflowShape"));
  }

  /** Blank shapes count toward declines but do not enter the frequency map. */
  @Test
  public void blankShape_countsWithoutFrequencyEntry() {
    var metrics = new GremlinTranslationMetrics((MetricsRegistry) null);
    metrics.recordDecline(" ");
    metrics.recordDecline(null);

    assertThat(metrics.getDeclines()).isEqualTo(2);
    assertThat(metrics.topDeclinedShapes(10)).isEmpty();
  }

  /** Recording through a live registry feeds the four global Ratio definitions. */
  @Test
  public void recordThroughRegistry_feedsGlobalRatios() {
    var registry = new MetricsRegistry(new StubTicker(1));
    var metrics = new GremlinTranslationMetrics(registry);

    metrics.recordSuccess();
    metrics.recordRuleNative();
    metrics.recordDecline("[GraphStep]");
    metrics.recordError("[GraphStep, HasStep]");

    Ratio successes = registry.globalMetric(CoreMetrics.GREMLIN_TRANSLATION_SUCCESS_RATIO);
    Ratio declines = registry.globalMetric(CoreMetrics.GREMLIN_TRANSLATION_DECLINE_RATIO);
    Ratio errors = registry.globalMetric(CoreMetrics.GREMLIN_TRANSLATION_ERROR_RATIO);
    assertThat(successes).isInstanceOf(Ratio.Impl.class);
    assertThat(declines).isInstanceOf(Ratio.Impl.class);
    assertThat(errors).isInstanceOf(Ratio.Impl.class);
    Ratio nativeRatio = registry.globalMetric(CoreMetrics.GREMLIN_TRANSLATION_RULE_NATIVE_RATIO);
    assertThat(nativeRatio).isInstanceOf(Ratio.Impl.class);
    assertThat(metrics.getRuleNative()).isEqualTo(1);
    assertThat(metrics.getAttempts()).isEqualTo(4);
    assertThat(metrics.ruleNativeShare()).isEqualTo(0.25);
    assertThat(metrics.successShare()).isEqualTo(0.25);
    assertThat(metrics.declineShare()).isEqualTo(0.25);
    assertThat(metrics.errorShare()).isEqualTo(0.25);
  }

  /** A fresh holder has no native share and a native choice never enters decline frequencies. */
  @Test
  public void ruleNativeHasIndependentCounterAndNoDeclineShape() {
    var metrics = new GremlinTranslationMetrics((MetricsRegistry) null);
    assertThat(metrics.ruleNativeShare()).isZero();
    metrics.recordRuleNative();
    assertThat(metrics.getRuleNative()).isEqualTo(1);
    assertThat(metrics.ruleNativeShare()).isEqualTo(1);
    assertThat(metrics.getSuccesses()).isZero();
    assertThat(metrics.getDeclines()).isZero();
    assertThat(metrics.getErrors()).isZero();
    assertThat(metrics.topDeclinedShapes(10)).isEmpty();
  }
}
