package com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.gremlin.tokens.YTDBQueryConfigParam;
import com.jetbrains.youtrackdb.api.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.step.sideeffect.YTDBGraphStep;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal.Admin;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategy.ProviderOptimizationStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.step.HasContainerHolder;
import org.apache.tinkerpop.gremlin.process.traversal.step.Parameterizing;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.GraphStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.EmptyStep;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.AbstractTraversalStrategy;
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
    final var schema = tx.getDatabaseSession().getMetadata().getImmutableSchemaSnapshot();

    final var polymorphicByDefault = tx.getDatabaseSession().getConfiguration()
        .getValueAsBoolean(GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT);

    @SuppressWarnings({"rawtypes", "unchecked"}) final var ytdbGraphStep = new YTDBGraphStep(
        originalGraphStep);
    //noinspection unchecked
    TraversalHelper.replaceStep(step, ytdbGraphStep, traversal);
    Boolean queryPolymorphic = null;

    Step<?, ?> currentStep = ytdbGraphStep.getNextStep();
    while (currentStep instanceof HasContainerHolder hch) {

      if (currentStep instanceof Parameterizing parameterizing &&
          parameterizing.getParameters().contains(YTDBQueryConfigParam.polymorphicQuery.name())) {
        final var paramValue = parameterizing.getParameters()
            .get(YTDBQueryConfigParam.polymorphicQuery.name(), () -> polymorphicByDefault)
            .getFirst();

        if (queryPolymorphic == null) {
          queryPolymorphic = paramValue;
        } else if (queryPolymorphic != paramValue) {
          throw new IllegalStateException(
              "Can't combine polymorphic and non-polymorphic query steps");
        }
      } else {
        queryPolymorphic = polymorphicByDefault;
      }

      hch.getHasContainers().forEach(ytdbGraphStep::addHasContainer);

      final Set<String> labels;
      if (queryPolymorphic) {
        labels = currentStep.getLabels().stream()
            .flatMap(lbl ->
                Stream.concat(
                    Stream.of(lbl),
                    schema.getClass(lbl).getAllSuperClasses().stream().map(SchemaClass::getName)
                )
            )
            .collect(Collectors.toSet());
      } else {
        labels = currentStep.getLabels();
      }

      labels.forEach(ytdbGraphStep::addLabel);
      traversal.removeStep(currentStep);
      currentStep = currentStep.getNextStep();
    }
    ytdbGraphStep.setPolymorphic(
        queryPolymorphic == null ? polymorphicByDefault : queryPolymorphic
    );
    return ytdbGraphStep;
  }

  public static YTDBGraphStepStrategy instance() {
    return INSTANCE;
  }
}
