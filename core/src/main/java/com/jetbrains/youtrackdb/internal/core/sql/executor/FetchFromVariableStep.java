package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.query.ResultSet;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.Collections;
import org.apache.commons.collections4.IteratorUtils;

/**
 * Source step that fetches records from a context variable (e.g. {@code SELECT FROM $myVar}).
 *
 * <p>The variable's runtime value is inspected and handled based on its type:
 * <ul>
 *   <li>{@link ExecutionStream} -- used directly</li>
 *   <li>{@link ResultSet} -- copied (if internal) and streamed</li>
 *   <li>{@link Identifiable} -- loaded as a single record</li>
 *   <li>{@link Result} -- wrapped as a single-element stream</li>
 *   <li>{@link Iterable} -- iterated, loading entities as needed</li>
 * </ul>
 *
 * <p>Variables typically come from LET assignments:
 * <pre>
 *  LET $friends = (SELECT expand(out('Friend')) FROM #10:0)
 *  SELECT FROM $friends WHERE age &gt; 30
 * </pre>
 *
 * <pre>
 *  ctx.getVariable(variableName)
 *    +-- ExecutionStream?  --&gt; use directly
 *    +-- ResultSet?        --&gt; copy if internal, stream with loadEntity, attach onClose
 *    +-- Identifiable?     --&gt; reload from tx, wrap as single-element stream
 *    +-- Result?           --&gt; loadEntity, wrap as single-element stream
 *    +-- Iterable?         --&gt; iterate with transforming iterator (loadEntity)
 *    +-- null/other        --&gt; throw CommandExecutionException
 * </pre>
 *
 * @see SelectExecutionPlanner#handleVariableAsTarget
 */
public class FetchFromVariableStep extends AbstractExecutionStep {

  /** The name of the context variable (without the '$' prefix). */
  private String variableName;

  public FetchFromVariableStep(String variableName, CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.variableName = variableName;
    reset();
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    // Drain predecessor for side effects before reading variable.
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
        // The entity may have been assigned in a prior transaction; reload from the current active transaction.
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

  /**
   * Ensures entities are fully loaded from the current transaction.
   *
   * <p>Handles two cross-transaction scenarios:
   * <ul>
   *   <li><b>Entity instance</b>: if unloaded (e.g. assigned in a prior tx), reload
   *       it from the current active transaction.</li>
   *   <li><b>Result wrapping an entity</b>: the result may come from a serialized
   *       result set where only the identity (RID) survives. If the entity reference
   *       is null, rewrap with just the identity so the downstream can load it.
   *       Otherwise, rewrap with the entity itself for a consistent ResultInternal.</li>
   * </ul>
   */
  private static Result loadEntity(DatabaseSessionEmbedded session, Result result) {
    if (result instanceof Entity entity) {
      if (entity.isUnloaded()) {
        var tx = session.getActiveTransaction();
        return tx.loadEntity(entity);
      }
    } else if (result instanceof Result sqlResult) {
      if (sqlResult.isEntity()) {
        var entity = sqlResult.asEntityOrNull();
        // Entity ref may be null when the Result only carries an identity (RID)
        // from a serialized or cross-session result set.
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
    var result = ExecutionStepInternal.getIndent(depth, indent)
        + "+ FETCH FROM VARIABLE\n"
        + ExecutionStepInternal.getIndent(depth, indent)
        + "  "
        + variableName;
    if (profilingEnabled) {
      result += " (" + getCostFormatted() + ")";
    }
    return result;
  }

  @Override
  public Result serialize(DatabaseSessionEmbedded session) {
    var result = ExecutionStepInternal.basicSerialize(session, this);
    result.setProperty("variableName", variableName);
    return result;
  }

  @Override
  public void deserialize(Result fromResult, DatabaseSessionEmbedded session) {
    try {
      ExecutionStepInternal.basicDeserialize(fromResult, this, session);
      if (fromResult.getProperty("variableName") != null) {
        this.variableName = fromResult.getProperty("variableName");
      }
      reset();
    } catch (Exception e) {
      throw BaseException.wrapException(new CommandExecutionException(session, ""), e, session);
    }
  }

  /**
   * Not cacheable: the variable's value is resolved at execution time from the
   * command context and may differ between executions (e.g. from different LET
   * assignments or input parameters).
   */
  @Override
  public boolean canBeCached() {
    return false;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new FetchFromVariableStep(variableName, ctx, profilingEnabled);
  }
}
