package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.internal.core.sql.executor.IndexSearchDescriptor;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import javax.annotation.Nullable;

/**
 * Pattern B — {@code .outE('E'){where: ...}.inV(){where: @rid = $matched.X.@rid}} chain.
 * Collapses two schedule edges into one {@link BackRefHashJoinStep}.
 *
 * <p>The hash table is built from {@code X}'s reverse link bag, optionally filtered by
 * an index on the intermediate edge (e.g., {@code joinDate >= :minDate}). Maps source
 * vertex RID to a list of matching edge records.
 *
 * @param edgeClass          the edge class from edge_j-1 (the {@code .outE('E')} edge)
 * @param direction          the traversal direction from edge_j-1
 * @param backRefExpression  the expression resolving to the back-referenced alias's RID
 * @param sourceAlias        the upstream alias whose RID is the probe key
 * @param backRefAlias       the alias whose vertex provides the reverse link bag
 * @param intermediateAlias  the alias for the edge record (from the {@code .outE()} step)
 * @param targetAlias        the alias bound by the {@code .inV()} target
 * @param indexFilter        optional index pre-filter on the intermediate edge, or null
 */
public record ChainSemiJoin(
    String edgeClass,
    String direction,
    SQLExpression backRefExpression,
    String sourceAlias,
    String backRefAlias,
    String intermediateAlias,
    String targetAlias,
    @Nullable IndexSearchDescriptor indexFilter) implements SemiJoinDescriptor {

  @Override
  public JoinMode joinMode() {
    return JoinMode.SEMI_JOIN;
  }
}
