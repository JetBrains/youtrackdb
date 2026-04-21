package com.jetbrains.youtrackdb.internal.common.profiler;

import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/// Default implementation of [Ticker] that updates its internal time at a certain granularity in a
/// separate thread.
///
/// ## Concurrency
///
/// A single scheduled task samples [System#nanoTime()] every `granularity` and publishes a
/// fresh [Snapshot] into the `volatile` reference [#snapshot]. Every *N*-th fire the same
/// task also samples [System#currentTimeMillis()] and recomputes the wall-clock offset
/// (`nanoTimeDifference`) where `N = timestampRefreshRate / granularity`. Readers do a single
/// volatile read of `snapshot` and derive both accessors from it — no reader-side CAS.
///
/// Monotonicity is enforced at the writer by [#nextSnapshot]: when building the next
/// snapshot the task compares the candidate against the previously published snapshot and
/// clamps
///
/// - `nanoTime` so it never regresses across publishes (insurance against cross-core clock
///   jitter on older hardware),
/// - the derived wall-clock millis so sub-millisecond drift between `System.nanoTime()` and
///   `System.currentTimeMillis()` at a recalibration fire cannot produce a backward jump in
///   [#approximateCurrentTimeMillis()].
///
/// Because the refresh task is the only writer, `snapshot = nextSnapshot(...)` never races
/// with itself and no further serialization is needed. Readers observe an atomic, monotonic
/// `(nanoTime, nanoTimeDifference)` pair via the volatile store on `snapshot`.
///
/// Callers such as `Stopwatch.timed()`, `YTDBQueryMetricsStep` and
/// `FrontendTransactionImpl.notifyMetricsListener` compute deltas across two reader
/// invocations and rely on non-negative durations, so monotonicity on both accessors is
/// load-bearing.
public class GranularTicker implements Ticker, AutoCloseable {

  /// Immutable view of the pair `(nanoTime, nanoTimeDifference)`. Held behind one volatile
  /// reference so readers observe both values consistently.
  record Snapshot(long nanoTime, long nanoTimeDifference) {
  }

  private static final Snapshot INITIAL_SNAPSHOT = new Snapshot(0L, 0L);

  private final long granularity;
  /// How many refresh ticks happen between wall-clock recalibrations. Computed once from
  /// `timestampRefreshRate / granularity`; floored at 1 so the refresh task still recalibrates
  /// when the two configuration values are equal (used heavily by tests).
  private final long recalibrationInterval;
  private volatile boolean started;
  private volatile boolean stopped;

  /// Combined `(nanoTime, nanoTimeDifference)` snapshot. `nanoTime` is the most recently
  /// sampled [System#nanoTime()]. `nanoTimeDifference` is the offset between
  /// [System#currentTimeMillis()] and `nanoTime / 1_000_000` — it lets us derive an
  /// approximate wall-clock timestamp from the cached `nanoTime` without an extra
  /// `currentTimeMillis()` call. The difference is refreshed periodically to account for clock
  /// drift.
  private volatile Snapshot snapshot = INITIAL_SNAPSHOT;

  /// Fire counter owned exclusively by the scheduled refresh task. `scheduleAtFixedRate`
  /// guarantees a single task does not execute concurrently with itself, so no
  /// synchronization is needed here.
  private long tickCount;

  private final ScheduledExecutorService executor;
  private volatile ScheduledFuture<?> refreshFuture;

  public GranularTicker(long granularityNanos, long timestampRefreshRate,
      ScheduledExecutorService executor) {
    this.executor = Objects.requireNonNull(executor, "ScheduledExecutorService must not be null");
    this.granularity = granularityNanos;
    final long safeGranularity = Math.max(1L, granularityNanos);
    this.recalibrationInterval = Math.max(1L, timestampRefreshRate / safeGranularity);
  }

  @Override
  public void start() {
    assert !started : "Ticker is already started";
    started = true;
    final long initialNano = System.nanoTime();
    final long initialDiff = System.currentTimeMillis() - initialNano / 1_000_000;
    snapshot = new Snapshot(initialNano, initialDiff);

    refreshFuture = executor.scheduleAtFixedRate(
        this::refresh, granularity, granularity, TimeUnit.NANOSECONDS);
  }

  /// Single-writer refresh. Samples `System.nanoTime()` every fire and, every
  /// [#recalibrationInterval] fires, also samples `System.currentTimeMillis()` to recompute
  /// the wall-clock offset. Runs on the scheduled executor's thread.
  private void refresh() {
    if (stopped) {
      return;
    }
    tickCount++;
    final boolean recalibrate = tickCount % recalibrationInterval == 0;
    final long freshNano = System.nanoTime();
    // Sample currentTimeMillis only on recalibration fires. On non-recalibration fires the
    // previous snapshot's nanoTimeDifference is reused, so the syscall is skipped entirely.
    final long wallMillis = recalibrate ? System.currentTimeMillis() : 0L;
    snapshot = nextSnapshot(snapshot, freshNano, wallMillis, recalibrate);
  }

  /// Builds the next snapshot while enforcing monotonicity of both accessors. Package-private
  /// so unit tests can exercise the clamp without timing-dependent scheduler runs.
  ///
  /// - `nano` is clamped to `max(prev.nanoTime, freshNano)` so successive snapshots never
  ///   expose a regressing raw `nanoTime`.
  /// - On a recalibration fire the new offset is bumped up if needed so
  ///   `nano / 1_000_000 + newDiff >= prev.nanoTime / 1_000_000 + prev.nanoTimeDifference`,
  ///   absorbing the ±1 ms drift that can arise between `System.nanoTime()` and
  ///   `System.currentTimeMillis()`.
  static Snapshot nextSnapshot(Snapshot prev, long freshNano, long wallMillis,
      boolean recalibrate) {
    final long nano = Math.max(prev.nanoTime, freshNano);
    if (!recalibrate) {
      return new Snapshot(nano, prev.nanoTimeDifference);
    }
    long newDiff = wallMillis - nano / 1_000_000;
    final long prevMillis = prev.nanoTime / 1_000_000 + prev.nanoTimeDifference;
    final long candidateMillis = nano / 1_000_000 + newDiff;
    if (candidateMillis < prevMillis) {
      // Absorb the sub-ms drift between System.nanoTime() and System.currentTimeMillis() at
      // recalibration fires: inflate the offset so the derived wall-clock timestamp does not
      // regress against the previously published snapshot.
      newDiff += (prevMillis - candidateMillis);
    }
    return new Snapshot(nano, newDiff);
  }

  @Override
  public long approximateNanoTime() {
    return snapshot.nanoTime;
  }

  @Override
  public long approximateCurrentTimeMillis() {
    final var s = snapshot;
    return s.nanoTime / 1_000_000 + s.nanoTimeDifference;
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
    var f = refreshFuture;
    if (f != null) {
      f.cancel(false);
    }
  }

  @Override
  public void close() {
    stop();
  }
}
