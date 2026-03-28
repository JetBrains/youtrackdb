package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.executor.AbstractExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ExecutionStepInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.IndexSearchDescriptor;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.RidFilteredIndexValuesStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.RidSet;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.RidPair;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * MATCH execution step that traverses an edge using an index-ordered scan.
 * Replaces {@link MatchStep} for edges where:
 * <ol>
 *   <li>The ORDER BY references a property on the edge's target alias</li>
 *   <li>An index exists on that property</li>
 *   <li>The edge is simple (not WHILE/recursive)</li>
 * </ol>
 *
 * <h3>Two execution modes</h3>
 *
 * <p><b>Single-source</b> (one upstream row): builds a RidSet from the source
 * vertex's LinkBag, scans the index filtered by RidSet membership. Only
 * matching records are loaded.
 *
 * <p><b>Multi-source</b> (many upstream rows): collects all upstream rows,
 * builds a small {@code sourceMap} (source RID → upstream row), then scans
 * the index globally. For each entry, loads the record, follows the reverse
 * edge to find the source vertex, and checks {@code sourceMap} membership.
 * This produces globally sorted results across all sources.
 *
 * <h3>Cost-based heuristic</h3>
 *
 * <p>Both modes compare index scan cost vs load-all-and-sort cost using
 * {@link CostModel} constants. When the cost model rejects the index scan,
 * both modes fall back to loading all targets and sorting in-memory — still
 * producing correctly ordered results.
 */
public class IndexOrderedEdgeStep extends AbstractExecutionStep {

  private final String sourceAlias;
  private final String targetAlias;
  private final String edgeClassName;
  private final String linkBagFieldName;
  private final Index index;
  private final boolean orderAsc;
  private final EdgeTraversal edge;
  private final long limit;

  /** Multi-source execution mode, or null for single-source. */
  @Nullable private final MatchExecutionPlanner.MultiSourceMode multiSourceMode;

  /** Reverse LinkBag field on target for reverse edge lookup (multi-source). */
  @Nullable private final String reverseFieldName;

  /** Source vertex class name for class-check modes (UNFILTERED_BOUND/UNBOUND). */
  @Nullable private final String sourceClassName;

  /** WHERE filter on the target alias (e.g., creationDate < :maxDate). */
  @Nullable private final SQLWhereClause targetFilter;

  /** Class constraint on the target alias (for class-based filtering). */
  @Nullable private final String targetClassName;

  public IndexOrderedEdgeStep(
      CommandContext ctx,
      String sourceAlias,
      String targetAlias,
      String edgeClassName,
      String linkBagFieldName,
      Index index,
      boolean orderAsc,
      EdgeTraversal edge,
      long limit,
      @Nullable MatchExecutionPlanner.MultiSourceMode multiSourceMode,
      @Nullable String reverseFieldName,
      @Nullable String sourceClassName,
      @Nullable SQLWhereClause targetFilter,
      @Nullable String targetClassName,
      boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.sourceAlias = sourceAlias;
    this.targetAlias = targetAlias;
    this.edgeClassName = edgeClassName;
    this.linkBagFieldName = linkBagFieldName;
    this.index = index;
    this.orderAsc = orderAsc;
    this.edge = edge;
    this.limit = limit;
    this.multiSourceMode = multiSourceMode;
    this.reverseFieldName = reverseFieldName;
    this.sourceClassName = sourceClassName;
    this.targetFilter = targetFilter;
    this.targetClassName = targetClassName;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    assert MatchAssertions.checkNotNull(prev, "previous step");
    if (multiSourceMode != null) {
      return multiSourceDispatch(ctx);
    }
    // Guaranteed single-source: exactly 1 upstream row (source has RID constraint).
    // Safe to use flatMap — results from the single call are already globally sorted.
    var resultSet = prev.start(ctx);
    return resultSet.flatMap(this::processUpstreamRow);
  }

  // =====================================================================
  // Single-source mode
  // =====================================================================

