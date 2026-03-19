package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.RidPair;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.List;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Centralised utility for pre-filter operations shared by both the SELECT
 * engine ({@link ExpandStep}, {@link SelectExecutionPlanner}) and the MATCH
 * engine ({@code MatchEdgeTraverser}, {@code MatchExecutionPlanner}).
 *
 * <p>Consolidates logic that was previously scattered across iterator,
 * execution-step, and planner classes, eliminating tight coupling between
 * query planning and specific iterator/step implementations.
 */
public final class TraversalPreFilterHelper {

  /** Returns the absolute upper bound on RidSet size — protects against OOM. */
  public static int maxRidSetSize() {
    return GlobalConfiguration.QUERY_PREFILTER_MAX_RIDSET_SIZE.getValueAsInteger();
  }

  /**
   * Returns the maximum ratio of {@code ridSetSize / linkBagSize} at which
   * the pre-filter is still considered useful. Above this threshold the
   * filter lets through too many elements and the overhead of
   * {@code contains()} checks outweighs the I/O savings.
   */
  public static double maxSelectivityRatio() {
    return GlobalConfiguration.QUERY_PREFILTER_MAX_SELECTIVITY_RATIO
        .getValueAsDouble();
  }

  /**
   * Returns the minimum link bag size below which pre-filtering is skipped
   * entirely. Loading a handful of records is cheaper than building
   * a RidSet and checking {@code contains()} on each.
   */
  public static int minLinkBagSize() {
    return GlobalConfiguration.QUERY_PREFILTER_MIN_LINKBAG_SIZE.getValueAsInteger();
  }

  /** Number of index/edge results between adaptive-abort checks. */
  private static final int CHECKPOINT_INTERVAL_MASK = 0x3FF; // every 1024

  private TraversalPreFilterHelper() {
  }

  /**
   * Returns the set of collection (cluster) IDs for the given class and all
   * its subclasses. Used to build a class-based pre-filter that skips
   * vertices whose RID belongs to a different collection without disk I/O.
   */
  @Nonnull
  public static IntSet collectionIdsForClass(@Nonnull SchemaClass clazz) {
    var ids = clazz.getPolymorphicCollectionIds();
    var set = new IntOpenHashSet(ids.length);
    for (var id : ids) {
      set.add(id);
    }
    return set;
  }

  /**
   * Queries the index described by the given descriptor and collects
   * matching RIDs into a {@link RidSet}. Uses adaptive abort: every
   * 1024 results the method checks whether the accumulated count
   * exceeds {@link #maxRidSetSize()} or the actual link bag size,
   * aborting early when the pre-filter would not be effective.
   *
   * <p>After collecting all results, a final ratio check against
   * {@link #maxSelectivityRatio()} discards sets that are too large
   * relative to the link bag.
   *
   * @param desc        index search descriptor
   * @param ctx         command context
   * @param linkBagSize actual size of the link bag to be filtered, or
   *                    {@link RidFilterDescriptor#UNKNOWN_LINKBAG_SIZE}
   * @return the RidSet, or {@code null} if the query should fall back
   *     to unfiltered iteration
   */
  @Nullable public static RidSet resolveIndexToRidSet(
      IndexSearchDescriptor desc, CommandContext ctx, int linkBagSize) {
    List<Stream<RawPair<Object, RID>>> streams;
    streams = FetchFromIndexStep.init(desc, true, ctx);
    if (streams.isEmpty()) {
      return null;
    }

    var ridSet = new RidSet();
    var count = 0;
    var aborted = false;
    try {
      for (var stream : streams) {
        var iter = stream.iterator();
        while (iter.hasNext()) {
          ridSet.add(iter.next().second());
          count++;
          if ((count & CHECKPOINT_INTERVAL_MASK) == 0
              && shouldAbort(count, linkBagSize)) {
            aborted = true;
            break;
          }
        }
        if (aborted) {
          break;
        }
      }
    } finally {
      for (var stream : streams) {
        stream.close();
      }
    }
    if (aborted) {
      return null;
    }
    return passesRatioCheck(ridSet.size(), linkBagSize) ? ridSet : null;
  }

