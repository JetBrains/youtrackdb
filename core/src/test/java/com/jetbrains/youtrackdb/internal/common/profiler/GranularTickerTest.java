package com.jetbrains.youtrackdb.internal.common.profiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.jetbrains.youtrackdb.internal.SequentialTest;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Delayed;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.experimental.categories.Category;

// GranularTicker now reads (nanoTime, nanoTimeDifference) as an atomic snapshot and clamps
// both approximateNanoTime() and approximateCurrentTimeMillis() through monotonic AtomicLong
// high-water marks, so correctness no longer depends on sequential execution. The @Category
// marker is retained because the wall-clock timing assertions in testTimeApproximation are
// still sensitive to CPU contention on virtualized CI runners (per-fire scheduler lag up to
// ~75 ms has been observed on GitHub-hosted macOS arm) — this is a stability hint, not a
// correctness requirement.
@Category(SequentialTest.class)
public class GranularTickerTest {

  @Test
  public void testTimeApproximation() throws Exception {
    final var granularityNanos = TimeUnit.MILLISECONDS.toNanos(10);
    final var timeAdjustmentNanos = TimeUnit.MILLISECONDS.toNanos(50);
    var scheduler = Executors.newScheduledThreadPool(1);
    try (var ticker = new GranularTicker(granularityNanos, timeAdjustmentNanos, scheduler)) {

      ticker.start();
      final var granularityMillis = TimeUnit.NANOSECONDS.toMillis(granularityNanos);

      final var startRealNano = System.nanoTime();
      final var startApproxNano = ticker.approximateNanoTime();
      var prevApproxNano = startApproxNano;
      var prevApproxMillis = ticker.approximateCurrentTimeMillis();

      for (var i = 0; i < 20; i++) {
        Thread.sleep(13);

        final var approxNano = ticker.approximateNanoTime();
        final var approxMillis = ticker.approximateCurrentTimeMillis();

        final var afterNano = System.nanoTime();
        final var afterMillis = System.currentTimeMillis();

        // The ticker should never run ahead of real time.
        assertThat(approxNano)
            .isLessThanOrEqualTo(afterNano + granularityNanos);
        assertThat(approxMillis)
            .isLessThanOrEqualTo(afterMillis + granularityMillis);

        // The ticker should be monotonically non-decreasing.
        assertThat(approxNano).isGreaterThanOrEqualTo(prevApproxNano);
        assertThat(approxMillis).isGreaterThanOrEqualTo(prevApproxMillis);

        prevApproxNano = approxNano;
        prevApproxMillis = approxMillis;
      }

      // The ticker's background sampler fires at fixed-rate intervals of `granularityNanos`,
      // but the scheduler can delay a single fire significantly. On virtualized CI
      // environments the worst-case per-fire lag goes well beyond one granularity tick:
      // GitHub-hosted macOS arm (virtualized Apple Silicon) has shown per-fire ticker lag
      // up to ~75 ms for a 10 ms granularity across the tests in this package, and Windows
      // CI has a ~15.6 ms OS timer quantum that can stack with CPU contention. A 10x
      // multiplier (100 ms for a 10 ms granularity) covers both with some headroom while
      // still being a meaningful lower bound on cumulative ticker advancement over 20
      // iterations — a ticker that silently stopped advancing would still fail since
      // totalTickerNanos would stay at zero while totalRealNanos exceeds 100 ms.
      final var totalRealNanos = System.nanoTime() - startRealNano;
      final var totalTickerNanos = prevApproxNano - startApproxNano;
      assertThat(totalTickerNanos)
          .as("ticker should advance over 20 iterations")
          .isGreaterThanOrEqualTo(totalRealNanos - 10 * granularityNanos);
    } finally {
      scheduler.shutdownNow();
    }
  }

  /** Calling stop() on a ticker that was never started should be a safe no-op. */
  @Test
  public void stopWithoutStartIsNoOp() {
    var scheduler = Executors.newScheduledThreadPool(1);
    try {
      var ticker = new GranularTicker(TimeUnit.MILLISECONDS.toNanos(10),
          TimeUnit.MILLISECONDS.toNanos(50), scheduler);
      // stop() without start() — the scheduled futures are null, so nothing to cancel.
      ticker.stop();
      // No exception means success.
    } finally {
      scheduler.shutdownNow();
    }
  }

  /**
   * After starting and then stopping, the ticker's scheduled futures are cancelled and the
   * approximate time no longer advances.
   */
  @Test
  public void startThenStopFreezesTime() throws Exception {
    var scheduler = Executors.newScheduledThreadPool(1);
    try {
      var ticker = new GranularTicker(TimeUnit.MILLISECONDS.toNanos(5),
          TimeUnit.MILLISECONDS.toNanos(50), scheduler);
      ticker.start();
      // Let a few ticks elapse so the ticker is actively updating.
      Thread.sleep(50);
      assertThat(ticker.approximateNanoTime()).isGreaterThan(0);

      ticker.stop();
      // After stop, the approximate nano time should no longer advance.
      var frozenTime = ticker.approximateNanoTime();
      Thread.sleep(50);
      assertThat(ticker.approximateNanoTime()).isEqualTo(frozenTime);
    } finally {
      scheduler.shutdownNow();
    }
  }

