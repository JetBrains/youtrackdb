package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;

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
 * @param edgeClass          the edge class name (e.g., "KNOWS")
 * @param direction          the scheduled traversal direction ("out" or "in"), matching
 *                           the format returned by {@code getEdgeDirection()}
 * @param backRefExpression  the expression resolving to the back-referenced alias's RID
 *                           (e.g., {@code $matched.person.@rid})
 * @param sourceAlias        the upstream alias whose RID is used as the probe key
 * @param backRefAlias       the alias whose vertex provides the reverse link bag for building
 * @param targetAlias        the alias bound by this edge's target node
 */
public record SingleEdgeSemiJoin(
    String edgeClass,
    String direction,
    SQLExpression backRefExpression,
    String sourceAlias,
    String backRefAlias,
    String targetAlias) implements SemiJoinDescriptor {

  @Override
  public JoinMode joinMode() {
    return JoinMode.SEMI_JOIN;
  }
}