  private ExecutionStream processUpstreamRow(Result upstreamRow, CommandContext ctx) {
    var session = ctx.getDatabaseSession();
    var ridSet = resolveEdgeRidSet(upstreamRow, session);
    if (ridSet == null || ridSet.isEmpty()) {
      return ExecutionStream.empty();
    }

    int maxRidSetSize =
        GlobalConfiguration.QUERY_PREFILTER_MAX_RIDSET_SIZE.getValueAsInteger();
    if (ridSet.size() > maxRidSetSize) {
      return loadAllAndSort(ridSet, ctx, upstreamRow);
    }

    if (shouldUseIndexScan(ridSet.size(), session)) {
      return indexScanFiltered(ridSet, ctx, upstreamRow);
    } else {
      return loadAllAndSort(ridSet, ctx, upstreamRow);
    }
  }

  /**
   * Single-source index scan: filtered by RidSet, only matching records loaded.
   */
  private ExecutionStream indexScanFiltered(
      RidSet ridSet, CommandContext ctx, Result upstreamRow) {
    var session = ctx.getDatabaseSession();
    var indexDesc = new IndexSearchDescriptor(index);
    var filteredStep = new RidFilteredIndexValuesStep(
        indexDesc, orderAsc, ctx, profilingEnabled, ridSet);
    var indexStream = filteredStep.internalStart(ctx);

    return indexStream.map((indexResult, mapCtx) -> {
      var rid = (RID) indexResult.getProperty("rid");
      var targetRecord = loadRecord(rid, session);
      if (targetRecord == null) {
        return null;
      }
      if (!matchesTargetFilter(targetRecord, mapCtx)) {
        return null;
      }
      if (isAlreadyBoundAndDifferent(upstreamRow, targetRecord, session)) {
        return null;
      }
      return new MatchResultRow(session, upstreamRow, targetAlias, targetRecord);
    }).filter(ExecutionStream.SKIP_NULLS);
  }

  /**
   * Single-source fallback: load all targets, sort in-memory.
   */
  private ExecutionStream loadAllAndSort(
      RidSet ridSet, CommandContext ctx, Result upstreamRow) {
    var session = ctx.getDatabaseSession();
    var propertyName = index.getDefinition().getProperties().get(0);
    var loaded = new ArrayList<Result>(ridSet.size());
    for (var rid : ridSet) {
      var record = loadRecord(rid, session);
      if (record == null) {
        continue;
      }
      if (!matchesTargetFilter(record, ctx)) {
        continue;
      }
      if (isAlreadyBoundAndDifferent(upstreamRow, record, session)) {
        continue;
      }
      loaded.add(record);
    }
    loaded.sort(propertyComparator(propertyName));
    return ExecutionStream.resultIterator(
        loaded.stream()
            .map(record -> (Result) new MatchResultRow(
                session, upstreamRow, targetAlias, record))
            .iterator());
  }

  // =====================================================================
  // Multi-source mode — dispatch
  // =====================================================================

  /** Routes to the appropriate multi-source strategy based on the mode. */
  private ExecutionStream multiSourceDispatch(CommandContext ctx) {
    return switch (multiSourceMode) {
      case FILTERED_BOUND -> filteredBound(ctx);
      case FILTERED_UNBOUND -> filteredUnbound(ctx);
      case UNFILTERED_BOUND -> unfilteredBound(ctx);
      case UNFILTERED_UNBOUND -> unfilteredUnbound(ctx);
      case null -> throw new IllegalStateException("multiSourceMode is null");
    };
  }

  // ---- Mode A: FILTERED_BOUND (sourceMap + reverse lookup) ----

