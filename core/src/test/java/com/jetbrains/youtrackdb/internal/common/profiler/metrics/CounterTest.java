package com.jetbrains.youtrackdb.internal.common.profiler.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class CounterTest {

  /** Every completed addition contributes to the cumulative total under concurrent updates. */
  @Test
  public void concurrentUpdatesAccumulateWithoutReset() throws Exception {
    var counter = MetricType.counter().create(new StubTicker(1_000_000));
    var executor = Executors.newFixedThreadPool(4);
    try {
      for (int i = 0; i < 4; i++) {
        executor.submit(() -> {
          for (int j = 0; j < 1000; j++) {
            counter.add(2);
          }
        });
      }
    } finally {
      executor.shutdown();
    }
    assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    assertThat(counter.getValue()).isEqualTo(8000);
    assertThat(counter.getValue()).isEqualTo(8000);
    assertThat(MetricType.counter().valueType()).isEqualTo(Long.class);
    var noop = MetricType.counter().noop();
    noop.add(10);
    assertThat(noop.getValue()).isZero();
  }
}
