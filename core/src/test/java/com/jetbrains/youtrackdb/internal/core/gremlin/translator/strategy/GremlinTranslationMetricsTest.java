package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.jetbrains.youtrackdb.internal.common.profiler.metrics.CoreMetrics;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.MetricsRegistry;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.Ratio;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.StubTicker;
import org.junit.Test;

/**
 * Unit tests for {@link GremlinTranslationMetrics}: lifetime counters and shares, shape-frequency
 * ranking, the soft cap on tracked shapes, and the three CoreMetrics Ratio sinks.
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

  /** Recording through a live registry feeds the three global Ratio definitions. */
  @Test
  public void recordThroughRegistry_feedsGlobalRatios() {
    var registry = new MetricsRegistry(new StubTicker(1));
    var metrics = new GremlinTranslationMetrics(registry);

    metrics.recordSuccess();
    metrics.recordDecline("[GraphStep]");
    metrics.recordError("[GraphStep, HasStep]");

    Ratio successes = registry.globalMetric(CoreMetrics.GREMLIN_TRANSLATION_SUCCESS_RATIO);
    Ratio declines = registry.globalMetric(CoreMetrics.GREMLIN_TRANSLATION_DECLINE_RATIO);
    Ratio errors = registry.globalMetric(CoreMetrics.GREMLIN_TRANSLATION_ERROR_RATIO);
    assertThat(successes).isInstanceOf(Ratio.Impl.class);
    assertThat(declines).isInstanceOf(Ratio.Impl.class);
    assertThat(errors).isInstanceOf(Ratio.Impl.class);
  }
}
