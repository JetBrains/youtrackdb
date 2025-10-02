package com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization;

import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.step.filter.YTDBHasLabelStep;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.step.sideeffect.YTDBGraphStep;
import java.util.ArrayList;
import java.util.List;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal.Admin;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategy.ProviderOptimizationStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.step.TraversalParent;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.HasStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.GraphStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.EmptyStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.AbstractTraversalStrategy;
import org.apache.tinkerpop.gremlin.structure.T;

public final class YTDBGraphStepStrategy
    extends AbstractTraversalStrategy<ProviderOptimizationStrategy>
    implements ProviderOptimizationStrategy {

  private static final YTDBGraphStepStrategy INSTANCE = new YTDBGraphStepStrategy();

  private YTDBGraphStepStrategy() {
  }

  @Override
  public void apply(final Admin<?, ?> traversal) {
    final var startStep = traversal.getStartStep();
    if (!(startStep instanceof GraphStep<?, ?>) || startStep instanceof YTDBGraphStep) {
      return;
    }

    final var polymorphic = YTDBStrategyUtil.isPolymorphic(traversal);
    if (polymorphic == null) {
      // means we couldn't access the graph from the traversal
      return;
    }

    rebuildTraversal(traversal, polymorphic);
  }

  /// Recursive function that rebuilds traversal, replacing TinkerPop steps with YouTrackDB ones.
  /// Here is what gets replaced:
  /// 1. All "has" steps that strictly follow a GraphStep. They are gonna be handled natively by
  /// YouTrackDB (via SQL FROM and WHERE clauses at the moment).
  /// 2. All "hasLabel" steps at any place in the traversal. This is needed to correctly handle
  /// polymorphic queries.
  private static void rebuildTraversal(Admin<?, ?> traversal, boolean polymorphic) {

    var current = traversal.getStartStep();
    var idx = 0;
    var isTraversalStart = false;

    // once we encounter a GraphStep, we replace it with YTDBGraphStep and
    // attach all the subsequent "has" steps to it.
    YTDBGraphStep<?, ?> currentGraphStep = null;

    while (current != null && !(current instanceof EmptyStep)) {
      if (current instanceof GraphStep<?, ?> graphStep) {

        // replacing GraphStep with YTDBGraphStep
        isTraversalStart = true;
        currentGraphStep = new YTDBGraphStep<>(graphStep);
        currentGraphStep.setPolymorphic(polymorphic);
        traversal.removeStep(idx);
        traversal.addStep(idx, currentGraphStep);
      } else if (current instanceof HasStep<?> hch) {

        // HasContainers that will be handled natively by YouTrackDB.
        // Those are 1) all "has" steps that strictly follow a GraphStep and 2) all "hasLabel" steps.
        final List<HasContainer> specialContainers = new ArrayList<>();

        // if we replace all HasContainers, then we want to remove the original "has" step.
        var removeOriginalStep = true;
        for (var hc : hch.getHasContainers()) {
          if (isTraversalStart || T.label.getAccessor().equals(hc.getKey())) {
            specialContainers.add(hc);
          } else {
            removeOriginalStep = false;
          }
        }

        if (isTraversalStart) {
          // This is the situation when the "has" step follows directly a GraphStep.
          // In this case, all HasContainers will be added to the new YTDBGraphStep to
          // be translated to YouTrackDB SQL.
          hch.getHasContainers().forEach(currentGraphStep::addHasContainer);
          current.getLabels().forEach(currentGraphStep::addLabel);
        } else if (!specialContainers.isEmpty()) {
          // "hasLabel" steps that don't directly follow a GraphStep are replaced by
          // YTDBHasLabelStep, that handles the "polymorphic" flag correctly.
          final var predicates = specialContainers.stream()
              .<P<? super String>>map(hc -> ((P<? super String>) hc.getPredicate()))
              .toList();
          final var ytdbHasLabelStep = new YTDBHasLabelStep<>(traversal, predicates, polymorphic);
          traversal.addStep(idx, ytdbHasLabelStep);
          idx++;
          specialContainers.forEach(hch::removeHasContainer);
        }

        if (removeOriginalStep) {
          traversal.removeStep(idx);
          idx--;
        }
      } else {
        isTraversalStart = false;
      }

      // applying the transformation recursively to all children traversals.
      if (current instanceof TraversalParent tp) {
        tp.getLocalChildren().forEach(t -> rebuildTraversal(t, polymorphic));
      }

      idx = idx + 1;
      current = current.getNextStep();
    }
  }

  public static YTDBGraphStepStrategy instance() {
    return INSTANCE;
  }
}
