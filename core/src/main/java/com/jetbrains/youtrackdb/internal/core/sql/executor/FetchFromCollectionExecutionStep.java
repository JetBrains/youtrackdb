package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.iterator.RecordIteratorCollection;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;

/**
 * Source step that scans a single collection (by numeric collection ID) rather
 * than a whole class. This is a lower-level variant of
 * {@link FetchFromClassExecutionStep} used when the target is a specific collection.
 *
 * <p>Supports optional ASC/DESC ordering of the collection scan and can be
 * narrowed by RID range conditions from the query planning info.
 */
public class FetchFromCollectionExecutionStep extends AbstractExecutionStep {

  /** Sentinel value for ascending scan order. */
  public static final Object ORDER_ASC = "ASC";

  /** Sentinel value for descending scan order. */
  public static final Object ORDER_DESC = "DESC";

  /**
   * Planning info preserved for plan copying via {@link #copy()}. Not currently
   * consulted during execution. Retained so that copied plans carry complete metadata.
   */
  private final QueryPlanningInfo queryPlanning;

  /** The numeric collection ID to scan. */
  private int collectionId;

  /** Scan order: {@link #ORDER_ASC}, {@link #ORDER_DESC}, or null for default (ascending). */
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
    // Drain predecessor for side effects before scanning.
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

  // sendTimeout() and close() delegate to super -- no additional resources to manage.
  // These overrides exist for historical reasons and are retained for binary compatibility.

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
  public void deserialize(Result fromResult, DatabaseSessionEmbedded session) {
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

  /** Cacheable: collection ID is a stable numeric reference. */
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