  /** close() delegates to stop(), behaving identically. */
  @Test
  public void closeAfterStartCancelsFutures() throws Exception {
    var scheduler = Executors.newScheduledThreadPool(1);
    try {
      var ticker = new GranularTicker(TimeUnit.MILLISECONDS.toNanos(5),
          TimeUnit.MILLISECONDS.toNanos(50), scheduler);
      ticker.start();
      Thread.sleep(30);
      ticker.close();

      var frozenTime = ticker.approximateNanoTime();
      Thread.sleep(30);
      assertThat(ticker.approximateNanoTime()).isEqualTo(frozenTime);
    } finally {
      scheduler.shutdownNow();
    }
  }

  /**
   * Verifies that the scheduled tasks skip updates when the ticker is stopped.
   * Uses a very long granularity so the tasks never fire on their own, then
   * manually invokes the captured runnables before and after stop() to exercise the
   * {@code if (!stopped)} branches in both scheduled tasks.
   *
   * <p>The test observes the task effects by comparing the {@code snapshot} reference identity:
   * while running, each task allocates a new {@code Snapshot} instance and publishes it; after
   * stop, neither task publishes a new snapshot so the reference is preserved.
   */
  @Test
  public void scheduledTasksSkipUpdateAfterStop() throws Exception {
    List<Runnable> capturedTasks = new ArrayList<>();
    // Fake scheduler that only captures the runnables — no threads, no real scheduling.
    ScheduledExecutorService fakeScheduler = new DelegatingScheduledExecutorService(
        Executors.newSingleThreadScheduledExecutor()) {
      @Override
      public ScheduledFuture<?> scheduleAtFixedRate(
          Runnable command, long initialDelay, long period, TimeUnit unit) {
        capturedTasks.add(command);
        return NO_OP_FUTURE;
      }
    };
    try {
      var granularityNanos = TimeUnit.HOURS.toNanos(1);
      var ticker = new GranularTicker(
          granularityNanos, TimeUnit.HOURS.toNanos(1), fakeScheduler);
      ticker.start();
      assertThat(capturedTasks).hasSize(2);

      // Verify getGranularity() returns the configured value
      assertThat(ticker.getGranularity()).isEqualTo(granularityNanos);

      Field snapshotField = GranularTicker.class.getDeclaredField("snapshot");
      snapshotField.setAccessible(true);

      // Invoke the nanoTime task while ticker is running — nanoTime must advance
      // because real time has elapsed since start() set it, and a new snapshot must be
      // published.
      var nanoBefore = ticker.approximateNanoTime();
      var snapshotBefore = snapshotField.get(ticker);
      capturedTasks.get(0).run();
      var snapshotAfterTask0 = snapshotField.get(ticker);
      assertThat(ticker.approximateNanoTime())
          .as("nanoTime task must update nanoTime when ticker is running")
          .isGreaterThan(nanoBefore);
      assertThat(snapshotAfterTask0)
          .as("nanoTime task must publish a new snapshot when ticker is running")
          .isNotSameAs(snapshotBefore);

      // Invoke the nanoTimeDifference task while running — it must publish a new snapshot
      // with a freshly recalibrated offset so that approximateCurrentTimeMillis() is close
      // to the real wall-clock time.
      capturedTasks.get(1).run();
      var snapshotAfterTask1 = snapshotField.get(ticker);
      assertThat(snapshotAfterTask1)
          .as("nanoTimeDifference task must publish a new snapshot when ticker is running")
          .isNotSameAs(snapshotAfterTask0);
      var millisAfterRunning = ticker.approximateCurrentTimeMillis();
      assertThat(millisAfterRunning)
          .as("nanoTimeDifference task must restore wall-clock offset when running")
          .isCloseTo(System.currentTimeMillis(), within(100L));

      // Verify getTick() returns nanoTime / granularity
      var expectedTick = ticker.approximateNanoTime() / granularityNanos;
      assertThat(ticker.getTick()).isEqualTo(expectedTick);

      // Stop the ticker — sets stopped = true
      ticker.stop();

      // Capture values after stop — they should be frozen.
      var nanoAfterStop = ticker.approximateNanoTime();
      var millisAfterStop = ticker.approximateCurrentTimeMillis();
      var snapshotAfterStop = snapshotField.get(ticker);

      // Invoke both tasks after stop — they should see stopped == true and skip the update,
      // leaving the published snapshot (and therefore both approximations) unchanged.
      capturedTasks.get(0).run();
      capturedTasks.get(1).run();

      // The snapshot reference must be identical (no new Snapshot allocated).
      assertThat(snapshotField.get(ticker))
          .as("scheduled tasks must not publish a new snapshot after stop")
          .isSameAs(snapshotAfterStop);
      // nanoTime must not have changed (task 0 skipped the update).
      assertThat(ticker.approximateNanoTime()).isEqualTo(nanoAfterStop);
      // approximateCurrentTimeMillis must not have changed (task 1 skipped the update).
      assertThat(ticker.approximateCurrentTimeMillis()).isEqualTo(millisAfterStop);
    } finally {
      fakeScheduler.shutdownNow();
    }
  }

