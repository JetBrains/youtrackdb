package com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization;

import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.step.filter.YTDBHasLabelStep;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.step.sideeffect.YTDBGraphStep;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal.Admin;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategy.ProviderOptimizationStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.step.TraversalParent;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.HasStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.GraphStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.EmptyStep;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.AbstractTraversalStrategy;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.util.reference.ReferenceEdge;
import org.apache.tinkerpop.gremlin.structure.util.reference.ReferenceVertex;

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
    rebuildSideEffects(traversal);
  }

  /// This method fixes all String IDs in [ReferenceVertex]es and [ReferenceEdge]s by replacing them
  /// with [RID]s.
  private static void rebuildSideEffects(Admin<?, ?> traversal) {
    final var sideEffects = traversal.getSideEffects();
    if (sideEffects.isEmpty()) {
      return;
    }

    final var replacedVertices = new HashMap<String, ReferenceVertex>();
    sideEffects.forEach((name, sideEffect) -> {
      if (sideEffect instanceof String stringIdCandidate && !stringIdCandidate.isEmpty()
          && stringIdCandidate.charAt(0) == '#') {
        traversal.getSideEffects().add(name, RID.of(stringIdCandidate));
      } else if (sideEffect instanceof ReferenceVertex v && v.id() instanceof String) {
        traversal.getSideEffects().add(name, replaceRefVertex(v, replacedVertices));
      } else if (sideEffect instanceof ReferenceEdge e && e.id() instanceof String id) {
        traversal.getSideEffects().add(name, new ReferenceEdge(
            RID.of(id), e.label(),
            replaceRefVertex((ReferenceVertex) e.inVertex(), replacedVertices),
            replaceRefVertex((ReferenceVertex) e.outVertex(), replacedVertices)
        ));
      }
    });
  }

  private static ReferenceVertex replaceRefVertex(
      ReferenceVertex v,
      HashMap<String, ReferenceVertex> replacedVertices
  ) {
    if (!(v.id() instanceof String id)) {
      return v;
    }
    var replaced = replacedVertices.get(id);
    if (replaced == null) {
      replaced = new ReferenceVertex(RID.of(id), v.label());
      replacedVertices.put(id, replaced);
    } else {
      if (replaced.label() != null && v.label() != null && !replaced.label()
          .equals(v.label())) {
        throw new IllegalStateException(
            "Cannot replace side effect with id " + id
                + " because it has been replaced with a vertex of a different type");
      }
    }
    return replaced;
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

        final boolean removeOriginalStep;
        if (isTraversalStart) {
          // This is the situation when the "has" step follows directly a GraphStep.
          // In this case, all HasContainers will be added to the new YTDBGraphStep to
          // be translated to YouTrackDB SQL.
          hch.getHasContainers().forEach(currentGraphStep::addHasContainer);
          current.getLabels().forEach(currentGraphStep::addLabel);
          removeOriginalStep = true;
        } else {

          // "hasLabel" steps that don't directly follow a GraphStep are replaced by
          // YTDBHasLabelStep, that handles the "polymorphic" flag correctly.
          final List<P<? super String>> labelPredicates = new ArrayList<>();
          for (var hc : new ArrayList<>(hch.getHasContainers())) {
            if (T.label.getAccessor().equals(hc.getKey())) {
              //noinspection unchecked
              labelPredicates.add((P<? super String>) hc.getPredicate());
              hch.removeHasContainer(hc);
            }
          }

          if (!labelPredicates.isEmpty()) {
            // adding a new YTDBHasLabelStep that handles all label predicates
            final var ytdbHasLabelStep =
                new YTDBHasLabelStep<>(traversal, labelPredicates, polymorphic);
            traversal.addStep(idx, ytdbHasLabelStep);
            idx++;
          }

          // if we've replaced all HasContainers, then we want to remove the original step
          removeOriginalStep = hch.getHasContainers().isEmpty();
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
