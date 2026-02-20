package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import javax.annotation.Nullable;

/**
 * Intermediate step that filters records by class membership.
 *
 * <p>Always appended after an index-based fetch because an index may be defined on a
 * superclass and return records from sibling subclasses that don't match the target.
 *
 * <pre>
 *  Example:
 *    Class hierarchy: Animal &gt; Dog, Animal &gt; Cat
 *    Index on Animal.name
 *    SELECT FROM Dog WHERE name = 'Rex'
 *
 *    FetchFromIndex(idx_animal_name, 'Rex')  -- may return Dogs AND Cats
 *      -&gt; FilterByClassStep('Dog')           -- keeps only Dog instances
 * </pre>
 *
 * <p>The filter checks
 * {@code ((EntityImpl) entity).getImmutableSchemaClass(session).isSubClassOf(className)}
 * so it correctly handles further subclasses (e.g. GermanShepherd extends Dog).
 * Records with no schema class (schema-less entities) are silently dropped.
 *
 * @see SelectExecutionPlanner#handleClassAsTarget
 */
public class FilterByClassStep extends AbstractExecutionStep {

  /** The SQL identifier for the target class. */
  private SQLIdentifier identifier;

  /** The resolved class name string for the subclass check. */
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

    var resultSet = prev.start(ctx);
    return resultSet.filter(this::filterMap);
  }

  @Nullable
  private Result filterMap(Result result, CommandContext ctx) {
    if (result.isEntity()) {
      var session = ctx.getDatabaseSession();
      // Use getImmutableSchemaClass for a thread-safe schema snapshot.
      var clazz = ((EntityImpl) result.asEntity()).getImmutableSchemaClass(session);
      // isSubClassOf returns true for the class itself and all descendants.
      if (clazz != null && clazz.isSubClassOf(className)) {
        return result;
      }
    }
    return null;
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var result = new StringBuilder();
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
  public Result serialize(DatabaseSessionEmbedded session) {
    var result = ExecutionStepInternal.basicSerialize(session, this);
    result.setProperty("identifier", identifier.serialize(session));

    return result;
  }

  @Override
  public void deserialize(Result fromResult, DatabaseSessionEmbedded session) {
    try {
      ExecutionStepInternal.basicDeserialize(fromResult, this, session);
      identifier = SQLIdentifier.deserialize(fromResult.getProperty("identifier"));
    } catch (Exception e) {
      throw BaseException.wrapException(new CommandExecutionException(session, ""), e, session);
    }
  }

  /** Cacheable: the target class name is a stable string. */
  @Override
  public boolean canBeCached() {
    return true;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new FilterByClassStep(this.identifier.copy(), ctx, this.profilingEnabled);
  }
}