  /**
   * Materializes filtered upstream rows into sourceMap, then picks the
   * cheapest scan strategy (union/global/sort) with reverse edge lookup.
   */
  private ExecutionStream filteredBound(CommandContext ctx) {
    var session = ctx.getDatabaseSession();

    var sourceMap = new HashMap<RID, List<Result>>();
    int sourceCount = 0;
    var upstream = prev.start(ctx);
    while (upstream.hasNext(ctx)) {
      var row = upstream.next(ctx);
      var sourceRid = extractSourceRid(row);
      if (sourceRid != null) {
        sourceMap.computeIfAbsent(sourceRid, k -> new ArrayList<>(1)).add(row);
        sourceCount++;
      }
    }
    upstream.close(ctx);

    if (sourceMap.isEmpty()) {
      return ExecutionStream.empty();
    }

    int maxSources =
        GlobalConfiguration.QUERY_INDEX_ORDERED_MAX_SOURCES.getValueAsInteger();
    if (sourceCount > maxSources) {
      return loadAllMultiSource(sourceMap, ctx);
    }

    long indexSize = index.size(session);
    int estimatedTotalEdges = estimateTotalEdges(sourceMap, session);
    var strategy = pickMultiSourceStrategy(
        estimatedTotalEdges, indexSize, session);

    return switch (strategy) {
      case UNION_RIDSET_SCAN -> indexScanWithUnion(sourceMap, ctx);
      case GLOBAL_SCAN -> indexScanGlobal(sourceMap, ctx);
      case LOAD_ALL_SORT -> loadAllMultiSource(sourceMap, ctx);
    };
  }

  // ---- Mode B: FILTERED_UNBOUND (union RidSet, no binding) ----

  /**
   * Builds union RidSet from filtered upstream LinkBags. No sourceMap needed
   * — just bitmap check per entry. Source alias is NOT bound in results.
   */
  private ExecutionStream filteredUnbound(CommandContext ctx) {
    var session = ctx.getDatabaseSession();
    int maxRidSetSize =
        GlobalConfiguration.QUERY_PREFILTER_MAX_RIDSET_SIZE.getValueAsInteger();

    // Collect upstream source RIDs + build union RidSet simultaneously.
    // If union exceeds maxRidSetSize, stop adding to union but keep collecting
    // source RIDs for the fallback path.
    var sourceRids = new ArrayList<RID>();
    var unionRidSet = new RidSet();
    boolean ridSetOverflow = false;
    var upstream = prev.start(ctx);
    while (upstream.hasNext(ctx)) {
      var row = upstream.next(ctx);
      var sourceRid = extractSourceRid(row);
      if (sourceRid != null) {
        sourceRids.add(sourceRid);
      }
      if (!ridSetOverflow) {
        var ridSet = resolveEdgeRidSet(row, session);
        if (ridSet != null) {
          for (var rid : ridSet) {
            unionRidSet.add(rid);
          }
          if (unionRidSet.size() > maxRidSetSize) {
            ridSetOverflow = true;
            unionRidSet = new RidSet(); // release partial set
          }
        }
      }
    }
    upstream.close(ctx);

    if (sourceRids.isEmpty()) {
      return ExecutionStream.empty();
    }

    // If union overflowed, fall back to load-all from source LinkBags
    if (ridSetOverflow) {
      return loadAllFromSourcesUnbound(sourceRids, ctx);
    }

    if (unionRidSet.isEmpty()) {
      return ExecutionStream.empty();
    }

    if (!shouldUseIndexScan(unionRidSet.size(), session)) {
      return loadAllAndSortUnbound(unionRidSet, ctx);
    }

    var indexDesc = new IndexSearchDescriptor(index);
    var filteredStep = new RidFilteredIndexValuesStep(
        indexDesc, orderAsc, ctx, profilingEnabled, unionRidSet);
    var indexStream = filteredStep.internalStart(ctx);

    return indexStream.map((indexResult, mapCtx) -> {
      var rid = (RID) indexResult.getProperty("rid");
      var targetRecord = loadRecord(rid, session);
      if (targetRecord == null) {
        return null;
      }
      if (!matchesTargetFilter(targetRecord, mapCtx)) {
        return null;
      }
      var emptyUpstream = new ResultInternal(session);
      return new MatchResultRow(session, emptyUpstream, targetAlias, targetRecord);
    }).filter(ExecutionStream.SKIP_NULLS);
  }

