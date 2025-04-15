package com.jetbrains.youtrack.db.internal.common.profiler.metrics;

/**
 * Definition of a database metric that can be registered in {@link MetricsRegistry}.
 */
public record MetricDefinition<
    S extends MetricScope,
    I extends Metric<?>>(
    String name,
    String label,
    String description,
    MetricType<I> type,
    boolean enabled
) {

  public MetricDefinition(String name, String label, String description, MetricType<I> type) {
    this(name, label, description, type, true);
  }

  public MetricDefinition<S, I> disable() {
    return new MetricDefinition<>(name, label, description, type, false);
  }
}
