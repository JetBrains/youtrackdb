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
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLFromClause;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLFromItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSelectStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.HashSet;
import java.util.List;
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
 *       RIDs into a {@link HashSet}.</li>
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

  @Nullable private Set<RID> reachableRids;

  InvertedWhileHashJoinStep(
      CommandContext ctx,
      String anchorClass,
      SQLWhereClause anchorFilter,
      String edgeLabel,
      boolean edgeOut,
      String probeAlias,
      String targetAlias,
      boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    // anchorClass may be null if neither alias has an explicit class constraint
    assert MatchAssertions.checkNotNull(edgeLabel, "edge label");
    assert MatchAssertions.checkNotNull(probeAlias, "probe alias");
    assert MatchAssertions.checkNotNull(targetAlias, "target alias");
    this.anchorClass = anchorClass;
    this.anchorFilter = anchorFilter;
    this.edgeLabel = edgeLabel;
    this.edgeOut = edgeOut;
    this.probeAlias = probeAlias;
    this.targetAlias = targetAlias;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    if (prev == null) {
      throw new IllegalStateException(
          "inverted-while hash join step requires a previous step");
    }

    // Build phase: find anchor vertex and collect all reachable RIDs
    var session = ctx.getDatabaseSession();
    var anchorResult = findAnchorVertex(ctx);
    if (anchorResult == null) {
      // Anchor not found → no row can match → consume and discard all upstream
      var upstream = prev.start(ctx);
      return upstream.filter((row, c) -> null);
    }

    var anchorVertex = toResultInternal(anchorResult, session);
    reachableRids = collectReachableRids(anchorVertex, session);

    // Capture locally for null-safety
    var builtSet = reachableRids;
    var anchor = anchorVertex;

    // Probe phase: filter upstream rows by directClass membership
    var upstream = prev.start(ctx);
    return upstream.filter((row, c) -> {
      var probeValue = row.getProperty(probeAlias);
      var rid = extractRid(probeValue);
      if (rid == null || !builtSet.contains(rid)) {
        return null; // Not in hierarchy → discard
      }
      // Match: set targetAlias to the anchor vertex
      return new MatchResultRow(c.getDatabaseSession(), row, targetAlias, anchor);
    });
  }

  /**
   * Finds the anchor vertex by executing SELECT FROM anchorClass WHERE anchorFilter.
   */
  @Nullable private Result findAnchorVertex(CommandContext ctx) {
    var select = new SQLSelectStatement(-1);
    var from = new SQLFromClause(-1);
    var fromItem = new SQLFromItem(-1);
    if (anchorClass != null) {
      fromItem.setIdentifier(new SQLIdentifier(anchorClass));
    } else {
      // No class constraint — scan all vertices matching the WHERE filter
      fromItem.setIdentifier(new SQLIdentifier("V"));
    }
    from.setItem(fromItem);
    select.setTarget(from);
    select.setWhereClause(anchorFilter != null ? anchorFilter.copy() : null);

    var subCtx = new BasicCommandContext();
    subCtx.setParentWithoutOverridingChild(ctx);
    var plan = select.createExecutionPlan(subCtx, false);
    var stream = plan.start();
    try {
      if (stream.hasNext(subCtx)) {
        return stream.next(subCtx);
      }
      return null;
    } finally {
      stream.close(subCtx);
      plan.close();
    }
  }

  /**
   * Collects all RIDs reachable from the anchor vertex via the inverse edge
   * direction. Uses one SQL query per BFS level to ensure correct edge resolution
   * across all storage backends. The anchor itself is included in the set.
   */
  private Set<RID> collectReachableRids(Result anchor, DatabaseSessionEmbedded session) {
    var rids = new HashSet<RID>();
    var anchorRid = extractRid(anchor);
    if (anchorRid == null) {
      return rids;
    }
    rids.add(anchorRid);

    // Level-by-level BFS from anchor via inverse edge direction. Batches all
    // frontier nodes into a single SQL query per level to avoid per-node overhead.
    var inverseDir = edgeOut ? "in" : "out";
    var sql = "SELECT expand(" + inverseDir + "('" + edgeLabel + "')) FROM ?";
    var frontier = List.of(anchorRid);
    while (!frontier.isEmpty()) {
      var nextFrontier = new java.util.ArrayList<RID>();
      try (var rs = session.query(sql, frontier)) {
        while (rs.hasNext()) {
          var row = rs.next();
          var rid = extractRid(row);
          if (rid != null && rids.add(rid)) {
            nextFrontier.add(rid);
          }
        }
      }
      frontier = nextFrontier;
    }
    return rids;
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
        edgeLabel, edgeOut, probeAlias, targetAlias, profilingEnabled);
  }
}