  /** Mode B fallback: load union entries, sort, emit without source binding. */
  private ExecutionStream loadAllAndSortUnbound(
      RidSet ridSet, CommandContext ctx) {
    var session = ctx.getDatabaseSession();
    var propertyName = index.getDefinition().getProperties().get(0);
    var loaded = new ArrayList<Result>(ridSet.size());
    for (var rid : ridSet) {
      var record = loadRecord(rid, session);
      if (record != null && matchesTargetFilter(record, ctx)) {
        loaded.add(record);
      }
    }
    loaded.sort(propertyComparator(propertyName));
    return ExecutionStream.resultIterator(
        loaded.stream()
            .map(record -> {
              var emptyUpstream = new ResultInternal(session);
              return (Result) new MatchResultRow(
                  session, emptyUpstream, targetAlias, record);
            })
            .iterator());
  }

  /**
   * Mode B overflow fallback: iterate source LinkBags directly, load all
   * targets, sort, emit without source binding. Used when union RidSet
   * exceeds maxRidSetSize.
   */
  private ExecutionStream loadAllFromSourcesUnbound(
      List<RID> sourceRids, CommandContext ctx) {
    var session = ctx.getDatabaseSession();
    var propertyName = index.getDefinition().getProperties().get(0);
    var loaded = new ArrayList<Result>();

    for (var sourceRid : sourceRids) {
      EntityImpl entity;
      try {
        var rec = session.getActiveTransaction().load(sourceRid);
        if (!(rec instanceof EntityImpl e)) {
          continue;
        }
        entity = e;
      } catch (Exception e) {
        continue;
      }
      var fieldValue = entity.getPropertyInternal(linkBagFieldName);
      if (!(fieldValue instanceof LinkBag linkBag)) {
        continue;
      }
      for (RidPair pair : linkBag) {
        var record = loadRecord(pair.secondaryRid(), session);
        if (record != null && matchesTargetFilter(record, ctx)) {
          loaded.add(record);
        }
      }
    }

    loaded.sort(propertyComparator(propertyName));
    return ExecutionStream.resultIterator(
        loaded.stream()
            .map(record -> {
              var emptyUpstream = new ResultInternal(session);
              return (Result) new MatchResultRow(
                  session, emptyUpstream, targetAlias, record);
            })
            .iterator());
  }

  // ---- Mode C: UNFILTERED_BOUND (class check + lazy load) ----

  /**
   * No sourceMap, no union. Scans index globally, per hit: loads record,
   * follows reverse edge, verifies source class, loads source on-demand
   * for binding.
   */
  private ExecutionStream unfilteredBound(CommandContext ctx) {
    var session = ctx.getDatabaseSession();
    var schema = session.getMetadata().getImmutableSchemaSnapshot();
    var srcClass = schema.getClassInternal(sourceClassName);

    // Consume upstream to avoid pipeline stall, but don't collect
    var upstream = prev.start(ctx);
    upstream.close(ctx);

    if (srcClass == null) {
      return ExecutionStream.empty();
    }

    var indexDesc = new IndexSearchDescriptor(index);
    var fullScan = new RidFilteredIndexValuesStep(
        indexDesc, orderAsc, ctx, profilingEnabled, null);
    var indexStream = fullScan.internalStart(ctx);

    return indexStream.flatMap((indexResult, mapCtx) -> {
      var rid = (RID) indexResult.getProperty("rid");
      var targetRecord = loadRecord(rid, session);
      if (targetRecord == null) {
        return ExecutionStream.empty();
      }
      if (!matchesTargetFilter(targetRecord, mapCtx)) {
        return ExecutionStream.empty();
      }

      // Check ALL reverse edges — a target may link to multiple valid
      // sources of the correct class. Emit one row per valid source.
      var reverseRids = resolveReverseEdges(targetRecord);
      var results = new ArrayList<Result>();
      for (var sourceRid : reverseRids) {
        if (!srcClass.hasPolymorphicCollectionId(sourceRid.getCollectionId())) {
          continue;
        }
        var sourceRecord = loadRecord(sourceRid, session);
        if (sourceRecord == null) {
          continue;
        }
        var upstreamRow = new ResultInternal(session);
        ((ResultInternal) upstreamRow).setProperty(sourceAlias, sourceRecord);
        results.add(
            new MatchResultRow(session, upstreamRow, targetAlias, targetRecord));
      }
      return ExecutionStream.resultIterator(results.iterator());
    });
  }

