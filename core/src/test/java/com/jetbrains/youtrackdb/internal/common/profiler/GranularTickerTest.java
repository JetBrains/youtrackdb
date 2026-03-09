package com.jetbrains.youtrackdb.internal.common.profiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Delayed;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

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

      // The ticker can lag behind real time by at most one granularity tick
      // at any point, so its total advancement should be close to real elapsed time.
      final var totalRealNanos = System.nanoTime() - startRealNano;
      final var totalTickerNanos = prevApproxNano - startApproxNano;
      assertThat(totalTickerNanos)
          .as("ticker should advance over 20 iterations")
          .isGreaterThanOrEqualTo(totalRealNanos - 2 * granularityNanos);
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
  public void scheduledTasksSkipUpdateAfterStop() {
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

      // Invoke the nanoTime task while ticker is running — nanoTime should update
      var nanoBefore = ticker.approximateNanoTime();
      capturedTasks.get(0).run();
      var nanoAfterRunning = ticker.approximateNanoTime();
      assertThat(nanoAfterRunning).isGreaterThanOrEqualTo(nanoBefore);

      // Invoke the nanoTimeDifference task while running — approximateCurrentTimeMillis
      // should reflect the updated difference.
      var millisBefore = ticker.approximateCurrentTimeMillis();
      capturedTasks.get(1).run();
      var millisAfterRunning = ticker.approximateCurrentTimeMillis();
      // The millis value is derived from nanoTime/1_000_000 + nanoTimeDifference.
      // After refreshing the difference, it should still be close to real wall-clock time.
      assertThat(millisAfterRunning).isGreaterThan(0);

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
