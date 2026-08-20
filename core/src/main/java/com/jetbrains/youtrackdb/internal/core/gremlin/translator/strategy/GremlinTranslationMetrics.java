package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.common.profiler.metrics.CoreMetrics;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.MetricDefinition;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.MetricScope.Global;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.MetricsRegistry;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.Ratio;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import javax.annotation.Nullable;

/**
 * Per-database counters for Gremlin-to-MATCH translation outcomes. Lives on {@code SharedContext}
 * beside {@link GremlinPlanCache}. Counts translation <em>attempts</em>: shapes the strategy tried
 * to cover — successes, declines (unsupported walk, edge / non-vertex start, repeat veto, cached
 * decline), and throw-safety-net errors. Kill-switch / missing session and idempotent re-applies
 * (boundary already present) are not attempts.
 *
 * <p>Each recording also feeds three engine-global {@link Ratio} metrics in {@link CoreMetrics}
 * (success / decline / error share of attempts). Shape frequencies for declines are bounded
 * ({@link #MAX_TRACKED_SHAPES}) so a long-running process cannot grow an unbounded map of rare
 * shapes.
 */
public final class GremlinTranslationMetrics {

  /** Cap on distinct step-shape keys retained for frequency reporting. */
  static final int MAX_TRACKED_SHAPES = 256;

  private final LongAdder successes = new LongAdder();
  private final LongAdder declines = new LongAdder();
  private final LongAdder errors = new LongAdder();

  private final ConcurrentHashMap<String, LongAdder> declineShapes = new ConcurrentHashMap<>();

  private final Ratio successRatio;
  private final Ratio declineRatio;
  private final Ratio errorRatio;

  public GremlinTranslationMetrics() {
    this(YouTrackDBEnginesManager.instance().getMetricsRegistry());
  }

  /**
   * Package-visible so tests can inject a registry (or null) without standing up the engine.
   */
  GremlinTranslationMetrics(@Nullable MetricsRegistry registry) {
    this.successRatio = globalRatio(registry, CoreMetrics.GREMLIN_TRANSLATION_SUCCESS_RATIO);
    this.declineRatio = globalRatio(registry, CoreMetrics.GREMLIN_TRANSLATION_DECLINE_RATIO);
    this.errorRatio = globalRatio(registry, CoreMetrics.GREMLIN_TRANSLATION_ERROR_RATIO);
  }

  /** Resolves the metrics holder for {@code session}'s shared context. */
  public static GremlinTranslationMetrics of(DatabaseSessionEmbedded session) {
    return session.getSharedContext().getGremlinTranslationMetrics();
  }

  public void recordSuccess() {
    successes.increment();
    // One attempt: success numerator advances; decline and error numerators stay zero.
    successRatio.record(1, 1);
    declineRatio.record(0, 1);
    errorRatio.record(0, 1);
  }

  /**
   * Records a decline (walker returned null, or a cached decline template was reused). {@code
   * shape} is the step-class list (no literal values); null/blank shapes are counted but not
   * tracked in the frequency map.
   */
  public void recordDecline(@Nullable String shape) {
    declines.increment();
    successRatio.record(0, 1);
    declineRatio.record(1, 1);
    errorRatio.record(0, 1);
    trackShape(declineShapes, shape);
  }

  /**
   * Records a throw-safety-net failure (unexpected {@link RuntimeException} during walk/plan).
   * {@code shape} is the step-class list at the time of the failure.
   */
  public void recordError(@Nullable String shape) {
    errors.increment();
    successRatio.record(0, 1);
    declineRatio.record(0, 1);
    errorRatio.record(1, 1);
  }

  public long getSuccesses() {
    return successes.sum();
  }

  public long getDeclines() {
    return declines.sum();
  }

  public long getErrors() {
    return errors.sum();
  }

  /** Successes + declines + errors. */
  public long getAttempts() {
    return getSuccesses() + getDeclines() + getErrors();
  }

  /**
   * Lifetime success share of attempts in {@code [0, 1]}, or {@code 0} when no attempts have been
   * recorded yet.
   */
  public double successShare() {
    return share(getSuccesses());
  }

  /**
   * Lifetime decline share of attempts in {@code [0, 1]}, or {@code 0} when no attempts have been
   * recorded yet.
   */
  public double declineShare() {
    return share(getDeclines());
  }

  /**
   * Lifetime error share of attempts in {@code [0, 1]}, or {@code 0} when no attempts have been
   * recorded yet.
   */
  public double errorShare() {
    return share(getErrors());
  }

  /**
   * Top declined step shapes by count, highest first, at most {@code limit} entries. Answers which
   * unsupported shapes dominate declines.
   */
  public List<ShapeCount> topDeclinedShapes(int limit) {
    return topShapes(declineShapes, limit);
  }

  private double share(long numerator) {
    var attempts = getAttempts();
    return attempts == 0 ? 0.0 : (double) numerator / (double) attempts;
  }

  private static void trackShape(ConcurrentHashMap<String, LongAdder> map, @Nullable String shape) {
    if (shape == null || shape.isBlank()) {
      return;
    }
    var existing = map.get(shape);
    if (existing != null) {
      existing.increment();
      return;
    }
    if (map.size() >= MAX_TRACKED_SHAPES) {
      // At capacity: keep counting totals, but do not retain a new rare shape key.
      return;
    }
    map.computeIfAbsent(shape, ignored -> new LongAdder()).increment();
  }

  private static List<ShapeCount> topShapes(ConcurrentHashMap<String, LongAdder> map, int limit) {
    if (limit <= 0 || map.isEmpty()) {
      return List.of();
    }
    var entries = new ArrayList<Map.Entry<String, LongAdder>>(map.size());
    entries.addAll(map.entrySet());
    entries.sort(
        Comparator.<Map.Entry<String, LongAdder>>comparingLong(e -> e.getValue().sum())
            .reversed()
            .thenComparing(Map.Entry::getKey));
    var n = Math.min(limit, entries.size());
    var out = new ArrayList<ShapeCount>(n);
    for (int i = 0; i < n; i++) {
      var e = entries.get(i);
      out.add(new ShapeCount(e.getKey(), e.getValue().sum()));
    }
    return List.copyOf(out);
  }

  private static Ratio globalRatio(
      @Nullable MetricsRegistry registry, MetricDefinition<Global, Ratio> definition) {
    return registry != null ? registry.globalMetric(definition) : Ratio.NOOP;
  }

  /**
   * One step-shape key and its lifetime count. Shape strings use step class simple names only (no
   * predicate literals).
   */
  public record ShapeCount(String shape, long count) {
  }
}
