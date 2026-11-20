package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Direction;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.record.impl.VertexEntityImpl;
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
   * Returns the maximum overlap ratio for {@link
   * RidFilterDescriptor.EdgeRidLookup} pre-filters. Above this threshold
   * the reverse set covers too much of the link bag to be useful.
   */
  public static double edgeLookupMaxRatio() {
    return GlobalConfiguration.QUERY_PREFILTER_EDGE_LOOKUP_MAX_RATIO
        .getValueAsDouble();
  }

  /**
   * Returns the maximum class-level selectivity for {@link
   * RidFilterDescriptor.IndexLookup} pre-filters. Above this threshold
   * the index condition matches too many records to be useful.
   */
  public static double indexLookupMaxSelectivity() {
    return GlobalConfiguration.QUERY_PREFILTER_INDEX_LOOKUP_MAX_SELECTIVITY
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

  /**
   * Returns the explicitly configured load-to-scan cost ratio, or
   * {@code -1.0} if not set (callers fall back to
   * {@code EdgeTraversal.DEFAULT_LOAD_TO_SCAN_RATIO}). Non-positive and
   * non-finite values are treated as "not set".
   */
  public static double configuredLoadToScanRatio() {
    double value =
        GlobalConfiguration.QUERY_PREFILTER_LOAD_TO_SCAN_RATIO.getValueAsDouble();
    return value > 0 && Double.isFinite(value) ? value : -1.0;
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
   * exceeds {@link #maxRidSetSize()}, aborting early to prevent
   * unbounded materialization.
   *
   * <p>Per-vertex ratio checks (comparing RidSet size against the
   * actual link bag size) are performed by the caller.
   *
   * @param desc index search descriptor
   * @param ctx  command context
   * @return the RidSet, or {@code null} if the query should fall back
   *     to unfiltered iteration
   */
  @Nullable public static RidSet resolveIndexToRidSet(
      IndexSearchDescriptor desc, CommandContext ctx) {
    // Up-front estimate guard: if the index estimates more hits than
    // the absolute cap, skip iteration entirely.
    long estimated = desc.estimateHits(ctx);
    if (estimated > 0 && estimated > maxRidSetSize()) {
      return null;
    }

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
              && shouldAbort(count)) {
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
    return ridSet;
  }

  /**
   * Loads the vertex identified by {@code targetRid}, reads the reverse
   * link fields for the edge class and its subclasses, and collects the secondary RIDs
   * (the vertices on the other side of each edge) into a {@link RidSet}.
   *
   * <p>For example, if {@code traversalDirection} is {@code "out"} and
   * {@code edgeClassName} is {@code "HAS_CREATOR"}, reads the
   * {@code in_HAS_CREATOR} field on the target vertex.
   *
   * <p>Uses the same adaptive abort strategy as
   * {@link #resolveIndexToRidSet}: checkpoints every 1024 elements
   * compare the count against {@link #maxRidSetSize()}, aborting
   * early to prevent unbounded materialization.
   *
   * <p>Per-vertex ratio checks (comparing RidSet size against the
   * actual link bag size) are performed by the caller.
   *
   * @param targetRid          RID of the vertex whose reverse edges to read
   * @param edgeClassName      the edge class name (e.g. {@code "HAS_CREATOR"})
   * @param traversalDirection the direction of the original edge
   *                           ({@code "out"}, {@code "in"} or {@code "both"})
   * @param ctx                command context for transaction access
   * @return the set of opposite-side vertex RIDs, or {@code null} if
   *     resolution fails or the absolute cap is exceeded
   */
  @Nullable public static RidSet resolveReverseEdgeLookup(
      RID targetRid,
      String edgeClassName,
      String traversalDirection,
      CommandContext ctx) {
    return resolveReverseEdgeLookup(
        targetRid, edgeClassName, traversalDirection, false, ctx);
  }

  /**
   * Resolves the reverse edge lookup, optionally collecting edge RIDs
   * (primary) instead of vertex RIDs (secondary).
   *
   * <p>When {@code collectEdgeRids} is {@code false} (default), collects
   * secondary RIDs (the vertices on the other side of each edge). This is
   * correct for {@code .out()}/{@code .in()} traversals whose link bag
   * iterators filter on vertex RIDs.
   *
   * <p>When {@code collectEdgeRids} is {@code true}, collects primary RIDs
   * (the edge records themselves). This is needed for {@code .outE()}/
   * {@code .inE()} traversals whose link bag iterators filter on edge RIDs.
   *
   * @param collectEdgeRids if {@code true}, collect edge RIDs (primary);
   *     if {@code false}, collect vertex RIDs (secondary)
   */
  @Nullable public static RidSet resolveReverseEdgeLookup(
      RID targetRid,
      String edgeClassName,
      String traversalDirection,
      boolean collectEdgeRids,
      CommandContext ctx) {
    return resolveReverseEdgeLookup(targetRid,
        reverseLinkFields(ctx.getDatabaseSession().getMetadata().getImmutableSchemaSnapshot(),
            edgeClassName, traversalDirection),
        collectEdgeRids, ctx);
  }

  /** Uses exactly the traversal's schema-based field rule, in the reverse direction. */
  static List<String> reverseLinkFields(
      Schema schema, String edgeClassName, String traversalDirection) {
    var direction = switch (traversalDirection) {
      case "out" -> Direction.IN;
      case "both" -> Direction.BOTH;
      default -> Direction.OUT;
    };
    return VertexEntityImpl.getAllPossibleEdgePropertyNames(schema, direction, edgeClassName);
  }

  @Nullable static RidSet resolveReverseEdgeLookup(
      RID targetRid, List<String> fields, boolean collectEdgeRids, CommandContext ctx) {
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

    // Validate every field before building. Skipping a non-bag would give an incomplete set.
    int total = linkBagTotal(targetEntity, fields);
    if (total < 0 || total > maxRidSetSize()) {
      return null;
    }
    var ridSet = new RidSet();
    long count = 0;
    for (var field : fields) {
      var value = targetEntity.getPropertyInternal(field);
      if (value instanceof LinkBag linkBag) {
        for (RidPair pair : linkBag) {
          ridSet.add(collectEdgeRids ? pair.primaryRid() : pair.secondaryRid());
          count++;
          if ((count & CHECKPOINT_INTERVAL_MASK) == 0 && count > maxRidSetSize()) {
            return null;
          }
        }
      }
    }
    return ridSet;
  }

  /**
   * Returns the saturating total of reverse link bag sizes for the target vertex
   * and edge class hierarchy, without iterating entries.
   *
   * <p>Used by {@link RidFilterDescriptor.EdgeRidLookup#estimatedSize}
   * for pre-resolution ratio checks.
   *
   * @return the reverse link bag size, or {@code -1} if the target vertex
   *     cannot be loaded or has no matching reverse link bag
   */
  public static int reverseLinkBagSize(
      RID targetRid, String edgeClassName,
      String traversalDirection, CommandContext ctx) {
    if (ctx.getDatabaseSession() == null) {
      return -1;
    }
    return reverseLinkBagSize(targetRid,
        reverseLinkFields(ctx.getDatabaseSession().getMetadata().getImmutableSchemaSnapshot(),
            edgeClassName, traversalDirection),
        ctx);
  }

  static int reverseLinkBagSize(RID targetRid, List<String> fields, CommandContext ctx) {
    var db = ctx.getDatabaseSession();
    if (db == null) {
      return -1;
    }
    EntityImpl targetEntity;
    try {
      var rec = db.getActiveTransaction().load(targetRid);
      if (!(rec instanceof EntityImpl entity)) {
        return -1;
      }
      targetEntity = entity;
    } catch (RecordNotFoundException e) {
      return -1;
    }

    return linkBagTotal(targetEntity, fields);
  }

  /** For both directions, use the larger directional total, not their overlapping sum. */
  static int reverseLinkBagEstimate(
      RID targetRid, List<String> fields, boolean both, CommandContext ctx) {
    if (!both) {
      return reverseLinkBagSize(targetRid, fields, ctx);
    }
    int incoming = reverseLinkBagSize(targetRid,
        fields.stream().filter(name -> name.startsWith("in_")).toList(), ctx);
    int outgoing = reverseLinkBagSize(targetRid,
        fields.stream().filter(name -> name.startsWith("out_")).toList(), ctx);
    return Math.max(incoming, outgoing);
  }

  /** Measured bags belong to one execution and can be reused after a ratio rejection. */
  public record ReverseLookup(List<LinkBag> bags, int estimate, int total) {

    @Nullable public RidSet resolve(boolean collectEdgeRids) {
      if (total < 0 || total > maxRidSetSize()) {
        return null;
      }
      var result = new RidSet();
      for (var bag : bags) {
        for (RidPair pair : bag) {
          result.add(collectEdgeRids ? pair.primaryRid() : pair.secondaryRid());
        }
      }
      return result;
    }
  }

  /** One vertex load and one field pass produce both directional and absolute totals. */
  @Nullable static ReverseLookup prepareReverseLookup(
      RID target, List<String> fields, int incomingCount, boolean both, CommandContext ctx) {
    if (ctx.getDatabaseSession() == null) {
      return null;
    }
    try {
      var record = ctx.getDatabaseSession().getActiveTransaction().load(target);
      if (!(record instanceof EntityImpl vertex)) {
        return null;
      }
      vertex.deserializeProperties();
      var bags = new java.util.ArrayList<LinkBag>();
      long incoming = 0;
      long outgoing = 0;
      for (int i = 0; i < fields.size(); i++) {
        var value = vertex.getPropertyInternal(fields.get(i));
        if (value == null) {
          continue;
        }
        if (!(value instanceof LinkBag bag)) {
          return new ReverseLookup(List.of(), -1, -1);
        }
        bags.add(bag);
        if (i < incomingCount) {
          incoming = Math.min(Integer.MAX_VALUE, incoming + bag.size());
        } else {
          outgoing = Math.min(Integer.MAX_VALUE, outgoing + bag.size());
        }
      }
      int total = bags.isEmpty() ? -1
          : (int) Math.min(Integer.MAX_VALUE, incoming + outgoing);
      return new ReverseLookup(bags,
          both ? (int) Math.max(incoming, outgoing) : total, total);
    } catch (RecordNotFoundException e) {
      return null;
    }
  }

  private static int linkBagTotal(EntityImpl vertex, List<String> fields) {
    // Fully load once so absent hierarchy fields do not trigger repeated partial parses.
    vertex.deserializeProperties();
    long total = 0;
    boolean found = false;
    for (var field : fields) {
      var value = vertex.getPropertyInternal(field);
      if (value == null) {
        continue;
      }
      if (!(value instanceof LinkBag bag)) {
        return -1;
      }
      found = true;
      total = Math.min(Integer.MAX_VALUE, total + bag.size());
    }
    return found ? (int) total : -1;
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
   * be aborted because it has grown beyond the absolute cap.
   */
  static boolean shouldAbort(int count) {
    return count > maxRidSetSize();
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
    return (double) ridSetSize / linkBagSize <= edgeLookupMaxRatio();
  }
}
