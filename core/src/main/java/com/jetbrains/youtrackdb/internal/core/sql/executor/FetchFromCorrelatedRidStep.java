package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import java.util.Collections;

/**
 * Fetches a single record by evaluating a correlated RID expression at execution time. Used when a
 * LET subquery contains {@code SELECT FROM <Class> WHERE @rid = $parent.$current.<field>}: the RID
 * is not known at plan time but resolves to exactly one record per parent row.
 *
 * <p>Replaces the {@code FetchFromClassExecutionStep + FilterStep} combination that would otherwise
 * scan every record in the class and post-filter on the RID predicate.
 */
public class FetchFromCorrelatedRidStep extends AbstractExecutionStep {

  private final SQLExpression ridExpression;

  public FetchFromCorrelatedRidStep(
      SQLExpression ridExpression, CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.ridExpression = ridExpression;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    if (prev != null) {
      prev.start(ctx).close(ctx);
    }
    var value = ridExpression.execute((Result) null, ctx);
    var rid = toRecordId(value);
    if (rid == null) {
      return ExecutionStream.empty();
    }
    return ExecutionStream.loadIterator(Collections.singleton(rid).iterator(), false);
  }

  private static RecordIdInternal toRecordId(Object value) {
    if (value instanceof RecordIdInternal r) {
      return r;
    }
    if (value instanceof RID r && r instanceof RecordIdInternal ri) {
      return ri;
    }
    if (value instanceof com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable id) {
      var identity = id.getIdentity();
      if (identity instanceof RecordIdInternal ri) {
        return ri;
      }
    }
    return null;
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    return ExecutionStepInternal.getIndent(depth, indent)
        + "+ FETCH FROM CORRELATED RID\n"
        + ExecutionStepInternal.getIndent(depth, indent)
        + "  "
        + ridExpression;
  }

  @Override
  public Result serialize(DatabaseSessionEmbedded session) {
    var result = ExecutionStepInternal.basicSerialize(session, this);
    result.setProperty("ridExpression", ridExpression.toString());
    return result;
  }

  @Override
  public void deserialize(Result fromResult, DatabaseSessionEmbedded session) {
    try {
      ExecutionStepInternal.basicDeserialize(fromResult, this, session);
    } catch (Exception e) {
      throw BaseException.wrapException(
          new CommandExecutionException(session, ""), e, session);
    }
  }

  @Override
  public boolean canBeCached() {
    return false;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new FetchFromCorrelatedRidStep(ridExpression, ctx, profilingEnabled);
  }
}
