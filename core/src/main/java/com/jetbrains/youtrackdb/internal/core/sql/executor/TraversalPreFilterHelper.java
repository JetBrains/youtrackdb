package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.CoreMetrics;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.MetricsRegistry;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.TimeRate;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
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
   * Returns the maximum overlap ratio for {@link
   * RidFilterDescriptor.EdgeRidLookup} pre-filters. Above this threshold
   * the reverse set covers too much of the link bag to be useful.
   *
   * <p>Resolution order:
   * <ol>
   *   <li>New {@code QUERY_PREFILTER_EDGE_LOOKUP_MAX_RATIO} if explicitly
   *       set</li>
   *   <li>Old {@code QUERY_PREFILTER_MAX_SELECTIVITY_RATIO} value as
   *       fallback (same overlap-ratio semantics; default 0.8)</li>
   * </ol>
   */
  public static double edgeLookupMaxRatio() {
    if (GlobalConfiguration.QUERY_PREFILTER_EDGE_LOOKUP_MAX_RATIO
        .isChanged()) {
      return GlobalConfiguration.QUERY_PREFILTER_EDGE_LOOKUP_MAX_RATIO
          .getValueAsDouble();
    }
    // Fall back to old property (same semantics: overlap ratio)
    return maxSelectivityRatio();
  }

  /**
   * Returns the maximum class-level selectivity for {@link
   * RidFilterDescriptor.IndexLookup} pre-filters. Above this threshold
   * the index condition matches too many records to be useful.
   *
   * <p>Does NOT fall back to the old
   * {@code QUERY_PREFILTER_MAX_SELECTIVITY_RATIO} — the semantics are
   * fundamentally different (class-level selectivity vs. overlap ratio).
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
   * {@code -1.0} if not explicitly set (auto-compute from live metrics).
   * A positive return value overrides live metric computation.
   * Non-positive values (including the sentinel default {@code -1.0})
   * are treated as "auto-compute".
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

    // Resolve scan metrics once per invocation. Metrics capture scans
    // from all engines (MATCH, SELECT, hash join) — hardware I/O cost
    // is engine-independent.
    TimeRate scanNanos = resolveScanNanos();
    TimeRate scanEntries = resolveScanEntries();

    var ridSet = new RidSet();
    var count = 0;
    var aborted = false;
    long startNanos = System.nanoTime();
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
      // Record scan metrics once per invocation (not per-entry).
      long elapsedNanos = System.nanoTime() - startNanos;
      scanNanos.record(elapsedNanos);
      scanEntries.record(count);
    }
    if (aborted) {
      return null;
    }
    return ridSet;
  }

  /**
   * Resolves the {@link CoreMetrics#PREFILTER_SCAN_NANOS} metric from
   * the global registry, falling back to {@link TimeRate#NOOP} when
   * the registry is unavailable (early startup, tests without engine).
   */
  static TimeRate resolveScanNanos() {
    MetricsRegistry registry =
        YouTrackDBEnginesManager.instance().getMetricsRegistry();
    return registry != null
        ? registry.globalMetric(CoreMetrics.PREFILTER_SCAN_NANOS)
        : TimeRate.NOOP;
  }

  /**
   * Resolves the {@link CoreMetrics#PREFILTER_SCAN_ENTRIES} metric from
   * the global registry, falling back to {@link TimeRate#NOOP} when
   * the registry is unavailable.
   */
  static TimeRate resolveScanEntries() {
    MetricsRegistry registry =
        YouTrackDBEnginesManager.instance().getMetricsRegistry();
    return registry != null
        ? registry.globalMetric(CoreMetrics.PREFILTER_SCAN_ENTRIES)
        : TimeRate.NOOP;
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
   * compare the count against {@link #maxRidSetSize()}, aborting
   * early to prevent unbounded materialization.
   *
   * <p>Per-vertex ratio checks (comparing RidSet size against the
   * actual link bag size) are performed by the caller.
   *
   * @param targetRid          RID of the vertex whose reverse edges to read
   * @param edgeClassName      the edge class name (e.g. {@code "HAS_CREATOR"})
   * @param traversalDirection the direction of the original edge
   *                           ({@code "out"} or {@code "in"})
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

    // O(1) up-front guard: skip iteration if the reverse link bag
    // already exceeds the absolute cap.
    if (linkBag.size() > maxRidSetSize()) {
      return null;
    }

    var ridSet = new RidSet();
    var count = 0;
    for (RidPair pair : linkBag) {
      ridSet.add(collectEdgeRids ? pair.primaryRid() : pair.secondaryRid());
      count++;
      if ((count & CHECKPOINT_INTERVAL_MASK) == 0
          && shouldAbort(count)) {
        return null;
      }
    }
    return ridSet;
  }

  /**
   * Returns the size of the reverse link bag for the given target vertex
   * and edge class, without iterating its entries. O(1) — the size is a
   * stored field on the link bag.
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

    var reversePrefix = "out".equals(traversalDirection) ? "in_" : "out_";
    var fieldName = reversePrefix + edgeClassName;
    var fieldValue = targetEntity.getPropertyInternal(fieldName);
    if (!(fieldValue instanceof LinkBag linkBag)) {
      return -1;
    }
    return linkBag.size();
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
    return (double) ridSetSize / linkBagSize <= maxSelectivityRatio();
  }
}
