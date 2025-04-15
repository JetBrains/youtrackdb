package com.jetbrains.youtrack.db.internal.common.profiler.metrics;

/**
 * Metric scope: global, per-database, per-class.
 */
public sealed interface MetricScope {

  final class Global implements MetricScope {

    private Global() {
    }
  }

  final class Database implements MetricScope {

    private Database() {
    }
  }

  final class Class implements MetricScope {

    private Class() {
    }
  }
}
