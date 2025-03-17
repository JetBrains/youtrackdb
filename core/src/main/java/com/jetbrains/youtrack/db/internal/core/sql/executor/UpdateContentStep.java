package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.common.concur.TimeoutException;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Security;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLInputParameter;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLJson;
import java.util.HashSet;
import java.util.Map;

/**
 *
 */
public class UpdateContentStep extends AbstractExecutionStep {

  private SQLJson json;
  private SQLInputParameter inputParameter;

  public UpdateContentStep(SQLJson json, CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.json = json;
  }

  public UpdateContentStep(
      SQLInputParameter inputParameter, CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.inputParameter = inputParameter;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    assert prev != null;
    var upstream = prev.start(ctx);
    return upstream.map(this::mapResult);
  }

  private Result mapResult(Result result, CommandContext ctx) {
    if (result instanceof ResultInternal) {
      var entity = result.asEntityOrNull();
      assert entity != null;
      handleContent((EntityImpl) entity, ctx);
    }

    return result;
  }

  private void handleContent(EntityImpl record, CommandContext ctx) {
    // REPLACE ALL THE CONTENT
    var session = ctx.getDatabaseSession();
    var cls = record.getImmutableSchemaClass(session);
    var preserverdProperties = new HashSet<>();

    if (cls != null && cls.isRestricted()) {
      var restrictedCls = session.getSchema().getClass(Security.RESTRICTED_CLASSNAME);
      preserverdProperties.addAll(restrictedCls.properties());
    }

    for (var propertyNames : record.getPropertyNames()) {
      if (!preserverdProperties.contains(propertyNames)) {
        record.removeProperty(propertyNames);
      }
    }

    if (json != null) {
      record.updateFromJSON(json.toString());
    } else if (inputParameter != null) {
      var val = inputParameter.getValue(ctx.getInputParameters());
      if (val instanceof Map<?, ?> map) {
        //noinspection unchecked
        record.updateFromMap((Map<String, ?>) map);
      } else {
        throw new CommandExecutionException(session, "Invalid value for UPDATE CONTENT: " + val);
      }
    }

    if (cls != null) {
      record.convertPropertiesToClassAndInitDefaultValues(cls);
    }
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    var result = new StringBuilder();
    result.append(spaces);
    result.append("+ UPDATE CONTENT\n");
    result.append(spaces);
    result.append("  ");
    if (json != null) {
      result.append(json);
    } else {
      result.append(inputParameter);
    }
    return result.toString();
  }
}