  // ---- Mode D: UNFILTERED_UNBOUND (class check, no source load) ----

  /**
   * Lightest mode: scan index, per hit verify reverse edge points to correct
   * source class (no load of source, no binding). Only target alias bound.
   */
  private ExecutionStream unfilteredUnbound(CommandContext ctx) {
    var session = ctx.getDatabaseSession();
    var schema = session.getMetadata().getImmutableSchemaSnapshot();
    var srcClass = schema.getClassInternal(sourceClassName);

    var upstream = prev.start(ctx);
    upstream.close(ctx);

    if (srcClass == null) {
      return ExecutionStream.empty();
    }

    var indexDesc = new IndexSearchDescriptor(index);
    var fullScan = new RidFilteredIndexValuesStep(
        indexDesc, orderAsc, ctx, profilingEnabled, null);
    var indexStream = fullScan.internalStart(ctx);

    return indexStream.map((indexResult, mapCtx) -> {
      var rid = (RID) indexResult.getProperty("rid");
      var targetRecord = loadRecord(rid, session);
      if (targetRecord == null) {
        return null;
      }
      if (!matchesTargetFilter(targetRecord, mapCtx)) {
        return null;
      }

      // Source is not bound — just verify ANY reverse edge points to a
      // source of the correct class. Check all because the first might
      // point to a wrong-class vertex while a later one is valid.
      var reverseRids = resolveReverseEdges(targetRecord);
      boolean anyValid = false;
      for (var sourceRid : reverseRids) {
        if (srcClass.hasPolymorphicCollectionId(sourceRid.getCollectionId())) {
          anyValid = true;
          break;
        }
      }
      if (!anyValid) {
        return null;
      }

      var emptyUpstream = new ResultInternal(session);
      return new MatchResultRow(session, emptyUpstream, targetAlias, targetRecord);
    }).filter(ExecutionStream.SKIP_NULLS);
  }

  /**
   * Strategy 1: Build union RidSet from all sources' LinkBags, scan index
   * with bitmap filter. Only matching records are loaded. Per match: reverse
   * edge lookup to find and bind the upstream row.
   */
  private ExecutionStream indexScanWithUnion(
      Map<RID, List<Result>> sourceMap, CommandContext ctx) {
    var session = ctx.getDatabaseSession();

    // Build union RidSet from all sources' LinkBags.
    // If union exceeds maxRidSetSize, fall back to loadAllMultiSource.
    int maxRidSetSize =
        GlobalConfiguration.QUERY_PREFILTER_MAX_RIDSET_SIZE.getValueAsInteger();
    var unionRidSet = new RidSet();
    boolean overflow = false;
    for (var sourceRid : sourceMap.keySet()) {
      if (overflow) {
        break;
      }
      EntityImpl entity;
      try {
        var rec = session.getActiveTransaction().load(sourceRid);
        if (!(rec instanceof EntityImpl e)) {
          continue;
        }
        entity = e;
      } catch (Exception e) {
        continue;
      }
      var fieldValue = entity.getPropertyInternal(linkBagFieldName);
      if (fieldValue instanceof LinkBag linkBag) {
        for (RidPair pair : linkBag) {
          unionRidSet.add(pair.secondaryRid());
        }
        if (unionRidSet.size() > maxRidSetSize) {
          overflow = true;
        }
      }
    }

    if (overflow) {
      return loadAllMultiSource(sourceMap, ctx);
    }

    if (unionRidSet.isEmpty()) {
      return ExecutionStream.empty();
    }

    // Scan index filtered by union RidSet (seqRead per entry, bitmap check)
    var indexDesc = new IndexSearchDescriptor(index);
    var filteredStep = new RidFilteredIndexValuesStep(
        indexDesc, orderAsc, ctx, profilingEnabled, unionRidSet);
    var indexStream = filteredStep.internalStart(ctx);

    // Per match: load record, reverse edge → find upstream row(s).
    // flatMap handles shared targets (one target linked to multiple sources).
    return indexStream.flatMap(
        (indexResult, mapCtx) -> matchTargetToSources(indexResult, sourceMap, mapCtx));
  }

