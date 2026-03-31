package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
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
 *       values into a {@link HashSet} of {@link JoinKey}s.</li>
 *   <li><b>Probe phase</b>: For each upstream row, extract the same shared alias
 *       values and probe the hash set. The {@link JoinMode} determines the
 *       filtering logic:
 *       <ul>
 *         <li>{@link JoinMode#ANTI_JOIN}: keep if key is NOT found (NOT pattern)</li>
 *         <li>{@link JoinMode#SEMI_JOIN}: keep if key IS found (EXISTS filter)</li>
 *         <li>{@link JoinMode#INNER_JOIN}: enrich with matching build-side rows</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <p>The hash table is built eagerly in {@link #internalStart} before any
 * upstream rows are consumed. It is built per-execution (not cached), so there
 * are no thread-safety concerns.
 *
 * <p>When the build-side plan produces zero rows, the hash set is empty.
 * In ANTI_JOIN mode this means all upstream rows pass through (no matches to
 * exclude). In SEMI_JOIN mode this means all upstream rows are filtered out.
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

      // Capture locally for null-safety in the flatMap lambda
      var builtMap = hashMap;

      // Probe phase: for each upstream row, emit merged rows for all matches
      var upstream = prev.start(ctx);
      return upstream.flatMap((row, c) -> mergeMatches(row, builtMap, c));
    }

    // Build phase: execute build-side plan with isolated context
    hashSet = buildHashSet(ctx);

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
  private Set<JoinKey> buildHashSet(CommandContext ctx) {
    var isolatedCtx = new BasicCommandContext();
    isolatedCtx.setDatabaseSession(ctx.getDatabaseSession());

    // Deep-copy the build plan bound to the isolated context so that every
    // step in the plan (including MatchSteps that write $matched) operates
    // on the isolated context, not the parent.
    var isolatedPlan = (SelectExecutionPlan) buildPlan.copy(isolatedCtx);
    var set = new HashSet<JoinKey>();

    var stream = isolatedPlan.start();
    try {
      while (stream.hasNext(isolatedCtx)) {
        var row = stream.next(isolatedCtx);
        var key = extractKey(row);
        if (key != null) {
          set.add(key);
        }
      }
    } finally {
      stream.close(isolatedCtx);
      isolatedPlan.close();
    }
    return set;
  }

  /**
   * Executes the build-side plan and collects full (flattened) rows into a hash map
   * keyed by shared alias values. Used for {@link JoinMode#INNER_JOIN} where the
   * build-side row data must be merged into upstream rows during the probe phase.
   *
   * <p>Each build-side row is flattened into a plain {@link ResultInternal} (copying
   * all properties) to avoid retaining the layered {@code MatchResultRow} chain in
   * memory.
   */
  private Map<JoinKey, List<Result>> buildHashMap(CommandContext ctx) {
    var isolatedCtx = new BasicCommandContext();
    isolatedCtx.setDatabaseSession(ctx.getDatabaseSession());

    var isolatedPlan = (SelectExecutionPlan) buildPlan.copy(isolatedCtx);
    var map = new HashMap<JoinKey, List<Result>>();

    var stream = isolatedPlan.start();
    try {
      while (stream.hasNext(isolatedCtx)) {
        var row = stream.next(isolatedCtx);
        var key = extractKey(row);
        if (key != null) {
          // Flatten: copy all properties into a plain ResultInternal to avoid
          // retaining the layered MatchResultRow chain in memory.
          var flat = new ResultInternal(ctx.getDatabaseSession());
          for (var prop : row.getPropertyNames()) {
            flat.setProperty(prop, row.getProperty(prop));
          }
          map.computeIfAbsent(key, k -> new ArrayList<>()).add(flat);
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
   * shared aliases already present from the upstream row). Returns an empty
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
    var merged = new ArrayList<Result>(matches.size());
    for (var buildRow : matches) {
      var result = new ResultInternal(mergeCtx.getDatabaseSession());
      // Copy all upstream properties first
      for (var prop : upstream.getPropertyNames()) {
        result.setProperty(prop, upstream.getProperty(prop));
      }
      // Copy build-side properties, skipping shared aliases already present
      for (var prop : buildRow.getPropertyNames()) {
        if (!result.hasProperty(prop)) {
          result.setProperty(prop, buildRow.getProperty(prop));
        }
      }
      merged.add(result);
    }
    return ExecutionStream.resultIterator(merged.iterator());
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

    // Multi-alias: check if all values are RIDs
    var size = sharedAliases.size();
    var values = new Object[size];
    var allRids = true;
    for (int i = 0; i < size; i++) {
      values[i] = row.getProperty(sharedAliases.get(i));
      if (values[i] == null) {
        return null;
      }
      if (!(values[i] instanceof RID)) {
        allRids = false;
      }
    }

    if (allRids) {
      var rids = new RID[size];
      for (int i = 0; i < size; i++) {
        rids[i] = (RID) values[i];
      }
      return JoinKey.ofRidsOwned(rids);
    }
    return JoinKey.ofObjectsOwned(values);
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
