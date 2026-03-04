package com.jetbrains.youtrackdb.internal.common.profiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.Executors;
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

  /** Constructor should reject null executor. */
  @Test
  public void constructorRejectsNullExecutor() {
    assertThatThrownBy(
        () -> new GranularTicker(10_000_000, 10_000_000, null))
        .isInstanceOf(NullPointerException.class);
  }
}
