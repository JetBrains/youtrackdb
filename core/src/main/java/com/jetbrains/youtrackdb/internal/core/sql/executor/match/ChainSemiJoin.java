package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.internal.core.sql.executor.IndexSearchDescriptor;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import javax.annotation.Nullable;

/**
 * Pattern B — {@code .outE('E'){where: ...}.inV(){where: @rid = $matched.X.@rid}} chain.
 * Collapses two schedule edges into one {@link BackRefHashJoinStep}.
 *
 * <p>The hash table is built from {@code X}'s reverse link bag. Two filter hooks control
 * which edges enter the map:
 * <ul>
 *   <li>{@code indexFilter} — optional index pre-filter (RidSet intersection) used to
 *       reject edges <em>without</em> loading them. Covers any subset of the edge WHERE
 *       clause that the query planner could map to an index.</li>
 *   <li>{@code edgeFilter} — the complete edge WHERE clause. When present, applied to
 *       every loaded edge as the authoritative correctness check. Non-indexable terms
 *       (e.g. {@code category='A'} when the index is on {@code score} alone) are
 *       silently dropped without this — the consumed predecessor's MatchStep would
 *       normally evaluate them, but it is skipped in the collapsed plan.</li>
 * </ul>
 *
 * @param edgeClass          the edge class from edge_j-1 (the {@code .outE('E')} edge)
 * @param direction          the traversal direction from edge_j-1
 * @param backRefExpression  the expression resolving to the back-referenced alias's RID
 * @param sourceAlias        the upstream alias whose RID is the probe key
 * @param backRefAlias       the alias whose vertex provides the reverse link bag
 * @param intermediateAlias  the alias for the edge record (from the {@code .outE()} step)
 * @param targetAlias        the alias bound by the {@code .inV()} target
 * @param indexFilter        optional index pre-filter on the intermediate edge, or null
 * @param edgeFilter         the full WHERE clause on the intermediate edge, or null
 *                           when the edge has no filter (and therefore no residual
 *                           correctness check is required)
 */
public record ChainSemiJoin(
    String edgeClass,
    String direction,
    SQLExpression backRefExpression,
    String sourceAlias,
    String backRefAlias,
    String intermediateAlias,
    String targetAlias,
    @Nullable IndexSearchDescriptor indexFilter,
    @Nullable SQLWhereClause edgeFilter) implements SemiJoinDescriptor {

  @Override
  public JoinMode joinMode() {
    return JoinMode.SEMI_JOIN;
  }
}
