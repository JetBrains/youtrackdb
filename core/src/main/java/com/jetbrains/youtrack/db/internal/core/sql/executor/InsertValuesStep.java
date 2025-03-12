package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.schema.SchemaProperty;
import com.jetbrains.youtrack.db.internal.common.concur.TimeoutException;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ResultMapper;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLUpdateItem;
import java.util.List;

/**
 *
 */
public class InsertValuesStep extends AbstractExecutionStep {

  private final List<SQLIdentifier> identifiers;
  private final List<List<SQLExpression>> values;

  public InsertValuesStep(
      List<SQLIdentifier> identifierList,
      List<List<SQLExpression>> valueExpressions,
      CommandContext ctx,
      boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.identifiers = identifierList;
    this.values = valueExpressions;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    assert prev != null;
    var upstream = prev.start(ctx);

    return upstream.map(
        new ResultMapper() {

          private int nextValueSet = 0;

          @Override
          public Result map(Result result, CommandContext ctx) {
            if (!(result instanceof ResultInternal)) {
              if (!result.isEntity()) {
                throw new CommandExecutionException(ctx.getDatabaseSession(),
                    "Error executing INSERT, cannot modify entity: " + result);
              }
              result = new UpdatableResult(ctx.getDatabaseSession(), result.asEntity());
            }
            var currentValues = values.get(nextValueSet++);
            if (currentValues.size() != identifiers.size()) {
              throw new CommandExecutionException(ctx.getDatabaseSession(),
                  "Cannot execute INSERT, the number of fields is different from the number of"
                      + " expressions: "
                      + identifiers
                      + " "
                      + currentValues);
            }
            nextValueSet %= values.size();
            for (var i = 0; i < currentValues.size(); i++) {
              var identifier = identifiers.get(i);
              var propertyName = identifier.getStringValue();

              var session = ctx.getDatabaseSession();
              SchemaProperty schemaProperty = null;

              if (result.isEntity()) {
                var entity = (EntityImpl) result.castToEntity();
                var schema = entity.getImmutableSchemaClass(session);
                schemaProperty =
                    schema != null ? schema.getProperty(propertyName) : null;
              }

              var value = currentValues.get(i).execute(result, ctx);
              value = SQLUpdateItem.cleanPropertyValue(value, session, schemaProperty);
              ((ResultInternal) result).setProperty(propertyName, value);
            }
            return result;
          }
        });
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    var result = new StringBuilder();
    result.append(spaces);
    result.append("+ SET VALUES \n");
    result.append(spaces);
    result.append("  (");
    for (var i = 0; i < identifiers.size(); i++) {
      if (i > 0) {
        result.append(", ");
      }
      result.append(identifiers.get(i));
    }
    result.append(")\n");

    result.append(spaces);
    result.append("  VALUES\n");

    for (var c = 0; c < this.values.size(); c++) {
      if (c > 0) {
        result.append("\n");
      }
      var exprs = this.values.get(c);
      result.append(spaces);
      result.append("  (");
      for (var i = 0; i < exprs.size() && i < 3; i++) {
        if (i > 0) {
          result.append(", ");
        }
        result.append(exprs.get(i));
      }
      result.append(")");
    }
    if (this.values.size() >= 3) {
      result.append(spaces);
      result.append("  ...");
    }
    return result.toString();
  }
}
