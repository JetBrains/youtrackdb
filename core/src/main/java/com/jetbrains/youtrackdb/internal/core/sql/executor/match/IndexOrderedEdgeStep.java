package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.index.engine.EquiDepthHistogram;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.executor.AbstractExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.CostModel;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ExecutionStepInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.IndexSearchDescriptor;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.RidFilteredIndexValuesStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.RidSet;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
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
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    assert MatchAssertions.checkNotNull(prev, "previous step");
    if (multiSourceMode != null) {
      return multiSourceDispatch(ctx);
    }
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
      // RidSet too large for index scan — load all and sort instead
      return loadAllAndSort(ridSet, session, upstreamRow);
    }

    if (shouldUseIndexScan(ridSet.size(), session)) {
      return indexScanFiltered(ridSet, ctx, upstreamRow);
    } else {
      return loadAllAndSort(ridSet, session, upstreamRow);
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
      if (isAlreadyBoundAndDifferent(upstreamRow, targetRecord, session)) {
        return null;
      }
      return new MatchResultRow(session, upstreamRow, targetAlias, targetRecord);
    });
  }

  /**
   * Single-source fallback: load all targets, sort in-memory.
   */
  private ExecutionStream loadAllAndSort(
      RidSet ridSet, DatabaseSessionEmbedded session, Result upstreamRow) {
    var propertyName = index.getDefinition().getProperties().get(0);
    var loaded = new ArrayList<Result>(ridSet.size());
    for (var rid : ridSet) {
      var record = loadRecord(rid, session);
      if (record == null) {
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
      return loadAllMultiSource(sourceMap, session);
    }

    long indexSize = index.size(session);
    int estimatedTotalEdges = estimateTotalEdges(sourceMap.size(), session);
    var strategy = pickMultiSourceStrategy(
        estimatedTotalEdges, indexSize, session);

    return switch (strategy) {
      case UNION_RIDSET_SCAN -> indexScanWithUnion(sourceMap, ctx);
      case GLOBAL_SCAN -> indexScanGlobal(sourceMap, ctx);
      case LOAD_ALL_SORT -> loadAllMultiSource(sourceMap, session);
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
      return loadAllFromSourcesUnbound(sourceRids, session);
    }

    if (unionRidSet.isEmpty()) {
      return ExecutionStream.empty();
    }

    if (!shouldUseIndexScan(unionRidSet.size(), session)) {
      return loadAllAndSortUnbound(unionRidSet, session);
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
      var emptyUpstream = new ResultInternal(session);
      return new MatchResultRow(session, emptyUpstream, targetAlias, targetRecord);
    });
  }

  /** Mode B fallback: load union entries, sort, emit without source binding. */
  private ExecutionStream loadAllAndSortUnbound(
      RidSet ridSet, DatabaseSessionEmbedded session) {
    var propertyName = index.getDefinition().getProperties().get(0);
    var loaded = new ArrayList<Result>(ridSet.size());
    for (var rid : ridSet) {
      var record = loadRecord(rid, session);
      if (record != null) {
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
      List<RID> sourceRids, DatabaseSessionEmbedded session) {
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
        if (record != null) {
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

    return indexStream.map((indexResult, mapCtx) -> {
      var rid = (RID) indexResult.getProperty("rid");
      var targetRecord = loadRecord(rid, session);
      if (targetRecord == null) {
        return null;
      }

      var sourceRid = resolveFirstReverseEdge(targetRecord);
      if (sourceRid == null) {
        return null;
      }
      if (!srcClass.hasPolymorphicCollectionId(sourceRid.getCollectionId())) {
        return null;
      }

      // Lazy load source for binding
      var sourceRecord = loadRecord(sourceRid, session);
      if (sourceRecord == null) {
        return null;
      }
      var upstreamRow = new ResultInternal(session);
      ((ResultInternal) upstreamRow).setProperty(sourceAlias, sourceRecord);
      return new MatchResultRow(session, upstreamRow, targetAlias, targetRecord);
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

      var sourceRid = resolveFirstReverseEdge(targetRecord);
      if (sourceRid == null) {
        return null;
      }
      if (!srcClass.hasPolymorphicCollectionId(sourceRid.getCollectionId())) {
        return null;
      }

      var emptyUpstream = new ResultInternal(session);
      return new MatchResultRow(session, emptyUpstream, targetAlias, targetRecord);
    });
  }

  private enum MultiSourceStrategy {
    UNION_RIDSET_SCAN, GLOBAL_SCAN, LOAD_ALL_SORT
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
      return loadAllMultiSource(sourceMap, session);
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
        (indexResult, mapCtx) -> matchTargetToSources(indexResult, sourceMap, session));
  }

  /**
   * Strategy 2: Scan index without filter, load every entry, check reverse
   * edge against sourceMap. Cheaper than union when density is high and LIMIT
   * is small (avoids union build cost).
   */
  private ExecutionStream indexScanGlobal(
      Map<RID, List<Result>> sourceMap, CommandContext ctx) {
    var session = ctx.getDatabaseSession();
    var indexDesc = new IndexSearchDescriptor(index);
    var fullScan = new RidFilteredIndexValuesStep(
        indexDesc, orderAsc, ctx, profilingEnabled, null);
    var indexStream = fullScan.internalStart(ctx);

    return indexStream.flatMap(
        (indexResult, mapCtx) -> matchTargetToSources(indexResult, sourceMap, session));
  }

  /**
   * Strategy 3: Iterate all sources' LinkBags, load all targets, sort
   * globally, emit in order. Fallback when index scan is too expensive.
   */
  private ExecutionStream loadAllMultiSource(
      Map<RID, List<Result>> sourceMap, DatabaseSessionEmbedded session) {
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
    var costs = computeCosts(linkBagSize, index.size(session), session);
    if (costs == null) {
      return false;
    }
    return costs.costUnionScan < costs.costLoadSort;
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
  private MultiSourceStrategy pickMultiSourceStrategy(
      int totalEdges, long indexSize, DatabaseSessionEmbedded session) {
    var costs = computeCosts(totalEdges, indexSize, session);
    if (costs == null) {
      return MultiSourceStrategy.LOAD_ALL_SORT;
    }

    double costBias =
        GlobalConfiguration.QUERY_INDEX_ORDERED_COST_BIAS.getValueAsDouble();

    // Strategy 1: Union RidSet scan
    // Build union: totalEdges × cpu
    // Scan index: expectedScanLength × seqRead (bitmap check, no load)
    // Load matches + reverse lookup: k × (randRead + cpu)
    double costUnion = totalEdges * costs.cpu
        + costs.seekCost
        + costs.expectedScanLength * (costs.seqRead + costs.cpu)
        + costs.k * (costs.randRead + costs.cpu);
    costUnion *= costBias;

    // Strategy 2: Global scan (no union, load every entry)
    // Scan: expectedScanLength × randRead (load each record + check reverse)
    double costGlobal = costs.seekCost
        + costs.expectedScanLength * (costs.randRead + costs.cpu);
    costGlobal *= costBias;

    // Strategy 3: Load all + sort
    double costSort = costs.costLoadSort;

    if (costSort <= costUnion && costSort <= costGlobal) {
      return MultiSourceStrategy.LOAD_ALL_SORT;
    }
    if (costUnion <= costGlobal) {
      return MultiSourceStrategy.UNION_RIDSET_SCAN;
    }
    return MultiSourceStrategy.GLOBAL_SCAN;
  }

  /** Shared cost computation for both single-source and multi-source. */
  private record CostEstimate(
      double expectedScanLength,
      long k,
      double seqRead,
      double randRead,
      double cpu,
      double seekCost,
      double costUnionScan,
      double costLoadSort) {
  }

  @Nullable private CostEstimate computeCosts(
      int linkBagSize, long indexSize, DatabaseSessionEmbedded session) {
    int minLinkBag =
        GlobalConfiguration.QUERY_INDEX_ORDERED_MIN_LINKBAG.getValueAsInteger();
    if (linkBagSize < minLinkBag || indexSize <= 0) {
      return null;
    }

    long k = limit > 0 ? Math.min(limit, linkBagSize) : linkBagSize;
    double density = Math.min((double) linkBagSize / indexSize, 1.0);
    if (density <= 0.0) {
      return null;
    }
    double expectedScanLength = k / density;

    var histogram = index.getHistogram(session);
    if (histogram != null && histogram.nonNullCount() > 0) {
      expectedScanLength = applyHistogramSkew(
          expectedScanLength, indexSize, histogram);
    }

    long maxScan =
        GlobalConfiguration.QUERY_INDEX_ORDERED_MAX_SCAN.getValueAsLong();
    if (expectedScanLength > maxScan) {
      return null;
    }

    double seqRead = CostModel.seqPageReadCost();
    double randRead = CostModel.randomPageReadCost();
    double cpu = CostModel.perRowCpuCost();
    double seekCost = CostModel.indexSeekCost();
    double costBias =
        GlobalConfiguration.QUERY_INDEX_ORDERED_COST_BIAS.getValueAsDouble();

    // Union RidSet scan: build RidSet + scan (seq) + load matches
    double costUnionScan = linkBagSize * cpu
        + seekCost
        + expectedScanLength * (seqRead + cpu)
        + k * randRead;
    costUnionScan *= costBias;

    // Load all + sort
    double sortFactor = (limit > 0 && limit < linkBagSize)
        ? log2(limit) : log2(linkBagSize);
    double costLoadSort = (double) linkBagSize * randRead
        + (double) linkBagSize * cpu
        + (double) linkBagSize * sortFactor * cpu;

    return new CostEstimate(
        expectedScanLength, k, seqRead, randRead, cpu, seekCost,
        costUnionScan, costLoadSort);
  }

  private double applyHistogramSkew(
      double expectedScanLength, long indexSize,
      EquiDepthHistogram histogram) {
    double targetFraction = Math.min(expectedScanLength / indexSize, 1.0);
    int bucketsToScan = Math.max(1,
        (int) Math.ceil(targetFraction * histogram.bucketCount()));
    bucketsToScan = Math.min(bucketsToScan, histogram.bucketCount());

    long scanRegionEntries;
    if (orderAsc) {
      scanRegionEntries = sumFrequencies(
          histogram.frequencies(), 0, bucketsToScan);
    } else {
      int start = histogram.bucketCount() - bucketsToScan;
      scanRegionEntries = sumFrequencies(
          histogram.frequencies(), Math.max(0, start), histogram.bucketCount());
    }

    double uniformExpected = targetFraction * histogram.nonNullCount();
    if (uniformExpected <= 0) {
      return expectedScanLength;
    }

    double skew = scanRegionEntries / uniformExpected;
    skew = Math.max(0.5, Math.min(3.0, skew));
    return expectedScanLength * skew;
  }

  /**
   * For a single index hit, loads the target record, follows reverse edges to
   * find ALL matching source vertices, and emits one MatchResultRow per
   * (source, target) pair. Handles shared targets correctly.
   */
  private ExecutionStream matchTargetToSources(
      Result indexResult,
      Map<RID, List<Result>> sourceMap,
      DatabaseSessionEmbedded session) {
    var rid = (RID) indexResult.getProperty("rid");
    var targetRecord = loadRecord(rid, session);
    if (targetRecord == null) {
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
   * Follow the reverse edge to find the FIRST source vertex RID only.
   * Cheaper than {@link #resolveReverseEdges} — used for class-check modes
   * (C, D) where we don't need all sources, just one for verification.
   */
  @Nullable private RID resolveFirstReverseEdge(Result targetRecord) {
    if (reverseFieldName == null) {
      return null;
    }
    var entity = targetRecord.asEntityOrNull();
    if (!(entity instanceof EntityImpl impl)) {
      return null;
    }
    var fieldValue = impl.getPropertyInternal(reverseFieldName);
    if (fieldValue instanceof LinkBag linkBag) {
      var iter = linkBag.iterator();
      if (iter.hasNext()) {
        return iter.next().secondaryRid();
      }
    } else if (fieldValue instanceof RID rid) {
      return rid;
    }
    return null;
  }

  /**
   * Estimate total edges for multi-source cost model. Uses a simple
   * heuristic: sourceCount × (indexSize / approximateTargetClassCount).
   * Falls back to sourceCount × defaultFanOut.
   */
  private int estimateTotalEdges(int sourceCount, DatabaseSessionEmbedded session) {
    long indexSize = index.size(session);
    if (indexSize <= 0) {
      return sourceCount
          * GlobalConfiguration.QUERY_STATS_DEFAULT_FAN_OUT.getValueAsInteger();
    }
    // Rough estimate: each source has indexSize/totalSources edges on average
    // Cap to avoid overflow
    long estimate = Math.min(
        (long) sourceCount * indexSize / Math.max(sourceCount, 1),
        Integer.MAX_VALUE);
    return (int) estimate;
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

  private static double log2(double x) {
    return x <= 1.0 ? 0.0 : Math.log(x) / Math.log(2.0);
  }

  private static long sumFrequencies(long[] frequencies, int from, int to) {
    long sum = 0;
    for (int i = from; i < to; i++) {
      sum += Math.max(frequencies[i], 0);
    }
    return sum;
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
        reverseFieldName, sourceClassName, profilingEnabled);
  }
}