  /**
   * Strategy 2: Scan index without filter, load every entry, check reverse
   * edge against sourceMap. Cheaper than union when density is high and LIMIT
   * is small (avoids union build cost).
   */
  private ExecutionStream indexScanGlobal(
      Map<RID, List<Result>> sourceMap, CommandContext ctx) {
    var indexDesc = new IndexSearchDescriptor(index);
    var fullScan = new RidFilteredIndexValuesStep(
        indexDesc, orderAsc, ctx, profilingEnabled, null);
    var indexStream = fullScan.internalStart(ctx);

    return indexStream.flatMap(
        (indexResult, mapCtx) -> matchTargetToSources(indexResult, sourceMap, mapCtx));
  }

  /**
   * Strategy 3: Iterate all sources' LinkBags, load all targets, sort
   * globally, emit in order. Fallback when index scan is too expensive.
   */
  private ExecutionStream loadAllMultiSource(
      Map<RID, List<Result>> sourceMap, CommandContext ctx) {
    var session = ctx.getDatabaseSession();
    var propertyName = index.getDefinition().getProperties().get(0);

    record Hit(Result record, Result upstream) {
    }
    var hits = new ArrayList<Hit>();

    for (var entry : sourceMap.entrySet()) {
      var sourceRid = entry.getKey();
      var upstreamRows = entry.getValue();

      EntityImpl entity;
      try {
        var rec = session.getActiveTransaction().load(sourceRid);
        if (!(rec instanceof EntityImpl e)) {
          continue;
        }
        entity = e;
      } catch (Exception e) {
        continue;
      }

      var fieldValue = entity.getPropertyInternal(linkBagFieldName);
      if (!(fieldValue instanceof LinkBag linkBag)) {
        continue;
      }

      for (RidPair pair : linkBag) {
        var record = loadRecord(pair.secondaryRid(), session);
        if (record == null) {
          continue;
        }
        if (!matchesTargetFilter(record, ctx)) {
          continue;
        }
        for (var upstreamRow : upstreamRows) {
          if (!isAlreadyBoundAndDifferent(upstreamRow, record, session)) {
            hits.add(new Hit(record, upstreamRow));
          }
        }
      }
    }

    hits.sort((a, b) -> propertyComparator(propertyName)
        .compare(a.record, b.record));

    return ExecutionStream.resultIterator(
        hits.stream()
            .map(h -> (Result) new MatchResultRow(
                session, h.upstream, targetAlias, h.record))
            .iterator());
  }

  // =====================================================================
  // Cost-based heuristic
  // =====================================================================

  /**
   * Single-source: compare RidSet-filtered index scan vs load-all-and-sort.
   */
  private boolean shouldUseIndexScan(
      int linkBagSize, DatabaseSessionEmbedded session) {
    var costs = IndexOrderedCostModel.computeCosts(
        linkBagSize, index.size(session), limit,
        index.getHistogram(session), orderAsc);
    if (costs == null) {
      return false;
    }
    return costs.costUnionScan() < costs.costLoadSort();
  }

  /**
   * Multi-source: pick the cheapest of three strategies.
   *
   * <ul>
   *   <li><b>Union RidSet scan</b> — build union from all LinkBags, scan
   *       index with bitmap filter (seqRead per entry), load only matches,
   *       reverse edge lookup per match. Best at low density.</li>
   *   <li><b>Global scan</b> — scan index without filter, load every entry
   *       (randRead per entry), check reverse edge. Best at high density +
   *       small LIMIT (avoids union build cost).</li>
   *   <li><b>Load all + sort</b> — iterate all LinkBags, load everything,
   *       sort in-memory. Best when index scan is too expensive.</li>
   * </ul>
   */
  private IndexOrderedCostModel.MultiSourceStrategy pickMultiSourceStrategy(
      int totalEdges, long indexSize, DatabaseSessionEmbedded session) {
    return IndexOrderedCostModel.pickMultiSourceStrategy(
        totalEdges, indexSize, limit,
        index.getHistogram(session), orderAsc);
  }

