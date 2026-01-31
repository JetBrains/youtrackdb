package com.jetbrains.youtrackdb.internal.common.profiler.monitoring;

import com.jetbrains.youtrackdb.api.gremlin.tokens.YTDBQueryConfigParam;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraph;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal.Admin;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategy.FinalizationStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.AbstractTraversalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalHelper;
import org.apache.tinkerpop.gremlin.structure.Graph;

public class YTDBQueryMetricsStrategy
    extends AbstractTraversalStrategy<FinalizationStrategy>
    implements FinalizationStrategy {

  private static final YTDBQueryMetricsStrategy INSTANCE = new YTDBQueryMetricsStrategy();

  private YTDBQueryMetricsStrategy() {
  }

  @Override
  public void apply(Admin<?, ?> traversal) {

    if (!traversal.isRoot() || traversal.getEndStep() instanceof YTDBQueryMetricsStep) {
      return;
    }

    if (traversal.getGraph().isEmpty() ||
        !(traversal.getGraph().get() instanceof YTDBGraph ytdbGraph)) {
      // this is not an embedded mode
      return;
    }

    final var ytdbTx = ((YTDBTransaction) ytdbGraph.tx());
    if (!ytdbTx.isMonitoringEnabled()) {
      return;
    }

    final var ticker = YouTrackDBEnginesManager.instance().getTicker();
    final String querySummary = YTDBQueryConfigParam.querySummary.getValue(traversal);
    final var metricsStep = new YTDBQueryMetricsStep<>(traversal, ytdbTx, querySummary, ticker);
    traversal.addStep(metricsStep);
  }

  public static YTDBQueryMetricsStrategy instance() {
    return INSTANCE;
  }
}
