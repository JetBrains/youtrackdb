package com.jetbrains.youtrackdb.internal.common.profiler;

import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/// Default implementation of [Ticker] that updates its internal time at a certain granularity in a
/// separate thread.
///
/// ## Concurrency
///
/// The `(nanoTime, nanoTimeDifference)` pair is held in a single immutable [Snapshot] stored
/// behind one `volatile` reference so that [#approximateCurrentTimeMillis()] reads both values
/// atomically. Two independent volatile fields would allow a reader to observe a stale
/// `nanoTime` with a freshly recomputed `nanoTimeDifference` (or vice versa), producing a
/// combined value that drops below a previously returned timestamp — breaking the
/// non-decreasing contract that callers (e.g. elapsed-time measurements) rely on.
///
/// Both [#approximateNanoTime()] and [#approximateCurrentTimeMillis()] run their output
/// through an [AtomicLong] high-water mark ([#lastApproxNanos] / [#lastApproxMillis]) to
/// guarantee strict monotonicity. Two sources can otherwise produce a backward-moving
/// reading:
///
/// - The nanoTime-refresh task does a read-modify-write on the `snapshot` field (reads the
///   current `nanoTimeDifference`, then publishes a new `Snapshot` carrying a freshly
///   sampled `nanoTime`). If the wall-clock-recalibration task publishes between those two
///   steps with a later-sampled `nanoTime`, the nanoTime-refresh task's publish overwrites
///   it with an earlier-sampled value. Without the clamp, a reader observing the two
///   snapshots in sequence would see `nanoTime` decrease.
/// - Sub-millisecond drift between `System.nanoTime()` and `System.currentTimeMillis()`
///   can cause `nanoTimeDifference` to fluctuate by ±1 ms across recalibration fires,
///   which would otherwise manifest as a 1 ms backward jump in
///   `approximateCurrentTimeMillis()`.
///
/// Callers such as `Stopwatch.timed()` and `YTDBQueryMetricsStep` compute deltas across
/// two reader invocations and require non-negative durations, so monotonicity on both
/// accessors is load-bearing.
public class GranularTicker implements Ticker, AutoCloseable {

  /// Immutable view of the pair `(nanoTime, nanoTimeDifference)`. Held behind one volatile
  /// reference so readers observe both values consistently.
  private record Snapshot(long nanoTime, long nanoTimeDifference) {
  }

  private static final Snapshot INITIAL_SNAPSHOT = new Snapshot(0L, 0L);

  private final long granularity;
  private final long timestampRefreshRate;
  private volatile boolean started;
  private volatile boolean stopped;

  /// Combined `(nanoTime, nanoTimeDifference)` snapshot. `nanoTime` is the most recently
  /// sampled [System#nanoTime()]. `nanoTimeDifference` is the offset between
  /// [System#currentTimeMillis()] and [System#nanoTime()] converted to milliseconds — it lets
  /// us derive an approximate wall-clock timestamp from the cached `nanoTime` without an extra
  /// `currentTimeMillis()` call. The difference is refreshed periodically to account for clock
  /// drift.
  private volatile Snapshot snapshot = INITIAL_SNAPSHOT;

  /// Highest value ever returned by [#approximateNanoTime()]. Used to clamp the output so the
  /// method is strictly non-decreasing across concurrent readers and refresh fires. See the
  /// class-level concurrency notes for the race that necessitates this.
  private final AtomicLong lastApproxNanos = new AtomicLong(0L);

  /// Highest value ever returned by [#approximateCurrentTimeMillis()]. Used to clamp the
  /// output so the method is strictly non-decreasing across concurrent readers and refresh
  /// fires.
  private final AtomicLong lastApproxMillis = new AtomicLong(0L);

  private final ScheduledExecutorService executor;
  private volatile ScheduledFuture<?> nanoTimeFuture;
  private volatile ScheduledFuture<?> nanoTimeDifferenceFuture;

  public GranularTicker(long granularityNanos, long timestampRefreshRate,
      ScheduledExecutorService executor) {
    this.executor = Objects.requireNonNull(executor, "ScheduledExecutorService must not be null");
    this.timestampRefreshRate = timestampRefreshRate;
    this.granularity = granularityNanos;
  }

  @Override
  public void start() {
    assert !started : "Ticker is already started";
    started = true;
    final long initialNano = System.nanoTime();
    final long initialDiff = System.currentTimeMillis() - initialNano / 1_000_000;
    snapshot = new Snapshot(initialNano, initialDiff);

    nanoTimeFuture = executor.scheduleAtFixedRate(
        () -> {
          if (!stopped) {
            // Refresh only the nanoTime component. The snapshot's nanoTimeDifference stays
            // valid between wall-clock recalibrations because wall clock and nanoTime advance
            // at the same rate over short intervals.
            //
            // This read-modify-write is not atomic with a concurrent publish from the
            // wall-clock-recalibration task; see the class-level concurrency notes.
            var current = snapshot;
            snapshot = new Snapshot(System.nanoTime(), current.nanoTimeDifference);
          }
        },
        granularity, granularity, TimeUnit.NANOSECONDS);
    nanoTimeDifferenceFuture = executor.scheduleAtFixedRate(
        () -> {
          if (!stopped) {
            // Read wall clock and nanoTime together so the resulting snapshot represents a
            // consistent point in time. Sampling nanoTime here (rather than using the cached
            // field) ensures `nanoTime/1M + nanoTimeDifference` evaluates to exactly the
            // wall clock at recalibration fire time.
            final long fresh = System.nanoTime();
            final long newDiff = System.currentTimeMillis() - fresh / 1_000_000;
            snapshot = new Snapshot(fresh, newDiff);
          }
        },
        timestampRefreshRate, timestampRefreshRate, TimeUnit.NANOSECONDS);
  }

  @Override
  public long approximateNanoTime() {
    // Clamp to the highest value ever returned so a losing read-modify-write publish in the
    // nanoTime-refresh task cannot produce a backward jump. See class-level concurrency notes.
    return lastApproxNanos.accumulateAndGet(snapshot.nanoTime, Math::max);
  }

  @Override
  public long approximateCurrentTimeMillis() {
    final var s = snapshot;
    final long computed = s.nanoTime / 1_000_000 + s.nanoTimeDifference;
    // Clamp to the highest value ever returned so sub-ms drift at refresh fires cannot
    // produce a backward jump.
    return lastApproxMillis.accumulateAndGet(computed, Math::max);
  }

  @Override
  public long currentNanoTime() {
    return System.nanoTime();
  }

  @Override
  public long getTick() {
    return snapshot.nanoTime / granularity;
  }

  @Override
  public long getGranularity() {
    return granularity;
  }

  @Override
  public void stop() {
    stopped = true;
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
