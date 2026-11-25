package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.index.engine.EquiDepthHistogram;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.ResolvedOrderByNullsPlacement;
import com.jetbrains.youtrackdb.internal.core.sql.executor.AbstractExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ExecutionStepInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.FetchFromIndexStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.IndexSearchDescriptor;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.RidFilteredIndexValuesStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.RidSet;
import com.jetbrains.youtrackdb.internal.core.sql.executor.TraversalPreFilterHelper;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLLimit;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrderByItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSkip;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.RidPair;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

  private static final Logger logger =
      LoggerFactory.getLogger(IndexOrderedEdgeStep.class);

  private final String sourceAlias;
  private final String targetAlias;
  private final String edgeClassName;
  private final String linkBagFieldName;
  private final Index index;
  private final boolean orderAsc;
  private final SQLOrderByItem comparisonItem;
  private final ResolvedOrderByNullsPlacement nullsPlacement;
  @Nullable private final SQLSkip skipClause;
  @Nullable private final SQLLimit limitClause;

  /** Current SKIP + LIMIT, or -1 for an unbounded execution. Never copied. */
  private long rowTarget = -1;

  /** Bind-independent lookup built from this edge item's own target filter. */
  @Nullable private IndexSearchDescriptor targetIndexDescriptor;

  /** Scheduled traversal metadata. Runtime caches are reset for every execution. */
  @Nullable private EdgeTraversal plannedTraversal;
  @Nullable private EdgeTraversal executionTraversal;

  /** Multi-source execution mode, or null for single-source. */
  @Nullable private final IndexOrderedPlanner.MultiSourceMode multiSourceMode;

  /** Reverse LinkBag field on target for reverse edge lookup (multi-source). */
  @Nullable private final String reverseFieldName;

  /** Source vertex class name for class-check modes (UNFILTERED_BOUND/UNBOUND). */
  @Nullable private final String sourceClassName;

  /** WHERE filter on the target alias (e.g., creationDate < :maxDate). */
  @Nullable private final SQLWhereClause targetFilter;

  /**
   * Class constraint on the target alias (for class-based filtering). Never
   * null: {@link IndexOrderedPlanner} rejects the candidate when the target
   * alias has no resolvable class, so this step is only built with one.
   */
  @Nonnull
  private final String targetClassName;

  /**
   * When true, the traversal is .inE()/.outE() and we want edge record RIDs
   * (primaryRid) from LinkBag pairs instead of opposite vertex RIDs (secondaryRid).
   */
  private final boolean edgeTraversal;

  /**
   * Number of MATCH edges scheduled after this step's target alias.
   * Used by the cost model to estimate downstream traversal work that
   * index scan + LIMIT avoids (only K rows go downstream vs all N).
   */
  private final int downstreamEdgeCount;

  /**
   * When true, ORDER BY already ends in {@code @rid} and equal property keys must keep
   * index RID order. See {@link #hasPendingIndexChanges}.
   */
  private final boolean ridTieBreakAccepted;

  /**
   * Runtime strategy chosen on the last {@link #internalStart} of this step.
   * Null until the step runs — EXPLAIN before execution will not show it;
   * PROFILE / post-query {@link #prettyPrint} will.
   */
  @Nullable private volatile RuntimePath chosenRuntimePath;

  /**
   * Entry budget the last budgeted scan was given, or {@code -1} when none ran.
   * Exposed for testing through {@link #lastScanBudget()}.
   */
  private volatile long lastScanBudget = -1;

  /**
   * Index entries the last budgeted scan consumed, or {@code -1} when none ran.
   * Exposed for testing through {@link #lastScanConsumedEntries()}.
   */
  private volatile long lastScanConsumedEntries = -1;

  // Execution-local observations retain only counts and completion state, never candidate records.
  private EqualityObservation lastEqualityObservation;
  private boolean multiEqualityEligible;
  private int lastEqualitySourceEdges = -1;
  private long lastMultiSourceLoadCalls;
  private long multiSourcePrefilterBudget = Long.MAX_VALUE;

  /** Target load calls made by the eager multi-source LinkBag fallback, excluding scan work. */
  long lastMultiSourceLoadCalls() {
    return lastMultiSourceLoadCalls;
  }

  int lastEqualitySourceEdges() {
    return lastEqualitySourceEdges;
  }

  private int lastOrderedBufferedRows;
  // Cache admission only after runtime singleton verification, and only with positive LIMIT.
  private boolean singleSourceLoad;
  private boolean loadPrefilterResolved;
  private RidSet loadPrefilter;
  private RidSet completedMembers;
  private Map<RID, Integer> completedDuplicates;
  private long lastCandidateCount = -1;
  private double lastCandidateLoadPrice;
  private long lastCandidateLoads;
  private long lastCandidateScanEntries;
  private long lastCandidateScanLoads;
  private int lastCandidateScanBuffered;
  private boolean lastCandidateScanExhausted;
  private double lastCandidateScanWork;

  record CandidateScanObservation(
      long entries, long loads, int buffered, boolean exhausted, double work) {
  }

  CandidateScanObservation lastCandidateScanObservation() {
    return new CandidateScanObservation(lastCandidateScanEntries, lastCandidateScanLoads,
        lastCandidateScanBuffered, lastCandidateScanExhausted, lastCandidateScanWork);
  }

  long lastCandidateCount() {
    return lastCandidateCount;
  }

  /** Remaining record-read and sort price, excluding all paid setup and intersection probes. */
  double lastCandidateLoadPrice() {
    return lastCandidateLoadPrice;
  }

  long lastCandidateLoads() {
    return lastCandidateLoads;
  }

  record EqualityObservation(long entries, boolean complete, boolean requiresLinkBagLoad) {
  }

  EqualityObservation lastEqualityObservation() {
    return lastEqualityObservation;
  }

  int lastOrderedBufferedRows() {
    return lastOrderedBufferedRows;
  }

  /**
   * Which physical strategy {@link IndexOrderedEdgeStep} actually ran.
   * Distinct from plan-time {@code multiSourceMode}: that selects the MATCH
   * binding shape; this records index scan vs local load/sort after the cost
   * model runs.
   */
  public enum RuntimePath {
    /** Single-source RidSet-filtered B-tree scan ({@code indexScanFiltered}). */
    INDEX_SCAN("index-scan"),
    /** Candidate-side lookup with one result per matching edge. */
    EQUALITY_FETCH("equality-fetch"),
    /** Final load of the admitted prefilter intersected with completed source membership. */
    CANDIDATE_LOAD("candidate-load"),
    /** Candidate-filtered ordering scan with a buffered prefix and lazy continuation, or EOF. */
    CANDIDATE_SCAN("candidate-scan"),
    /** The admitted intersection contains no linked target RID. */
    CANDIDATE_EMPTY("candidate-empty"),
    /** Single-source load LinkBag + local sort ({@code loadSortFromLinkBag}). */
    LOAD_SORT("load-sort"),
    /** Single-source load LinkBag unsorted ({@code loadFromLinkBag}). */
    LOAD_UNSORTED("load-unsorted"),
    /** Multi-source union RidSet + filtered index scan. */
    UNION_SCAN("union-scan"),
    /** Multi-source global index scan (no RidSet filter). */
    GLOBAL_SCAN("global-scan"),
    /** Multi-source / unbound fallback: stream LinkBags, OrderByStep sorts. */
    LOAD_UNSORTED_MULTI("load-unsorted-multi"),
    /**
     * Ordered scan spent its runtime budget without enough rows and fell back
     * to unsorted load + sort. Distinct from {@link #LOAD_UNSORTED_MULTI},
     * which is chosen before any scanning.
     */
    SCAN_BUDGET_BAILOUT("scan-budget-bailout");

    private final String label;

    RuntimePath(String label) {
      this.label = label;
    }

    String label() {
      return label;
    }
  }

  /** Last runtime path chosen by this step, or null if not started yet. */
  @Nullable public RuntimePath getChosenRuntimePath() {
    return chosenRuntimePath;
  }

  /** Entry budget given to the last budgeted scan, or {@code -1} when none ran. */
  public long lastScanBudget() {
    return lastScanBudget;
  }

  /** Index entries the last budgeted scan consumed, or {@code -1} when none ran. */
  public long lastScanConsumedEntries() {
    return lastScanConsumedEntries;
  }

  /** Clears observations before this step runs again. */
  @Override
  public void reset() {
    chosenRuntimePath = null;
    rowTarget = -1;
    lastScanBudget = -1;
    lastScanConsumedEntries = -1;
    lastEqualityObservation = null;
    lastEqualitySourceEdges = -1;
    lastMultiSourceLoadCalls = 0;
    multiSourcePrefilterBudget = Long.MAX_VALUE;
    lastOrderedBufferedRows = 0;
    singleSourceLoad = false;
    loadPrefilterResolved = false;
    loadPrefilter = null;
    completedMembers = null;
    completedDuplicates = null;
    lastCandidateCount = -1;
    lastCandidateLoadPrice = 0;
    lastCandidateLoads = 0;
    lastCandidateScanEntries = 0;
    lastCandidateScanLoads = 0;
    lastCandidateScanBuffered = 0;
    lastCandidateScanExhausted = false;
    lastCandidateScanWork = 0;
    executionTraversal = plannedTraversal == null ? null : plannedTraversal.copy();
  }

  public IndexOrderedEdgeStep(
      CommandContext ctx,
      String sourceAlias,
      String targetAlias,
      String edgeClassName,
      String linkBagFieldName,
      Index index,
      boolean orderAsc,
      SQLOrderByItem comparisonItem,
      ResolvedOrderByNullsPlacement nullsPlacement,
      @Nullable SQLSkip skipClause,
      @Nullable SQLLimit limitClause,
      @Nullable IndexOrderedPlanner.MultiSourceMode multiSourceMode,
      @Nullable String reverseFieldName,
      @Nullable String sourceClassName,
      @Nullable SQLWhereClause targetFilter,
      @Nonnull String targetClassName,
      boolean edgeTraversal,
      int downstreamEdgeCount,
      boolean ridTieBreakAccepted,
      boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.sourceAlias = sourceAlias;
    this.targetAlias = targetAlias;
    this.edgeClassName = edgeClassName;
    this.linkBagFieldName = linkBagFieldName;
    this.index = index;
    this.orderAsc = orderAsc;
    this.comparisonItem = comparisonItem;
    this.nullsPlacement = nullsPlacement;
    this.skipClause = skipClause;
    this.limitClause = limitClause;
    this.multiSourceMode = multiSourceMode;
    this.reverseFieldName = reverseFieldName;
    this.sourceClassName = sourceClassName;
    this.targetFilter = targetFilter;
    this.targetClassName = targetClassName;
    this.edgeTraversal = edgeTraversal;
    this.downstreamEdgeCount = downstreamEdgeCount;
    this.ridTieBreakAccepted = ridTieBreakAccepted;
  }

  /** Keeps the scheduled forecast and root lineage for the ordinary MATCH prefilter policy. */
  IndexOrderedEdgeStep withTraversal(EdgeTraversal traversal, CommandContext context) {
    plannedTraversal = traversal.copy();
    multiEqualityEligible = IndexOrderedPlanner.indexedEquality(
        targetFilter, targetClassName, context) != null;
    if (targetFilter != null) {
      targetIndexDescriptor = TraversalPreFilterHelper.findIndexForFilter(
          targetFilter, targetClassName, context);
    }
    // Only this target filter is safe. Scheduled intersections can constrain optional aliases.
    plannedTraversal.setIntersectionDescriptor(
        MatchExecutionPlanner.targetFilterDescriptor(targetFilter, targetClassName, context));
    return this;
  }

  @Nullable IndexSearchDescriptor targetIndexDescriptor() {
    return targetIndexDescriptor;
  }

  @Nullable EdgeTraversal executionTraversal() {
    return executionTraversal;
  }

  /**
   * Signals that this step's output is already in the plan's ORDER BY sequence, so the downstream
   * {@code OrderByStep} can stream it. Called from the paths that hand rows straight out of an
   * ordered index scan: the scan walks the composite {@code (property, rid)} key, so equal property
   * values come back in scan-direction identifier order, which is what an accepted trailing record
   * identifier item asks for.
   *
   * <p>A pending index change inside the running transaction withdraws the signal when such an item
   * was accepted. The transaction-local entries are merged into the scan by key alone, so two
   * entries of one key can arrive in either identifier order. This is checked per execution rather
   * than per plan, because the plan outlives the transaction in the plan cache.
   */
  private void signalIndexOrderedOutput(CommandContext ctx) {
    var ridOrderHolds = !ridTieBreakAccepted || !hasPendingIndexChanges(ctx);
    ctx.setSystemVariable(
        CommandContext.VAR_INDEX_ORDERED_PRE_SORTED, Boolean.valueOf(ridOrderHolds));
  }

  /** Signals pre-sorted output after the local sort has produced the complete accepted order. */
  private void signalLoadSortedOutput(CommandContext ctx) {
    ctx.setSystemVariable(CommandContext.VAR_INDEX_ORDERED_PRE_SORTED, Boolean.TRUE);
  }

  /**
   * Whether the running transaction holds an uncommitted record change, which is what can put the
   * scan out of identifier order.
   *
   * <p>The test is any record change rather than a change to this index, because the index entry of
   * an uncommitted record is written under a provisional identifier. The scan then hands that entry
   * back at the position of the provisional identifier while the row carries the identifier the
   * record has by then, so the entry can arrive anywhere inside its key group. The per-index change
   * map of the transaction does not see this at all: it stays empty, since the embedded engine
   * writes index entries into the transaction's atomic operation instead.
   */
  private boolean hasPendingIndexChanges(CommandContext ctx) {
    return ctx.getDatabaseSession().getTransactionInternal().getEntryCount() > 0;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    // Prefill can build several rows before emission. Refresh $matched only at this external
    // boundary so downstream hops never mutate a previously emitted or buffered binding.
    return startOrdered(ctx).map((row, context) -> {
      context.setSystemVariable(CommandContext.VAR_MATCHED, row);
      return row;
    });
  }

  private ExecutionStream startOrdered(CommandContext ctx) {
    assert MatchAssertions.checkNotNull(prev, "previous step");
    reset();
    ctx.setSystemVariable(CommandContext.VAR_INDEX_ORDERED_PRE_SORTED, Boolean.FALSE);
    // Resolve once before any path decision. Each execution uses its own current bindings.
    if (limitClause != null) {
      var limitSize = limitClause.getValue(ctx);
      if (limitSize >= 0) {
        var skipSize = skipClause == null ? 0 : Math.max(0, skipClause.getValue(ctx));
        rowTarget = (long) skipSize + limitSize;
      }
    }
    if (multiSourceMode != null) {
      return multiSourceDispatch(ctx);
    }
    // Single-source is a plan-time assumption. Check it before loading targets or emitting rows.
    // Consume the upstream eagerly and compute the cost model decision NOW, so
    // that VAR_INDEX_ORDERED_PRE_SORTED is correctly set BEFORE OrderByStep
    // checks it. The pipeline starts bottom-up (LimitStep → OrderByStep →
    // ... → IndexOrderedEdgeStep), so OrderByStep.internalStart() runs first
    // and checks the flag after calling prev.start() which reaches here.
    // If we deferred the cost model to a lazy flatMap, OrderByStep would see
    // the flag as null and always fall through to collect-all mode — defeating
    // the sort push-down that makes index scan + LIMIT worthwhile.
    var resultSet = prev.start(ctx);
    Result upstreamRow;
    Throwable readFailure = null;
    try {
      if (!resultSet.hasNext(ctx)) {
        ctx.setSystemVariable(CommandContext.VAR_INDEX_ORDERED_PRE_SORTED, Boolean.FALSE);
        return ExecutionStream.empty();
      }
      upstreamRow = resultSet.next(ctx);
      if (resultSet.hasNext(ctx)) {
        // A frozen root schedule can repeat even a UNIQUE-matched source. Keep all bindings and
        // duplicates, including the first row. This map belongs only to the running execution.
        var rows = new ArrayList<Result>();
        rows.add(upstreamRow);
        while (resultSet.hasNext(ctx)) {
          rows.add(resultSet.next(ctx));
        }
        var sourceMap = new LinkedHashMap<RID, List<Result>>();
        for (var row : rows) {
          var sourceRid = extractSourceRid(row);
          if (sourceRid != null
              && sourceMatchesDeclaredClass(sourceRid, ctx.getDatabaseSession())) {
            sourceMap.computeIfAbsent(sourceRid, k -> new ArrayList<>(1)).add(row);
          }
        }
        if (sourceMap.isEmpty()) {
          ctx.setSystemVariable(CommandContext.VAR_INDEX_ORDERED_PRE_SORTED, Boolean.FALSE);
          return ExecutionStream.empty();
        }
        // Local per-source ordering cannot satisfy global ORDER BY. Do not use the singleton
        // cost path, which can signal pre-sorted after sorting only one source's targets.
        chosenRuntimePath = RuntimePath.LOAD_UNSORTED_MULTI;
        ctx.setSystemVariable(CommandContext.VAR_INDEX_ORDERED_PRE_SORTED, Boolean.FALSE);
        return loadFromSourcesUnsorted(sourceMap, ctx);
      }
    } catch (RuntimeException | Error failure) {
      readFailure = failure;
      throw failure;
    } finally {
      try {
        resultSet.close(ctx);
      } catch (RuntimeException | Error closeFailure) {
        if (readFailure == null) {
          throw closeFailure;
        }
        // Cleanup must not replace the upstream failure, even if both operations throw.
        if (readFailure != closeFailure) {
          readFailure.addSuppressed(closeFailure);
        }
      }
    }

    singleSourceLoad = rowTarget > 0;
    return processUpstreamRow(upstreamRow, ctx);
  }

  // =====================================================================
  // Single-source mode
  // =====================================================================

  private ExecutionStream processUpstreamRow(Result upstreamRow, CommandContext ctx) {
    var session = ctx.getDatabaseSession();
    var sourceRid = extractSourceRid(upstreamRow);
    if (sourceRid == null) {
      ctx.setSystemVariable(
          CommandContext.VAR_INDEX_ORDERED_PRE_SORTED, Boolean.FALSE);
      return ExecutionStream.empty();
    }
    // Parser rid: + class used to fetch by RID alone and drop the class.
    // MatchFirstStep now keeps the class; still refuse a source whose collection is
    // outside the declared source class so a stray upstream row cannot load its LinkBag.
    if (!sourceMatchesDeclaredClass(sourceRid, session)) {
      ctx.setSystemVariable(
          CommandContext.VAR_INDEX_ORDERED_PRE_SORTED, Boolean.FALSE);
      return ExecutionStream.empty();
    }
    var linkBag = loadLinkBag(sourceRid, session);
    if (linkBag == null || linkBag.size() == 0) {
      ctx.setSystemVariable(
          CommandContext.VAR_INDEX_ORDERED_PRE_SORTED, Boolean.FALSE);
      return ExecutionStream.empty();
    }

    long indexSize = index.estimateEntryCount(session);
    if (indexSize <= 0) {
      return terminalLoad(linkBag, upstreamRow, ctx);
    }
    var histogram = index.getHistogram(session);
    long estimate = targetIndexDescriptor == null ? -1 : targetIndexDescriptor.estimateHits(ctx);
    if (!edgeTraversal && estimate >= 0
        && (rowTarget > 0 || (limitClause == null && multiEqualityEligible))) {
      return equalityDispatch(upstreamRow, sourceRid, linkBag, indexSize, histogram, estimate, ctx);
    }
    // A singleton with no LIMIT clause never scans the ordering index.
    // Parameterized LIMIT plans retain their existing runtime-bound behavior.
    if (limitClause == null) {
      return terminalLoad(linkBag, upstreamRow, ctx);
    }
    if (shouldUseIndexScan(linkBag.size(), session)) {
      var ridSet = distinctRidsFromLinkBag(linkBag);
      if (ridSet != null) {
        chosenRuntimePath = RuntimePath.INDEX_SCAN;
        signalIndexOrderedOutput(ctx);
        return indexScanFiltered(ridSet, linkBag, ctx, upstreamRow);
      }
    }
    if (downstreamEdgeCount > 0 && rowTarget > 0) {
      // Low density but downstream edges + LIMIT: load all, sort locally,
      // and mark PRE_SORTED=true. This enables LIMIT to short-circuit the
      // pipeline — only K records traverse expensive downstream edges
      // (REPLY_OF chain, HAS_CREATOR, etc.) instead of all N.
      // Sort cost O(N log N) is trivial for typical LinkBag sizes (~50-500).
      chosenRuntimePath = RuntimePath.LOAD_SORT;
      signalLoadSortedOutput(ctx);
      return loadSortFromLinkBag(linkBag, ctx, upstreamRow);
    } else {
      // No downstream edges or no LIMIT: stream unsorted to OrderByStep.
      // OrderByStep's bounded heap handles sorting with O(N log K).
      chosenRuntimePath = RuntimePath.LOAD_UNSORTED;
      ctx.setSystemVariable(CommandContext.VAR_INDEX_ORDERED_PRE_SORTED, Boolean.FALSE);
      return loadFromLinkBag(linkBag, ctx, upstreamRow);
    }
  }

  /** Chooses before building source membership. Speculative reads finish before emission. */
  private ExecutionStream equalityDispatch(
      Result upstream, RID source, LinkBag bag, long indexSize,
      EquiDepthHistogram histogram, long estimate, CommandContext context) {
    IndexOrderedCostModel.WorkAccount account = null;
    java.util.function.Supplier<ExecutionStream> load = () -> {
      try {
        if (rowTarget > 0 && completedMembers != null) {
          var candidates = candidateLoad(bag, upstream, context);
          if (candidates != null) {
            return candidates;
          }
        }
      } finally {
        completedMembers = null;
        completedDuplicates = null;
      }
      if (limitClause != null) {
        return terminalLoad(bag, upstream, context);
      }
      chosenRuntimePath = RuntimePath.LOAD_UNSORTED;
      return loadFromLinkBag(bag, context, upstream);
    };
    // Stored statistics are approximate and do not walk pending transaction operations.
    var statistics = targetIndexDescriptor.getIndex().getStatistics(context.getDatabaseSession());
    var population = statistics == null ? indexSize : statistics.totalCount();
    var memory = GlobalConfiguration.QUERY_INDEX_ORDERED_MAX_EQUALITY_CANDIDATES.getValueAsLong();
    while (true) {
      var costs = IndexOrderedCostModel.equalityCosts(
          bag.size(), bag.size(), indexSize, population, rowTarget, estimate, memory,
          histogram, orderAsc);
      // Local downstream ordering needs every row. Otherwise OrderByStep uses a bounded heap.
      double loadPrice = predictsLoadPrefilter(bag.size(), estimate, context)
          ? IndexOrderedCostModel.prefilteredLoadCost(bag.size(), estimate, population,
              limitClause != null && downstreamEdgeCount == 0 ? rowTarget : -1)
          : costs.linkBagLoad();
      costs = new IndexOrderedCostModel.EqualityCosts(
          limitClause == null ? Double.POSITIVE_INFINITY : costs.orderedScan(),
          costs.equalityFetch(), loadPrice);
      if (account == null) {
        account = limitClause == null ? new IndexOrderedCostModel.WorkAccount(bag.size())
            : new IndexOrderedCostModel.WorkAccount(loadPrice);
      }
      switch (account.choose(costs)) {
        case EQUALITY -> {
          var attempt = readEqualityCandidates(
              upstream, source, bag, context, account, costs, memory);
          lastEqualityObservation = new EqualityObservation(
              attempt.observedEntries(), attempt.complete(), attempt.requiresLinkBagLoad());
          if (attempt.complete()) {
            completedMembers = null;
            completedDuplicates = null;
            chosenRuntimePath = RuntimePath.EQUALITY_FETCH;
            return equalityRows(attempt.candidates(), upstream, context);
          }
          if (attempt.requiresLinkBagLoad()) {
            return load.get();
          }
          // A refused next read raises the estimate before the remaining paths are priced.
          estimate = Math.max(estimate, attempt.observedEntries() + 1);
        }
        case ORDERED -> {
          var stream = equalityOrderedAttempt(bag, upstream, context, account, costs);
          if (stream != null) {
            completedMembers = null;
            completedDuplicates = null;
            chosenRuntimePath = RuntimePath.INDEX_SCAN;
            signalIndexOrderedOutput(context);
            return stream;
          }
        }
        case LOAD -> {
          return load.get();
        }
      }
    }
  }

  /**
   * Predicts a fresh source's IndexLookup admission without advancing its amortization state.
   * Links is one bag or the summed admitted bags of all sources. The mode uses the plan-time
   * rootSourceRows and forecastN, as EdgeTraversal.checkIndexLookupAmortization does.
   * Execution uses resolveLoadPrefilter and its runtime build guards. DirectRid admission,
   * a different index selected after splitting matched references, or a runtime build refusal
   * can make prediction and resolution differ without changing load semantics.
   */
  private boolean predictsLoadPrefilter(long links, long estimate, CommandContext context) {
    if (executionTraversal == null || estimate < 0
        || estimate > TraversalPreFilterHelper.maxRidSetSize()
        || links < TraversalPreFilterHelper.minLinkBagSize()) {
      return false;
    }
    double selectivity = targetIndexDescriptor.estimateSelectivity(context);
    if (!Double.isFinite(selectivity) || selectivity < 0
        || selectivity > TraversalPreFilterHelper.indexLookupMaxSelectivity()) {
      return false;
    }
    double minimum = EdgeTraversal.computeMinNeighborsForBuild(
        (int) estimate, EdgeTraversal.currentLoadToScanRatio(), selectivity);
    long roots = executionTraversal.getRootSourceRows();
    long forecast = executionTraversal.getForecastN();
    if (roots >= EdgeTraversal.MIN_FOR_CLT && forecast > (long) Math.ceil(minimum)) {
      return true;
    }
    double trigger = roots >= 0 && roots < EdgeTraversal.MIN_FOR_CLT
        ? minimum * EdgeTraversal.CLT_FAIL_M_SAFETY_MULTIPLIER
        : Math.max(2.0 * Math.max(0, forecast), minimum);
    return links >= (long) Math.ceil(trigger);
  }

  /**
   * Phase-2 scan/load choice. Intersect the completed bitmap, never walk the edges again.
   * Choose a bounded prefix scan or the terminal load after completing the intersection.
   * The final candidate load is terminal and is never truncated.
   */
  @Nullable private ExecutionStream candidateLoad(
      LinkBag bag, Result upstream, CommandContext context) {
    var prefilter = resolveLoadPrefilter(bag, context);
    if (prefilter == null) {
      return null;
    }
    var members = new RidSet();
    var duplicates = new HashMap<RID, Integer>();
    long links = 0;
    var targetClass = resolveTargetClass(context);
    var membership = new LoadMembership(prefilter);
    for (var rid : completedMembers) {
      if (targetClass == null
          || !targetClass.hasPolymorphicCollectionId(rid.getCollectionId())) {
        continue;
      }
      int count = completedDuplicates.getOrDefault(rid, 1);
      if (!membership.accepts(rid, count)) {
        continue;
      }
      if (members.size() >= TraversalPreFilterHelper.maxRidSetSize()) {
        return null;
      }
      members.add(rid);
      links += count;
      if (count > 1) {
        duplicates.put(rid, count);
      }
    }
    // A refused intersection contributes no probes. The ordinary load records its own work.
    membership.close();
    lastCandidateCount = members.size();
    lastCandidateLoadPrice = IndexOrderedCostModel.candidateLoadCost(
        members.size(), links,
        downstreamEdgeCount == 0 ? rowTarget : -1);
    if (members.isEmpty()) {
      chosenRuntimePath = RuntimePath.CANDIDATE_EMPTY;
      return ExecutionStream.empty();
    }
    // Both alternatives exclude the already-paid prefilter and membership build.
    if (downstreamEdgeCount == 0
        && IndexOrderedCostModel.candidateScanCost(
            members.size(), index.estimateEntryCount(context.getDatabaseSession()), rowTarget)
            < lastCandidateLoadPrice) {
      var scanned = candidateScan(members, duplicates, upstream, context);
      if (scanned != null) {
        chosenRuntimePath = RuntimePath.CANDIDATE_SCAN;
        signalIndexOrderedOutput(context);
        return scanned;
      }
    }
    // Only RIDs and sparse counts survive setup. Without later edges, load one record at a time.
    var candidates = java.util.stream.StreamSupport.stream(members.spliterator(), false)
        .map(rid -> {
          lastCandidateLoads++;
          var record = loadRecord(rid, context.getDatabaseSession());
          if (record != null && matchesTargetFilter(record, rid, context, targetClass)
              && !isAlreadyBoundAndDifferent(upstream, record, context.getDatabaseSession())) {
            return new EqualityCandidate(record, duplicates.getOrDefault(rid, 1));
          }
          return null;
        }).filter(java.util.Objects::nonNull);
    chosenRuntimePath = RuntimePath.CANDIDATE_LOAD;
    return equalityRows(candidates, upstream, context);
  }

  /**
   * Buffer to the row target before fixing the final path. After handoff the ordinary
   * ORDER BY consumer can read ties or the whole population through the lazy continuation.
   */
  @Nullable private ExecutionStream candidateScan(
      RidSet members, Map<RID, Integer> duplicates, Result upstream, CommandContext context) {
    var account = new IndexOrderedCostModel.CandidateScanAccount(lastCandidateLoadPrice);
    var buffered = new ArrayList<Result>();
    lastCandidateScanExhausted = true;
    try {
      if (!account.spend(IndexOrderedCostModel.indexSeekEntryUnits())) {
        return null;
      }
      var scan = new FetchFromIndexStep(
          new IndexSearchDescriptor(index), orderAsc, nullsFirst(), context, profilingEnabled);
      var cursor = scan.internalStart(context);
      boolean handedOff = false;
      try {
        while (true) {
          // Reserve before hasNext because the Java stream iterator reads ahead there.
          // EOF also reserves one entry, so the recorded work is a conservative bound.
          if (!account.spend(1)) {
            return null;
          }
          if (!cursor.hasNext(context)) {
            lastCandidateScanExhausted = false;
            return ExecutionStream.resultIterator(buffered.iterator());
          }
          var entry = cursor.next(context);
          lastCandidateScanEntries++;
          var rid = (RID) entry.getProperty("rid");
          if (!members.contains(rid)) {
            continue;
          }
          if (!account.spend(IndexOrderedCostModel.recordReadEntryUnits())) {
            return null;
          }
          lastCandidateScanLoads++;
          var rows = orderedEqualityRows(entry, duplicates, upstream, context);
          try {
            while (rows.hasNext(context)) {
              if (buffered.size() >= maxBufferedRows(rowTarget)) {
                return null;
              }
              buffered.add(rows.next(context));
              if (buffered.size() >= rowTarget) {
                // No speculative row escapes until the path is fixed. Keep duplicate rows
                // left in this entry as well as future entries for ties and distinct slices.
                lastCandidateScanExhausted = false;
                handedOff = true;
                return batchedStream(List.<Supplier<ExecutionStream>>of(
                    () -> ExecutionStream.resultIterator(buffered.iterator()),
                    () -> rows,
                    () -> cursor
                        .flatMap((next, c) -> members.contains((RID) next.getProperty("rid"))
                            ? orderedEqualityRows(next, duplicates, upstream, c)
                            : ExecutionStream.empty()))
                    .iterator(), Supplier::get)
                    .onClose(c -> {
                      rows.close(c);
                      cursor.close(c);
                    });
              }
            }
          } finally {
            if (!handedOff) {
              rows.close(context);
            }
          }
        }
      } finally {
        if (!handedOff) {
          cursor.close(context);
        }
      }
    } finally {
      lastCandidateScanBuffered = buffered.size();
      lastCandidateScanWork = account.spent();
    }
  }

  private ExecutionStream terminalLoad(LinkBag bag, Result upstream, CommandContext context) {
    if (downstreamEdgeCount > 0 && rowTarget > 0) {
      chosenRuntimePath = RuntimePath.LOAD_SORT;
      var stream = loadSortFromLinkBag(bag, context, upstream);
      signalLoadSortedOutput(context);
      return stream;
    }
    chosenRuntimePath = RuntimePath.LOAD_UNSORTED;
    return loadFromLinkBag(bag, context, upstream);
  }

  /** Expands every link and sorts all candidates when later MATCH edges need ordered input. */
  private ExecutionStream equalityRows(
      List<EqualityCandidate> candidates, Result upstream, CommandContext context) {
    return equalityRows(candidates.stream(), upstream, context);
  }

  private ExecutionStream equalityRows(
      java.util.stream.Stream<EqualityCandidate> candidates, Result upstream,
      CommandContext context) {
    var records = candidates
        .flatMap(candidate -> java.util.stream.LongStream.range(0, candidate.links())
            .mapToObj(ignored -> candidate.record()));
    if (downstreamEdgeCount > 0 && rowTarget > 0) {
      // Later edges can reject any prefix. Secondary ORDER BY keys can reorder ties.
      // Keep every row, just as loadSortFromLinkBag does.
      var ordered = new ArrayList<Result>(records.toList());
      sortByOrderProperty(ordered);
      records = ordered.stream();
      signalLoadSortedOutput(context);
    }
    return ExecutionStream.resultIterator(records.map(record -> (Result) new MatchResultRow(
        context.getDatabaseSession(), upstream, targetAlias, record)).iterator());
  }

  /** Every cursor advance is visible, including entries outside source membership. */
  @Nullable private ExecutionStream equalityOrderedAttempt(
      LinkBag bag, Result upstream, CommandContext context,
      IndexOrderedCostModel.WorkAccount account, IndexOrderedCostModel.EqualityCosts costs) {
    var buffered = new ArrayList<Result>();
    try {
      lastScanBudget = (long) account.allowance(costs);
      lastScanConsumedEntries = 0;
      var members = new RidSet();
      var duplicates = new HashMap<RID, Integer>();
      if (!account.spend((bag.size() + 1.0) * IndexOrderedCostModel.reverseEdgeEntryUnits())) {
        return null;
      }
      for (var pair : bag) {
        var rid = pair.secondaryRid();
        if (!members.add(rid)) {
          duplicates.merge(rid, 2, (count, ignored) -> count + 1);
        }
      }
      completedMembers = members;
      completedDuplicates = duplicates;
      if (!account.spend(IndexOrderedCostModel.indexSeekEntryUnits())) {
        return null;
      }
      // Reserve cursor work before opening it. Keep half the allowance for member loads.
      // The null-key and non-null-key streams can each consume one entry past the bound.
      long entryBudget = Math.max(0, (long) (account.allowance(costs) / 2) - 2);
      if (!account.spend(entryBudget + 2.0)) {
        return null;
      }
      var filtered = new RidFilteredIndexValuesStep(
          new IndexSearchDescriptor(index), orderAsc, nullsFirst(), context, profilingEnabled,
          members, entryBudget);
      var cursor = filtered.internalStart(context);
      boolean handedOff = false;
      try {
        while (buffered.size() < maxBufferedRows(rowTarget)) {
          var hasNext = cursor.hasNext(context);
          lastScanConsumedEntries = filtered.consumedEntryCount();
          if (filtered.scanBudgetExhausted()) {
            return null;
          }
          if (!hasNext) {
            handedOff = true;
            cursor.close(context);
            return ExecutionStream.resultIterator(buffered.iterator());
          }
          var entry = cursor.next(context);
          if (!account.spend(IndexOrderedCostModel.recordReadEntryUnits())) {
            return null;
          }
          var rows = orderedEqualityRows(entry, duplicates, upstream, context);
          try {
            while (rows.hasNext(context)) {
              buffered.add(rows.next(context));
            }
          } finally {
            rows.close(context);
          }
        }
        // Later MATCH filters can reject this prefix. Preserve the unbounded continuation.
        filtered.liftScanBudget();
        handedOff = true;
        return batchedStream(List.<Supplier<ExecutionStream>>of(
            () -> ExecutionStream.resultIterator(buffered.iterator()),
            () -> cursor.flatMap((entry, c) -> orderedEqualityRows(entry, duplicates, upstream, c)))
            .iterator(), Supplier::get).onClose(cursor::close);
      } finally {
        if (!handedOff) {
          cursor.close(context);
        }
      }
    } finally {
      lastOrderedBufferedRows = buffered.size();
    }
  }

  private ExecutionStream orderedEqualityRows(
      Result entry, Map<RID, Integer> duplicates, Result upstream, CommandContext context) {
    var rid = (RID) entry.getProperty("rid");
    var links = duplicates.getOrDefault(rid, 1);
    var record = loadRecord(rid, context.getDatabaseSession());
    if (record == null || !matchesTargetFilter(record, rid, context)
        || isAlreadyBoundAndDifferent(upstream, record, context.getDatabaseSession())) {
      return ExecutionStream.empty();
    }
    return ExecutionStream.resultIterator(java.util.stream.IntStream.range(0, links)
        .mapToObj(ignored -> (Result) new MatchResultRow(
            context.getDatabaseSession(), upstream, targetAlias, record))
        .iterator());
  }

  /**
   * Builds the membership filter for the singleton scan. Fall back to walking the LinkBag
   * if parallel edges would make the bitmap drop repeated vertex bindings.
   */
  @Nullable private RidSet distinctRidsFromLinkBag(LinkBag linkBag) {
    var ridSet = new RidSet();
    for (RidPair pair : linkBag) {
      if (!ridSet.add(ridFromPair(pair))) {
        return null;
      }
    }
    return ridSet;
  }

  /**
   * Single-source index scan: filtered by RidSet, only matching records loaded.
   * Output is sorted by the ORDER BY property (pre-sorted).
   *
   * <p>Runs under {@link #scanUnderBudget}: the membership filter hides dropped
   * entries, so the budget is pushed into {@link RidFilteredIndexValuesStep}.
   */
  private ExecutionStream indexScanFiltered(
      RidSet ridSet, LinkBag linkBag, CommandContext ctx, Result upstreamRow) {
    var session = ctx.getDatabaseSession();
    var indexDesc = new IndexSearchDescriptor(index);
    var entryBudget =
        IndexOrderedCostModel.entriesWorthTheLoadAlternative(linkBag.size());
    if (entryBudget <= 0) {
      if (downstreamEdgeCount > 0 && rowTarget > 0) {
        chosenRuntimePath = RuntimePath.LOAD_SORT;
        signalLoadSortedOutput(ctx);
        return loadSortFromLinkBag(linkBag, ctx, upstreamRow);
      }
      chosenRuntimePath = RuntimePath.LOAD_UNSORTED;
      ctx.setSystemVariable(CommandContext.VAR_INDEX_ORDERED_PRE_SORTED, Boolean.FALSE);
      return loadFromLinkBag(linkBag, ctx, upstreamRow);
    }
    var filteredStep = new RidFilteredIndexValuesStep(
        indexDesc, orderAsc, nullsFirst(), ctx, profilingEnabled, ridSet, entryBudget);
    var indexStream = filteredStep.internalStart(ctx);

    return scanUnderBudget(
        filteredStep, indexStream, ctx, entryBudget,
        (indexResult, mapCtx) -> {
          var rid = (RID) indexResult.getProperty("rid");
          var targetRecord = loadRecord(rid, session);
          if (targetRecord == null) {
            return ExecutionStream.empty();
          }
          if (!matchesTargetFilter(targetRecord, rid, mapCtx)) {
            return ExecutionStream.empty();
          }
          if (isAlreadyBoundAndDifferent(upstreamRow, targetRecord, session)) {
            return ExecutionStream.empty();
          }
          return ExecutionStream.singleton(
              new MatchResultRow(session, upstreamRow, targetAlias, targetRecord));
        },
        () -> {
          // Mirror the non-scan branch: with downstream edges + LIMIT, sort
          // locally and keep PRE_SORTED so only k rows cross those edges.
          if (downstreamEdgeCount > 0 && rowTarget > 0) {
            signalLoadSortedOutput(ctx);
            return loadSortFromLinkBag(linkBag, ctx, upstreamRow);
          }
          return loadFromLinkBag(linkBag, ctx, upstreamRow);
        });
  }

  /**
   * Single-source low-density fallback with downstream edges + LIMIT:
   * load all targets from the source LinkBag, sort by ORDER BY property,
   * and emit as a pre-sorted stream. With PRE_SORTED=true, OrderByStep
   * passes through and LimitStep stops after K rows — only K records
   * traverse expensive downstream MATCH edges instead of all N.
   *
   * <p>Iterates the LinkBag directly. An admitted target index prefilter can
   * build a RID set first to skip record loads. No source-membership RID set is built.
   */
  @SuppressWarnings("unchecked")
  private ExecutionStream loadSortFromLinkBag(
      LinkBag linkBag, CommandContext ctx, Result upstreamRow) {
    var session = ctx.getDatabaseSession();
    // Class filter is per LinkBag entry; resolve the target class once for the scan.
    var targetClass = resolveTargetClass(ctx);

    var records = new ArrayList<Result>();
    try (var membership = new LoadMembership(resolveLoadPrefilter(linkBag, ctx))) {
      for (RidPair pair : linkBag) {
        var rid = ridFromPair(pair);
        if (targetClass == null
            || !targetClass.hasPolymorphicCollectionId(rid.getCollectionId())
            || !membership.accepts(rid)) {
          continue;
        }
        var record = loadRecord(rid, session);
        if (record != null
            && matchesTargetFilter(record, rid, ctx, targetClass)
            && !isAlreadyBoundAndDifferent(upstreamRow, record, session)) {
          records.add(record);
        }
      }
    }

    sortByOrderProperty(records);

    return ExecutionStream.resultIterator(
        records.stream()
            .map(record -> (Result) new MatchResultRow(
                session, upstreamRow, targetAlias, record))
            .iterator());
  }

  /**
   * Single-source low-density fallback: load targets directly from the
   * source LinkBag without sorting. Downstream OrderByStep handles sorting
   * with its bounded heap (or unbounded collect when there is no LIMIT).
   *
   * <p>Streams lazily after resolving any target index prefilter. That prefilter
   * can materialize a RID set before iteration.
   */
  private ExecutionStream loadFromLinkBag(
      LinkBag linkBag, CommandContext ctx, Result upstreamRow) {
    var prefilter = resolveLoadPrefilter(linkBag, ctx);
    var session = ctx.getDatabaseSession();
    var targetClass = resolveTargetClass(ctx);
    var membership = new LoadMembership(prefilter);
    var iter = linkBag.iterator();
    return ExecutionStream.resultIterator(new Iterator<Result>() {
      private Result pending;

      @Override
      public boolean hasNext() {
        while (pending == null && iter.hasNext()) {
          var rid = ridFromPair(iter.next());
          if (targetClass == null
              || !targetClass.hasPolymorphicCollectionId(rid.getCollectionId())
              || !membership.accepts(rid)) {
            continue;
          }
          var record = loadRecord(rid, session);
          if (record != null
              && matchesTargetFilter(record, rid, ctx, targetClass)
              && !isAlreadyBoundAndDifferent(upstreamRow, record, session)) {
            pending = new MatchResultRow(session, upstreamRow, targetAlias, record);
          }
        }
        return pending != null;
      }

      @Override
      public Result next() {
        if (!hasNext()) {
          throw new java.util.NoSuchElementException();
        }
        var result = pending;
        pending = null;
        return result;
      }
    }).onClose(context -> membership.close());
  }

  /** Applies the same size, amortization and selectivity gates as ordinary MATCH. */
  @Nullable private RidSet resolveLoadPrefilter(LinkBag bag, CommandContext context) {
    if (!singleSourceLoad) {
      return executionTraversal == null ? null
          : executionTraversal.resolvePreFilter(context, bag.size());
    }
    if (!loadPrefilterResolved) {
      loadPrefilter = executionTraversal == null ? null
          : executionTraversal.resolvePreFilter(context, bag.size());
      loadPrefilterResolved = true;
    }
    return loadPrefilter;
  }

  /** Counts real membership probes without updating the global metric for each edge. */
  private final class LoadMembership implements AutoCloseable {
    @Nullable private final RidSet accepted;
    private long probed;
    private long filtered;

    private LoadMembership(@Nullable RidSet accepted) {
      this.accepted = accepted;
    }

    boolean accepts(RID rid) {
      return accepts(rid, 1);
    }

    boolean accepts(RID rid, int links) {
      if (accepted == null) {
        return true;
      }
      probed += links;
      if (accepted.contains(rid)) {
        return true;
      }
      filtered += links;
      return false;
    }

    @Override
    public void close() {
      if (accepted != null && probed > 0) {
        executionTraversal.resolveEffectivenessMetric().record(filtered, probed);
        probed = 0;
        filtered = 0;
      }
    }
  }

  /**
   * One equality-index candidate and its edge multiplicity. Keeping the count separate avoids
   * allocating a result row for every parallel edge before a runtime path has been fixed.
   */
  record EqualityCandidate(Result record, int links) {
  }

  /**
   * A completed attempt contains every matching candidate. An incomplete attempt must be
   * discarded before another path runs. Observed entries include repeated index RIDs so the
   * runtime dispatcher can raise an underestimated cardinality before repricing.
   */
  record EqualityAttempt(
      List<EqualityCandidate> candidates, long observedEntries, boolean complete,
      boolean requiresLinkBagLoad) {
  }

  /**
   * Reads candidates without emitting rows or changing the ordering signal. The caller owns the
   * execution-local account and must choose EQUALITY before calling this method. The caller owns
   * the source record load and supplies its already loaded LinkBag.
   *
   * <p>Only vertex traversals can use candidate-side membership. The reverse field is derived
   * from the literal source field, not from polymorphic edge classes. Every index entry, record
   * load and reverse edge visit is charged before the corresponding read.
   */
  EqualityAttempt readEqualityCandidates(
      Result upstreamRow, RID sourceRid, LinkBag sourceBag, CommandContext context,
      IndexOrderedCostModel.WorkAccount account, IndexOrderedCostModel.EqualityCosts costs,
      long memoryLimit) {
    return readEqualityCandidates(upstreamRow, sourceRid, sourceBag, context, account, costs,
        memoryLimit, new EqualityReads());
  }

  /** Per-call read boundaries allow tests to observe storage work without shared mutable state. */
  class EqualityReads {
    IndexSearchDescriptor descriptor() {
      return targetIndexDescriptor;
    }

    ExecutionStream open(CommandContext context, Runnable beforeRangeLookup) {
      return new FetchFromIndexStep(
          descriptor(), true, context, profilingEnabled, beforeRangeLookup)
          .internalStart(context);
    }

    boolean advance(ExecutionStream cursor, CommandContext context) {
      return cursor.hasNext(context);
    }

    Result record(RID rid, CommandContext context) {
      return loadRecord(rid, context.getDatabaseSession());
    }

    Iterator<RidPair> edges(LinkBag bag) {
      return bag.iterator();
    }

    RidPair next(Iterator<RidPair> edges) {
      return edges.next();
    }
  }

  private static final class EqualityWorkExhausted extends RuntimeException {
  }

  EqualityAttempt readEqualityCandidates(
      Result upstreamRow, RID sourceRid, LinkBag sourceBag, CommandContext context,
      IndexOrderedCostModel.WorkAccount account, IndexOrderedCostModel.EqualityCosts costs,
      long memoryLimit, EqualityReads reads) {
    var candidates = new ArrayList<EqualityCandidate>();
    long observed = 0;
    if (edgeTraversal || targetIndexDescriptor == null
        || sourceBag == null) {
      return new EqualityAttempt(List.of(), observed, false, true);
    }
    var candidateLimit = account.candidateReadLimit(costs, memoryLimit);
    var seen = new HashSet<RID>();
    var targetClass = resolveTargetClass(context);
    var reverseField = linkBagFieldName.startsWith("out_")
        ? "in_" + linkBagFieldName.substring(4)
        : "out_" + linkBagFieldName.substring(3);
    ExecutionStream cursor = null;
    try {
      cursor = reads.open(context, () -> {
        if (!account.spend(IndexOrderedCostModel.indexSeekEntryUnits())) {
          throw new EqualityWorkExhausted();
        }
      });
      while (true) {
        // hasNext may advance the storage cursor. Reserve that read before asking it.
        if (!account.spend(1)) {
          return new EqualityAttempt(List.of(), observed, false, false);
        }
        if (!reads.advance(cursor, context)) {
          return new EqualityAttempt(List.copyOf(candidates), observed, true, false);
        }
        observed++;
        var rid = (RID) cursor.next(context).getProperty("rid");
        if (seen.contains(rid)) {
          continue;
        }
        if (seen.size() >= candidateLimit) {
          return new EqualityAttempt(List.of(), observed, false, false);
        }
        seen.add(rid);
        if (!account.spend(IndexOrderedCostModel.recordReadEntryUnits())) {
          return new EqualityAttempt(List.of(), observed, false, false);
        }
        var record = reads.record(rid, context);
        if (record == null
            || !matchesTargetFilter(record, rid, context, targetClass)
            || isAlreadyBoundAndDifferent(upstreamRow, record, context.getDatabaseSession())) {
          continue;
        }
        var entity = record.asEntityOrNull();
        if (!(entity instanceof EntityImpl impl)) {
          return new EqualityAttempt(List.of(), observed, false, true);
        }
        var reverse = impl.getPropertyInternal(reverseField);
        if (reverse == null) {
          continue;
        }
        if (!(reverse instanceof LinkBag reverseBag)) {
          return new EqualityAttempt(List.of(), observed, false, true);
        }
        int links = 0;
        // Iterator construction prefetches a pair. next also reads ahead, so reserve each
        // operation before calling it, including the final advance past the last edge.
        if (!account.spend(IndexOrderedCostModel.reverseEdgeEntryUnits())) {
          return new EqualityAttempt(List.of(), observed, false, false);
        }
        var edges = reads.edges(reverseBag);
        while (edges.hasNext()) {
          if (!account.spend(IndexOrderedCostModel.reverseEdgeEntryUnits())) {
            return new EqualityAttempt(List.of(), observed, false, false);
          }
          if (sourceRid.equals(reads.next(edges).secondaryRid())) {
            links++;
          }
        }
        if (links > 0) {
          candidates.add(new EqualityCandidate(record, links));
        }
      }
    } catch (EqualityWorkExhausted exhausted) {
      return new EqualityAttempt(List.of(), observed, false, false);
    } finally {
      if (cursor != null) {
        cursor.close(context);
      }
    }
  }

  // =====================================================================
  // Sort helper
  // =====================================================================

  /**
   * Sorts records by the ORDER BY property (from the index definition).
   * Used by loadSortFromLinkBag to produce pre-sorted output that enables
   * LIMIT-based early termination through downstream MATCH edges.
   *
   * <p>The planner resolves null placement once and stores it in this step. The shared ORDER BY
   * comparison then applies explicit clauses and direction-specific defaults consistently.
   */
  private void sortByOrderProperty(List<Result> records) {
    records.sort(this::compareOrderRecords);
  }

  private int compareOrderRecords(Result a, Result b) {
    var cmp = comparisonItem.compare(a, b, ctx, nullsPlacement);
    if (cmp == 0 && ridTieBreakAccepted) {
      cmp = a.getIdentity().compareTo(b.getIdentity());
      return orderAsc ? cmp : -cmp;
    }
    return cmp;
  }

  private boolean nullsFirst() {
    return comparisonItem.nullsFirstFor(nullsPlacement);
  }

  // =====================================================================
  // Multi-source mode — dispatch
  // =====================================================================

  /** Routes to the appropriate multi-source strategy based on the mode. */
  private ExecutionStream multiSourceDispatch(CommandContext ctx) {
    return switch (multiSourceMode) {
      case FILTERED_BOUND -> filteredBound(ctx);
      case FILTERED_UNBOUND -> rowTarget > 0 && multiEqualityEligible
          && targetIndexDescriptor.estimateHits(ctx) >= 0
              ? filteredBound(ctx) : filteredUnbound(ctx);
      case UNFILTERED_BOUND -> {
        // Full index scan + reverse-edge class check; no RidSet filter.
        chosenRuntimePath = RuntimePath.GLOBAL_SCAN;
        signalIndexOrderedOutput(ctx);
        yield unfilteredBound(ctx);
      }
      case UNFILTERED_UNBOUND -> {
        chosenRuntimePath = RuntimePath.GLOBAL_SCAN;
        signalIndexOrderedOutput(ctx);
        yield unfilteredUnbound(ctx);
      }
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

    // LinkedHashMap preserves insertion order for deterministic sampling
    // in estimateTotalEdges().
    var sourceMap = new LinkedHashMap<RID, List<Result>>();
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
    long indexSize = sourceCount > maxSources ? -1 : index.estimateEntryCount(session);
    if (indexSize <= 0) {
      chosenRuntimePath = RuntimePath.LOAD_UNSORTED_MULTI;
      ctx.setSystemVariable(CommandContext.VAR_INDEX_ORDERED_PRE_SORTED, Boolean.FALSE);
      return loadFromSourcesUnsorted(sourceMap, ctx);
    }

    var histogram = index.getHistogram(session);
    var sourceSizes = new HashMap<RID, Integer>();
    long equalityEstimate = targetIndexDescriptor == null ? -1
        : targetIndexDescriptor.estimateHits(ctx);
    if (!edgeTraversal && rowTarget > 0 && equalityEstimate >= 0
        && multiEqualityEligible) {
      var equality = multiSourceEquality(
          sourceMap, sourceSizes, indexSize, histogram, equalityEstimate, ctx);
      if (equality != null) {
        return equality;
      }
    }
    if (multiSourceMode == IndexOrderedPlanner.MultiSourceMode.FILTERED_UNBOUND) {
      // Equality was not selected. Preserve the unbound union strategy and its EXPLAIN mode.
      return filteredUnbound(ctx, ExecutionStream.resultIterator(
          sourceMap.values().stream().flatMap(List::stream).iterator()));
    }
    var edgeEstimate = estimateTotalEdges(sourceMap, sourceSizes, session, indexSize);
    var strategy = pickMultiSourceStrategy(
        edgeEstimate.totalEdges(), indexSize, histogram, edgeEstimate.capped());

    return switch (strategy) {
      case UNION_RIDSET_SCAN -> {
        chosenRuntimePath = RuntimePath.UNION_SCAN;
        signalIndexOrderedOutput(ctx);
        yield indexScanWithUnion(sourceMap, ctx);
      }
      case GLOBAL_SCAN -> {
        chosenRuntimePath = RuntimePath.GLOBAL_SCAN;
        signalIndexOrderedOutput(ctx);
        yield indexScanGlobal(sourceMap, ctx);
      }
      case LOAD_ALL_SORT -> {
        chosenRuntimePath = RuntimePath.LOAD_UNSORTED_MULTI;
        ctx.setSystemVariable(CommandContext.VAR_INDEX_ORDERED_PRE_SORTED, Boolean.FALSE);
        yield loadFromSourcesUnsorted(sourceMap, ctx);
      }
    };
  }

  /**
   * Reads equality candidates once for the complete source set. The real source LinkBag total
   * prices membership and bounds speculative work before any candidate load. A failed speculative
   * attempt ends at plain LinkBag loading, which retains the planned traversal's cached prefilter
   * and forecast policy.
   * A null result keeps the existing ordered/union/load choice without opening an equality cursor.
   */
  @Nullable private ExecutionStream multiSourceEquality(
      Map<RID, List<Result>> sources, Map<RID, Integer> sourceSizes, long indexSize,
      EquiDepthHistogram histogram, long estimate, CommandContext context) {
    // The sample is useful for scan selection, but must never enlarge speculative work.
    // Read each source's literal field once here, including empty sources in the real total.
    long realEdges = 0;
    boolean unsupportedStorage = false;
    for (var source : sources.keySet()) {
      var entity = (EntityImpl) context.getDatabaseSession().getActiveTransaction().load(source);
      var field = entity.getPropertyInternal(linkBagFieldName);
      if (field instanceof LinkBag bag) {
        int size = bag.size();
        sourceSizes.put(source, size);
        realEdges += size;
      } else {
        // A negative size retains the sampler's exclusion of absent or unsupported bags.
        sourceSizes.put(source, -1);
        unsupportedStorage |= field != null;
      }
    }
    int edges = (int) Math.min(realEdges, Integer.MAX_VALUE);
    lastEqualitySourceEdges = edges;
    var statistics = targetIndexDescriptor.getIndex().getStatistics(context.getDatabaseSession());
    var population = statistics == null ? indexSize : statistics.totalCount();
    var memory = GlobalConfiguration.QUERY_INDEX_ORDERED_MAX_EQUALITY_CANDIDATES.getValueAsLong();
    var costs = IndexOrderedCostModel.equalityCosts(
        edges, edges, indexSize, population, rowTarget, estimate, memory, histogram, orderAsc);
    // Plain MATCH decides the mode once from the planner's root lineage and forecast, then
    // accumulates admitted links across sources and caches one build. Sum the distinct sources'
    // bags of at least minLinkBagSize and apply the same plan-time mode and trigger.
    // This prediction neither advances admission nor builds RIDs.
    long admissionLinks = sourceSizes.values().stream()
        .filter(size -> size >= TraversalPreFilterHelper.minLinkBagSize())
        .mapToLong(Integer::longValue).sum();
    boolean prefiltered = !unsupportedStorage && admissionLinks > 0 && predictsLoadPrefilter(
        admissionLinks, estimate, context);
    if (prefiltered) {
      double loadPrice = IndexOrderedCostModel.prefilteredLoadCost(
          edges, estimate, population, rowTarget);
      costs = new IndexOrderedCostModel.EqualityCosts(
          costs.orderedScan(), costs.equalityFetch(), loadPrice);
      multiSourcePrefilterBudget = (long) loadPrice;
    }
    var account = prefiltered ? new IndexOrderedCostModel.WorkAccount(costs.linkBagLoad())
        : new IndexOrderedCostModel.WorkAccount(edges);
    var choice = account.choose(costs);
    if (choice != IndexOrderedCostModel.EqualityPath.EQUALITY) {
      if (prefiltered && choice == IndexOrderedCostModel.EqualityPath.LOAD) {
        chosenRuntimePath = RuntimePath.LOAD_UNSORTED_MULTI;
        context.setSystemVariable(CommandContext.VAR_INDEX_ORDERED_PRE_SORTED, Boolean.FALSE);
        return loadFromSourcesUnsorted(sources, context);
      }
      return null;
    }
    var rows = new ArrayList<Result>();
    long observed = 0;
    boolean complete = false;
    boolean unsupported = false;
    ExecutionStream cursor = null;
    try {
      if (unsupportedStorage) {
        unsupported = true;
        throw new EqualityWorkExhausted();
      }
      var reads = new EqualityReads();
      var candidateLimit = account.candidateReadLimit(costs, memory);
      var seen = new HashSet<RID>();
      var targetClass = resolveTargetClass(context);
      cursor = reads.open(context, () -> {
        if (!account.spend(IndexOrderedCostModel.indexSeekEntryUnits())) {
          throw new EqualityWorkExhausted();
        }
      });
      while (true) {
        if (!account.spend(1)) {
          throw new EqualityWorkExhausted();
        }
        if (!reads.advance(cursor, context)) {
          complete = true;
          break;
        }
        observed++;
        var rid = (RID) cursor.next(context).getProperty("rid");
        if (!seen.add(rid)) {
          continue;
        }
        if (seen.size() > candidateLimit
            || !account.spend(IndexOrderedCostModel.recordReadEntryUnits())) {
          throw new EqualityWorkExhausted();
        }
        var record = reads.record(rid, context);
        if (record == null || !matchesTargetFilter(record, rid, context, targetClass)) {
          continue;
        }
        var reverse = ((EntityImpl) record.asEntityOrNull()).getPropertyInternal(reverseFieldName);
        if (reverse == null) {
          continue;
        }
        if (!(reverse instanceof LinkBag bag)) {
          unsupported = true;
          throw new EqualityWorkExhausted();
        }
        if (!account.spend(IndexOrderedCostModel.reverseEdgeEntryUnits())) {
          throw new EqualityWorkExhausted();
        }
        var links = reads.edges(bag);
        while (links.hasNext()) {
          if (!account.spend(IndexOrderedCostModel.reverseEdgeEntryUnits())) {
            throw new EqualityWorkExhausted();
          }
          var bindings = sources.get(reads.next(links).secondaryRid());
          if (bindings != null) {
            for (var binding : bindings) {
              if (!isAlreadyBoundAndDifferent(binding, record, context.getDatabaseSession())) {
                rows.add(new MatchResultRow(
                    context.getDatabaseSession(), binding, targetAlias, record));
              }
            }
          }
        }
      }
    } catch (EqualityWorkExhausted exhausted) {
      // No speculative row is visible. The terminal load starts from the complete source map.
    } finally {
      if (cursor != null) {
        cursor.close(context);
      }
    }
    lastEqualityObservation = new EqualityObservation(observed, complete, unsupported);
    context.setSystemVariable(CommandContext.VAR_INDEX_ORDERED_PRE_SORTED, Boolean.FALSE);
    if (!complete) {
      chosenRuntimePath = RuntimePath.LOAD_UNSORTED_MULTI;
      return batchedStream(sources.entrySet().iterator(),
          entry -> batchedStream(entry.getValue().iterator(), binding -> {
            var bag = loadLinkBag(entry.getKey(), context.getDatabaseSession());
            return bag == null ? ExecutionStream.empty() : loadFromLinkBag(bag, context, binding);
          }));
    }
    // The global OrderByStep owns every ORDER BY item, tie, null placement and final LIMIT.
    chosenRuntimePath = RuntimePath.EQUALITY_FETCH;
    return ExecutionStream.resultIterator(rows.iterator());
  }

  // ---- Mode B: FILTERED_UNBOUND (union RidSet, no binding) ----

  /**
   * When equality is selected, Mode B uses the shared candidate-side executor and retains
   * bindings internally until projection. Otherwise it builds a union RidSet. No sourceMap needed
   * — just bitmap check per entry. Source alias is NOT bound in results.
   *
   * <p>Not binding the source alias does not license collapsing the row
   * multiplicity: a MATCH pattern emits one row per (source, target) binding
   * whether or not RETURN reads the source. The union scan below therefore
   * counts, per target, how many upstream source occurrences reach it, and
   * emits that many rows. Only the source <em>values</em> are omitted.
   */
  private ExecutionStream filteredUnbound(CommandContext ctx) {
    return filteredUnbound(ctx, prev.start(ctx));
  }

  private ExecutionStream filteredUnbound(CommandContext ctx, ExecutionStream upstream) {
    var session = ctx.getDatabaseSession();
    int maxRidSetSize =
        GlobalConfiguration.QUERY_PREFILTER_MAX_RIDSET_SIZE.getValueAsInteger();

    // Collect upstream source RIDs + build union RidSet simultaneously.
    // If union exceeds maxRidSetSize, stop adding to union but keep collecting
    // source RIDs for the fallback path. When the source count exceeds
    // maxSources the index scan is no longer profitable, so release the
    // (expensive, per-edge) union RidSet and fall back to streaming from all
    // source LinkBags. We keep collecting the (cheap) source RID list to the
    // end: dropping sources here would silently truncate results. This mirrors
    // filteredBound, which retains the full sourceMap on overflow.
    int maxSources =
        GlobalConfiguration.QUERY_INDEX_ORDERED_MAX_SOURCES.getValueAsInteger();
    var sourceRids = new ArrayList<RID>();
    var unionRidSet = new RidSet();
    // Occurrences per source RID, not distinct sources: two upstream rows for
    // one source produce two rows per reachable target, which is what the
    // loadFromSourcesUnbound fallback below also produces.
    var sourceOccurrences = new HashMap<RID, Integer>();
    boolean ridSetOverflow = false;
    boolean sourceOverflow = false;
    while (upstream.hasNext(ctx)) {
      var row = upstream.next(ctx);
      var sourceRid = extractSourceRid(row);
      if (sourceRid != null) {
        sourceRids.add(sourceRid);
        sourceOccurrences.merge(sourceRid, 1, Integer::sum);
        if (!sourceOverflow && sourceRids.size() > maxSources) {
          sourceOverflow = true;
          ridSetOverflow = true;
          unionRidSet = new RidSet(); // release partial set; we will fall back
        }
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

    // If source or union overflowed, or cost model rejects, stream from
    // source LinkBags without sorting. OrderByStep handles sort downstream.
    if (sourceOverflow || ridSetOverflow || unionRidSet.isEmpty()
        || !shouldUseIndexScan(unionRidSet.size(), session)) {
      chosenRuntimePath = RuntimePath.LOAD_UNSORTED_MULTI;
      ctx.setSystemVariable(CommandContext.VAR_INDEX_ORDERED_PRE_SORTED, Boolean.FALSE);
      return loadFromSourcesUnbound(sourceRids, ctx);
    }

    chosenRuntimePath = RuntimePath.UNION_SCAN;
    signalIndexOrderedOutput(ctx);
    var indexDesc = new IndexSearchDescriptor(index);
    var entryBudget = Math.min(multiSourcePrefilterBudget,
        IndexOrderedCostModel.entriesWorthTheLoadAlternative(unionRidSet.size()));
    if (entryBudget <= 0) {
      chosenRuntimePath = RuntimePath.LOAD_UNSORTED_MULTI;
      ctx.setSystemVariable(CommandContext.VAR_INDEX_ORDERED_PRE_SORTED, Boolean.FALSE);
      return loadFromSourcesUnbound(sourceRids, ctx);
    }
    var filteredStep = new RidFilteredIndexValuesStep(
        indexDesc, orderAsc, nullsFirst(), ctx, profilingEnabled, unionRidSet, entryBudget);
    var indexStream = filteredStep.internalStart(ctx);

    // Shared empty upstream — safe because MatchResultRow never writes to parent
    var emptyUpstream = new ResultInternal(session);
    return scanUnderBudget(
        filteredStep, indexStream, ctx, entryBudget,
        (indexResult, mapCtx) -> {
          var rid = (RID) indexResult.getProperty("rid");
          var targetRecord = loadRecord(rid, session);
          if (targetRecord == null) {
            return ExecutionStream.empty();
          }
          if (!matchesTargetFilter(targetRecord, rid, mapCtx)) {
            return ExecutionStream.empty();
          }
          // Reverse edges give the multiplicity the bitmap check cannot: a target
          // reached from several upstream sources binds the pattern once per source.
          var rowCount = 0;
          for (var sourceRid : resolveReverseEdges(targetRecord)) {
            rowCount += sourceOccurrences.getOrDefault(sourceRid, 0);
          }
          if (rowCount == 0) {
            return ExecutionStream.empty();
          }
          var results = new ArrayList<Result>(rowCount);
          for (var i = 0; i < rowCount; i++) {
            results.add(new MatchResultRow(session, emptyUpstream, targetAlias, targetRecord));
          }
          return ExecutionStream.resultIterator(results.iterator());
        },
        () -> loadFromSourcesUnbound(sourceRids, ctx));
  }

  /**
   * Mode B fallback: iterate source LinkBags, load targets, emit
   * without sorting or source binding. OrderByStep sorts downstream.
   */
  private ExecutionStream loadFromSourcesUnbound(
      List<RID> sourceRids, CommandContext ctx) {
    var session = ctx.getDatabaseSession();
    var emptyUpstream = new ResultInternal(session);
    return batchedStream(
        sourceRids.iterator(),
        sourceRid -> loadSourceEdgesUnbound(sourceRid, emptyUpstream, session, ctx));
  }

  private ExecutionStream loadSourceEdgesUnbound(
      RID sourceRid, Result emptyUpstream,
      DatabaseSessionEmbedded session, CommandContext ctx) {
    var linkBag = loadLinkBag(sourceRid, session);
    if (linkBag == null) {
      return ExecutionStream.empty();
    }
    var results = new ArrayList<Result>();
    forEachMatchingTarget(linkBag, session, ctx, record -> results.add(
        new MatchResultRow(session, emptyUpstream, targetAlias, record)));
    return ExecutionStream.resultIterator(results.iterator());
  }

  /**
   * Iterates a source LinkBag, loading each target record (via
   * {@link #ridFromPair}) and applying {@link #matchesTargetFilter}. Every
   * target that loads and passes the filter is handed to {@code consumer}.
   * Shared by the eager list-building fallback paths (bound and unbound) so the
   * load-and-filter loop is written once.
   */
  private void forEachMatchingTarget(
      LinkBag linkBag, DatabaseSessionEmbedded session, CommandContext ctx,
      java.util.function.Consumer<Result> consumer) {
    var targetClass = resolveTargetClass(ctx);
    try (var membership = new LoadMembership(resolveLoadPrefilter(linkBag, ctx))) {
      for (RidPair pair : linkBag) {
        var rid = ridFromPair(pair);
        if (targetClass == null
            || !targetClass.hasPolymorphicCollectionId(rid.getCollectionId())
            || !membership.accepts(rid)) {
          continue;
        }
        lastMultiSourceLoadCalls++;
        var record = loadRecord(rid, session);
        if (record != null && matchesTargetFilter(record, rid, ctx, targetClass)) {
          consumer.accept(record);
        }
      }
    }
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

    // Start and immediately close upstream: this mode does not read source
    // rows (it scans the index and class-checks reverse edges), but the
    // upstream chain must still be started/closed so its own side effects and
    // resources run and release.
    var upstream = prev.start(ctx);
    upstream.close(ctx);

    if (srcClass == null) {
      return ExecutionStream.empty();
    }

    var indexDesc = new IndexSearchDescriptor(index);
    var fullScan = new RidFilteredIndexValuesStep(
        indexDesc, orderAsc, nullsFirst(), ctx, profilingEnabled, null);
    var indexStream = fullScan.internalStart(ctx);

    return indexStream.flatMap((indexResult, mapCtx) -> {
      var rid = (RID) indexResult.getProperty("rid");
      var targetRecord = loadRecord(rid, session);
      if (targetRecord == null) {
        return ExecutionStream.empty();
      }
      if (!matchesTargetFilter(targetRecord, rid, mapCtx)) {
        return ExecutionStream.empty();
      }

      // Check ALL reverse edges — a target may link to multiple valid
      // sources of the correct class. Emit one row per valid source.
      var reverseRids = resolveReverseEdges(targetRecord);
      var results = new ArrayList<Result>(1);
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
   * Lightest mode: scan index, per hit count the reverse edges that point to a
   * vertex of the source class (no load of source, no binding). Only the target
   * alias is bound, but one row is emitted per matching reverse edge, because
   * the pattern binds once per (source, target) pair even when RETURN never
   * reads the source.
   */
  private ExecutionStream unfilteredUnbound(CommandContext ctx) {
    var session = ctx.getDatabaseSession();
    var schema = session.getMetadata().getImmutableSchemaSnapshot();
    var srcClass = schema.getClassInternal(sourceClassName);

    // Start and immediately close upstream: this mode does not read source
    // rows, but the upstream chain must still be started/closed so its own
    // side effects and resources run and release.
    var upstream = prev.start(ctx);
    upstream.close(ctx);

    if (srcClass == null) {
      return ExecutionStream.empty();
    }

    var indexDesc = new IndexSearchDescriptor(index);
    var fullScan = new RidFilteredIndexValuesStep(
        indexDesc, orderAsc, nullsFirst(), ctx, profilingEnabled, null);
    var indexStream = fullScan.internalStart(ctx);

    var emptyUpstream = new ResultInternal(session);
    return indexStream.flatMap((indexResult, mapCtx) -> {
      var rid = (RID) indexResult.getProperty("rid");
      var targetRecord = loadRecord(rid, session);
      if (targetRecord == null) {
        return ExecutionStream.empty();
      }
      if (!matchesTargetFilter(targetRecord, rid, mapCtx)) {
        return ExecutionStream.empty();
      }

      // The source values are not bound, but every reverse edge from a
      // source-class vertex is one pattern binding, so count them all rather
      // than stopping at the first valid one.
      var rowCount = 0;
      for (var sourceRid : resolveReverseEdges(targetRecord)) {
        if (srcClass.hasPolymorphicCollectionId(sourceRid.getCollectionId())) {
          rowCount++;
        }
      }
      if (rowCount == 0) {
        return ExecutionStream.empty();
      }

      var results = new ArrayList<Result>(rowCount);
      for (var i = 0; i < rowCount; i++) {
        results.add(new MatchResultRow(session, emptyUpstream, targetAlias, targetRecord));
      }
      return ExecutionStream.resultIterator(results.iterator());
    });
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
    // If union exceeds maxRidSetSize, fall back to unsorted streaming.
    int maxRidSetSize =
        GlobalConfiguration.QUERY_PREFILTER_MAX_RIDSET_SIZE.getValueAsInteger();
    var unionRidSet = new RidSet();
    boolean overflow = false;
    for (var sourceRid : sourceMap.keySet()) {
      if (overflow) {
        break;
      }
      var linkBag = loadLinkBag(sourceRid, session);
      if (linkBag != null) {
        for (RidPair pair : linkBag) {
          unionRidSet.add(ridFromPair(pair));
        }
        if (unionRidSet.size() > maxRidSetSize) {
          overflow = true;
        }
      }
    }

    if (overflow) {
      chosenRuntimePath = RuntimePath.LOAD_UNSORTED_MULTI;
      ctx.setSystemVariable(
          CommandContext.VAR_INDEX_ORDERED_PRE_SORTED, Boolean.FALSE);
      return loadFromSourcesUnsorted(sourceMap, ctx);
    }

    if (unionRidSet.isEmpty()) {
      return ExecutionStream.empty();
    }

    // Scan index filtered by union RidSet (seqRead per entry, bitmap check).
    // Filtered cursor advances are cheap. Equality dispatch can lower the load alternative.
    var indexDesc = new IndexSearchDescriptor(index);
    var entryBudget = Math.min(multiSourcePrefilterBudget,
        IndexOrderedCostModel.entriesWorthTheLoadAlternative(unionRidSet.size()));
    if (entryBudget <= 0) {
      chosenRuntimePath = RuntimePath.LOAD_UNSORTED_MULTI;
      ctx.setSystemVariable(CommandContext.VAR_INDEX_ORDERED_PRE_SORTED, Boolean.FALSE);
      return loadFromSourcesUnsorted(sourceMap, ctx);
    }
    var filteredStep = new RidFilteredIndexValuesStep(
        indexDesc, orderAsc, nullsFirst(), ctx, profilingEnabled, unionRidSet, entryBudget);
    var indexStream = filteredStep.internalStart(ctx);

    return scanUnderBudget(
        filteredStep, indexStream, ctx, entryBudget,
        (indexResult, mapCtx) -> matchTargetToSources(indexResult, sourceMap, mapCtx),
        () -> loadFromSourcesUnsorted(sourceMap, ctx));
  }

  /**
   * Strategy 2: Scan index without filter, load every entry, check reverse
   * edge against sourceMap. Cheaper than union when density is high and LIMIT
   * is small (avoids union build cost).
   *
   * <p>Each index entry loads a record, so the runtime budget is the real
   * source-edge count, further capped by the predicted prefiltered load price converted
   * to record-plus-cursor units. Union scans instead count cheap filtered-cursor advances.
   */
  private ExecutionStream indexScanGlobal(
      Map<RID, List<Result>> sourceMap, CommandContext ctx) {
    var session = ctx.getDatabaseSession();
    var realEdgeCount = countTotalEdges(sourceMap, session);
    var indexDesc = new IndexSearchDescriptor(index);
    var fullScan = new RidFilteredIndexValuesStep(
        indexDesc, orderAsc, nullsFirst(), ctx, profilingEnabled, null);
    var indexStream = fullScan.internalStart(ctx);

    return scanUnderBudget(
        fullScan, indexStream, ctx, Math.min(realEdgeCount,
            (long) (multiSourcePrefilterBudget
                / (1.0 + IndexOrderedCostModel.recordReadEntryUnits()))),
        (indexResult, mapCtx) -> matchTargetToSources(indexResult, sourceMap, mapCtx),
        () -> loadFromSourcesUnsorted(sourceMap, ctx));
  }

  /**
   * Low-density fallback: iterate all sources' LinkBags, load targets,
   * emit WITHOUT sorting. Downstream OrderByStep handles sort with
   * bounded heap (O(LIMIT) memory).
   */
  private ExecutionStream loadFromSourcesUnsorted(
      Map<RID, List<Result>> sourceMap, CommandContext ctx) {
    var session = ctx.getDatabaseSession();
    return batchedStream(
        sourceMap.entrySet().iterator(),
        entry -> loadSourceEdgesBound(entry, session, ctx));
  }

  /** Load edges from one source, emit as MatchResultRows with source binding. */
  private ExecutionStream loadSourceEdgesBound(
      Map.Entry<RID, List<Result>> entry,
      DatabaseSessionEmbedded session, CommandContext ctx) {
    var linkBag = loadLinkBag(entry.getKey(), session);
    if (linkBag == null) {
      return ExecutionStream.empty();
    }
    var upstreamRows = entry.getValue();
    var results = new ArrayList<Result>();
    forEachMatchingTarget(linkBag, session, ctx, record -> {
      for (var row : upstreamRows) {
        if (!isAlreadyBoundAndDifferent(row, record, session)) {
          results.add(
              new MatchResultRow(session, row, targetAlias, record));
        }
      }
    });
    return ExecutionStream.resultIterator(results.iterator());
  }

  // =====================================================================
  // Runtime scan budget
  // =====================================================================

  /**
   * Runs an ordered index scan under a RUNTIME SCAN BUDGET, with a PRE-EMISSION
   * BAIL-OUT.
   *
   * <p>Plan-time density assumes reachable targets are spread evenly. Clustered
   * membership breaks that. This method counts index entries the scan consumes
   * (including ones a RidSet filter drops) and abandons the scan for
   * load-from-sources once spend reaches {@code entryBudget}, before any row is
   * emitted.
   *
   * <p>When the prefill collects enough rows, the in-pipeline budget is removed before chaining
   * the buffered prefix and remaining scan. A later graph-pattern filter can reject prefetched
   * rows. The continuation remains unbounded so the query can still satisfy its requested row
   * count. A post-emission bail-out cannot preserve index order.
   *
   * <p>Callers choose the budget units:
   * <ul>
   *   <li>UNION / single-source filtered: {@link
   *       IndexOrderedCostModel#entriesWorthTheLoadAlternative} — cursor advances
   *       are cheap relative to record loads</li>
   *   <li>GLOBAL: the real source-edge count — each entry already loads a
   *       record</li>
   * </ul>
   */
  private ExecutionStream scanUnderBudget(
      RidFilteredIndexValuesStep scanStep,
      ExecutionStream indexStream,
      CommandContext ctx,
      long entryBudget,
      BiFunction<Result, CommandContext, ExecutionStream> rowsForEntry,
      Supplier<ExecutionStream> bailOut) {
    lastScanBudget = entryBudget;
    lastScanConsumedEntries = 0;
    if (rowTarget <= 0) {
      // No LIMIT means the scan has to reach the end of the reachable set
      // either way — abandoning it cannot save work. The filtered cursor must also be
      // unbounded, otherwise a positive-to-unbounded copy can silently stop at its budget.
      scanStep.liftScanBudget();
      return indexStream.flatMap(rowsForEntry::apply);
    }
    if (entryBudget <= 0) {
      // Filtered callers reject this budget before constructing a scan. Keep this guard so a
      // future caller cannot reinterpret an invalid initial budget as an unbounded scan.
      return bailOutTo(indexStream, ctx, bailOut);
    }

    var maxBuffered = maxBufferedRows(rowTarget);
    var buffered = new ArrayList<Result>();
    long delivered = 0;
    while (buffered.size() < maxBuffered && indexStream.hasNext(ctx)) {
      // Unfiltered scan: every entry reaches here, so delivered is exact.
      // Filtered scan: RidFilteredIndexValuesStep stops itself; see below.
      if (delivered >= entryBudget) {
        lastScanConsumedEntries = Math.max(delivered, scanStep.consumedEntryCount());
        return bailOutTo(indexStream, ctx, bailOut);
      }
      delivered++;
      var rows = rowsForEntry.apply(indexStream.next(ctx), ctx);
      while (rows.hasNext(ctx)) {
        buffered.add(rows.next(ctx));
      }
      rows.close(ctx);
    }
    lastScanConsumedEntries = Math.max(delivered, scanStep.consumedEntryCount());
    if (buffered.size() < maxBuffered && scanStep.scanBudgetExhausted()) {
      return bailOutTo(indexStream, ctx, bailOut);
    }

    // A full prefill closes the bail-out path. Remove the gate because later graph-pattern
    // filters can reject buffered rows and force the query to continue scanning for its limit.
    if (buffered.size() >= maxBuffered) {
      scanStep.liftScanBudget();
    }

    List<Supplier<ExecutionStream>> parts = List.of(
        () -> ExecutionStream.resultIterator(buffered.iterator()),
        () -> indexStream.flatMap(rowsForEntry::apply));
    return batchedStream(parts.iterator(), Supplier::get)
        .onClose(indexStream::close);
  }

  /** Abandons the scan and hands over the fallback. Never after a row was emitted. */
  private ExecutionStream bailOutTo(
      ExecutionStream indexStream, CommandContext ctx, Supplier<ExecutionStream> bailOut) {
    indexStream.close(ctx);
    chosenRuntimePath = RuntimePath.SCAN_BUDGET_BAILOUT;
    ctx.setSystemVariable(CommandContext.VAR_INDEX_ORDERED_PRE_SORTED, Boolean.FALSE);
    return bailOut.get();
  }

  /**
   * Rows the pre-emission buffer may hold: the row target, capped by
   * {@code QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP}.
   */
  private static long maxBufferedRows(long rowTarget) {
    var maxElements =
        GlobalConfiguration.QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP.getValueAsLong();
    return maxElements > 0 ? Math.min(rowTarget, maxElements) : rowTarget;
  }

  // =====================================================================
  // Cost-based heuristic
  // =====================================================================

  /**
   * Single-source: compare RidSet-filtered index scan vs load-all-and-sort.
   */
  private boolean shouldUseIndexScan(int linkBagSize, DatabaseSessionEmbedded session) {
    long indexSize = index.estimateEntryCount(session);
    if (indexSize <= 0) {
      return false;
    }
    if (IndexOrderedCostModel.entriesWorthTheLoadAlternative(linkBagSize) <= 0) {
      // A cached plan may outlive a configuration change. Reach the pre-cursor fallback below.
      return true;
    }
    var costs = IndexOrderedCostModel.computeCosts(
        linkBagSize, indexSize, rowTarget, index.getHistogram(session), orderAsc,
        downstreamEdgeCount);
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
      int totalEdges, long indexSize,
      @Nullable EquiDepthHistogram histogram,
      boolean estimateCapped) {
    return IndexOrderedCostModel.pickMultiSourceStrategy(
        totalEdges, indexSize, rowTarget, histogram, orderAsc, estimateCapped);
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
    if (!matchesTargetFilter(targetRecord, rid, ctx)) {
      return ExecutionStream.empty();
    }

    var sourceRids = resolveReverseEdges(targetRecord);
    var results = new ArrayList<Result>(1);
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

  /**
   * Creates a lazy ExecutionStream that iterates over sources, producing a
   * batch ExecutionStream per source via {@code batchProducer}. Advances to
   * the next source when the current batch is exhausted.
   */
  private static <T> ExecutionStream batchedStream(
      Iterator<T> sourceIter,
      Function<T, ExecutionStream> batchProducer) {
    return new ExecutionStream() {
      private ExecutionStream currentBatch = ExecutionStream.empty();

      @Override
      public boolean hasNext(CommandContext c) {
        while (!currentBatch.hasNext(c)) {
          currentBatch.close(c);
          if (!sourceIter.hasNext()) {
            return false;
          }
          currentBatch = batchProducer.apply(sourceIter.next());
        }
        return true;
      }

      @Override
      public Result next(CommandContext c) {
        return currentBatch.next(c);
      }

      @Override
      public void close(CommandContext c) {
        currentBatch.close(c);
      }
    };
  }

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

    if (edgeTraversal) {
      // Edge record: reverse field ("out"/"in") is a direct LINK to a single vertex,
      // not a LinkBag.
      var link = impl.getLinkPropertyInternal(reverseFieldName);
      return link != null ? List.of(link) : List.of();
    }

    // Vertex: reverse field is a LinkBag (e.g., out_LIKES).
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
   * Exact sum of LinkBag sizes for every source. Used as the GLOBAL_SCAN
   * runtime budget: that path loads one record per index entry, so the
   * fallback's record count is the entry allowance (not
   * {@link IndexOrderedCostModel#entriesWorthTheLoadAlternative}).
   */
  private int countTotalEdges(
      Map<RID, ?> sourceMap, DatabaseSessionEmbedded session) {
    long total = 0;
    for (var sourceRid : sourceMap.keySet()) {
      var linkBag = loadLinkBag(sourceRid, session);
      if (linkBag != null) {
        total += linkBag.size();
      }
    }
    return (int) Math.min(total, Integer.MAX_VALUE);
  }

  private record TotalEdgesEstimate(int totalEdges, boolean capped) {
  }

  /**
   * Estimate total edges for multi-source cost model by sampling up to 5 source vertices'
   * LinkBag sizes, then extrapolating to all sources. Falls back to
   * {@code sourceCount × defaultFanOut} if sampling fails.
   *
   * <p>Uses the <em>median</em> of the sample, not the mean. A hub in a small sample pulls the
   * mean far above the typical source; the mean then extrapolates past the index size and the
   * cap would report density {@code 1.0}. Median keeps one hub from rewriting the density.
   *
   * <p>The index estimate caps the extrapolation for costing only. With a positive row
   * target, the cost model uses the load path rather than trusting the resulting density
   * when the cap fires.
   */
  private TotalEdgesEstimate estimateTotalEdges(
      Map<RID, ?> sourceMap, Map<RID, Integer> sourceSizes,
      DatabaseSessionEmbedded session, long indexSize) {
    int sampleSize = Math.min(sourceMap.size(), 5);
    var sampleSizes = new int[sampleSize];
    int sampled = 0;
    for (var sourceRid : sourceMap.keySet()) {
      if (sampled >= sampleSize) {
        break;
      }
      // Reuse metadata collected for equality pricing without iterating any source edges.
      var size = sourceSizes.get(sourceRid);
      if (size != null) {
        if (size >= 0) {
          sampleSizes[sampled++] = size;
        }
      } else {
        var linkBag = loadLinkBag(sourceRid, session);
        if (linkBag != null) {
          sampleSizes[sampled++] = linkBag.size();
        }
      }
    }
    long perSource;
    if (sampled == 0) {
      perSource = GlobalConfiguration.QUERY_STATS_DEFAULT_FAN_OUT.getValueAsInteger();
    } else {
      Arrays.sort(sampleSizes, 0, sampled);
      perSource = sampleSizes[sampled / 2];
    }
    var extrapolated = (long) sourceMap.size() * perSource;
    var capped = false;
    if (indexSize > 0 && extrapolated >= indexSize) {
      extrapolated = indexSize;
      capped = true;
    }
    return new TotalEdgesEstimate(
        (int) Math.min(extrapolated, Integer.MAX_VALUE), capped);
  }

  /**
   * Extracts the appropriate RID from a LinkBag pair based on traversal mode.
   * For vertex traversal (.in/.out): returns secondaryRid (opposite vertex).
   * For edge traversal (.inE/.outE): returns primaryRid (edge record).
   */
  private RID ridFromPair(RidPair pair) {
    return edgeTraversal ? pair.primaryRid() : pair.secondaryRid();
  }

  /**
   * Whether {@code sourceRid}'s collection is owned by {@link #sourceClassName}
   * or a subclass. No declared source class means every RID is accepted.
   */
  private boolean sourceMatchesDeclaredClass(
      RID sourceRid, DatabaseSessionEmbedded session) {
    if (sourceClassName == null) {
      return true;
    }
    var owner = session.getMetadata().getImmutableSchemaSnapshot()
        .getClassByCollectionId(sourceRid.getCollectionId());
    return owner != null && owner.isSubClassOf(sourceClassName);
  }

  /**
   * Loads the entity for the given RID and extracts its LinkBag field.
   * Returns null if the record cannot be loaded or the field is not a LinkBag.
   */
  @Nullable private LinkBag loadLinkBag(
      RID sourceRid, DatabaseSessionEmbedded session) {
    try {
      var rec = session.getActiveTransaction().load(sourceRid);
      if (!(rec instanceof EntityImpl entity)) {
        return null;
      }
      var fieldValue = entity.getPropertyInternal(linkBagFieldName);
      return fieldValue instanceof LinkBag lb ? lb : null;
    } catch (Exception e) {
      logger.debug("Failed to load LinkBag for RID {}: {}", sourceRid, e.getMessage(), e);
      return null;
    }
  }

  @Nullable private RidSet resolveEdgeRidSet(
      Result upstreamRow, DatabaseSessionEmbedded session) {
    var sourceRid = extractSourceRid(upstreamRow);
    if (sourceRid == null) {
      return null;
    }

    var linkBag = loadLinkBag(sourceRid, session);
    if (linkBag == null) {
      return null;
    }

    var ridSet = new RidSet();
    for (RidPair pair : linkBag) {
      ridSet.add(ridFromPair(pair));
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
      return ResultInternal.fromLoadedRecord(session, rec);
    } catch (Exception e) {
      logger.debug("Failed to load record {}: {}", rid, e.getMessage(), e);
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
   * Resolves the MATCH target class from the session's immutable schema
   * snapshot. The planner refuses to build this step unless the target class
   * name resolves at plan time, so {@code null} here means the class was
   * dropped between planning and execution.
   *
   * <p>The snapshot is the required source, not merely a convenient one. Its
   * classes carry a precomputed polymorphic collection-id set, which is what
   * makes the per-record membership test in
   * {@link #matchesTargetFilter(Result, RID, CommandContext, SchemaClass)}
   * lock-free. Reading the class off the live shared schema instead would
   * reintroduce the schema lock this step exists to avoid.
   */
  @Nullable private SchemaClass resolveTargetClass(CommandContext ctx) {
    var schema = ctx.getDatabaseSession().getMetadata().getImmutableSchemaSnapshot();
    assert schema != null;
    return schema.getClassInternal(targetClassName);
  }

  /**
   * Convenience overload for call sites that filter a single record and have no
   * loop to hoist the class resolution out of. Loops should resolve the class
   * once with {@link #resolveTargetClass} and call the four-argument form.
   */
  private boolean matchesTargetFilter(
      Result targetRecord, RID targetRid, CommandContext ctx) {
    return matchesTargetFilter(
        targetRecord, targetRid, ctx, resolveTargetClass(ctx));
  }

  /**
   * Checks if a target record passes the WHERE filter and class constraint.
   * Returns true if the record should be included, false if filtered out.
   *
   * <p>Class membership is decided from the record's collection id against
   * {@code targetClass}, which covers the target class and its subclasses. That
   * is equivalent to an {@code isSubClassOf} check, but it does not take the
   * shared schema lock on every record — important when many targets are
   * filtered under concurrent queries. Same approach as
   * {@link #unfilteredBound}.
   *
   * <p>The caller supplies {@code targetClass} so a filtering loop resolves it
   * once instead of once per record.
   */
  private boolean matchesTargetFilter(
      Result targetRecord,
      RID targetRid,
      CommandContext ctx,
      @Nullable SchemaClass targetClass) {
    if (targetFilter != null
        && !targetFilter.matchesFilters(targetRecord, ctx)) {
      return false;
    }
    // A class dropped between planning and execution leaves nothing that can
    // satisfy the constraint.
    if (targetClass == null) {
      return false;
    }
    return targetClass.hasPolymorphicCollectionId(targetRid.getCollectionId());
  }

  @Override
  public boolean canBeCached() {
    return true;
  }

  /** Plan-text marker for an accepted trailing record identifier sort item. */
  public static final String RID_TIE_BREAK_MARKER = "+@rid";

  @Override
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    var direction = orderAsc ? "ASC" : "DESC";
    var mode = multiSourceMode != null ? " (" + multiSourceMode + ")" : "";
    var edgeSuffix = edgeTraversal ? "E" : "";
    var path = chosenRuntimePath != null ? " [" + chosenRuntimePath.label() + "]" : "";
    // Surface whether the planner accepted a trailing @rid ORDER BY item so
    // EXPLAIN can tell an accepted shape from a refused one without inferring it from
    // whether the query buffered.
    var tieBreak = ridTieBreakAccepted ? " " + RID_TIE_BREAK_MARKER : "";
    return spaces + "+ INDEX ORDERED MATCH" + edgeSuffix + " " + direction + mode + path
        + tieBreak + "\n"
        + spaces + "  {" + sourceAlias + "}." + edgeClassName
        + "{" + targetAlias + "} via " + index.getName();
  }

  @Override
  public IndexOrderedEdgeStep copy(CommandContext ctx) {
    return new IndexOrderedEdgeStep(
        ctx, sourceAlias, targetAlias, edgeClassName, linkBagFieldName,
        index, orderAsc, comparisonItem.copy(), nullsPlacement,
        skipClause == null ? null : skipClause.copy(),
        limitClause == null ? null : limitClause.copy(), multiSourceMode,
        reverseFieldName, sourceClassName, targetFilter == null ? null : targetFilter.copy(),
        targetClassName,
        edgeTraversal, downstreamEdgeCount, ridTieBreakAccepted, profilingEnabled)
        .copyLookupPlan(this);
  }

  private IndexOrderedEdgeStep copyLookupPlan(IndexOrderedEdgeStep template) {
    targetIndexDescriptor = template.targetIndexDescriptor;
    multiEqualityEligible = template.multiEqualityEligible;
    plannedTraversal = template.plannedTraversal == null ? null : template.plannedTraversal.copy();
    return this;
  }
}
