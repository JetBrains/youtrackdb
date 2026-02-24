package com.jetbrains.youtrackdb.internal.common.profiler;


import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class GranularTickerTest {

  @Test
  public void testTimeApproximation() throws Exception {
    final var granularityNanos = TimeUnit.MILLISECONDS.toNanos(10);
    final var timeAdjustmentNanos = TimeUnit.MILLISECONDS.toNanos(50);
    try (var ticker = new GranularTicker(granularityNanos, timeAdjustmentNanos)) {

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
    }
  }
}