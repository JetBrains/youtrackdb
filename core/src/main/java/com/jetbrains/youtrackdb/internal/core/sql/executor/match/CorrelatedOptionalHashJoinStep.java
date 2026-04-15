package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.AbstractExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ExecutionStepInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.RidSet;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Replaces an optional edge with a {@code $matched} correlation
 * (e.g., {@code .out('KNOWS'){where: (@rid = $matched.startPerson.@rid), optional: true}})
 * with a pre-materialized hash lookup.
 *
 * <p>Instead of per-row edge traversal + WHERE filter, this step:
 * <ol>
 *   <li><b>Build phase</b>: Read the correlated alias vertex from the first upstream
 *       row. Traverse its edges (e.g., {@code startPerson.in('KNOWS')}) to collect
 *       all neighbor RIDs into a {@link RidSet}.</li>
 *   <li><b>Probe phase</b>: For each upstream row, check if the probe alias's RID
 *       (e.g., liker) is in the set. On hit, set the target alias to the correlated
 *       vertex. On miss, set the target alias to {@code null} (LEFT/optional semantics —
 *       all rows pass through).</li>
 * </ol>
 *
 * <p>This converts O(|upstream| × |avg edge neighbors|) into
 * O(|edge neighbors| + |upstream|).
 */
class CorrelatedOptionalHashJoinStep extends AbstractExecutionStep {

  /** Cached neighbor set with its truncation state. */
  private record NeighborEntry(Set<RID> rids, boolean truncated) {
  }

  private static final int NEIGHBOR_CACHE_SIZE = 16;

  private final String correlatedAlias;
  private final String probeAlias;
  private final String targetAlias;
  private final String edgeLabel;
  private final boolean edgeOut;

