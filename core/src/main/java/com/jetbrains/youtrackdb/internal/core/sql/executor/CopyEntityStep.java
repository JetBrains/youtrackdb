package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.api.query.ExecutionStep;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.api.record.Entity;
import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;


/**
 * Reads an upstream result set and returns a new result set that contains copies of the original
 * Result instances
 *
 * <p>This is mainly used from statements that need to copy of the original data to save it
 * somewhere else, eg. INSERT ... FROM SELECT
 */
public class CopyEntityStep extends AbstractExecutionStep {

  private final String className;

  public CopyEntityStep(CommandContext ctx, boolean profilingEnabled, String className) {
    super(ctx, profilingEnabled);
    this.className = className;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    assert prev != null;

    var upstream = prev.start(ctx);
    return upstream.map(this::mapResult);
  }

  private Result mapResult(Result result, CommandContext ctx) {
    Entity resultEntity;
    var session = ctx.getDatabaseSession();
    if (className == null) {
      resultEntity = session.newEntity();
    } else {
      var immutableSchema = session.getMetadata().getFastImmutableSchema(session);
      var cls = immutableSchema.getClass(className);
      if (cls == null) {
        throw new CommandExecutionException("Class " + className + " not found");
      }

      if (cls.isVertexType()) {
        resultEntity = session.newVertex(className);
      } else if (cls.isEdgeType()) {
        throw new CommandExecutionException(
            "Class " + className + " is an edge. Please use create edge command instead");
      } else {
        resultEntity = session.newEntity(className);
      }
    }
    if (result.isEntity()) {
      var docToCopy = (EntityImpl) result.asEntity();
      for (var propName : docToCopy.getPropertyNames()) {
        final var propType =
            PropertyTypeInternal.convertFromPublicType(docToCopy.getPropertyType(propName));
        final var origValue = docToCopy.getProperty(propName);
        final var copiedValue = propType == null ? origValue :
            propType.copy(origValue, session);
        resultEntity.setProperty(propName, copiedValue);
      }
    } else {
      for (var propName : result.getPropertyNames()) {
        resultEntity.setProperty(propName, result.getProperty(propName));
      }
    }

    return new UpdatableResult(ctx.getDatabaseSession(), resultEntity);
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    var result = new StringBuilder();
    result.append(spaces);
    result.append("+ COPY ENTITY");
    if (profilingEnabled) {
      result.append(" (").append(getCostFormatted()).append(")");
    }
    return result.toString();
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new CopyEntityStep(ctx, profilingEnabled, className);
  }
}
