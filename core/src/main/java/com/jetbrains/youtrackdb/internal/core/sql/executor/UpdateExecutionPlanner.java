package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLFromClause;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLLimit;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLProjection;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSelectStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLTimeout;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLUpdateEdgeStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLUpdateOperations;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLUpdateStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 *
 */
public class UpdateExecutionPlanner {

  private final SQLFromClause target;
  public SQLWhereClause whereClause;

  protected boolean upsert = false;

  protected List<SQLUpdateOperations> operations = new ArrayList<SQLUpdateOperations>();
  protected boolean returnBefore = false;
  protected boolean returnAfter = false;
  protected boolean returnCount = false;

  protected boolean updateEdge = false;

  protected SQLProjection returnProjection;

  public SQLLimit limit;
  public SQLTimeout timeout;

  public UpdateExecutionPlanner(SQLUpdateStatement oUpdateStatement) {
    if (oUpdateStatement instanceof SQLUpdateEdgeStatement) {
      updateEdge = true;
    }
    this.target = oUpdateStatement.getTarget().copy();
    this.whereClause =
        oUpdateStatement.getWhereClause() == null ? null : oUpdateStatement.getWhereClause().copy();
    if (oUpdateStatement.getOperations() == null) {
      this.operations = null;
    } else {
      this.operations =
          oUpdateStatement.getOperations().stream().map(x -> x.copy()).collect(Collectors.toList());
    }
    this.upsert = oUpdateStatement.isUpsert();

    this.returnBefore = oUpdateStatement.isReturnBefore();
    this.returnAfter = oUpdateStatement.isReturnAfter();
    this.returnCount = !(returnAfter || returnBefore);
    this.returnProjection =
        oUpdateStatement.getReturnProjection() == null
            ? null
            : oUpdateStatement.getReturnProjection().copy();
    this.limit = oUpdateStatement.getLimit() == null ? null : oUpdateStatement.getLimit().copy();
    this.timeout =
        oUpdateStatement.getTimeout() == null ? null : oUpdateStatement.getTimeout().copy();
  }

  public UpdateExecutionPlan createExecutionPlan(CommandContext ctx, boolean enableProfiling) {
    var result = new UpdateExecutionPlan(ctx);

    handleTarget(result, ctx, this.target, this.whereClause, this.timeout, enableProfiling);
    if (updateEdge) {
      result.chain(new CheckRecordTypeStep(ctx, "E", enableProfiling));
    }
    handleUpsert(result, ctx, this.target, this.whereClause, this.upsert, enableProfiling);
    handleTimeout(result, ctx, this.timeout, enableProfiling);
    convertToModifiableResult(result, ctx, enableProfiling);
    handleLimit(result, ctx, this.limit, enableProfiling);
    handleReturnBefore(result, ctx, this.returnBefore, enableProfiling);
    handleOperations(result, ctx, this.operations, enableProfiling);
    handleResultForReturnBefore(result, ctx, this.returnBefore, returnProjection, enableProfiling);
    handleResultForReturnAfter(result, ctx, this.returnAfter, returnProjection, enableProfiling);
    handleResultForReturnCount(result, ctx, this.returnCount, enableProfiling);
    return result;
  }

  /**
   * add a step that transforms a normal Result in a specific object that under setProperty()
   * updates the actual Identifiable
   *
   * @param plan the execution plan
   * @param ctx  the executino context
   */
  private void convertToModifiableResult(
      UpdateExecutionPlan plan, CommandContext ctx, boolean profilingEnabled) {
    plan.chain(new ConvertToUpdatableResultStep(ctx, profilingEnabled));
  }

  private void handleResultForReturnCount(
      UpdateExecutionPlan result,
      CommandContext ctx,
      boolean returnCount,
      boolean profilingEnabled) {
    if (returnCount) {
      result.chain(new CountStep(ctx, profilingEnabled));
    }
  }

