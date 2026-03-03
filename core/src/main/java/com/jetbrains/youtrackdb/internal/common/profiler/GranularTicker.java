package com.jetbrains.youtrackdb.internal.common.profiler;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/// Default implementation of [Ticker] that updates its internal time at a certain granularity in a
/// separate thread.
public class GranularTicker implements Ticker, AutoCloseable {

  private final long granularity;
  private final long timestampRefreshRate;
  private volatile long nanoTime;
  /// Offset between {@link System#currentTimeMillis()} and {@link System#nanoTime()} converted to
  /// milliseconds. Since {@code nanoTime} has no defined origin (it is only meaningful for
  /// measuring elapsed time), this difference lets us derive an approximate wall-clock timestamp
  /// from the cached {@code nanoTime} without an extra {@code currentTimeMillis()} call. Refreshed
  /// periodically to account for clock drift.
  private volatile long nanoTimeDifference;

  private final ScheduledExecutorService executor;
  private volatile ScheduledFuture<?> nanoTimeFuture;
  private volatile ScheduledFuture<?> nanoTimeDifferenceFuture;

  public GranularTicker(long granularityNanos, long timestampRefreshRate,
      ScheduledExecutorService executor) {
    this.executor = executor;
    this.timestampRefreshRate = timestampRefreshRate;
    this.granularity = granularityNanos;
  }

  @Override
  public void start() {
    if (executor == null) {
      throw new IllegalStateException(
          "Cannot start GranularTicker without a ScheduledExecutorService");
    }
    assert nanoTime == 0 : "Ticker is already started";
    this.nanoTime = System.nanoTime();
    this.nanoTimeDifference = System.currentTimeMillis() - this.nanoTime / 1_000_000;

    nanoTimeFuture = executor.scheduleAtFixedRate(
        () -> nanoTime = System.nanoTime(),
        granularity, granularity, TimeUnit.NANOSECONDS
    );
    nanoTimeDifferenceFuture = executor.scheduleAtFixedRate(
        () -> nanoTimeDifference = System.currentTimeMillis() - System.nanoTime() / 1_000_000,
        timestampRefreshRate, timestampRefreshRate, TimeUnit.NANOSECONDS
    );
  }

  @Override
  public long approximateNanoTime() {
    return nanoTime;
  }

  @Override
  public long approximateCurrentTimeMillis() {
    return nanoTime / 1_000_000 + nanoTimeDifference;
  }

  @Override
  public long currentNanoTime() {
    return System.nanoTime();
  }

  @Override
  public long getTick() {
    return nanoTime / granularity;
  }

  @Override
  public long getGranularity() {
    return granularity;
  }

  @Override
  public void stop() {
    var f1 = nanoTimeFuture;
    if (f1 != null) {
      f1.cancel(false);
    }
    var f2 = nanoTimeDifferenceFuture;
    if (f2 != null) {
      f2.cancel(false);
    }
  }

  @Override
  public void close() {
    stop();
  }
}
