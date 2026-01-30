package com.jetbrains.youtrackdb.internal.common.profiler;

/// Entity that provides a way to approximate the current time. Because functions like
/// [System#nanoTime()] have a significant latency, it is not recommended to use them in
/// high-frequency operations. Instead, a ticker can be used to provide a less precise but more
/// efficient way to measure time. Internally, a ticker is based on a tick counter that is updated at
/// a certain granularity.
public interface Ticker {

  /// Starts the ticker.
  void start();

  /// Returns the last time the ticker was updated.
  long approximateNanoTime();

  long approximateCurrentTimeMillis();

  /// Returns the current nano time. Equivalent to [System#nanoTime()], but can be overridden
  /// for testing purposes.
  long currentNanoTime();

  /// Returns the current tick. The value of the tick does not have any specific meaning, but it is
  /// guaranteed to be updated at a certain granularity.
  long getTick();

  /// Returns the granularity of the ticker, in nanoseconds.
  long getGranularity();

  /// Stops the ticker and releases all resources.
  void stop();
}