  /**
   * LRU cache of correlated RID → neighbor set. Handles interleaved upstream
   * rows (e.g., after a preceding INNER_JOIN that reorders by a different key)
   * without rebuilding the neighbor set on every alternation.
   */
  private final Map<RID, NeighborEntry> neighborCache =
      new LinkedHashMap<>(NEIGHBOR_CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<RID, NeighborEntry> eldest) {
          return size() > NEIGHBOR_CACHE_SIZE;
        }
      };

  CorrelatedOptionalHashJoinStep(
      CommandContext ctx,
      String correlatedAlias,
      String probeAlias,
      String targetAlias,
      String edgeLabel,
      boolean edgeOut,
      boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    assert MatchAssertions.checkNotNull(correlatedAlias, "correlated alias");
    assert MatchAssertions.checkNotNull(probeAlias, "probe alias");
    assert MatchAssertions.checkNotNull(targetAlias, "target alias");
    assert MatchAssertions.checkNotNull(edgeLabel, "edge label");
    this.correlatedAlias = correlatedAlias;
    this.probeAlias = probeAlias;
    this.targetAlias = targetAlias;
    this.edgeLabel = edgeLabel;
    this.edgeOut = edgeOut;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    assert neighborCache.isEmpty()
        : "internalStart() called on a step with residual state — was copy() called?";
    if (prev == null) {
      throw new IllegalStateException(
          "correlated optional hash join step requires a previous step");
    }

    var session = ctx.getDatabaseSession();
    var upstream = prev.start(ctx);

    // LRU-cached probe: look up or build the neighbor set for the current
    // correlated RID. Handles interleaved upstream rows without rebuilding
    // on every alternation.
    return upstream.map((row, c) -> {
      var correlatedValue = row.getProperty(correlatedAlias);
      var currentCorrelatedRid = InvertedWhileHashJoinStep.extractRid(correlatedValue);
      if (currentCorrelatedRid == null) {
        // No correlated vertex → optional semantics: pass row with null target
        return new MatchResultRow(session, row, targetAlias, null);
      }
      var entry = neighborCache.computeIfAbsent(
          currentCorrelatedRid, rid -> buildNeighborEntry(row, session));
      var localSet = entry.rids();

      var probeValue = row.getProperty(probeAlias);
      var probeRid = InvertedWhileHashJoinStep.extractRid(probeValue);
      if (probeRid != null && localSet.contains(probeRid)) {
        // Hit: liker KNOWS startPerson → set targetAlias to correlated vertex
        var matchValue = toResultOrNull(correlatedValue, session);
        return new MatchResultRow(session, row, targetAlias, matchValue);
      }
      if (entry.truncated() && probeRid != null
          && currentCorrelatedRid != null) {
        // Truncated set — probe miss might be false negative.
        // Per-row check: does probeRid have a direct edge to correlated vertex?
        if (checkEdgeFallback(probeRid, currentCorrelatedRid, session)) {
          var matchValue = toResultOrNull(correlatedValue, session);
          return new MatchResultRow(session, row, targetAlias, matchValue);
        }
      }
      // Definitive miss or null probe: optional semantics → null
      return new MatchResultRow(session, row, targetAlias, null);
    });
  }

  /**
   * Builds the neighbor RID set from the correlated vertex. For IC7:
   * correlatedAlias = startPerson, edge = out('KNOWS'), so we collect
   * all RIDs reachable via startPerson.out('KNOWS') (people startPerson knows)
   * OR startPerson.in('KNOWS') depending on the edge direction.
   *
   * <p>The probe checks if liker is in this set. For the IC7 query, the optional
   * edge is {@code .out('KNOWS'){where: @rid = $matched.startPerson.@rid}},
   * meaning: does traversing liker.out('KNOWS') reach startPerson? This is
   * equivalent to: is liker in startPerson.in('KNOWS')?
   */
  private NeighborEntry buildNeighborEntry(
      Result firstRow, DatabaseSessionEmbedded session) {
    var set = new RidSet();
    var correlatedValue = firstRow.getProperty(correlatedAlias);
    var correlatedRid = InvertedWhileHashJoinStep.extractRid(correlatedValue);
    if (correlatedRid == null) {
      return new NeighborEntry(set, false);
    }

    // The original edge is from probeAlias to targetAlias via edgeLabel.
    // The WHERE filter checks @rid = $matched.correlatedAlias.@rid on the target.
    // If edgeOut: probeAlias.out(label) should reach correlatedAlias
    //   → correlatedAlias.in(label) gives all vertices that can reach it
    // If !edgeOut: probeAlias.in(label) should reach correlatedAlias
    //   → correlatedAlias.out(label) gives all vertices that can reach it
    var inverseDir = edgeOut ? "in" : "out";
    var sql = "SELECT expand(" + inverseDir + "('" + edgeLabel + "')) FROM ?";

    var maxSize = MatchExecutionPlanner.getHashJoinThreshold();
    try (var rs = session.query(sql, correlatedRid)) {
      while (rs.hasNext() && (maxSize <= 0 || set.size() < maxSize)) {
        var row = rs.next();
        var rid = InvertedWhileHashJoinStep.extractRid(row);
        if (rid != null) {
          set.add(rid);
        }
      }
    }
    var truncated = maxSize > 0 && set.size() >= maxSize;
    return new NeighborEntry(set, truncated);
  }

  /**
   * Per-row fallback when the build-phase neighbor collection was truncated:
   * checks if probeRid has a direct edge to the correlated vertex.
   */
  private boolean checkEdgeFallback(
      RID probeRid, RID correlatedRid, DatabaseSessionEmbedded session) {
    var inverseDir = edgeOut ? "in" : "out";
    var sql = "SELECT FROM (SELECT expand(" + inverseDir + "('" + edgeLabel
        + "')) FROM ?) WHERE @rid = ?";
    try (var rs = session.query(sql, correlatedRid, probeRid)) {
      return rs.hasNext();
    }
  }

  @Nullable private static Result toResultOrNull(Object value, DatabaseSessionEmbedded session) {
    if (value instanceof Result result) {
      return result;
    }
    if (value instanceof Identifiable identifiable) {
      return new ResultInternal(session, identifiable);
    }
    return null;
  }

  @Override
  public boolean canBeCached() {
    return true;
  }

  @Nonnull
  @Override
  public List<ExecutionStep> getSubSteps() {
    return List.of();
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    return spaces
        + "+ CORRELATED OPTIONAL HASH JOIN "
        + probeAlias + " → " + targetAlias
        + " (correlated: " + correlatedAlias
        + ", edge: " + edgeLabel + ")";
  }

  @Override
  public void close() {
    neighborCache.clear();
    super.close();
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new CorrelatedOptionalHashJoinStep(
        ctx, correlatedAlias, probeAlias, targetAlias,
        edgeLabel, edgeOut, profilingEnabled);
  }
}
