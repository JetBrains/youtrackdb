package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.query.ExecutionStep;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.common.concur.TimeoutException;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;

/**
 * This step is used just as a gate check for classes (eg. for CREATE VERTEX to make sure that the
 * passed class is a vertex class).
 *
 * <p>It accepts two values: a target class and a parent class. If the two classes are the same or
 * if the parent class is indeed a parent class of the target class, then the syncPool() returns an
 * empty result set, otherwise it throws an CommandExecutionException
 */
public class CheckClassTypeStep extends AbstractExecutionStep {

  private final String targetClass;
  private final String parentClass;

  /**
   * @param targetClass      a class to be checked
   * @param parentClass      a class that is supposed to be the same or a parent class of the target
   *                         class
   * @param ctx              execuiton context
   * @param profilingEnabled true to collect execution stats
   */
  public CheckClassTypeStep(
      String targetClass, String parentClass, CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.targetClass = targetClass;
    this.parentClass = parentClass;
  }

  @Override
  public ExecutionStream internalStart(CommandContext context) throws TimeoutException {
    if (prev != null) {
      prev.start(context).close(ctx);
    }

    if (this.targetClass.equals(this.parentClass)) {
      return ExecutionStream.empty();
    }
    DatabaseSessionInternal db = context.getDatabase();

    Schema schema = db.getMetadata().getImmutableSchemaSnapshot();
    SchemaClass parentClazz = schema.getClass(this.parentClass);
    if (parentClazz == null) {
      throw new CommandExecutionException("Class not found: " + this.parentClass);
    }
    SchemaClass targetClazz = schema.getClass(this.targetClass);
    if (targetClazz == null) {
      throw new CommandExecutionException("Class not found: " + this.targetClass);
    }

    boolean found = false;
    if (parentClazz.equals(targetClazz)) {
      found = true;
    } else {
      for (SchemaClass sublcass : parentClazz.getAllSubclasses(db)) {
        if (sublcass.equals(targetClazz)) {
          found = true;
          break;
        }
      }
    }
    if (!found) {
      throw new CommandExecutionException(
          "Class  " + this.targetClass + " is not a subclass of " + this.parentClass);
    }
    return ExecutionStream.empty();
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    String spaces = ExecutionStepInternal.getIndent(depth, indent);
    StringBuilder result = new StringBuilder();
    result.append(spaces);
    result.append("+ CHECK CLASS HIERARCHY");
    if (profilingEnabled) {
      result.append(" (").append(getCostFormatted()).append(")");
    }
    result.append("\n");
    result.append("  ").append(this.parentClass);
    return result.toString();
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new CheckClassTypeStep(targetClass, parentClass, ctx, profilingEnabled);
  }

  @Override
  public boolean canBeCached() {
    return true;
  }
}
