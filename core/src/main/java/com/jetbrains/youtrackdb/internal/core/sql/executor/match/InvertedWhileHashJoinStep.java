package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
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
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLFromClause;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLFromItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSelectStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Replaces a WHILE-recursive edge traversal with a pre-materialized reachability
 * filter. Instead of per-row DFS up a hierarchy (e.g., IS_SUBCLASS_OF), this step:
 *
 * <ol>
 *   <li><b>Build phase</b>: Find the anchor vertex (e.g., TagClass WHERE name = :param),
 *       then BFS via the inverse edge direction to collect ALL reachable descendant
 *       RIDs into a {@link RidSet}.</li>
 *   <li><b>Probe phase</b>: For each upstream row, check if the probe alias's RID
 *       (e.g., directClass) is in the set. On hit, set the target alias to the anchor
 *       vertex. On miss, discard the row.</li>
 * </ol>
 *
 * <p>This converts O(|upstream| × |hierarchy depth|) into O(|hierarchy| + |upstream|).
 *
 * @see MatchEdgeTraverser#executeTraversal for the per-row WHILE recursion this replaces
 */
class InvertedWhileHashJoinStep extends AbstractExecutionStep {

  private final String anchorClass;
  private final SQLWhereClause anchorFilter;
  private final String edgeLabel;
  private final boolean edgeOut;
  private final String probeAlias;
  private final String targetAlias;
  /** Original edge traversal for runtime fallback when hash join is not feasible. */
  private final EdgeTraversal fallbackEdge;

  @Nullable private Set<RID> reachableRids;

  InvertedWhileHashJoinStep(
      CommandContext ctx,
      String anchorClass,
      SQLWhereClause anchorFilter,
      String edgeLabel,
      boolean edgeOut,
      String probeAlias,
      String targetAlias,
      EdgeTraversal fallbackEdge,
      boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    assert MatchAssertions.checkNotNull(anchorClass, "anchor class");
    assert MatchAssertions.checkNotNull(edgeLabel, "edge label");
    assert MatchAssertions.checkNotNull(probeAlias, "probe alias");
    assert MatchAssertions.checkNotNull(targetAlias, "target alias");
    assert MatchAssertions.checkNotNull(fallbackEdge, "fallback edge");
    this.anchorClass = anchorClass;
    this.anchorFilter = anchorFilter;
    this.edgeLabel = edgeLabel;
    this.edgeOut = edgeOut;
    this.probeAlias = probeAlias;
    this.targetAlias = targetAlias;
    this.fallbackEdge = fallbackEdge;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    assert reachableRids == null
        : "internalStart() called on a step with residual state — was copy() called?";
    if (prev == null) {
      throw new IllegalStateException(
          "inverted-while hash join step requires a previous step");
    }

    // Build phase: find anchor vertices and collect reachable RIDs.
    // Maps each reachable RID → the anchor vertex it descends from, so the
    // probe phase can set targetAlias to the correct anchor.
    // If anchor count exceeds the threshold, fall back to per-row WHILE traversal.
    var session = ctx.getDatabaseSession();
    var anchors = findAnchorVertices(ctx, session);
    if (anchors == null) {
      // Anchor count exceeded threshold — fall back to per-row WHILE traversal
      return fallbackToPerRowTraversal(ctx);
    }
    if (anchors.isEmpty()) {
      // No anchor found → no row can match → consume and discard all upstream
      var upstream = prev.start(ctx);
      return upstream.filter((row, c) -> null);
    }

    // Multi-map: each reachable RID may map to multiple anchors (e.g., a probe
    // vertex can reach several WHILE targets that all satisfy the WHERE filter).
    var ridToAnchors = new HashMap<RID, List<Result>>();
    reachableRids = new RidSet();
    var maxSize = MatchExecutionPlanner.getHashJoinThreshold();
    boolean truncated = false;
    for (var anchor : anchors) {
      var anchorRid = extractRid(anchor);
      if (anchorRid != null) {
        ridToAnchors.computeIfAbsent(anchorRid, k -> new ArrayList<>())
            .add(anchor);
        reachableRids.add(anchorRid);
      }
      var descendants = collectDescendantRids(anchor, session);
      if (maxSize > 0 && descendants.size() >= maxSize) {
        truncated = true;
      }
      for (var descendantRid : descendants) {
        reachableRids.add(descendantRid);
        ridToAnchors.computeIfAbsent(descendantRid, k -> new ArrayList<>())
            .add(anchor);
      }
    }

    // Capture locally for null-safety
    var builtSet = reachableRids;
    var builtMap = ridToAnchors;
    var wasTruncated = truncated;

    // Probe phase: for each upstream row, emit one result per matching anchor.
    // When the build-phase BFS was truncated, probe misses may be false
    // negatives — fall back to per-row forward BFS for those rows.
    var upstream = prev.start(ctx);
    return upstream.flatMap((row, c) -> {
      var probeValue = row.getProperty(probeAlias);
      var rid = extractRid(probeValue);
      if (rid == null) {
        return ExecutionStream.empty();
      }
      List<Result> matched = null;
      if (builtSet.contains(rid)) {
        matched = builtMap.get(rid);
      } else if (wasTruncated) {
        matched = forwardBfsToAnchors(
            rid, builtSet, builtMap, c.getDatabaseSession());
      }
      if (matched == null || matched.isEmpty()) {
        return ExecutionStream.empty();
      }
      if (matched.size() == 1) {
        return ExecutionStream.singleton(new MatchResultRow(
            c.getDatabaseSession(), row, targetAlias, matched.getFirst()));
      }
      var results = new ArrayList<Result>(matched.size());
      for (var a : matched) {
        results.add(new MatchResultRow(
            c.getDatabaseSession(), row, targetAlias, a));
      }
      return ExecutionStream.resultIterator(results.iterator());
    });
  }

