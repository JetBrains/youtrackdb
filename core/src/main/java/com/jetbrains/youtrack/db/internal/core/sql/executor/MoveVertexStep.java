package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.common.concur.TimeoutException;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLCluster;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLIdentifier;

/**
 *
 */
public class MoveVertexStep extends AbstractExecutionStep {

  private String targetCluster;
  private final String targetClass;

  public MoveVertexStep(
      SQLIdentifier targetClass,
      SQLCluster targetCluster,
      CommandContext ctx,
      boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.targetClass = targetClass == null ? null : targetClass.getStringValue();
    if (targetCluster != null) {
      this.targetCluster = targetCluster.getClusterName();
      if (this.targetCluster == null) {
        this.targetCluster = ctx.getDatabaseSession()
            .getClusterNameById(targetCluster.getClusterNumber());
      }
    }
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    assert prev != null;

    var upstream = prev.start(ctx);
    return upstream.map(this::mapResult);
  }

  private Result mapResult(Result result, CommandContext ctx) {
    if (result.isVertex()) {
      var v = result.asVertex();
      v.moveTo(targetClass);
    }

    return result;
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    var result = new StringBuilder();
    result.append(spaces);
    result.append("+ MOVE VERTEX TO ");
    if (targetClass != null) {
      result.append("CLASS ");
      result.append(targetClass);
    }
    if (targetCluster != null) {
      result.append("CLUSTER ");
      result.append(targetCluster);
    }
    return result.toString();
  }
}
