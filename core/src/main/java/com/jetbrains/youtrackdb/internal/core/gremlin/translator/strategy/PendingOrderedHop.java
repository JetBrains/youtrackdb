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
 * hop is still deferred — applied with {@link HasContainer#test(org.apache.tinkerpop.gremlin.structure.Element)}
 * during expand before the positional cut (native {@code order().hop().has().limit} semantics), or
 * pushed onto the MATCH target alias on flush. Traversal-bearing {@code has} is declined at accept
 * time; every other container shape already handled on the MATCH path is deferred here.
 *
 * @param direction TinkerPop hop direction ({@code OUT} / {@code IN} / {@code BOTH})
 * @param edgeLabel single edge label, or {@code null} for all edge types
 * @param fromAlias pattern alias of the ordered source (still the MATCH RETURN column while deferred)
 * @param targetAlias synthetic neighbour alias already allocated and used for label binding / boundary
 *     pinning; flush reuses it so filters and {@code as(...)} stay on the same alias
 * @param hasContainers AND-ed neighbour {@link HasContainer}s ({@code has} / {@code hasLabel} /
 *     {@code hasId} / {@code P.*}, including connectives)
 */
record PendingOrderedHop(
    @Nonnull Direction direction,
    @Nullable String edgeLabel,
    @Nonnull String fromAlias,
    @Nonnull String targetAlias,
    @Nonnull List<HasContainer> hasContainers) {

  PendingOrderedHop {
    hasContainers = List.copyOf(hasContainers);
  }

  PendingOrderedHop withHasContainers(@Nonnull List<HasContainer> containers) {
    return new PendingOrderedHop(direction, edgeLabel, fromAlias, targetAlias, containers);
  }
}