  // Cost model logic lives in IndexOrderedCostModel.

  /**
   * For a single index hit, loads the target record, follows reverse edges to
   * find ALL matching source vertices, and emits one MatchResultRow per
   * (source, target) pair. Handles shared targets correctly.
   */
  private ExecutionStream matchTargetToSources(
      Result indexResult,
      Map<RID, List<Result>> sourceMap,
      CommandContext ctx) {
    var session = ctx.getDatabaseSession();
    var rid = (RID) indexResult.getProperty("rid");
    var targetRecord = loadRecord(rid, session);
    if (targetRecord == null) {
      return ExecutionStream.empty();
    }
    if (!matchesTargetFilter(targetRecord, ctx)) {
      return ExecutionStream.empty();
    }

    var sourceRids = resolveReverseEdges(targetRecord);
    var results = new ArrayList<Result>();
    for (var sourceRid : sourceRids) {
      var upstreamRows = sourceMap.get(sourceRid);
      if (upstreamRows == null) {
        continue;
      }
      for (var upstreamRow : upstreamRows) {
        if (!isAlreadyBoundAndDifferent(upstreamRow, targetRecord, session)) {
          results.add(
              new MatchResultRow(session, upstreamRow, targetAlias, targetRecord));
        }
      }
    }
    return ExecutionStream.resultIterator(results.iterator());
  }

  // =====================================================================
  // Helpers
  // =====================================================================

  /** Extract the source vertex RID from an upstream row. */
  @Nullable private RID extractSourceRid(Result upstreamRow) {
    var sourceRecord = upstreamRow.getProperty(sourceAlias);
    if (sourceRecord instanceof Result result) {
      return result.getIdentity();
    } else if (sourceRecord instanceof RID rid) {
      return rid;
    }
    return null;
  }

  /**
   * Follow the reverse edge on a target record to find the source vertex RID.
   * E.g., for a Message record, read {@code out_HAS_CREATOR} to get the
   * Person RID.
   */
  /**
   * Follow the reverse edge on a target record to find ALL source vertex RIDs.
   * Returns all RIDs from the reverse LinkBag (handles shared targets where
   * a record is linked to multiple sources).
   */
  private List<RID> resolveReverseEdges(Result targetRecord) {
    if (reverseFieldName == null) {
      return List.of();
    }
    var entity = targetRecord.asEntityOrNull();
    if (!(entity instanceof EntityImpl impl)) {
      return List.of();
    }
    var fieldValue = impl.getPropertyInternal(reverseFieldName);
    if (fieldValue instanceof LinkBag linkBag) {
      var rids = new ArrayList<RID>(linkBag.size());
      for (RidPair pair : linkBag) {
        rids.add(pair.secondaryRid());
      }
      return rids;
    } else if (fieldValue instanceof RID rid) {
      return List.of(rid);
    }
    return List.of();
  }

  /**
   * Estimate total edges for multi-source cost model by sampling up to 5
   * source vertices' LinkBag sizes, then extrapolating to all sources.
   * Falls back to {@code sourceCount × defaultFanOut} if sampling fails.
   */
  private int estimateTotalEdges(
      Map<RID, ?> sourceMap, DatabaseSessionEmbedded session) {
    int sampleSize = Math.min(sourceMap.size(), 5);
    int totalSampled = 0;
    int sampled = 0;
    for (var sourceRid : sourceMap.keySet()) {
      if (sampled >= sampleSize) {
        break;
      }
      try {
        var rec = session.getActiveTransaction().load(sourceRid);
        if (!(rec instanceof EntityImpl entity)) {
          continue;
        }
        var fieldValue = entity.getPropertyInternal(linkBagFieldName);
        if (fieldValue instanceof LinkBag linkBag) {
          totalSampled += linkBag.size();
          sampled++;
        }
      } catch (Exception e) {
        // skip unloadable sources
      }
    }
    int avgPerSource = sampled > 0
        ? totalSampled / sampled
        : GlobalConfiguration.QUERY_STATS_DEFAULT_FAN_OUT.getValueAsInteger();
    return (int) Math.min(
        (long) sourceMap.size() * avgPerSource, Integer.MAX_VALUE);
  }

