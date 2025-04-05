package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.api.schema.SchemaProperty;
import com.jetbrains.youtrack.db.internal.common.concur.TimeoutException;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaImmutableClass;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Security;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityInternalUtils;
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
    ExecutionStream upstream = prev.start(ctx);
    return upstream.map(this::mapResult);
  }

  private Result mapResult(Result result, CommandContext ctx) {
    if (result instanceof ResultInternal) {
      var elem = result.toEntity();
      assert elem != null;
      handleContent((EntityInternal) elem, ctx);
    }
    return result;
  }

  private void handleContent(EntityInternal record, CommandContext ctx) {
    // REPLACE ALL THE CONTENT
    EntityImpl fieldsToPreserve = null;

    SchemaClass clazz = record.getSchemaType().orElse(null);
    if (clazz != null && ((SchemaImmutableClass) clazz).isRestricted()) {
      fieldsToPreserve = new EntityImpl();

      final SchemaClass restricted =
          ctx.getDatabase()
              .getMetadata()
              .getImmutableSchemaSnapshot()
              .getClass(Security.RESTRICTED_CLASSNAME);
      for (SchemaProperty prop : restricted.properties(ctx.getDatabase())) {
        fieldsToPreserve.field(prop.getName(), record.<Object>getProperty(prop.getName()));
      }
    }
    Map<String, Object> preDefaultValues = null;
    if (clazz != null) {
      for (SchemaProperty prop : clazz.properties(ctx.getDatabase())) {
        if (prop.getDefaultValue() != null) {
          if (preDefaultValues == null) {
            preDefaultValues = new HashMap<>();
          }
          preDefaultValues.put(prop.getName(), record.getPropertyInternal(prop.getName()));
        }
      }
    }

    SchemaClass recordClass =
        EntityInternalUtils.getImmutableSchemaClass(ctx.getDatabase(), record.getRecord());
    if (recordClass != null && recordClass.isSubClassOf(ctx.getDatabase(), "V")) {
      for (String fieldName : record.getPropertyNamesInternal()) {
        if (fieldName.startsWith("in_") || fieldName.startsWith("out_")) {
          if (fieldsToPreserve == null) {
            fieldsToPreserve = new EntityImpl();
          }
          fieldsToPreserve.field(fieldName, record.<Object>getPropertyInternal(fieldName));
        }
      }
    } else if (recordClass != null && recordClass.isSubClassOf(ctx.getDatabase(), "E")) {
      for (String fieldName : record.getPropertyNamesInternal()) {
        if (fieldName.equals("in") || fieldName.equals("out")) {
          if (fieldsToPreserve == null) {
            fieldsToPreserve = new EntityImpl();
          }
          fieldsToPreserve.field(fieldName, record.<Object>getPropertyInternal(fieldName));
        }
      }
    }
    EntityImpl entity = record.getRecord();
    if (json != null) {
      entity.merge(json.toDocument(record, ctx), false, false);
    } else if (inputParameter != null) {
      Object val = inputParameter.getValue(ctx.getInputParameters());
      if (val instanceof Entity) {
        entity.merge(((Entity) val).getRecord(), false, false);
      } else if (val instanceof Map<?, ?> map) {
        var mapDoc = new EntityImpl();
        //noinspection unchecked
        mapDoc.fromMap((Map<String, ?>) map);
        entity.merge(mapDoc, false, false);
      } else {
        throw new CommandExecutionException("Invalid value for UPDATE CONTENT: " + val);
      }
    }
    if (fieldsToPreserve != null) {
      entity.merge(fieldsToPreserve, true, false);
    }
    if (preDefaultValues != null) {
      for (Map.Entry<String, Object> val : preDefaultValues.entrySet()) {
        if (!entity.containsField(val.getKey())) {
          entity.setProperty(val.getKey(), val.getValue());
        }
      }
    }
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    String spaces = ExecutionStepInternal.getIndent(depth, indent);
    StringBuilder result = new StringBuilder();
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