  /**
   * Finds anchor vertices matching the anchor filter, up to the hash join threshold.
   * Returns {@code null} if the number of anchors exceeds the threshold, signalling
   * the caller to fall back to per-row WHILE traversal.
   */
  @Nullable private List<Result> findAnchorVertices(CommandContext ctx, DatabaseSessionEmbedded session) {
    var select = new SQLSelectStatement(-1);
    var from = new SQLFromClause(-1);
    var fromItem = new SQLFromItem(-1);
    fromItem.setIdentifier(new SQLIdentifier(anchorClass));
    from.setItem(fromItem);
    select.setTarget(from);
    select.setWhereClause(anchorFilter != null ? anchorFilter.copy() : null);

    var maxSize = MatchExecutionPlanner.getHashJoinThreshold();
    var subCtx = new BasicCommandContext();
    subCtx.setParentWithoutOverridingChild(ctx);
    var plan = select.createExecutionPlan(subCtx, false);
    var results = new ArrayList<Result>();
    var stream = plan.start();
    try {
      while (stream.hasNext(subCtx)) {
        results.add(toResultInternal(stream.next(subCtx), session));
        if (maxSize > 0 && results.size() >= maxSize) {
          return null;
        }
      }
    } finally {
      stream.close(subCtx);
      plan.close();
    }
    return results;
  }

  /**
   * Collects all descendant RIDs reachable from the anchor vertex via the inverse
   * edge direction (BFS). Does NOT include the anchor itself — the caller adds it.
   * Size is bounded by the hash join threshold to prevent OOM on large graphs.
   */
  private Set<RID> collectDescendantRids(Result anchor, DatabaseSessionEmbedded session) {
    var rids = new RidSet();
    var anchorRid = extractRid(anchor);
    if (anchorRid == null) {
      return rids;
    }

    // Level-by-level BFS from anchor via inverse edge direction.
    // Each frontier node is queried individually to avoid reliance on
    // List<RID> parameter binding behavior which varies across backends.
    var maxSize = MatchExecutionPlanner.getHashJoinThreshold();
    var inverseDir = edgeOut ? "in" : "out";
    var sql = "SELECT expand(" + inverseDir + "('" + edgeLabel + "')) FROM ?";
    var frontier = new ArrayList<RID>();
    frontier.add(anchorRid);
    while (!frontier.isEmpty() && (maxSize <= 0 || rids.size() < maxSize)) {
      var nextFrontier = new ArrayList<RID>();
      for (var current : frontier) {
        try (var rs = session.query(sql, current)) {
          while (rs.hasNext()) {
            var row = rs.next();
            var rid = extractRid(row);
            if (rid != null && rids.add(rid)) {
              nextFrontier.add(rid);
            }
          }
        }
        if (maxSize > 0 && rids.size() >= maxSize) {
          break;
        }
      }
      frontier = nextFrontier;
    }
    return rids;
  }

