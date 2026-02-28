package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.HashSet;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Intermediate step that removes duplicate records from the stream.
 *
 * <p>Two deduplication strategies are used:
 * <ul>
 *   <li><b>Entity records</b>: deduplication by RID (using a compact {@link RidSet}
 *       which is memory-efficient for identity-based comparison)</li>
 *   <li><b>Projected records</b> (non-entity): deduplication by full content equality
 *       (using a {@code HashSet<Result>})</li>
 * </ul>
 *
 * <pre>
 *  SQL:   SELECT DISTINCT city FROM Person
 *
 *  For each record:
 *    if (entity)     -&gt; check RID in pastRids set
 *    if (projection) -&gt; check full record in pastItems set
 *    already seen?   -&gt; discard
 *    first time?     -&gt; add to set, pass through
 * </pre>
 *
 * <p>A heap limit ({@code QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP}) is enforced on
 * the {@code pastItems} set (projected records) to prevent OOM. Entity records
 * tracked by RID via {@link RidSet} are not subject to this limit because RidSet
 * uses a compact bitmap representation.
 *
 * @see SelectExecutionPlanner#handleDistinct
 */
public class DistinctExecutionStep extends AbstractExecutionStep {

  /** Maximum number of distinct items allowed in the in-memory set. */
  private final long maxElementsAllowed;

  /**
   * @param ctx              the query context (used to read the max-heap-elements config)
   * @param profilingEnabled true to enable the profiling of the execution (for SQL PROFILE)
   */
  public DistinctExecutionStep(CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    var session = ctx == null ? null : ctx.getDatabaseSession();

    maxElementsAllowed =
        session == null
            ? GlobalConfiguration.QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP.getValueAsLong()
            : session.getConfiguration()
                .getValueAsLong(GlobalConfiguration.QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP);
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    assert prev != null;
    var resultSet = prev.start(ctx);
    Set<Result> pastItems = new HashSet<>();
    var pastRids = new RidSet();

    return resultSet.filter((result, context) -> filterMap(result, pastRids, pastItems,
        ctx.getDatabaseSession()));
  }

  @Nullable
  private Result filterMap(Result result, Set<RID> pastRids, Set<Result> pastItems,
      DatabaseSessionEmbedded session) {
    if (alreadyVisited(result, pastRids, pastItems)) {
      return null;
    } else {
      markAsVisited(result, pastRids, pastItems, session);
      return result;
    }
  }

  private void markAsVisited(Result nextValue, Set<RID> pastRids, Set<Result> pastItems,
      DatabaseSessionEmbedded session) {
    // Entity records with valid RIDs are tracked by RID (memory-efficient).
    // Non-entity results (projections) fall through to full-content tracking.
    if (nextValue.isEntity()) {
      var identity = nextValue.asEntityOrNull().getIdentity();
      var collection = identity.getCollectionId();
      var pos = identity.getCollectionPosition();
      if (collection >= 0 && pos >= 0) {
        pastRids.add(identity);
        return;
      }
    }
    pastItems.add(nextValue);
    if (maxElementsAllowed > 0 && maxElementsAllowed < pastItems.size()) {
      // Clear the set before throwing to release memory -- the exception handler
      // should not hold a reference to a potentially huge set.
      pastItems.clear();
      throw new CommandExecutionException(session,
          "Limit of allowed entities for in-heap DISTINCT in a single query exceeded ("
              + maxElementsAllowed
              + ") . You can set "
              + GlobalConfiguration.QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP.getKey()
              + " to increase this limit");
    }
  }

  private static boolean alreadyVisited(Result nextValue, Set<RID> pastRids,
      Set<Result> pastItems) {
    if (nextValue.isEntity()) {
      var identity = nextValue.asEntityOrNull().getIdentity();
      var collection = identity.getCollectionId();
      var pos = identity.getCollectionPosition();
      if (collection >= 0 && pos >= 0) {
        return pastRids.contains(identity);
      }
    }
    return pastItems.contains(nextValue);
  }

  /**
   * No-op: DISTINCT does not propagate timeout signals. The terminal
   * {@link AccumulatingTimeoutStep} handles timeout enforcement, and propagating
   * backward through the deduplication state is not meaningful.
   */
  @Override
  public void sendTimeout() {
  }

  /**
   * Closes the predecessor directly without the {@link AbstractExecutionStep#alreadyClosed}
   * guard. This is safe because DistinctExecutionStep does not hold resources of its own
   * (the pastRids/pastItems sets are local to each {@link #internalStart} call), so only
   * the upstream needs to be closed.
   */
  @Override
  public void close() {
    if (prev != null) {
      prev.close();
    }
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var result = ExecutionStepInternal.getIndent(depth, indent) + "+ DISTINCT";
    if (profilingEnabled) {
      result += " (" + getCostFormatted() + ")";
    }
    return result;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new DistinctExecutionStep(ctx, profilingEnabled);
  }
}
