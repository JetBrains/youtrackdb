package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import javax.annotation.Nullable;

/**
 * Pattern D — {@code $currentMatch NOT IN $matched.X.out('E')} anti-semi-join.
 *
 * <p>The hash table is built from {@code X.out('E')} (forward link bag) as a set of
 * vertex RIDs. The probe discards upstream rows whose candidate RID appears in the set.
 *
 * @param anchorAlias           the alias X whose forward edges provide the exclusion set
 * @param traversalEdgeClass    the edge class E
 * @param traversalDirection    "out" or "in"
 * @param backRefAlias          same as anchorAlias (the vertex to traverse from)
 * @param targetAlias           the alias of the node whose WHERE clause contained NOT IN
 * @param residualFilter        any remaining WHERE conditions after NOT IN extraction, or null
 */
public record AntiSemiJoin(
    String anchorAlias,
    String traversalEdgeClass,
    String traversalDirection,
    String backRefAlias,
    String targetAlias,
    @Nullable SQLWhereClause residualFilter) implements SemiJoinDescriptor {

  @Override
  public JoinMode joinMode() {
    return JoinMode.ANTI_JOIN;
  }
}
