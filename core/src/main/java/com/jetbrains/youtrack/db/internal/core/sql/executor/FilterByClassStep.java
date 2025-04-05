package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.query.ExecutionStep;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.common.concur.TimeoutException;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLIdentifier;
import java.util.Optional;

/**
 *
 */
public class FilterByClassStep extends AbstractExecutionStep {

  private SQLIdentifier identifier;
  private final String className;

  public FilterByClassStep(SQLIdentifier identifier, CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.identifier = identifier;
    this.className = identifier.getStringValue();
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    if (prev == null) {
      throw new IllegalStateException("filter step requires a previous step");
    }

    ExecutionStream resultSet = prev.start(ctx);
    return resultSet.filter(this::filterMap);
  }

  private Result filterMap(Result result, CommandContext ctx) {
    if (result.isEntity()) {
      Optional<SchemaClass> clazz = result.toEntity().getSchemaType();
      if (clazz.isPresent() && clazz.get().isSubClassOf(ctx.getDatabase(), className)) {
        return result;
      }
    }
    return null;
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    StringBuilder result = new StringBuilder();
    result.append(ExecutionStepInternal.getIndent(depth, indent));
    result.append("+ FILTER ITEMS BY CLASS");
    if (profilingEnabled) {
      result.append(" (").append(getCostFormatted()).append(")");
    }
    result.append(" \n");
    result.append(ExecutionStepInternal.getIndent(depth, indent));
    result.append("  ");
    result.append(identifier.getStringValue());
    return result.toString();
  }

  @Override
  public Result serialize(DatabaseSessionInternal db) {
    ResultInternal result = ExecutionStepInternal.basicSerialize(db, this);
    result.setProperty("identifier", identifier.serialize(db));

    return result;
  }

  @Override
  public void deserialize(Result fromResult) {
    try {
      ExecutionStepInternal.basicDeserialize(fromResult, this);
      identifier = SQLIdentifier.deserialize(fromResult.getProperty("identifier"));
    } catch (Exception e) {
      throw BaseException.wrapException(new CommandExecutionException(""), e);
    }
  }

  @Override
  public boolean canBeCached() {
    return true;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new FilterByClassStep(this.identifier.copy(), ctx, this.profilingEnabled);
  }
}
