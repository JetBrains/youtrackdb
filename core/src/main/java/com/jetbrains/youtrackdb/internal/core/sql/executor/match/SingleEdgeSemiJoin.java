package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import javax.annotation.Nullable;

/**
 * Pattern A — single vertex-level edge with equality back-reference:
 * <pre>
 *   {source}.out('E'){target, where: (@rid = $matched.X.@rid)}
 * </pre>
 *
 * <p>The hash table is built from {@code X}'s reverse link bag ({@code X.in_E} or
 * {@code X.out_E} depending on direction) and maps source vertex RIDs to presence.
 * The probe checks if {@code source.@rid} is in the set.
 *
 * <p>{@code targetFilter} holds any residual WHERE terms on the target alias
 * (i.e. the target's full WHERE minus the {@code @rid = $matched.X.@rid}
 * equality) that are NOT covered by the hash lookup. Because this step
 * replaces the target's {@code MatchStep}, the terms would otherwise be
 * silently dropped — {@link BackRefHashJoinStep} re-evaluates them on the
 * loaded target entity to guarantee correctness.
 *
 * @param edgeClass          the edge class name (e.g., "KNOWS")
 * @param direction          the scheduled traversal direction ("out" or "in"), matching
 *                           the format returned by {@code getEdgeDirection()}
 * @param backRefExpression  the expression resolving to the back-referenced alias's RID
 *                           (e.g., {@code $matched.person.@rid})
 * @param sourceAlias        the upstream alias whose RID is used as the probe key
 * @param backRefAlias       the alias whose vertex provides the reverse link bag for building
 * @param targetAlias        the alias bound by this edge's target node
 * @param targetFilter       residual WHERE on the target alias after stripping the back-ref
 *                           equality, or {@code null} when no residual terms exist
 */
public record SingleEdgeSemiJoin(
    String edgeClass,
    String direction,
    SQLExpression backRefExpression,
    String sourceAlias,
    String backRefAlias,
    String targetAlias,
    @Nullable SQLWhereClause targetFilter) implements SemiJoinDescriptor {

  @Override
  public JoinMode joinMode() {
    return JoinMode.SEMI_JOIN;
  }
}
