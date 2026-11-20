package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.record.record.DBRecord;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.RuntimeRidStart;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.AbstractExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ExecutionStepInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.Objects;

/** Direct MATCH source for a single persistent RID supplied by the current execution. */
public final class RuntimeRidStartStep extends AbstractExecutionStep {

  private final RuntimeRidStart start;
  private final SQLWhereClause remainingFilter;

  public RuntimeRidStartStep(
      RuntimeRidStart start,
      SQLWhereClause remainingFilter,
      CommandContext ctx,
      boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.start = Objects.requireNonNull(start);
    this.remainingFilter = remainingFilter;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    if (prev != null) {
      prev.start(ctx).close(ctx);
    }

    var parameters = ctx.getInputParameters();
    var value = parameters == null ? null : parameters.get(start.parameterSlot());
    if (!(value instanceof com.jetbrains.youtrackdb.internal.core.db.record.record.RID rid)
        || !rid.isPersistent()) {
      throw new IllegalStateException("runtime RID start requires a persistent RID for alias "
          + start.alias());
    }
    // Copy the coordinates before the load. A caller can bind a changeable RID whose position
    // changes at commit, but neither the step nor its template may retain that object.
    var boundRid = new RecordId(rid);
    var session = ctx.getDatabaseSession();
    var owner = session.getMetadata().getImmutableSchemaSnapshot()
        .getClassByCollectionId(boundRid.getCollectionId());
    // Unlike the general MATCH class helper, an unowned collection must not trigger a load.
    if (owner == null || !owner.isSubClassOf(start.aliasClass())) {
      return ExecutionStream.empty();
    }

    DBRecord loaded;
    try {
      loaded = session.load(boundRid);
    } catch (RecordNotFoundException notFound) {
      return ExecutionStream.empty();
    }
    var record = new ResultInternal(session, loaded);
    if (remainingFilter != null && !remainingFilter.matchesFilters(record, ctx)) {
      return ExecutionStream.empty();
    }
    var row = new ResultInternal(session);
    row.setProperty(start.alias(), record);
    return ExecutionStream.singleton(row).map((result, context) -> {
      context.setSystemVariable(CommandContext.VAR_MATCHED, result);
      return result;
    });
  }

  @Override
  public boolean canBeCached() {
    // The template retains no bound RID, loaded record, or schema snapshot; copies own their filters.
    return true;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new RuntimeRidStartStep(
        start, remainingFilter == null ? null : remainingFilter.copy(), ctx, profilingEnabled);
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var text = new StringBuilder(ExecutionStepInternal.getIndent(depth, indent))
        .append("+ FETCH FROM RID PARAMETER");
    if (profilingEnabled) {
      text.append(" (").append(getCostFormatted()).append(")");
    }
    return text.append("\n")
        .append(ExecutionStepInternal.getIndent(depth, indent))
        .append("  alias ").append(start.alias())
        .append(" class ").append(start.aliasClass())
        .append(" RID ?")
        .toString();
  }
}