  private void handleResultForReturnAfter(
      UpdateExecutionPlan result,
      CommandContext ctx,
      boolean returnAfter,
      SQLProjection returnProjection,
      boolean profilingEnabled) {
    if (returnAfter) {
      // re-convert to normal step
      result.chain(new ConvertToResultInternalStep(ctx, profilingEnabled));
      if (returnProjection != null) {
        result.chain(new ProjectionCalculationStep(returnProjection, ctx, profilingEnabled));
      }
    }
  }

  private void handleResultForReturnBefore(
      UpdateExecutionPlan result,
      CommandContext ctx,
      boolean returnBefore,
      SQLProjection returnProjection,
      boolean profilingEnabled) {
    if (returnBefore) {
      result.chain(new UnwrapPreviousValueStep(ctx, profilingEnabled));
      if (returnProjection != null) {
        result.chain(new ProjectionCalculationStep(returnProjection, ctx, profilingEnabled));
      }
    }
  }

  private void handleTimeout(
      UpdateExecutionPlan result,
      CommandContext ctx,
      SQLTimeout timeout,
      boolean profilingEnabled) {
    if (timeout != null && timeout.getVal().longValue() > 0) {
      result.chain(new TimeoutStep(timeout, ctx, profilingEnabled));
    }
  }

  private void handleReturnBefore(
      UpdateExecutionPlan result,
      CommandContext ctx,
      boolean returnBefore,
      boolean profilingEnabled) {
    if (returnBefore) {
      result.chain(new CopyRecordContentBeforeUpdateStep(ctx, profilingEnabled));
    }
  }

  private void handleLimit(
      UpdateExecutionPlan plan, CommandContext ctx, SQLLimit limit, boolean profilingEnabled) {
    if (limit != null) {
      plan.chain(new LimitExecutionStep(limit, ctx, profilingEnabled));
    }
  }

  private void handleUpsert(
      UpdateExecutionPlan plan,
      CommandContext ctx,
      SQLFromClause target,
      SQLWhereClause where,
      boolean upsert,
      boolean profilingEnabled) {
    if (upsert) {
      plan.chain(new UpsertStep(target, where, ctx, profilingEnabled));
    }
  }

  private void handleOperations(
      UpdateExecutionPlan plan,
      CommandContext ctx,
      List<SQLUpdateOperations> ops,
      boolean profilingEnabled) {
    if (ops != null) {
      for (var op : ops) {
        switch (op.getType()) {
          case SQLUpdateOperations.TYPE_SET:
            plan.chain(new UpdateSetStep(op.getUpdateItems(), ctx, profilingEnabled));
            break;
          case SQLUpdateOperations.TYPE_REMOVE:
            plan.chain(new UpdateRemoveStep(op.getUpdateRemoveItems(), ctx, profilingEnabled));
            break;
          case SQLUpdateOperations.TYPE_MERGE:
            plan.chain(new UpdateMergeStep(op.getJson(), ctx, profilingEnabled));
            break;
          case SQLUpdateOperations.TYPE_CONTENT:
            plan.chain(new UpdateContentStep(op.getJson(), ctx, profilingEnabled));
            break;
          case SQLUpdateOperations.TYPE_PUT:
          case SQLUpdateOperations.TYPE_INCREMENT:
          case SQLUpdateOperations.TYPE_ADD:
            throw new CommandExecutionException(ctx.getDatabaseSession(),
                "Cannot execute with UPDATE PUT/ADD/INCREMENT new executor: " + op);
        }
      }
    }
  }

  private void handleTarget(
      UpdateExecutionPlan result,
      CommandContext ctx,
      SQLFromClause target,
      SQLWhereClause whereClause,
      SQLTimeout timeout,
      boolean profilingEnabled) {
    var sourceStatement = new SQLSelectStatement(-1);
    sourceStatement.setTarget(target);
    sourceStatement.setWhereClause(whereClause);
    if (timeout != null) {
      sourceStatement.setTimeout(this.timeout.copy());
    }
    var planner = new SelectExecutionPlanner(sourceStatement);
    result.chain(
        new SubQueryStep(
            planner.createExecutionPlan(ctx, profilingEnabled, false), ctx, ctx, profilingEnabled));
  }
}
