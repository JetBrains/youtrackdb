package com.jetbrains.youtrackdb.internal.common.profiler.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.common.profiler.Ticker;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.junit.Test;

public class YTDBQueryMetricsStepTickerTest {

  /** A lightweight query reports the injected ticker millis and exact ticker nano delta. */
  @Test
  public void lightweightQueryReportsOnlyInjectedTickerTimes() throws Exception {
    var tx = mock(YTDBTransaction.class);
    var ticker = mock(Ticker.class);
    var result = new long[2];
    var calls = new int[1];
    when(tx.getQueryMonitoringMode()).thenReturn(QueryMonitoringMode.LIGHTWEIGHT);
    when(tx.getQueryMetricsListener()).thenReturn((details, started, duration) -> {
      calls[0]++;
      result[0] = started;
      result[1] = duration;
    });
    when(ticker.approximateCurrentTimeMillis()).thenReturn(42L);
    when(ticker.approximateNanoTime()).thenReturn(1_000L, 5_000L);

    var traversal = __.inject(7).asAdmin();
    var step = new YTDBQueryMetricsStep<Integer>(traversal, tx, null, ticker);
    traversal.addStep(step);
    assertThat(traversal.hasNext()).isTrue();
    step.close();

    assertThat(calls[0]).isEqualTo(1);
    assertThat(result[0]).as("start must come from the injected ticker").isEqualTo(42L);
    assertThat(result[1]).as("duration must use the injected nano delta").isEqualTo(4_000L);
  }
}
