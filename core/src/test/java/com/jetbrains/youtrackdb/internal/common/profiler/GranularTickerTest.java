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

// The testTimeApproximation() method asserts monotonicity of approximateCurrentTimeMillis(),
// which reads two volatile fields (nanoTime, nanoTimeDifference) non-atomically. Under parallel
// test execution, CPU contention can cause a 1ms backward jump. Sequential execution avoids this.
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
   * manually invokes the captured runnables after stop() to exercise the
   * {@code if (!stopped)} branches (lines 42 and 49 in GranularTicker).
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

      // Invoke the nanoTime task while ticker is running — nanoTime must advance
      // because real time has elapsed since start() set it.
      var nanoBefore = ticker.approximateNanoTime();
      capturedTasks.get(0).run();
      var nanoAfterRunning = ticker.approximateNanoTime();
      assertThat(nanoAfterRunning)
          .as("nanoTime task must update nanoTime when ticker is running")
          .isGreaterThan(nanoBefore);

      // Corrupt nanoTimeDifference via reflection so we can verify the task restores it.
      // Setting it to 0 makes approximateCurrentTimeMillis() = nanoTime / 1_000_000,
      // which is NOT a valid wall-clock value.
      Field diffField = GranularTicker.class.getDeclaredField("nanoTimeDifference");
      diffField.setAccessible(true);
      diffField.setLong(ticker, 0L);
      // Invoke the nanoTimeDifference task while running — it must recalculate the offset
      // so that approximateCurrentTimeMillis() is close to the real wall-clock time.
      capturedTasks.get(1).run();
      var millisAfterRunning = ticker.approximateCurrentTimeMillis();
      assertThat(millisAfterRunning)
          .as("nanoTimeDifference task must restore wall-clock offset when running")
          .isCloseTo(System.currentTimeMillis(), within(5000L));

      // Verify getTick() returns nanoTime / granularity
      var expectedTick = ticker.approximateNanoTime() / granularityNanos;
      assertThat(ticker.getTick()).isEqualTo(expectedTick);

      // Stop the ticker — sets stopped = true
      ticker.stop();

      // Capture values after stop — they should be frozen
      var nanoAfterStop = ticker.approximateNanoTime();
      var millisAfterStop = ticker.approximateCurrentTimeMillis();

      // Invoke both tasks after stop — they should see stopped == true
      // and skip the update, leaving nanoTime and nanoTimeDifference unchanged.
      capturedTasks.get(0).run();
      capturedTasks.get(1).run();

      // nanoTime must not have changed (task 0 skipped the update)
      assertThat(ticker.approximateNanoTime()).isEqualTo(nanoAfterStop);
      // approximateCurrentTimeMillis must not have changed (task 1 skipped the update)
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
}