  @Nullable private RidSet resolveEdgeRidSet(
      Result upstreamRow, DatabaseSessionEmbedded session) {
    var sourceRid = extractSourceRid(upstreamRow);
    if (sourceRid == null) {
      return null;
    }

    EntityImpl entity;
    try {
      var rec = session.getActiveTransaction().load(sourceRid);
      if (!(rec instanceof EntityImpl e)) {
        return null;
      }
      entity = e;
    } catch (Exception e) {
      return null;
    }

    var fieldValue = entity.getPropertyInternal(linkBagFieldName);
    if (!(fieldValue instanceof LinkBag linkBag)) {
      return null;
    }

    var ridSet = new RidSet();
    for (RidPair pair : linkBag) {
      ridSet.add(pair.secondaryRid());
    }
    return ridSet;
  }

  @Nullable private Result loadRecord(
      RID rid, DatabaseSessionEmbedded session) {
    try {
      var rec = session.getActiveTransaction().load(rid);
      if (rec == null) {
        return null;
      }
      return new ResultInternal(session, rec);
    } catch (Exception e) {
      return null;
    }
  }

  /** Consistency check: if target alias was already bound, verify match. */
  private boolean isAlreadyBoundAndDifferent(
      Result upstreamRow, Result targetRecord,
      DatabaseSessionEmbedded session) {
    var prevValue = ResultInternal.toResult(
        upstreamRow.getProperty(targetAlias), session);
    return prevValue != null && !Objects.equals(targetRecord, prevValue);
  }

  /**
   * Checks if a target record passes the WHERE filter and class constraint.
   * Returns true if the record should be included, false if filtered out.
   */
  private boolean matchesTargetFilter(Result targetRecord, CommandContext ctx) {
    if (targetFilter != null
        && !targetFilter.matchesFilters(targetRecord, ctx)) {
      return false;
    }
    if (targetClassName != null) {
      var entity = targetRecord.asEntityOrNull();
      if (entity == null) {
        return false;
      }
      var schemaClass = entity.getSchemaClass();
      if (schemaClass == null) {
        return false;
      }
      if (!schemaClass.getName().equals(targetClassName)
          && !schemaClass.isSubClassOf(targetClassName)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Comparator that sorts by a property value, with NULLs always first
   * (matching the B-tree index NULL ordering used by the index scan path).
   */
  private Comparator<Result> propertyComparator(String propertyName) {
    return (a, b) -> {
      var va = (Comparable) a.getProperty(propertyName);
      var vb = (Comparable) b.getProperty(propertyName);
      if (va == null && vb == null) {
        return 0;
      }
      // NULLs first — consistent with index scan NULL ordering
      if (va == null) {
        return -1;
      }
      if (vb == null) {
        return 1;
      }
      return orderAsc ? va.compareTo(vb) : vb.compareTo(va);
    };
  }

  @Override
  public boolean canBeCached() {
    return false;
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    var direction = orderAsc ? "ASC" : "DESC";
    var mode = multiSourceMode != null ? " (" + multiSourceMode + ")" : "";
    return spaces + "+ INDEX ORDERED MATCH " + direction + mode + "\n"
        + spaces + "  {" + sourceAlias + "}." + edgeClassName
        + "{" + targetAlias + "} via " + index.getName();
  }

  @Override
  public IndexOrderedEdgeStep copy(CommandContext ctx) {
    return new IndexOrderedEdgeStep(
        ctx, sourceAlias, targetAlias, edgeClassName, linkBagFieldName,
        index, orderAsc, edge.copy(), limit, multiSourceMode,
        reverseFieldName, sourceClassName, targetFilter, targetClassName,
        profilingEnabled);
  }
}
