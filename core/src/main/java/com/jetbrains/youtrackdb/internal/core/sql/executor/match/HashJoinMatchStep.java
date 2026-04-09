package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.AbstractExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ExecutionStepInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.SelectExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Hash-based join step for MATCH patterns. Replaces {@link FilterNotMatchPatternStep}
 * when the NOT pattern qualifies for hash anti-join (no {@code $matched} dependency,
 * estimated cardinality below threshold).
 *
 * <p>Execution has two phases:
 * <ol>
 *   <li><b>Build phase</b>: Execute the build-side {@link SelectExecutionPlan}
 *       independently (using a copied {@link CommandContext} to isolate
 *       {@code $matched}), collect all result rows, and extract shared alias
 *       values into a {@link HashSet} of {@link JoinKey}s (for ANTI/SEMI) or
 *       a {@link HashMap} of {@code JoinKey → List<Result>} (for INNER_JOIN,
 *       storing full flattened rows).</li>
 *   <li><b>Probe phase</b>: For each upstream row, extract the same shared alias
 *       values and probe the hash structure. The {@link JoinMode} determines
 *       the behavior:
 *       <ul>
 *         <li>{@link JoinMode#ANTI_JOIN}: filter — keep if key is NOT found
 *             (NOT pattern)</li>
 *         <li>{@link JoinMode#SEMI_JOIN}: filter — keep if key IS found
 *             (EXISTS filter)</li>
 *         <li>{@link JoinMode#INNER_JOIN}: flatMap — for each match, create a
 *             merged row with upstream + build-side properties</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <p>The hash structure is built eagerly in {@link #internalStart} before any
 * upstream rows are consumed. It is built per-execution (not cached), so there
 * are no thread-safety concerns.
 *
 * <p>When the build-side plan produces zero rows, the hash set/map is empty.
 * In ANTI_JOIN mode this means all upstream rows pass through (no matches to
 * exclude). In SEMI_JOIN and INNER_JOIN mode this means all upstream rows are
 * filtered out (no matches possible).
 *
 * @see FilterNotMatchPatternStep
 * @see MatchExecutionPlanner
 */
class HashJoinMatchStep extends AbstractExecutionStep {

  private final SelectExecutionPlan buildPlan;
  private final List<String> sharedAliases;
  private final JoinMode joinMode;

  @Nullable private Set<JoinKey> hashSet;
  @Nullable private Map<JoinKey, List<Result>> hashMap;

  HashJoinMatchStep(
      CommandContext ctx,
      SelectExecutionPlan buildPlan,
      List<String> sharedAliases,
      JoinMode joinMode,
      boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    assert MatchAssertions.checkNotNull(buildPlan, "build-side plan");
    assert MatchAssertions.checkNotNull(sharedAliases, "shared aliases");
    assert MatchAssertions.checkNotNull(joinMode, "join mode");
    assert !sharedAliases.isEmpty() : "shared aliases must not be empty";
    this.buildPlan = buildPlan;
    this.sharedAliases = List.copyOf(sharedAliases);
    this.joinMode = joinMode;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    if (prev == null) {
      throw new IllegalStateException("hash join step requires a previous step");
    }

    if (joinMode == JoinMode.INNER_JOIN) {
      // Build phase: execute build-side plan, store full flattened rows
      hashMap = buildHashMap(ctx);

      if (hashMap == null) {
        // Build exceeded threshold — fall back to nested-loop per-row evaluation
        var upstream = prev.start(ctx);
        return upstream.flatMap((row, c) -> nestedLoopInnerJoin(row, c));
      }

      // Capture locally for null-safety in the flatMap lambda
      var builtMap = hashMap;

      // Probe phase: for each upstream row, emit merged rows for all matches
      var upstream = prev.start(ctx);
      return upstream.flatMap((row, c) -> mergeMatches(row, builtMap, c));
    }

    // Build phase: execute build-side plan with isolated context
    hashSet = buildHashSet(ctx);

    if (hashSet == null) {
      // Build exceeded threshold — fall back to nested-loop per-row evaluation
      var upstream = prev.start(ctx);
      return upstream.filter((row, c) -> nestedLoopProbe(row, c));
    }

    // Capture the reference locally so that the filter lambda is safe even if
    // close() nulls the field mid-stream (e.g., due to timeout).
    var builtSet = hashSet;

    // Probe phase: filter upstream rows against the hash set
    var upstream = prev.start(ctx);
    return upstream.filter((row, c) -> probeFilter(row, builtSet));
  }

  /**
   * Executes the build-side plan and collects shared alias values into a hash set.
   * Deep-copies the build plan with a fresh {@link BasicCommandContext} so that
   * build-side {@link MatchStep}s execute against the isolated context (not the
   * parent) — this prevents {@code $matched} pollution.
   */
  /**
   * Returns null if the build set exceeds the runtime threshold — the caller
   * must fall back to per-row nested-loop evaluation.
   */
  @Nullable private Set<JoinKey> buildHashSet(CommandContext ctx) {
    var isolatedCtx = new BasicCommandContext();
    isolatedCtx.setParentWithoutOverridingChild(ctx);

    var isolatedPlan = (SelectExecutionPlan) buildPlan.copy(isolatedCtx);
    var set = new HashSet<JoinKey>();
    var maxSize = MatchExecutionPlanner.getHashJoinThreshold();

    var stream = isolatedPlan.start();
    try {
      while (stream.hasNext(isolatedCtx)) {
        var row = stream.next(isolatedCtx);
        var key = extractKey(row);
        if (key != null) {
          set.add(key);
          if (maxSize > 0 && set.size() > maxSize) {
            return null; // threshold exceeded — caller falls back
          }
        }
      }
    } finally {
      stream.close(isolatedCtx);
      isolatedPlan.close();
    }
    return set;
  }

  /**
   * Returns null if the build map exceeds the runtime threshold — the caller
   * must fall back to per-row nested-loop evaluation.
   */
  @Nullable private Map<JoinKey, List<Result>> buildHashMap(CommandContext ctx) {
    var isolatedCtx = new BasicCommandContext();
    isolatedCtx.setParentWithoutOverridingChild(ctx);

    var isolatedPlan = (SelectExecutionPlan) buildPlan.copy(isolatedCtx);
    var map = new HashMap<JoinKey, List<Result>>();
    var maxSize = MatchExecutionPlanner.getHashJoinThreshold();
    long totalRows = 0;

    var stream = isolatedPlan.start();
    try {
      while (stream.hasNext(isolatedCtx)) {
        var row = stream.next(isolatedCtx);
        var key = extractKey(row);
        if (key != null) {
          var flat = new ResultInternal(ctx.getDatabaseSession());
          for (var prop : row.getPropertyNames()) {
            flat.setProperty(prop, row.getProperty(prop));
          }
          map.computeIfAbsent(key, k -> new ArrayList<>()).add(flat);
          totalRows++;
          if (maxSize > 0 && totalRows > maxSize) {
            return null; // threshold exceeded — caller falls back
          }
        }
      }
    } finally {
      stream.close(isolatedCtx);
      isolatedPlan.close();
    }
    return map;
  }

  /**
   * Merges matching build-side rows into each upstream row for INNER_JOIN.
   * For each matching build-side row, a new {@link ResultInternal} is created
   * containing all upstream properties plus build-side properties (skipping
   * any already present from the upstream row). Returns an empty
   * stream if the upstream key is null or has no match.
   */
  private ExecutionStream mergeMatches(
      Result upstream, Map<JoinKey, List<Result>> map, CommandContext mergeCtx) {
    var key = extractKey(upstream);
    if (key == null) {
      return ExecutionStream.empty();
    }
    var matches = map.get(key);
    if (matches == null) {
      return ExecutionStream.empty();
    }
    // Cache upstream property values once to avoid repeated getProperty() calls
    // through the MatchResultRow parent chain (O(depth × properties) per call).
    var upstreamCache = cacheProperties(upstream);
    var merged = new ArrayList<Result>(matches.size());
    for (var buildRow : matches) {
      merged.add(mergeRow(upstreamCache, buildRow, mergeCtx.getDatabaseSession()));
    }
    return ExecutionStream.resultIterator(merged.iterator());
  }

  /**
   * Caches all property name→value pairs from a Result into a flat map.
   * This avoids repeated {@code getProperty()} calls through the MatchResultRow
   * parent chain, which is O(depth × properties) per call for deep MATCH chains.
   */
  private static Map<String, Object> cacheProperties(Result row) {
    var props = row.getPropertyNames();
    var cache = new LinkedHashMap<String, Object>(props.size() * 2);
    for (var prop : props) {
      cache.put(prop, row.getProperty(prop));
    }
    return cache;
  }

  /**
   * Creates a merged row from pre-cached upstream properties plus non-overlapping
   * build-side properties. Upstream properties take precedence on name collision.
   */
  private static ResultInternal mergeRow(
      Map<String, Object> upstreamCache, Result buildRow,
      DatabaseSessionEmbedded session) {
    var result = new ResultInternal(session);
    upstreamCache.forEach(result::setProperty);
    for (var prop : buildRow.getPropertyNames()) {
      if (!result.hasProperty(prop)) {
        result.setProperty(prop, buildRow.getProperty(prop));
      }
    }
    return result;
  }

  /**
   * Probe filter applied to each upstream row. Returns the row if it should
   * be kept, or {@code null} to discard it. The hash set is passed explicitly
   * (captured at lambda creation time) to avoid null dereference if
   * {@link #close()} is called mid-stream.
   */
  @Nullable private Result probeFilter(Result row, Set<JoinKey> set) {
    var key = extractKey(row);
    if (key == null) {
      // Cannot extract key — conservative: keep in ANTI_JOIN, discard in SEMI_JOIN
      return joinMode == JoinMode.ANTI_JOIN ? row : null;
    }
    var found = set.contains(key);
    return switch (joinMode) {
      case ANTI_JOIN -> found ? null : row;
      case SEMI_JOIN -> found ? row : null;
      case INNER_JOIN -> throw new IllegalStateException(
          "INNER_JOIN uses flatMap, not filter");
    };
  }

  /**
   * Extracts a {@link JoinKey} from a result row using the shared alias names.
   * Uses the RID fast path when all alias values are {@link RID} instances;
   * falls back to Object[] otherwise.
   */
  @Nullable private JoinKey extractKey(Result row) {
    if (sharedAliases.size() == 1) {
      var value = row.getProperty(sharedAliases.getFirst());
      if (value instanceof RID rid) {
        return JoinKey.ofRid(rid);
      }
      if (value == null) {
        return null;
      }
      return JoinKey.ofObjectsOwned(new Object[] {value});
    }

    // Multi-alias: try to populate RID[] directly, fall back to Object[] on
    // first non-RID value (avoids double array allocation in the common case).
    var size = sharedAliases.size();
    var rids = new RID[size];
    for (int i = 0; i < size; i++) {
      var value = row.getProperty(sharedAliases.get(i));
      if (value == null) {
        return null;
      }
      if (value instanceof RID rid) {
        rids[i] = rid;
      } else {
        // Non-RID found — fall back to Object[] for all values
        var objects = new Object[size];
        for (int j = 0; j < i; j++) {
          objects[j] = rids[j]; // copy already-extracted RIDs
        }
        objects[i] = value;
        for (int j = i + 1; j < size; j++) {
          objects[j] = row.getProperty(sharedAliases.get(j));
          if (objects[j] == null) {
            return null;
          }
        }
        return JoinKey.ofObjectsOwned(objects);
      }
    }
    return JoinKey.ofRidsOwned(rids);
  }

  /**
   * Nested-loop fallback for ANTI/SEMI_JOIN when the build set exceeded the
   * runtime threshold. For each upstream row, re-executes the build plan in an
   * isolated context and checks if ANY build-side row has a matching key.
   */
  @Nullable private Result nestedLoopProbe(Result row, CommandContext ctx) {
    var upstreamKey = extractKey(row);
    if (upstreamKey == null) {
      return joinMode == JoinMode.ANTI_JOIN ? row : null;
    }

    var isolatedCtx = new BasicCommandContext();
    isolatedCtx.setParentWithoutOverridingChild(ctx);
    var plan = (SelectExecutionPlan) buildPlan.copy(isolatedCtx);
    var stream = plan.start();
    boolean found = false;
    try {
      while (stream.hasNext(isolatedCtx)) {
        var buildRow = stream.next(isolatedCtx);
        var buildKey = extractKey(buildRow);
        if (upstreamKey.equals(buildKey)) {
          found = true;
          break;
        }
      }
    } finally {
      stream.close(isolatedCtx);
      plan.close();
    }
    return switch (joinMode) {
      case ANTI_JOIN -> found ? null : row;
      case SEMI_JOIN -> found ? row : null;
      case INNER_JOIN -> throw new IllegalStateException("use nestedLoopInnerJoin");
    };
  }

  /**
   * Nested-loop fallback for INNER_JOIN when the build map exceeded the
   * runtime threshold. For each upstream row, re-executes the build plan and
   * merges all matching build-side rows.
   */
  private ExecutionStream nestedLoopInnerJoin(Result row, CommandContext ctx) {
    var upstreamKey = extractKey(row);
    if (upstreamKey == null) {
      return ExecutionStream.empty();
    }

    var isolatedCtx = new BasicCommandContext();
    isolatedCtx.setParentWithoutOverridingChild(ctx);
    var plan = (SelectExecutionPlan) buildPlan.copy(isolatedCtx);
    var stream = plan.start();
    var merged = new ArrayList<Result>();
    // Lazy-init: only cache upstream properties if at least one match is found
    Map<String, Object> upstreamCache = null;
    try {
      while (stream.hasNext(isolatedCtx)) {
        var buildRow = stream.next(isolatedCtx);
        var buildKey = extractKey(buildRow);
        if (upstreamKey.equals(buildKey)) {
          if (upstreamCache == null) {
            upstreamCache = cacheProperties(row);
          }
          merged.add(mergeRow(upstreamCache, buildRow, ctx.getDatabaseSession()));
        }
      }
    } finally {
      stream.close(isolatedCtx);
      plan.close();
    }
    return ExecutionStream.resultIterator(merged.iterator());
  }

  @Override
  public boolean canBeCached() {
    return buildPlan.canBeCached();
  }

  @Nonnull
  @Override
  public List<ExecutionStep> getSubSteps() {
    return List.copyOf(buildPlan.getSteps());
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    var result = new StringBuilder();
    result.append(spaces);
    result.append("+ HASH ").append(joinMode).append(" on ").append(sharedAliases);
    result.append(" (\n");
    result.append(buildPlan.prettyPrint(depth + 1, indent));
    result.append("\n");
    result.append(spaces);
    result.append("  )");
    return result.toString();
  }

  @Override
  public void close() {
    hashSet = null;
    hashMap = null;
    try {
      buildPlan.close();
    } finally {
      super.close();
    }
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    var planCopy = (SelectExecutionPlan) buildPlan.copy(ctx);
    return new HashJoinMatchStep(ctx, planCopy, sharedAliases, joinMode,
        profilingEnabled);
  }
}
