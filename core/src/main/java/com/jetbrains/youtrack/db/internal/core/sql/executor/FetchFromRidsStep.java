package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.common.concur.TimeoutException;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 *
 */
public class FetchFromRidsStep extends AbstractExecutionStep {

  private Collection<RecordId> rids;

  public FetchFromRidsStep(
      Collection<RecordId> rids, CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.rids = rids;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    if (prev != null) {
      prev.start(ctx).close(ctx);
    }
    return ExecutionStream.loadIterator(this.rids.iterator());
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    return ExecutionStepInternal.getIndent(depth, indent)
        + "+ FETCH FROM RIDs\n"
        + ExecutionStepInternal.getIndent(depth, indent)
        + "  "
        + rids;
  }

  @Override
  public Result serialize(DatabaseSessionInternal session) {
    var result = ExecutionStepInternal.basicSerialize(session, this);
    if (rids != null) {
      result.setProperty(
          "rids", rids.stream().map(RecordId::toString).collect(Collectors.toList()));
    }
    return result;
  }

  @Override
  public void deserialize(Result fromResult, DatabaseSessionInternal session) {
    try {
      ExecutionStepInternal.basicDeserialize(fromResult, this, session);
      if (fromResult.getProperty("rids") != null) {
        List<String> ser = fromResult.getProperty("rids");
        rids = ser.stream().map(RecordId::new).collect(Collectors.toList());
      }
      reset();
    } catch (Exception e) {
      throw BaseException.wrapException(new CommandExecutionException(session, ""), e, session);
    }
  }
}
