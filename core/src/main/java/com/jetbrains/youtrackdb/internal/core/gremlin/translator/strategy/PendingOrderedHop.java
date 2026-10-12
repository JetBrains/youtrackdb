package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.OrderedHopStage;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer;
import org.apache.tinkerpop.gremlin.structure.Direction;

/** A deferred ordered vertex hop and its following filter/barrier stages in native step order. */
record PendingOrderedHop(
    @Nonnull Direction direction,
    @Nullable String[] edgeLabels,
    @Nonnull String fromAlias,
    @Nonnull String targetAlias,
    @Nonnull List<OrderedHopStage> stages,
    @Nonnull OrderedExpandAccept.SourceProjection sourceProjection,
    @Nonnull List<HasStepRecogniser.HasContribution> contributions,
    @Nonnull String effectiveTargetClass) {

  PendingOrderedHop {
    edgeLabels = edgeLabels == null ? null : edgeLabels.clone();
    stages = List.copyOf(stages);
    contributions = List.copyOf(contributions);
  }

  PendingOrderedHop(
      Direction direction, String[] edgeLabels, String fromAlias, String targetAlias,
      List<OrderedHopStage> stages, OrderedExpandAccept.SourceProjection sourceProjection) {
    this(direction, edgeLabels, fromAlias, targetAlias, stages, sourceProjection, List.of(),
        WalkerContext.VERTEX_ROOT_CLASS);
  }

  static PendingOrderedHop start(
      Direction direction, String[] edgeLabels, String fromAlias, String targetAlias,
      OrderedExpandAccept.SourceProjection sourceProjection) {
    return new PendingOrderedHop(direction, edgeLabels, fromAlias, targetAlias,
        initialStages(direction, edgeLabels, List.of()), sourceProjection);
  }

  PendingOrderedHop(
      Direction direction, String[] edgeLabels, String fromAlias, String targetAlias,
      List<HasContainer> containers) {
    this(direction, edgeLabels, fromAlias, targetAlias,
        initialStages(direction, edgeLabels, containers),
        OrderedExpandAccept.SourceProjection.ELEMENT);
  }

  private static List<OrderedHopStage> initialStages(
      Direction direction, String[] edgeLabels, List<HasContainer> containers) {
    var stages = new ArrayList<OrderedHopStage>();
    stages.add(new OrderedHopStage.Expand(direction, edgeLabels));
    if (!containers.isEmpty()) {
      stages.add(new OrderedHopStage.Filter(containers, false));
    }
    return stages;
  }

  List<HasContainer> hasContainers() {
    var containers = new ArrayList<HasContainer>();
    for (var stage : stages) {
      if (stage instanceof OrderedHopStage.Filter filter) {
        containers.addAll(filter.containers());
      }
    }
    return List.copyOf(containers);
  }

  PendingOrderedHop appendHasStep(
      HasStepRecogniser.HasContribution contribution, boolean polymorphic) {
    var next = new ArrayList<>(stages);
    next.add(new OrderedHopStage.Filter(contribution.nativeContainers(), polymorphic));
    var prepared = new ArrayList<>(contributions);
    prepared.add(contribution);
    return new PendingOrderedHop(direction, edgeLabels, fromAlias, targetAlias, next,
        sourceProjection, prepared,
        contribution.effectiveClass());
  }

  PendingOrderedHop appendBarriers(List<OrderedHopStage.Barrier> barriers) {
    var next = new ArrayList<>(stages);
    next.addAll(barriers);
    return new PendingOrderedHop(direction, edgeLabels, fromAlias, targetAlias, next,
        sourceProjection, contributions, effectiveTargetClass);
  }

  PendingOrderedHop prependBarriers(List<OrderedHopStage.Barrier> barriers) {
    var next = new ArrayList<OrderedHopStage>();
    next.addAll(barriers);
    next.addAll(stages);
    return new PendingOrderedHop(direction, edgeLabels, fromAlias, targetAlias, next,
        sourceProjection, contributions, effectiveTargetClass);
  }
}
