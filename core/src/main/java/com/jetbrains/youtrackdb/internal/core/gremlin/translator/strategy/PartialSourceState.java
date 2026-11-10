package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.TraversalParent;
import org.apache.tinkerpop.gremlin.process.traversal.step.branch.RepeatStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.RequirementsStep;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.RequirementsStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.SackStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.SideEffectStrategy;

/** Partial-only refusals. Inspection never retrieves a value or evaluates user code. */
final class PartialSourceState {

  private PartialSourceState() {
  }

  static boolean configured(Traversal.Admin<?, ?> traversal) {
    var strategies = traversal.getStrategies();
    var effects = traversal.getSideEffects();
    if (strategies.getStrategy(SackStrategy.class).isPresent()
        || strategies.getStrategy(SideEffectStrategy.class).isPresent()
        || strategies.getStrategy(RequirementsStrategy.class).isPresent()
        || !effects.keys().isEmpty() || effects.getSackInitialValue() != null
        || effects.getSackSplitter() != null || effects.getSackMerger() != null) {
      return true;
    }
    for (var instruction : traversal.getBytecode().getSourceInstructions()) {
      if (java.util.Set.of("withSideEffect", "withSack", "withBulk", "withPath")
          .contains(instruction.getOperator())) {
        return true;
      }
    }
    return traversal.getSteps().stream().anyMatch(RequirementsStep.class::isInstance);
  }

  static boolean repeat(Traversal.Admin<?, ?> traversal) {
    if (RepeatDeclineStrategy.isVetoed(traversal)) {
      return true;
    }
    for (var step : traversal.getSteps()) {
      if (step instanceof RepeatStep<?>) {
        return true;
      }
      if (step instanceof TraversalParent parent
          && (parent.getLocalChildren().stream().anyMatch(PartialSourceState::repeat)
              || parent.getGlobalChildren().stream().anyMatch(PartialSourceState::repeat))) {
        return true;
      }
    }
    return false;
  }
}
