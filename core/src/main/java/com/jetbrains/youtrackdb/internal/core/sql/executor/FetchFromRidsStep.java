package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.Collection;

/**
 * Source step that fetches records by a pre-computed list of Record IDs (RIDs).
 *
 * <p>Used when the FROM clause specifies explicit RIDs:
 * <pre>
 *  SELECT FROM [#10:3, #10:7, #22:1]
 * </pre>
 * or when the planner resolves input parameters / metadata targets to specific RIDs.
 *
 * <p>Each RID is loaded from the storage engine via the standard record iterator.
 *
 * @see SelectExecutionPlanner#handleRidsAsTarget
 */
public class FetchFromRidsStep extends AbstractExecutionStep {

  /** The collection of RIDs to fetch; iterated lazily during execution. */
  private Collection<RecordIdInternal> rids;

  /**
   * When true, a RID that names a non-existent (deleted / never-allocated) position is skipped
   * rather than terminating the fetch. Only the class-target {@code @rid IN} fast path sets it,
   * where a dangling in-class RID must not truncate the RIDs after it (scan parity). The default
   * (false) preserves the legacy terminate-on-first-missing contract for every other caller.
   */
  private boolean skipMissing;

  public FetchFromRidsStep(
      Collection<RecordIdInternal> rids, CommandContext ctx, boolean profilingEnabled) {
    this(rids, ctx, profilingEnabled, false);
  }

  public FetchFromRidsStep(
      Collection<RecordIdInternal> rids,
      CommandContext ctx,
      boolean profilingEnabled,
      boolean skipMissing) {
    super(ctx, profilingEnabled);
    this.rids = rids;
    this.skipMissing = skipMissing;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    // Drain predecessor for side effects before fetching by RID.
    if (prev != null) {
      prev.start(ctx).close(ctx);
    }
    return ExecutionStream.loadIterator(this.rids.iterator(), skipMissing);
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    return ExecutionStepInternal.getIndent(depth, indent)
        + "+ FETCH FROM RIDs\n"
        + ExecutionStepInternal.getIndent(depth, indent)
        + "  "
        + rids;
  }

  /**
   * Not cacheable: the RID list is typically resolved from runtime parameters or
   * literal values in the SQL statement, which may differ between executions.
   */
  @Override
  public boolean canBeCached() {
    return false;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new FetchFromRidsStep(rids, ctx, profilingEnabled, skipMissing);
  }
}