  /**
   * Per-row fallback when the build-phase BFS was truncated: walks FORWARD from
   * probeRid (toward the anchors) to find all reachable anchors. Collects unique
   * anchors from all builtSet vertices encountered during the BFS. Returns
   * {@code null} if no anchor is reachable.
   */
  @Nullable private List<Result> forwardBfsToAnchors(
      RID probeRid, Set<RID> builtSet,
      Map<RID, List<Result>> ridToAnchors,
      DatabaseSessionEmbedded session) {
    var forwardDir = edgeOut ? "out" : "in";
    var sql = "SELECT expand(" + forwardDir + "('" + edgeLabel + "')) FROM ?";
    var visited = new RidSet();
    var frontier = new ArrayList<RID>();
    frontier.add(probeRid);
    var foundAnchorRids = new RidSet();
    var foundAnchors = new ArrayList<Result>();
    while (!frontier.isEmpty()) {
      var nextFrontier = new ArrayList<RID>();
      for (var current : frontier) {
        try (var rs = session.query(sql, current)) {
          while (rs.hasNext()) {
            var row = rs.next();
            var rid = extractRid(row);
            if (rid != null && visited.add(rid)) {
              if (builtSet.contains(rid)) {
                var mapped = ridToAnchors.get(rid);
                if (mapped != null) {
                  for (var a : mapped) {
                    var aRid = extractRid(a);
                    if (aRid != null && foundAnchorRids.add(aRid)) {
                      foundAnchors.add(a);
                    }
                  }
                }
              }
              nextFrontier.add(rid);
            }
          }
        }
      }
      frontier = nextFrontier;
    }
    return foundAnchors.isEmpty() ? null : foundAnchors;
  }

  /**
   * Falls back to per-row WHILE traversal when the hash join build phase is not
   * feasible (e.g., too many anchors). Delegates to the same traverser selection
   * that {@link MatchStep} uses, preserving full correctness.
   */
  private ExecutionStream fallbackToPerRowTraversal(CommandContext ctx) {
    var upstream = prev.start(ctx);
    return upstream.flatMap((row, c) -> {
      if (fallbackEdge.out) {
        return new MatchEdgeTraverser(row, fallbackEdge);
      } else {
        return new MatchReverseEdgeTraverser(row, fallbackEdge);
      }
    });
  }

  /** Extracts RID from a value that may be a RID, Identifiable, or Result. */
  @Nullable static RID extractRid(Object value) {
    if (value instanceof RID rid) {
      return rid;
    }
    if (value instanceof Identifiable identifiable) {
      return identifiable.getIdentity();
    }
    if (value instanceof Result result && result.isEntity()) {
      return result.asEntity().getIdentity();
    }
    return null;
  }

  private static Result toResultInternal(Result result, DatabaseSessionEmbedded session) {
    if (result.isEntity()) {
      return new ResultInternal(session, result.asEntity());
    }
    return result;
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
        + "+ INVERTED WHILE HASH JOIN on "
        + probeAlias + " → " + targetAlias
        + " (anchor: " + anchorClass
        + ", edge: " + edgeLabel + ")";
  }

  @Override
  public void close() {
    reachableRids = null;
    super.close();
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new InvertedWhileHashJoinStep(
        ctx, anchorClass,
        anchorFilter != null ? anchorFilter.copy() : null,
        edgeLabel, edgeOut, probeAlias, targetAlias, fallbackEdge,
        profilingEnabled);
  }
}
