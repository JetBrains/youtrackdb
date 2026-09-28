package com.jetbrains.youtrackdb.internal.core.tx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.common.profiler.Ticker;
import com.jetbrains.youtrackdb.internal.common.profiler.monitoring.QueryMonitoringMode;
import com.jetbrains.youtrackdb.internal.common.profiler.monitoring.TransactionMetricsListener;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import org.junit.After;
import org.junit.Test;

public class FrontendTransactionImplTickerTest extends DbTestBase {

  @After
  public void rollbackIfLeftOpen() {
    if (session != null && !session.isClosed() && session.getTransactionInternal().isActive()) {
      session.rollback();
    }
  }

  /** A monitored write reports the private transaction ticker values, never wall time. */
  @Test
  public void lightweightCommitReportsOnlyInjectedTickerTimes() {
    var ticker = mock(Ticker.class);
    when(ticker.approximateCurrentTimeMillis()).thenReturn(42L);
    when(ticker.approximateNanoTime()).thenReturn(1_000L, 5_000L);
    var values = new long[2];
    var calls = new int[1];
    var listener = new TransactionMetricsListener() {
      @Override
      public void writeTransactionCommitted(
          TransactionDetails details, long commitAtMillis, long commitTimeNanos) {
        calls[0]++;
        values[0] = commitAtMillis;
        values[1] = commitTimeNanos;
      }
    };

    session.begin(new ControlledTickerTransaction(session, ticker));
    var vertex = session.newVertex("V");
    vertex.setProperty("name", "controlled");
    session.monitoredCommit(listener, QueryMonitoringMode.LIGHTWEIGHT, "controlled-tx");

    assertThat(calls[0]).isEqualTo(1);
    assertThat(values[0]).as("commit start must come from the injected ticker").isEqualTo(42L);
    assertThat(values[1]).as("commit duration must use the injected nano delta")
        .isEqualTo(4_000L);
  }

  private static class ControlledTickerTransaction extends FrontendTransactionImpl {

    private final Ticker controlledTicker;

    ControlledTickerTransaction(DatabaseSessionEmbedded session, Ticker ticker) {
      super(session);
      this.controlledTicker = ticker;
    }

    @Override
    Ticker ticker() {
      return controlledTicker;
    }
  }
}
