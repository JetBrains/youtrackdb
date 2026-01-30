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
      for (var i = 0; i < 20; i++) {
        final var nanoDiff = System.nanoTime() - ticker.approximateNanoTime();
        final var millisDiff =
            System.currentTimeMillis() - ticker.approximateCurrentTimeMillis();

        final var precision = 1.2; // we allow for some error due to a possible multi-threading lag
        assertThat(nanoDiff).isLessThanOrEqualTo((long) (granularityNanos * precision));
        assertThat(millisDiff).isLessThanOrEqualTo(
            (long) (granularityNanos * precision / 1_000_000L));

        Thread.sleep(13);
      }
    }
  }
}