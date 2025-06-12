package com.jetbrains.youtrack.db.internal.core.gremlin.traversal.strategy.optimization;

import com.jetbrains.youtrack.db.internal.core.gremlin.traversal.step.sideeffect.YTDBGraphStep;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.step.HasContainerHolder;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.GraphStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.EmptyStep;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.AbstractTraversalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalHelper;

public final class YTDBGraphStepStrategy
        extends AbstractTraversalStrategy<TraversalStrategy.ProviderOptimizationStrategy>
        implements TraversalStrategy.ProviderOptimizationStrategy {
    private static final YTDBGraphStepStrategy INSTANCE = new YTDBGraphStepStrategy();

    private YTDBGraphStepStrategy() {
    }

    @Override
    public void apply(final Traversal.Admin<?, ?> traversal) {
        var current = traversal.getStartStep();
        do {
            current = replaceStrategy(traversal, current).getNextStep();
        } while (current != null && !(current instanceof EmptyStep));
    }

    private static Step<?, ?> replaceStrategy(Traversal.Admin<?, ?> traversal, Step<?, ?> step) {
        if (step instanceof GraphStep<?, ?> originalGraphStep && !(step instanceof YTDBGraphStep)) {
            @SuppressWarnings({"rawtypes",
                "unchecked"}) final var ytdbGraphStep = new YTDBGraphStep(originalGraphStep);
            //noinspection unchecked
            TraversalHelper.replaceStep(step, ytdbGraphStep, traversal);

            Step<?, ?> currentStep = ytdbGraphStep.getNextStep();
            while (currentStep instanceof HasContainerHolder) {
                ((HasContainerHolder) currentStep)
                        .getHasContainers()
                        .forEach(ytdbGraphStep::addHasContainer);
                currentStep.getLabels().forEach(ytdbGraphStep::addLabel);
                traversal.removeStep(currentStep);
                currentStep = currentStep.getNextStep();
            }
            return ytdbGraphStep;
        } else {
            return step;
        }
    }

    public static YTDBGraphStepStrategy instance() {
        return INSTANCE;
    }
}
