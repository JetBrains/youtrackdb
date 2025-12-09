package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.iterator.RecordIteratorCollection;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;

/**
 *
 */
public class FetchFromCollectionExecutionStep extends AbstractExecutionStep {

  public static final Object ORDER_ASC = "ASC";
  public static final Object ORDER_DESC = "DESC";
  private final QueryPlanningInfo queryPlanning;

  private int collectionId;
  private Object order;

  public FetchFromCollectionExecutionStep(
      int collectionId, CommandContext ctx, boolean profilingEnabled) {
    this(collectionId, null, ctx, profilingEnabled);
  }

  public FetchFromCollectionExecutionStep(
      int collectionId,
      QueryPlanningInfo queryPlanning,
      CommandContext ctx,
      boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.collectionId = collectionId;
    this.queryPlanning = queryPlanning;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    if (prev != null) {
      prev.start(ctx).close(ctx);
    }

    final var iter = new RecordIteratorCollection<>(
        ctx.getDatabaseSession(),
        collectionId,
        !ORDER_DESC.equals(order)
    );

    var set = ExecutionStream.loadIterator(iter);

    set = set.interruptable();
    return set;
  }

  @Override
  public void sendTimeout() {
    super.sendTimeout();
  }

  @Override
  public void close() {
    super.close();
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var orderString = ORDER_DESC.equals(order) ? "DESC" : "ASC";
    var result =
        ExecutionStepInternal.getIndent(depth, indent)
            + "+ FETCH FROM COLLECTION "
            + collectionId
            + " "
            + orderString;
    if (profilingEnabled) {
      result += " (" + getCostFormatted() + ")";
    }
    return result;
  }

  public void setOrder(Object order) {
    this.order = order;
  }

  @Override
  public Result serialize(DatabaseSessionEmbedded session) {
    var result = ExecutionStepInternal.basicSerialize(session, this);
    result.setProperty("collectionId", collectionId);
    result.setProperty("order", order);
    return result;
  }

  @Override
  public void deserialize(Result fromResult, DatabaseSessionInternal session) {
    try {
      ExecutionStepInternal.basicDeserialize(fromResult, this, session);
      this.collectionId = fromResult.getProperty("collectionId");
      var orderProp = fromResult.getProperty("order");
      if (orderProp != null) {
        this.order = ORDER_ASC.equals(fromResult.getProperty("order")) ? ORDER_ASC : ORDER_DESC;
      }
    } catch (Exception e) {
      throw BaseException.wrapException(new CommandExecutionException(session, ""), e, session);
    }
  }

  @Override
  public boolean canBeCached() {
    return true;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new FetchFromCollectionExecutionStep(
        this.collectionId,
        this.queryPlanning == null ? null : this.queryPlanning.copy(),
        ctx,
        profilingEnabled);
  }
}
