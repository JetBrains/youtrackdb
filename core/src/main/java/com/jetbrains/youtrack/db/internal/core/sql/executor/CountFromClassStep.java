package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.common.concur.TimeoutException;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ProduceExecutionStream;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLIdentifier;

/**
 * Returns the number of records contained in a class (including subclasses) Executes a count(*) on
 * a class and returns a single record that contains that value (with a specific alias).
 */
public class CountFromClassStep extends AbstractExecutionStep {

  private final SQLIdentifier target;
  private final String alias;

  /**
   * @param targetClass      An identifier containing the name of the class to count
   * @param alias            the name of the property returned in the result-set
   * @param ctx              the query context
   * @param profilingEnabled true to enable the profiling of the execution (for SQL PROFILE)
   */
  public CountFromClassStep(
      SQLIdentifier targetClass, String alias, CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.target = targetClass;
    this.alias = alias;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    if (prev != null) {
      prev.start(ctx).close(ctx);
    }

    return new ProduceExecutionStream(this::produce).limit(1);
  }

  private Result produce(CommandContext ctx) {
    var session = ctx.getDatabaseSession();
    var schema = session.getMetadata().getImmutableSchemaSnapshot();
    var clazz = schema.getClassInternal(target.getStringValue());

    if (clazz == null) {
      throw new CommandExecutionException(session,
          "Class " + target.getStringValue() + " does not exist in the database schema");
    }
    var size = clazz.count(session);
    var result = new ResultInternal(session);
    result.setProperty(alias, size);
    return result;
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    var result = spaces + "+ CALCULATE CLASS SIZE: " + target;
    if (profilingEnabled) {
      result += " (" + getCostFormatted() + ")";
    }
    return result;
  }

  @Override
  public boolean canBeCached() {
    return false; // explicit: in case of active security policies, the COUNT has to be manual
  }
}
