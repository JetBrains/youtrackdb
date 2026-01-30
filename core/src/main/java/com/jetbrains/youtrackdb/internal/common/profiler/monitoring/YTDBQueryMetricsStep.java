package com.jetbrains.youtrackdb.internal.common.profiler.monitoring;

import com.jetbrains.youtrackdb.internal.common.profiler.Ticker;
import com.jetbrains.youtrackdb.internal.common.profiler.monitoring.QueryMetricsListener.QueryDetails;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import java.util.NoSuchElementException;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.Traverser.Admin;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.AbstractStep;

public class YTDBQueryMetricsStep<S> extends AbstractStep<S, S> implements AutoCloseable {

  private final YTDBTransaction ytdbTx;
  private final Ticker ticker;
  private final boolean isLightweight;
  private boolean hasStarted = false;
  private long startMillis;
  private long nano;

  public YTDBQueryMetricsStep(
      Traversal.Admin<?, ?> traversal,
      YTDBTransaction ytdbTx,
      Ticker ticker
  ) {
    super(traversal);
    this.ytdbTx = ytdbTx;
    this.isLightweight = ytdbTx.getQueryMonitoringMode() == QueryMonitoringMode.LIGHTWEIGHT;
    this.ticker = ticker;
  }

  @Override
  protected Admin<S> processNextStart() throws NoSuchElementException {
    queryHasStarted();
    return starts.next();
  }

  @Override
  public boolean hasNext() {
    if (isLightweight) {
      return super.hasNext();
    }
    queryHasStarted();
    final var now = System.nanoTime();
    try {
      return super.hasNext();
    } finally {
      nano += System.nanoTime() - now;
    }
  }

  @Override
  public Admin<S> next() {
    if (isLightweight) {
      return super.next();
    }
    queryHasStarted();
    final var now = System.nanoTime();
    try {
      return super.next();
    } finally {
      nano += System.nanoTime() - now;
    }
  }

  @Override
  public void close() throws Exception {

    final var duration = isLightweight ? ticker.approximateNanoTime() - nano : nano;
    ytdbTx.getQueryMetricsListener().queryFinished(
        new QueryDetails() {
          @Override
          public String getQuery() {
            return traversal.getBytecode().toString();
          }

          @Override
          public String getQuerySummary() {
            return null; // TODO
          }

          @Override
          public String getTransactionTrackingId() {
            return ytdbTx.getTrackingId();
          }
        },
        startMillis,
        duration
    );
  }

  private void queryHasStarted() {
    if (hasStarted) {
      return;
    }
    hasStarted = true;

    if (isLightweight) {
      this.startMillis = ticker.approximateCurrentTimeMillis();
      this.nano = ticker.approximateNanoTime();
    } else {
      this.startMillis = System.currentTimeMillis();
    }
  }
}
