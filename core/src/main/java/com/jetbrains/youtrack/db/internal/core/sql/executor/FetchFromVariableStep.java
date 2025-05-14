package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.query.ResultSet;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.common.concur.TimeoutException;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.Collections;
import org.apache.commons.collections4.IteratorUtils;

/**
 *
 */
public class FetchFromVariableStep extends AbstractExecutionStep {

  private String variableName;

  public FetchFromVariableStep(String variableName, CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.variableName = variableName;
    reset();
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    if (prev != null) {
      prev.start(ctx).close(ctx);
    }
    var src = ctx.getVariable(variableName);
    var session = ctx.getDatabaseSession();

    ExecutionStream source;
    switch (src) {
      case ExecutionStream executionStream -> source = executionStream;
      case ResultSet resultSet -> {
        if (resultSet instanceof InternalResultSet internalResultSet) {
          resultSet = internalResultSet.copy(session);
        }
        source =
            ExecutionStream.resultIterator(
                    resultSet.stream().map(result -> loadEntity(session, result)).iterator())
                .onClose((context) -> ((ResultSet) src).close());
      }
      case Identifiable identifiable -> {
        //case when we pass variable between txs
        identifiable = session.getActiveTransaction().loadEntity(identifiable);
        source =
            ExecutionStream.resultIterator(
                Collections.singleton(
                        (Result) new ResultInternal(ctx.getDatabaseSession(), identifiable))
                    .iterator());
      }
      case Result result -> {
        source = ExecutionStream.resultIterator(
            Collections.singleton(loadEntity(session, result)).iterator());
      }
      case Iterable<?> iterable ->
          source = ExecutionStream.iterator(IteratorUtils.transformedIterator(
              iterable.iterator(), result -> {
                if (result instanceof Result sqlResult) {
                  return loadEntity(session, sqlResult);
                }

                return result;
              }));
      case null, default -> throw new CommandExecutionException(ctx.getDatabaseSession(),
          "Cannot use variable as query target: " + variableName);
    }
    return source;
  }

  private static Result loadEntity(DatabaseSessionInternal session, Result result) {
    if (result instanceof Entity entity) {
      if (entity.isUnloaded()) {
        var tx = session.getActiveTransaction();
        return tx.loadEntity(entity);
      }
    } else if (result instanceof Result sqlResult) {
      if (sqlResult.isEntity()) {
        var entity = sqlResult.asEntityOrNull();
        if (entity == null) {
          return new ResultInternal(session, sqlResult.getIdentity());
        }
        return new ResultInternal(session, entity);
      }
    }
    return result;
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    return ExecutionStepInternal.getIndent(depth, indent)
        + "+ FETCH FROM VARIABLE\n"
        + ExecutionStepInternal.getIndent(depth, indent)
        + "  "
        + variableName;
  }

  @Override
  public Result serialize(DatabaseSessionInternal session) {
    var result = ExecutionStepInternal.basicSerialize(session, this);
    result.setProperty("variableName", variableName);
    return result;
  }

  @Override
  public void deserialize(Result fromResult, DatabaseSessionInternal session) {
    try {
      ExecutionStepInternal.basicDeserialize(fromResult, this, session);
      if (fromResult.getProperty("variableName") != null) {
        this.variableName = fromResult.getProperty(variableName);
      }
      reset();
    } catch (Exception e) {
      throw BaseException.wrapException(new CommandExecutionException(session, ""), e, session);
    }
  }
}