  /** A no-op {@link ScheduledFuture} that reports as not done and not cancelled. */
  private static final ScheduledFuture<?> NO_OP_FUTURE = new ScheduledFuture<>() {
    @Override
    public long getDelay(TimeUnit unit) {
      return Long.MAX_VALUE;
    }

    @Override
    public int compareTo(Delayed o) {
      return 0;
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      return false;
    }

    @Override
    public boolean isCancelled() {
      return false;
    }

    @Override
    public boolean isDone() {
      return false;
    }

    @Override
    public Object get() {
      return null;
    }

    @Override
    public Object get(long timeout, TimeUnit unit) {
      return null;
    }
  };

  /** Constructor should reject null executor. */
  @Test
  public void constructorRejectsNullExecutor() {
    assertThatThrownBy(
        () -> new GranularTicker(10_000_000, 10_000_000, null))
        .isInstanceOf(NullPointerException.class);
  }

  /**
   * Directly verifies the monotonic clamp on {@link GranularTicker#approximateCurrentTimeMillis()}.
   * Installs a snapshot whose computed value is high, reads it, then installs a snapshot whose
   * computed value is strictly lower — the reader must continue to observe the previous high.
   * This guards against a future refactor that removes or weakens the {@code lastApproxMillis}
   * clamp: without it, sub-millisecond drift between {@code System.nanoTime()} and
   * {@code System.currentTimeMillis()} at recalibration fires can yield a 1 ms backward jump.
   */
  @Test
  public void approximateCurrentTimeMillisClampsBackwardSnapshot() throws Exception {
    var scheduler = Executors.newScheduledThreadPool(1);
    try (var ticker = new GranularTicker(
        TimeUnit.HOURS.toNanos(1), TimeUnit.HOURS.toNanos(1), scheduler)) {
      ticker.start();
      installSnapshot(ticker, 5_000_000_000L, 5_000L);
      long high = ticker.approximateCurrentTimeMillis();
      assertThat(high).as("installed snapshot yields computed=10000").isEqualTo(10_000L);

      // Install a snapshot whose computed value is 9_999 ms — a 1 ms backward drift that the
      // clamp must absorb.
      installSnapshot(ticker, 5_000_000_000L, 4_999L);
      assertThat(ticker.approximateCurrentTimeMillis())
          .as("clamp must absorb sub-ms backward drift")
          .isEqualTo(high);
    } finally {
      scheduler.shutdownNow();
    }
  }

  /**
   * Directly verifies the monotonic clamp on {@link GranularTicker#approximateNanoTime()}.
   * The nanoTime-refresh task reads the current snapshot then publishes a new one; if the
   * wall-clock-recalibration task wins the intermediate publish with a later nanoTime sample,
   * the refresh task's publish carries an earlier sample and the raw {@code snapshot.nanoTime}
   * regresses. The clamp guarantees no reader observes the regression. Installs a high
   * nanoTime snapshot, reads it, installs a lower nanoTime snapshot, and verifies the reader
   * continues to observe the previous high.
   */
  @Test
  public void approximateNanoTimeClampsBackwardSnapshot() throws Exception {
    var scheduler = Executors.newScheduledThreadPool(1);
    try (var ticker = new GranularTicker(
        TimeUnit.HOURS.toNanos(1), TimeUnit.HOURS.toNanos(1), scheduler)) {
      ticker.start();
      installSnapshot(ticker, 1_000_000_000L, 0L);
      long high = ticker.approximateNanoTime();
      assertThat(high).isEqualTo(1_000_000_000L);

      installSnapshot(ticker, 500_000_000L, 0L);
      assertThat(ticker.approximateNanoTime())
          .as("clamp must hold the previously-returned high nanoTime value")
          .isEqualTo(high);
    } finally {
      scheduler.shutdownNow();
    }
  }

  /**
   * Reflectively publishes a new {@code Snapshot} instance into the ticker's private
   * {@code snapshot} field. Used to inject known {@code (nanoTime, nanoTimeDifference)}
   * values for clamp verification without depending on scheduler timing.
   */
  private static void installSnapshot(GranularTicker ticker, long nanoTime, long diff)
      throws Exception {
    Class<?> snapCls = Class.forName(
        "com.jetbrains.youtrackdb.internal.common.profiler.GranularTicker$Snapshot");
    var ctor = snapCls.getDeclaredConstructor(long.class, long.class);
    ctor.setAccessible(true);
    Object instance = ctor.newInstance(nanoTime, diff);
    Field snapshotField = GranularTicker.class.getDeclaredField("snapshot");
    snapshotField.setAccessible(true);
    snapshotField.set(ticker, instance);
  }
}
