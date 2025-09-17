package com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.gremlin.YTDBQueryConfigParam;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.step.sideeffect.YTDBGraphStep;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal.Admin;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategy.ProviderOptimizationStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.step.HasContainerHolder;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.GraphStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.EmptyStep;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.AbstractTraversalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.OptionsStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalHelper;

public final class YTDBGraphStepStrategy
    extends AbstractTraversalStrategy<ProviderOptimizationStrategy>
    implements ProviderOptimizationStrategy {

  private static final YTDBGraphStepStrategy INSTANCE = new YTDBGraphStepStrategy();

  private YTDBGraphStepStrategy() {
  }

  @Override
  public void apply(final Admin<?, ?> traversal) {
    var current = traversal.getStartStep();
    do {
      current = replaceStrategy(traversal, current).getNextStep();
    } while (current != null && !(current instanceof EmptyStep));
  }

  private static Step<?, ?> replaceStrategy(Admin<?, ?> traversal, Step<?, ?> step) {
    if (!(step instanceof GraphStep<?, ?> originalGraphStep) || step instanceof YTDBGraphStep) {
      return step;
    }

    final var graph = traversal.getGraph().orElse(null);
    if (graph == null) {
      return step;
    }
    final var tx = (YTDBTransaction) graph.tx();
    tx.readWrite();

    final var polymorphic = traversal.getStrategies().getStrategy(OptionsStrategy.class)
        .map(s -> s.getOptions().get(YTDBQueryConfigParam.polymorphicQuery.name()))
        .map(s -> ((boolean) s))
        .orElseGet(() ->
            tx.getDatabaseSession()
                .getConfiguration()
                .getValueAsBoolean(GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT)
        );

    @SuppressWarnings({"rawtypes", "unchecked"})
    final var ytdbGraphStep = new YTDBGraphStep(originalGraphStep);
    ytdbGraphStep.setPolymorphic(polymorphic);
    //noinspection unchecked
    TraversalHelper.replaceStep(step, ytdbGraphStep, traversal);

    Step<?, ?> currentStep = ytdbGraphStep.getNextStep();
    while (currentStep instanceof HasContainerHolder hch) {
      hch.getHasContainers().forEach(ytdbGraphStep::addHasContainer);
      currentStep.getLabels().forEach(ytdbGraphStep::addLabel);
      traversal.removeStep(currentStep);
      currentStep = currentStep.getNextStep();
    }
    return ytdbGraphStep;
  }

  public static YTDBGraphStepStrategy instance() {
    return INSTANCE;
  }
}