  /**
   * Loads the vertex identified by {@code targetRid}, reads the reverse
   * link bag for the given edge class, and collects the secondary RIDs
   * (the vertices on the other side of each edge) into a {@link RidSet}.
   *
   * <p>For example, if {@code traversalDirection} is {@code "out"} and
   * {@code edgeClassName} is {@code "HAS_CREATOR"}, reads the
   * {@code in_HAS_CREATOR} field on the target vertex.
   *
   * <p>Uses the same adaptive abort strategy as
   * {@link #resolveIndexToRidSet}: checkpoints every 1024 elements
   * compare the count against {@link #maxRidSetSize()} and
   * {@code linkBagSize}, and a final ratio check discards sets that
   * are too large relative to the link bag.
   *
   * @param targetRid          RID of the vertex whose reverse edges to read
   * @param edgeClassName      the edge class name (e.g. {@code "HAS_CREATOR"})
   * @param traversalDirection the direction of the original edge
   *                           ({@code "out"} or {@code "in"})
   * @param ctx                command context for transaction access
   * @param linkBagSize        actual size of the link bag to be filtered, or
   *                           {@link RidFilterDescriptor#UNKNOWN_LINKBAG_SIZE}
   * @return the set of opposite-side vertex RIDs, or {@code null} if
   *     resolution fails or the pre-filter would not be effective
   */
  @Nullable public static RidSet resolveReverseEdgeLookup(
      RID targetRid,
      String edgeClassName,
      String traversalDirection,
      CommandContext ctx,
      int linkBagSize) {
    var db = ctx.getDatabaseSession();
    EntityImpl targetEntity;
    try {
      var rec = db.getActiveTransaction().load(targetRid);
      if (!(rec instanceof EntityImpl entity)) {
        return null;
      }
      targetEntity = entity;
    } catch (RecordNotFoundException e) {
      return null;
    }

    var reversePrefix = "out".equals(traversalDirection) ? "in_" : "out_";
    var fieldName = reversePrefix + edgeClassName;
    var fieldValue = targetEntity.getPropertyInternal(fieldName);
    if (!(fieldValue instanceof LinkBag linkBag)) {
      return null;
    }

    var ridSet = new RidSet();
    var count = 0;
    for (RidPair pair : linkBag) {
      ridSet.add(pair.secondaryRid());
      count++;
      if ((count & CHECKPOINT_INTERVAL_MASK) == 0
          && shouldAbort(count, linkBagSize)) {
        return null;
      }
    }
    return passesRatioCheck(ridSet.size(), linkBagSize) ? ridSet : null;
  }

  /**
   * Attempts to find the best index for the given WHERE clause on the
   * specified target class. Only single-OR-branch WHERE clauses are
   * considered (multi-branch OR is too complex for this optimisation).
   *
   * <p>No plan-time rejection is performed based on estimated hit count.
   * The runtime adaptive abort in {@link #resolveIndexToRidSet} (absolute
   * cap + checkpoint every 1024 elements) and the per-vertex
   * {@link #passesRatioCheck} provide all necessary protection. A
   * plan-time histogram gate was intentionally removed: it compared
   * estimated global index hits against {@link #maxRidSetSize()}, but
   * this caused false rejections — an index returning 150k global hits
   * can still be highly effective when intersected with a small link bag
   * (e.g. 200 edges). The per-vertex link bag size is unknown at plan
   * time, so the decision must be deferred to runtime.
   *
   * @param pushDownWhere the WHERE clause to analyse (should not reference
   *                      {@code $parent} or {@code $matched})
   * @param className     the target class name
   * @param ctx           command context
   * @return an index descriptor, or {@code null} if no suitable index
   *     exists for the given WHERE clause
   */
  @Nullable public static IndexSearchDescriptor findIndexForFilter(
      SQLWhereClause pushDownWhere, String className, CommandContext ctx) {
    if (ctx == null || ctx.getDatabaseSession() == null) {
      return null;
    }
    var schema = ctx.getDatabaseSession().getMetadata().getImmutableSchemaSnapshot();
    var schemaClass = schema.getClassInternal(className);
    if (schemaClass == null) {
      return null;
    }
    var indexes = schemaClass.getIndexesInternal();
    if (indexes.isEmpty()) {
      return null;
    }
    var flatWhere = pushDownWhere.flatten(ctx, schemaClass);
    if (flatWhere.size() != 1) {
      return null;
    }
    return SelectExecutionPlanner.findBestIndexFor(
        ctx, indexes, flatWhere.getFirst(), schemaClass);
  }

  /**
   * Converts a raw value (which may be a {@link RID} or an
   * {@link Identifiable}) to a {@link RID}, or returns {@code null}.
   */
  @Nullable public static RID toRid(@Nullable Object value) {
    if (value instanceof RID rid) {
      return rid;
    }
    if (value instanceof Identifiable identifiable) {
      return identifiable.getIdentity();
    }
    return null;
  }

  /**
   * Returns the intersection of two RID sets using bitmap-level
   * {@link RidSet#intersect}. If either input is {@code null},
   * returns the other. If both are {@code null}, returns {@code null}.
   */
  @Nullable public static RidSet intersect(@Nullable RidSet a, @Nullable RidSet b) {
    return RidSet.intersect(a, b);
  }

  /**
   * Checkpoint guard: returns {@code true} if the RidSet build should
   * be aborted because it has grown too large (absolute cap) or
   * already exceeds the link bag it will filter.
   */
  static boolean shouldAbort(int count, int linkBagSize) {
    if (count > maxRidSetSize()) {
      return true;
    }
    return linkBagSize >= 0 && count > linkBagSize;
  }

  /**
   * Final ratio guard: returns {@code true} if the completed RidSet
   * is small enough relative to the link bag to be worth applying.
   * When {@code linkBagSize} is unknown (negative), the check passes.
   */
  public static boolean passesRatioCheck(int ridSetSize, int linkBagSize) {
    if (linkBagSize <= 0) {
      return true;
    }
    return (double) ridSetSize / linkBagSize <= maxSelectivityRatio();
  }
}
