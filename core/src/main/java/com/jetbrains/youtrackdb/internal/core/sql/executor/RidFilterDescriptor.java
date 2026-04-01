package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import java.util.ArrayList;
import java.util.List;
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
   * Returns a cheap size estimate of the RidSet that {@link #resolve}
   * would produce, without performing full materialization. Used by
   * {@code EdgeTraversal.resolveWithCache()} to decide whether
   * materialization is worthwhile for a given forward link bag size
   * before paying the I/O cost.
   *
   * <p>Accuracy varies by descriptor type:
   * <ul>
   *   <li>{@link DirectRid} — always 1 (exact)
   *   <li>{@link EdgeRidLookup} — reverse link bag size (exact, O(1)
   *       stored field; requires loading the target vertex)
   *   <li>{@link IndexLookup} — histogram-based estimate (approximate)
   *   <li>{@link Composite} — minimum of child estimates
   * </ul>
   *
   * <p>Returns {@code -1} if the estimate is unavailable (e.g. the
   * target vertex cannot be loaded or the index has no statistics).
   *
   * <p>Implementations should use the pre-computed {@code cacheKey}
   * when available (e.g. {@link EdgeRidLookup} uses the target RID
   * from the cache key to avoid re-evaluating the expression).
   *
   * @param ctx      command context
   * @param cacheKey the value returned by {@link #cacheKey}, or
   *                 {@code null} if caching is disabled
   * @return estimated number of RIDs, or {@code -1} if unknown
   */
  int estimatedSize(CommandContext ctx, @Nullable Object cacheKey);

  /**
   * Resolves this descriptor against the current execution context
   * and returns a {@link RidSet} of accepted vertex RIDs, or
   * {@code null} if resolution fails or yields too many results
   * (exceeding {@link TraversalPreFilterHelper#maxRidSetSize()}).
   *
   * <p>Resolution uses only the absolute cap to bound materialization.
   * Per-vertex ratio checks (comparing RidSet size against the actual
   * link bag size) are performed by the caller — see
   * {@link TraversalPreFilterHelper#passesRatioCheck}.
   *
   * <p>Implementations should use the pre-computed {@code cacheKey}
   * when available to avoid re-evaluating the expression.
   *
   * @param ctx      command context (may contain {@code $parent}
   *                 or {@code $matched} references)
   * @param cacheKey the value returned by {@link #cacheKey}, or
   *                 {@code null} if caching is disabled
   */
  @Nullable RidSet resolve(CommandContext ctx, @Nullable Object cacheKey);

  /**
   * Returns a key that identifies the resolved RidSet content for
   * caching purposes. Two consecutive calls with keys that are
   * {@link Object#equals equal} are guaranteed to produce the same
   * RidSet from {@link #resolve}. Returns {@code null} if caching
   * is not worthwhile (e.g. singleton sets).
   *
   * <p>Used by {@code EdgeTraversal.resolveWithCache()} in the MATCH
   * engine to avoid redundant index/reverse-edge-lookup I/O when
   * multiple vertices are processed through the same edge.
   */
  @Nullable Object cacheKey(CommandContext ctx);

  /**
   * Direct RID equality: {@code WHERE @rid = <expr>}.
   * Resolves the expression to a single RID and returns a singleton set.
   */
  record DirectRid(SQLExpression ridExpression) implements RidFilterDescriptor {
    /** A direct RID filter always produces exactly 1 entry. */
    @Override
    public int estimatedSize(CommandContext ctx, @Nullable Object cacheKey) {
      return 1;
    }

    @Override
    @Nullable public RidSet resolve(CommandContext ctx, @Nullable Object cacheKey) {
      var value = ridExpression.execute((Result) null, ctx);
      RID rid = TraversalPreFilterHelper.toRid(value);
      if (rid == null) {
        return null;
      }
      var set = new RidSet();
      set.add(rid);
      return set;
    }

    /** Singleton sets are trivial to rebuild; caching is not worthwhile. */
    @Override
    @Nullable public Object cacheKey(CommandContext ctx) {
      return null;
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
   *
   * <p>When {@code collectEdgeRids} is {@code true}, collects primary RIDs
   * (edge records) instead of secondary RIDs (vertices). This is needed
   * for {@code .outE()}/{@code .inE()} traversals where the link bag
   * iterator filters on edge RIDs, not vertex RIDs.
   */
  record EdgeRidLookup(
      String edgeClassName,
      String traversalDirection,
      SQLExpression targetRidExpression,
      boolean collectEdgeRids) implements RidFilterDescriptor {

    /**
     * Returns the reverse link bag size — exact, O(1) stored field.
     * Uses the pre-computed target RID from {@code cacheKey} to avoid
     * re-evaluating the expression.
     */
    @Override
    public int estimatedSize(CommandContext ctx, @Nullable Object cacheKey) {
      RID targetRid = cacheKey instanceof RID rid ? rid : resolveTargetRid(ctx);
      if (targetRid == null) {
        return -1;
      }
      return TraversalPreFilterHelper.reverseLinkBagSize(
          targetRid, edgeClassName, traversalDirection, ctx);
    }

    /**
     * Resolves the reverse edge lookup. Uses the pre-computed target RID
     * from {@code cacheKey} to avoid re-evaluating the expression.
     */
    @Override
    @Nullable public RidSet resolve(CommandContext ctx, @Nullable Object cacheKey) {
      RID targetRid = cacheKey instanceof RID rid ? rid : resolveTargetRid(ctx);
      if (targetRid == null) {
        return null;
      }
      return TraversalPreFilterHelper.resolveReverseEdgeLookup(
          targetRid, edgeClassName, traversalDirection, collectEdgeRids, ctx);
    }

    /** Resolves the target RID from the expression. */
    @Nullable private RID resolveTargetRid(CommandContext ctx) {
      var value = targetRidExpression.execute((Result) null, ctx);
      return TraversalPreFilterHelper.toRid(value);
    }

    /**
     * Returns the resolved target RID. The RidSet is the same as long as
     * the target vertex does not change (e.g. same {@code $matched.person}).
     */
    @Override
    @Nullable public Object cacheKey(CommandContext ctx) {
      return resolveTargetRid(ctx);
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

    /**
     * Returns a histogram-based estimate of index hits. Approximate but
     * cheap (O(1), uses cached statistics). The {@code cacheKey} is not
     * used (index results are constant for the entire query).
     */
    @Override
    public int estimatedSize(CommandContext ctx, @Nullable Object cacheKey) {
      long est = indexDescriptor.estimateHits(ctx);
      if (est < 0) {
        return -1;
      }
      return (int) Math.min(est, Integer.MAX_VALUE);
    }

    @Override
    @Nullable public RidSet resolve(CommandContext ctx, @Nullable Object cacheKey) {
      return TraversalPreFilterHelper.resolveIndexToRidSet(
          indexDescriptor, ctx);
    }

    /**
     * Index query parameters are {@code :namedParams} or literals (never
     * {@code $matched}), so the result is constant for the entire query.
     * The index name uniquely identifies the result within a single query.
     */
    @Override
    @Nullable public Object cacheKey(CommandContext ctx) {
      return indexDescriptor.getIndex().getName();
    }
  }

  /**
   * Combines multiple descriptors by resolving each and intersecting
   * the results at the bitmap level. Used when an edge has both a
   * back-reference lookup and an index pre-filter.
   */
  record Composite(
      List<RidFilterDescriptor> descriptors) implements RidFilterDescriptor {

    /**
     * Returns the minimum of child estimates. Since Composite intersects
     * results, the output is bounded by the smallest input. Each child
     * receives {@code null} as its cache key (Composite manages its own
     * composite key).
     */
    @Override
    public int estimatedSize(CommandContext ctx, @Nullable Object cacheKey) {
      int min = -1;
      for (var d : descriptors) {
        int est = d.estimatedSize(ctx, null);
        if (est >= 0 && (min < 0 || est < min)) {
          min = est;
        }
      }
      return min;
    }

    @Override
    @Nullable public RidSet resolve(CommandContext ctx, @Nullable Object cacheKey) {
      RidSet combined = null;
      for (var desc : descriptors) {
        var partial = desc.resolve(ctx, null);
        combined = TraversalPreFilterHelper.intersect(combined, partial);
      }
      return combined;
    }

    @Override
    @Nullable public Object cacheKey(CommandContext ctx) {
      var keys = new ArrayList<>(descriptors.size());
      for (var d : descriptors) {
        var k = d.cacheKey(ctx);
        if (k == null) {
          return null;
        }
        keys.add(k);
      }
      return keys;
    }
  }
}
