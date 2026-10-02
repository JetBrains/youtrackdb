package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer;
import org.apache.tinkerpop.gremlin.structure.Direction;

/**
 * A folded vertex hop deferred after a captured {@code order()} so a following slice can expand in
 * VertexStep order rather than as a MATCH join. Stashed by {@link GremlinPatternAssembler} when the
 * hop would otherwise re-pin the boundary off the {@code ORDER BY} alias; consumed by
 * {@link RangeGlobalStepRecogniser} into an ordered-expand list-shaping stage, or flushed back into
 * the pattern when a non-slice step arrives first.
 *
 * <p>Optional {@link #hasContainers()} hold neighbour {@code has(...)} predicates gathered while the
 * hop is still deferred — evaluated with native label and collation-aware filters during expand
 * before the positional cut (native {@code order().hop().has().limit} semantics), or
 * pushed onto the MATCH target alias on flush. Traversal-bearing {@code has} is declined at accept
 * time; other supported neighbour container shapes are deferred here.
 *
 * @param direction TinkerPop hop direction ({@code OUT} / {@code IN} / {@code BOTH})
 * @param edgeLabels edge labels from {@link GremlinPatternAssembler.EdgeLabelArity#labels()}, or
 *     {@code null} for all edge types
 * @param fromAlias pattern alias of the ordered source (still the MATCH RETURN column while deferred)
 * @param targetAlias synthetic neighbour alias already allocated and used for label binding / boundary
 *     pinning; flush reuses it so filters and {@code as(...)} stay on the same alias
 * @param hasContainers neighbour {@link HasContainer}s ({@code has} / {@code hasLabel} /
 *     {@code hasId} / {@code P.*}, including connectives)
 * @param hasStepSizes container counts per native HasStep, preserving label OR within a step and
 *     AND across steps
 * @param sourceProjection the source payload before the synthetic target changed the boundary pin
 */
record PendingOrderedHop(
    @Nonnull Direction direction,
    @Nullable String[] edgeLabels,
    @Nonnull String fromAlias,
    @Nonnull String targetAlias,
    @Nonnull List<HasContainer> hasContainers,
    @Nonnull List<Integer> hasStepSizes,
    @Nonnull OrderedExpandAccept.SourceProjection sourceProjection) {

  PendingOrderedHop {
    edgeLabels = edgeLabels == null ? null : edgeLabels.clone();
    hasContainers = List.copyOf(hasContainers);
    hasStepSizes = List.copyOf(hasStepSizes);
  }

  PendingOrderedHop(
      Direction direction, String[] edgeLabels, String fromAlias, String targetAlias,
      List<HasContainer> hasContainers, OrderedExpandAccept.SourceProjection sourceProjection) {
    this(direction, edgeLabels, fromAlias, targetAlias, hasContainers,
        hasContainers.isEmpty() ? List.of() : List.of(hasContainers.size()), sourceProjection);
  }

  PendingOrderedHop(
      Direction direction, String[] edgeLabels, String fromAlias, String targetAlias,
      List<HasContainer> hasContainers) {
    this(direction, edgeLabels, fromAlias, targetAlias, hasContainers,
        OrderedExpandAccept.SourceProjection.ELEMENT);
  }

  PendingOrderedHop withHasContainers(@Nonnull List<HasContainer> containers) {
    return new PendingOrderedHop(direction, edgeLabels, fromAlias, targetAlias, containers,
        containers.isEmpty() ? List.of() : List.of(containers.size()), sourceProjection);
  }

  PendingOrderedHop appendHasStep(@Nonnull List<HasContainer> containers) {
    var merged = new java.util.ArrayList<>(hasContainers);
    merged.addAll(containers);
    var sizes = new java.util.ArrayList<>(hasStepSizes);
    sizes.add(containers.size());
    return new PendingOrderedHop(direction, edgeLabels, fromAlias, targetAlias, merged, sizes,
        sourceProjection);
  }
}
