package com.jetbrains.youtrackdb.internal.common.profiler.monitoring;

import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal.Admin;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategy.FinalizationStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.AbstractTraversalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalHelper;
import org.apache.tinkerpop.gremlin.structure.Graph;

public class YTDBQueryMetricsStrategy
    extends AbstractTraversalStrategy<FinalizationStrategy>
    implements FinalizationStrategy {

  private static final YTDBQueryMetricsStrategy INSTANCE = new YTDBQueryMetricsStrategy();
  private static final String MARKER = Graph.Hidden.hide("ytdb.query.metrics");

  private YTDBQueryMetricsStrategy() {
  }

  @Override
  public void apply(Admin<?, ?> traversal) {
    if (traversal.isRoot() && !traversal.getEndStep().getLabels().contains(MARKER)) {
//      TraversalHelper.applyTraversalRecursively(t -> t.getEndStep().addLabel(MARKER), traversal);
    }

    if (traversal.isRoot() && !(traversal.getEndStep() instanceof YTDBQueryMetricsStep)) {

      final var ticker = YouTrackDBEnginesManager.instance().getTicker();
      final var metricsStep = new YTDBQueryMetricsStep<>(traversal, ticker);
      traversal.addStep(metricsStep);
    }
  }

  public static YTDBQueryMetricsStrategy instance() {
    return INSTANCE;
  }
}
