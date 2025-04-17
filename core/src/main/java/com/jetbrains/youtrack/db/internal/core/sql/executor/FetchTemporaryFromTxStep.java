package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.query.ExecutionStep;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.common.concur.TimeoutException;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaImmutableClass;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * <p>Fetches temporary records (collection id -1) from current transaction
 */
public class FetchTemporaryFromTxStep extends AbstractExecutionStep {

  private String className;

  private Object order;

  public FetchTemporaryFromTxStep(CommandContext ctx, String className, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.className = className;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    if (prev != null) {
      prev.start(ctx).close(ctx);
    }

    Iterator<DBRecord> data;
    data = init(ctx);
    return ExecutionStream.iterator(data).map(this::setContext);
  }

  private Result setContext(Result result, CommandContext context) {
    context.setVariable("$current", result);
    return result;
  }

  private Iterator<DBRecord> init(CommandContext ctx) {
    var iterable =
        ctx.getDatabaseSession().getTransactionInternal().getRecordOperationsInternal();

    var db = ctx.getDatabaseSession();
    List<DBRecord> records = new ArrayList<>();
    if (iterable != null) {
      for (var op : iterable) {
        DBRecord record = op.record;
        if (matchesClass(db, record, className) && !hasCollection(record)) {
          records.add(record);
        }
      }
    }
    if (order == FetchFromCollectionExecutionStep.ORDER_ASC) {
      records.sort(
          (o1, o2) -> {
            var p1 = o1.getIdentity().getCollectionPosition();
            var p2 = o2.getIdentity().getCollectionPosition();
            return Long.compare(p1, p2);
          });
    } else {
      records.sort(
          (o1, o2) -> {
            var p1 = o1.getIdentity().getCollectionPosition();
            var p2 = o2.getIdentity().getCollectionPosition();
            return Long.compare(p2, p1);
          });
    }
    return records.iterator();
  }

  private static boolean hasCollection(DBRecord record) {
    var rid = record.getIdentity();
    return rid.getCollectionId() >= 0;
  }

  private static boolean matchesClass(DatabaseSessionInternal session, DBRecord record,
      String className) {
    if (!(record instanceof EntityImpl entity)) {
      return false;
    }

    SchemaImmutableClass result;
    result = entity.getImmutableSchemaClass(session);
    SchemaClass schema = result;
    if (schema == null) {
      return className == null;
    } else if (schema.getName().equals(className)) {
      return true;
    } else {
      return schema.isSubClassOf( className);
    }
  }

  public void setOrder(Object order) {
    this.order = order;
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    var result = new StringBuilder();
    result.append(spaces);
    result.append("+ FETCH NEW RECORDS FROM CURRENT TRANSACTION SCOPE (if any)");
    if (profilingEnabled) {
      result.append(" (").append(getCostFormatted()).append(")");
    }
    return result.toString();
  }

  @Override
  public Result serialize(DatabaseSessionInternal session) {
    var result = ExecutionStepInternal.basicSerialize(session, this);
    result.setProperty("className", className);
    return result;
  }

  @Override
  public void deserialize(Result fromResult, DatabaseSessionInternal session) {
    try {
      ExecutionStepInternal.basicDeserialize(fromResult, this, session);
      className = fromResult.getProperty("className");
    } catch (Exception e) {
      throw BaseException.wrapException(new CommandExecutionException(session, ""), e, session);
    }
  }

  @Override
  public boolean canBeCached() {
    return true;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new FetchTemporaryFromTxStep(ctx, this.className, profilingEnabled);
  }
}
