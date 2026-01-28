package com.jetbrains.youtrackdb.internal.common.profiler.monitoring;

import com.jetbrains.youtrackdb.internal.common.profiler.Ticker;
import com.jetbrains.youtrackdb.internal.common.profiler.monitoring.QueryMetricsListener.QueryDetails;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraph;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import java.util.NoSuchElementException;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.Traverser.Admin;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.AbstractStep;

public class YTDBQueryMetricsStep<S> extends AbstractStep<S, S> implements AutoCloseable {

  private final Ticker ticker;
  private boolean hasStarted = false;
  private long startMillis;
  private long startNano;

  public YTDBQueryMetricsStep(Traversal.Admin<?, ?> traversal, Ticker ticker) {
    super(traversal);
    this.ticker = ticker;
  }

  @Override
  protected Admin<S> processNextStart() throws NoSuchElementException {
    if (!hasStarted) {
      hasStarted = true;
      queryHasStarted();
    }
    return starts.next();
  }

  @Override
  public void close() throws Exception {

    final var graph = getTraversal().getGraph();
    if (graph.isEmpty() || !(graph.get() instanceof YTDBGraph ytdbGraph)) {
      return;
    }

    final var currentTx = (YTDBTransaction) ytdbGraph.tx();

    final var duration = ticker.currentNanoTime() - startNano;
    currentTx.getQueryMetricsListener().queryFinished(
        new QueryDetails() {
          @Override
          public String getQuery() {
            return traversal.getBytecode().toString();
          }

          @Override
          public String getQuerySummary() {
            return "";
          }

          @Override
          public String getTransactionTrackingId() {
            return "";
          }
        },
        startMillis,
        duration
    );
  }

  private void queryHasStarted() {
    this.startNano = ticker.lastNanoTime();
    this.startMillis = System.currentTimeMillis(); // we must use approximate value from ticker here
  }
}
