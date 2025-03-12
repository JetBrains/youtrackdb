package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.query.ExecutionStep;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.common.concur.TimeoutException;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.iterator.RecordIteratorCluster;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLBinaryCondition;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLGeOperator;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLGtOperator;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLLeOperator;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLLtOperator;
import java.util.Iterator;

/**
 *
 */
public class FetchFromClusterExecutionStep extends AbstractExecutionStep {

  public static final Object ORDER_ASC = "ASC";
  public static final Object ORDER_DESC = "DESC";
  private final QueryPlanningInfo queryPlanning;

  private int clusterId;
  private Object order;

  public FetchFromClusterExecutionStep(
      int clusterId, CommandContext ctx, boolean profilingEnabled) {
    this(clusterId, null, ctx, profilingEnabled);
  }

  public FetchFromClusterExecutionStep(
      int clusterId,
      QueryPlanningInfo queryPlanning,
      CommandContext ctx,
      boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.clusterId = clusterId;
    this.queryPlanning = queryPlanning;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    if (prev != null) {
      prev.start(ctx).close(ctx);
    }

    Iterator<RecordAbstract> iter;
    if (ORDER_DESC.equals(order)) {
      iter = new RecordIteratorCluster<>(
          ctx.getDatabaseSession(), clusterId, false);
    } else {
      iter = new RecordIteratorCluster<>(
          ctx.getDatabaseSession(), clusterId, true);
      ;
    }

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
            + "+ FETCH FROM CLUSTER "
            + clusterId
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
  public Result serialize(DatabaseSessionInternal session) {
    var result = ExecutionStepInternal.basicSerialize(session, this);
    result.setProperty("clusterId", clusterId);
    result.setProperty("order", order);
    return result;
  }

  @Override
  public void deserialize(Result fromResult, DatabaseSessionInternal session) {
    try {
      ExecutionStepInternal.basicDeserialize(fromResult, this, session);
      this.clusterId = fromResult.getProperty("clusterId");
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
    return new FetchFromClusterExecutionStep(
        this.clusterId,
        this.queryPlanning == null ? null : this.queryPlanning.copy(),
        ctx,
        profilingEnabled);
  }
}
