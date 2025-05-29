package com.jetbrain.youtrack.db.gremlin.internal.traversal.strategy.optimization;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import com.jetbrain.youtrack.db.gremlin.internal.traversal.step.sideeffect.YTDBGraphStep;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.step.HasContainerHolder;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.MatchStep;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.AbstractTraversalStrategy;

public final class YTDBGraphMatchStepStrategy
        extends AbstractTraversalStrategy<TraversalStrategy.ProviderOptimizationStrategy>
        implements TraversalStrategy.ProviderOptimizationStrategy {

    private static final YTDBGraphMatchStepStrategy INSTANCE = new YTDBGraphMatchStepStrategy();

    private YTDBGraphMatchStepStrategy() {
    }

    @Override
    public void apply(final Traversal.Admin<?, ?> traversal) {
        if (traversal.getSteps().size() >= 2) {
            final Step<?, ?> startStep = traversal.getStartStep();
            Step<?, ?> nextStep = startStep.getNextStep();
            if (startStep instanceof YTDBGraphStep<?, ?> ytdbGraphStep && nextStep instanceof MatchStep<?, ?> matchStep) {
                if (ytdbGraphStep.getHasContainers().isEmpty()) {
                    List<Traversal.Admin<Object, Object>> globalChildren = matchStep.getGlobalChildren();
                    Traversal.Admin<Object, Object> match = globalChildren.getFirst();
                    Step<?, ?> currentStep = match.getStartStep().getNextStep();

                    while (currentStep instanceof HasContainerHolder) {
                        ((HasContainerHolder) currentStep)
                                .getHasContainers()
                                .forEach(ytdbGraphStep::addHasContainer);
                        currentStep.getLabels().forEach(ytdbGraphStep::addLabel);
                        match.removeStep(currentStep);
                        currentStep = currentStep.getNextStep();
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
