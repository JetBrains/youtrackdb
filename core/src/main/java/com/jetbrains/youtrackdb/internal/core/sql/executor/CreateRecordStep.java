package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ProduceExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;

/**
 *
 */
public class CreateRecordStep extends AbstractExecutionStep {

  private final int total;
  private String targetClass;

  public CreateRecordStep(CommandContext ctx, SQLIdentifier targetClass, int total,
      boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.total = total;
    if (targetClass != null) {
      this.targetClass = targetClass.getStringValue();
    }
  }

  private CreateRecordStep(CommandContext ctx, String targetClass, int total,
      boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.total = total;
    this.targetClass = targetClass;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    if (prev != null) {
      prev.start(ctx).close(ctx);
    }

    return new ProduceExecutionStream(this::produce).limit(total);
  }

  private Result produce(CommandContext ctx) {
    var db = ctx.getDatabaseSession();
    final Entity entity;
    if (targetClass != null) {
      var cls = db.getMetadata().getImmutableSchemaSnapshot().getClass(targetClass);
      if (cls == null) {
        throw new DatabaseException("Class " + targetClass + " not found");
      }
      if (cls.isVertexType()) {
        entity = db.newVertex(targetClass);
      } else if (cls.isEdgeType()) {
        throw new DatabaseException(
            "Class " + targetClass + " is an edge class please use create edge command.");
      } else {
        entity = db.newEntity(targetClass);
      }
    } else {
      entity = db.newEntity();
    }

    return new UpdatableResult(db, entity);
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    var result = new StringBuilder();
    result.append(spaces);
    result.append("+ CREATE EMPTY RECORDS");
    if (profilingEnabled) {
      result.append(" (").append(getCostFormatted()).append(")");
    }
    result.append("\n");
    result.append(spaces);
    if (total == 1) {
      result.append("  1 record");
    } else {
      result.append("  ").append(total).append(" record");
    }
    return result.toString();
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new CreateRecordStep(ctx, targetClass, total, profilingEnabled);
  }
}
