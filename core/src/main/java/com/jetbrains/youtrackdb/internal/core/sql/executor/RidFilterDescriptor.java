package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import javax.annotation.Nullable;

/**
 * Describes a RID-based pre-filter that can be resolved at execution time
 * to a set of accepted RIDs. Used by both the SELECT engine ({@link
 * ExpandStep}) and the MATCH engine to skip non-matching vertices without
 * loading them from storage.
 *
 * <p>Three variants are supported:
 * <ul>
 *   <li>{@link DirectRid} — {@code @rid = <expr>}
 *   <li>{@link EdgeRidLookup} — {@code out/in('EdgeClass').@rid = <expr>}
 *       (also used for MATCH back-reference intersection via
 *       {@code $matched.X.@rid})
 *   <li>{@link IndexLookup} — queries an index to produce the accepted
 *       RID set
 * </ul>
 */
public sealed interface RidFilterDescriptor {

  /**
   * Resolves this descriptor against the current execution context
   * and returns a {@link RidSet} of accepted vertex RIDs, or
   * {@code null} if resolution fails or yields too many results.
   */
  @Nullable RidSet resolve(CommandContext ctx);

  /**
   * Direct RID equality: {@code WHERE @rid = <expr>}.
   * Resolves the expression to a single RID and returns a singleton set.
   */
  record DirectRid(SQLExpression ridExpression) implements RidFilterDescriptor {
    @Override
    @Nullable public RidSet resolve(CommandContext ctx) {
      var value = ridExpression.execute((Result) null, ctx);
      RID rid = TraversalPreFilterHelper.toRid(value);
      if (rid == null) {
        return null;
      }
      var set = new RidSet();
      set.add(rid);
      return set;
    }
  }

  /**
   * Reverse edge lookup: {@code WHERE out/in('EdgeClass').@rid = <expr>}.
   *
   * <p>Resolves the target RID from the expression, loads the target vertex,
   * reads the reverse link bag (e.g. for {@code out('X').@rid = Y}, reads
   * the {@code in_X} field on vertex Y), and collects the secondary RIDs
   * (the vertices on the other side of each edge) into a {@link RidSet}.
   *
   * <p>Also used by the MATCH engine for back-reference intersection:
   * when a pattern has {@code {B}.edge{C, where: (@rid = $matched.X.@rid)}},
   * the expression resolves to the already-bound X's RID, and the reverse
   * lookup produces the set of vertices connected to X via the edge.
   */
  record EdgeRidLookup(
      String edgeClassName,
      String traversalDirection,
      SQLExpression targetRidExpression) implements RidFilterDescriptor {

    @Override
    @Nullable public RidSet resolve(CommandContext ctx) {
      var value = targetRidExpression.execute((Result) null, ctx);
      RID targetRid = TraversalPreFilterHelper.toRid(value);
      if (targetRid == null) {
        return null;
      }
      return TraversalPreFilterHelper.resolveReverseEdgeLookup(
          targetRid, edgeClassName, traversalDirection, ctx);
    }
  }

  /**
   * Index-based pre-filter: queries an index for a given condition and
   * collects matching RIDs.
   *
   * <p>Used by the MATCH engine when a target node has an indexable filter
   * that does not reference {@code $matched} (e.g.
   * {@code WHERE creationDate >= :start AND creationDate < :end}).
   */
  record IndexLookup(
      IndexSearchDescriptor indexDescriptor) implements RidFilterDescriptor {

    @Override
    @Nullable public RidSet resolve(CommandContext ctx) {
      return TraversalPreFilterHelper.resolveIndexToRidSet(indexDescriptor, ctx);
    }
  }
}
