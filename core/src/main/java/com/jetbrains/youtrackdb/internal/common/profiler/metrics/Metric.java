package com.jetbrains.youtrackdb.internal.common.profiler.metrics;

/**
 * Basic interface for all metrics collectors.
 */
public interface Metric<T> {

  T getValue();
}
