package com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization;

import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.step.filter.YTDBHasLabelStep;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.step.sideeffect.YTDBGraphStep;
import java.util.Collections;
import java.util.Set;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.HasStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.MatchStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.AbstractTraversalStrategy;
import org.apache.tinkerpop.gremlin.structure.T;

public final class YTDBGraphMatchStepStrategy
    extends AbstractTraversalStrategy<TraversalStrategy.ProviderOptimizationStrategy>
    implements TraversalStrategy.ProviderOptimizationStrategy {

  private static final YTDBGraphMatchStepStrategy INSTANCE = new YTDBGraphMatchStepStrategy();

  private YTDBGraphMatchStepStrategy() {
  }

  @Override
  public void apply(final Traversal.Admin<?, ?> traversal) {
    if (traversal.getSteps().size() >= 2) {
      final var startStep = traversal.getStartStep();
      var nextStep = startStep.getNextStep();
      if (startStep instanceof YTDBGraphStep<?, ?> ytdbGraphStep
          && nextStep instanceof MatchStep<?, ?> matchStep) {
        var hasContainers = ytdbGraphStep.getHasContainers();
        var onlyLabels = true;
        for (var hasContainer : hasContainers) {
          if (!hasContainer.getKey().equals(T.label.getAccessor())) {
            onlyLabels = false;
            break;
          }
        }
        if (onlyLabels) {
          var globalChildren = matchStep.getGlobalChildren();
          var match = globalChildren.getFirst();
          var currentStep = match.getStartStep().getNextStep();

          var doIterate = true;
          while (doIterate) {

            final boolean replaced;
            if (currentStep instanceof HasStep<?> hasStep) {
              for (var hasContainer : hasStep.getHasContainers()) {
                ytdbGraphStep.addHasContainer(hasContainer);
              }
              replaced = true;
            } else if (currentStep instanceof YTDBHasLabelStep<?> hls) {
              for (var predicate : hls.getPredicates()) {
                ytdbGraphStep.addHasContainer(new HasContainer(T.label.getAccessor(), predicate));
              }
              replaced = true;
            } else {
              replaced = false;
            }

            if (replaced) {
              currentStep.getLabels().forEach(ytdbGraphStep::addLabel);
              match.removeStep(currentStep);
              currentStep = currentStep.getNextStep();
            } else {
              doIterate = false;
            }
          }
        }
      }
    }
  }

  @Override
  public Set<Class<? extends ProviderOptimizationStrategy>> applyPrior() {
    return Collections.singleton(YTDBGraphStepStrategy.class);
  }

  public static YTDBGraphMatchStepStrategy instance() {
    return INSTANCE;
  }
}
