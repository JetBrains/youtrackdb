package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.common.concur.TimeoutException;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Security;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityInternal;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLInputParameter;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLJson;
import java.util.HashMap;
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
      var entity = result.asEntity();
      assert entity != null;
      handleContent((EntityInternal) entity, ctx);
    }

    return result;
  }

  private void handleContent(EntityInternal record, CommandContext ctx) {
    // REPLACE ALL THE CONTENT
    ResultInternal fieldsToPreserve = null;

    var session = ctx.getDatabaseSession();
    var clazz = record.getImmutableSchemaClass(session);
    if (clazz != null && clazz.isRestricted()) {
      fieldsToPreserve = new ResultInternal(session);

      final var restricted =
          session
              .getMetadata()
              .getImmutableSchemaSnapshot()
              .getClass(Security.RESTRICTED_CLASSNAME);
      for (var prop : restricted.properties(session)) {
        fieldsToPreserve.setProperty(prop.getName(session),
            record.getProperty(prop.getName(session)));
      }
    }
    Map<String, Object> preDefaultValues = null;
    if (clazz != null) {
      for (var prop : clazz.properties(session)) {
        if (prop.getDefaultValue(session) != null) {
          if (preDefaultValues == null) {
            preDefaultValues = new HashMap<>();
          }
          preDefaultValues.put(prop.getName(session),
              record.getPropertyInternal(prop.getName(session)));
        }
      }
    }

    final var entity1 = (EntityImpl) record.getEntity(session);
    SchemaClass recordClass = entity1.getImmutableSchemaClass(session);

    if (recordClass != null && recordClass.isSubClassOf(session, "V")) {
      for (var fieldName : record.getPropertyNamesInternal()) {
        if (fieldName.startsWith("in_") || fieldName.startsWith("out_")) {
          if (fieldsToPreserve == null) {
            fieldsToPreserve = new ResultInternal(session);
          }
          fieldsToPreserve.setProperty(fieldName, record.getPropertyInternal(fieldName));
        }
      }
    } else if (recordClass != null && recordClass.isSubClassOf(session, "E")) {
      for (var fieldName : record.getPropertyNamesInternal()) {
        if (fieldName.equals("in") || fieldName.equals("out")) {
          if (fieldsToPreserve == null) {
            fieldsToPreserve = new ResultInternal(session);
          }
          fieldsToPreserve.setProperty(fieldName, record.getPropertyInternal(fieldName));
        }
      }
    }

    var entity = (EntityImpl) record.getEntity(session);
    if (json != null) {
      entity.merge(json.toEntity(record, ctx), false, false);
    } else if (inputParameter != null) {
      var val = inputParameter.getValue(ctx.getInputParameters());
      if (val instanceof Entity) {
        entity.merge(((Entity) val).getRecord(session), false, false);
      } else if (val instanceof Map<?, ?> map) {
        @SuppressWarnings("unchecked")
        var mapResult = new ResultInternal(session, (Map<String, ?>) map);
        entity.merge(mapResult, false, false);
      } else {
        throw new CommandExecutionException(session, "Invalid value for UPDATE CONTENT: " + val);
      }
    }
    if (fieldsToPreserve != null) {
      entity.merge(fieldsToPreserve, true, false);
    }
    if (preDefaultValues != null) {
      for (var val : preDefaultValues.entrySet()) {
        if (!entity.containsField(val.getKey())) {
          entity.setProperty(val.getKey(), val.getValue());
        }
      }
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
