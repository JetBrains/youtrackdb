package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBooleanExpression;
import javax.annotation.Nullable;

/**
 * Pattern D — {@code $currentMatch NOT IN $matched.X.out('E')} anti-semi-join.
 *
 * <p>The hash table is built from {@code X.out('E')} (forward link bag) as a set of
 * vertex RIDs. The probe discards upstream rows whose candidate RID appears in the set.
 *
 * <p>The NOT IN condition is stripped from the target alias's WHERE clause at plan
 * time so that the preceding {@link MatchStep} does not re-evaluate it per row.
 * The stripped condition is stored in {@link #notInCondition} for runtime fallback:
 * if the hash table build fails, {@link BackRefHashJoinStep#handleAntiJoinBuildFailure}
 * evaluates it per row (the slow but correct path).
 *
 * @param anchorAlias           the alias X whose forward edges provide the exclusion set
 * @param traversalEdgeClass    the edge class E
 * @param traversalDirection    "out" or "in"
 * @param targetAlias           the alias of the node whose WHERE clause contained NOT IN
 * @param notInCondition        the stripped NOT IN condition for fallback evaluation,
 *                              or null if the condition could not be preserved
 */
public record AntiSemiJoin(
    String anchorAlias,
    String traversalEdgeClass,
    String traversalDirection,
    String targetAlias,
    @Nullable SQLBooleanExpression notInCondition) implements SemiJoinDescriptor {

  @Override
  public JoinMode joinMode() {
    return JoinMode.ANTI_JOIN;
  }

  /** Returns {@link #anchorAlias()} — the vertex to traverse from is the anchor. */
  @Override
  public String backRefAlias() {
    return anchorAlias;
  }
}
