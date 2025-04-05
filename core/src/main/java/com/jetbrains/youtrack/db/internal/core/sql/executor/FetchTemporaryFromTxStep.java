package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.query.ExecutionStep;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.common.concur.TimeoutException;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityInternalUtils;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * <p>Fetches temporary records (cluster id -1) from current transaction
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
    Iterable<? extends RecordOperation> iterable =
        ctx.getDatabase().getTransaction().getRecordOperations();

    List<DBRecord> records = new ArrayList<>();
    if (iterable != null) {
      for (RecordOperation op : iterable) {
        DBRecord record = op.record;
        if (matchesClass(record, className) && !hasCluster(record)) {
          records.add(record);
        }
      }
    }
    if (order == FetchFromClusterExecutionStep.ORDER_ASC) {
      records.sort(
          (o1, o2) -> {
            long p1 = o1.getIdentity().getClusterPosition();
            long p2 = o2.getIdentity().getClusterPosition();
            return Long.compare(p1, p2);
          });
    } else {
      records.sort(
          (o1, o2) -> {
            long p1 = o1.getIdentity().getClusterPosition();
            long p2 = o2.getIdentity().getClusterPosition();
            return Long.compare(p2, p1);
          });
    }
    return records.iterator();
  }

  private static boolean hasCluster(DBRecord record) {
    RID rid = record.getIdentity();
    if (rid == null) {
      return false;
    }
    return rid.getClusterId() >= 0;
  }

  private static boolean matchesClass(DBRecord record, String className) {
    if (!(record.getRecord() instanceof EntityImpl entity)) {
      return false;
    }

    SchemaClass schema = EntityInternalUtils.getImmutableSchemaClass(entity);
    if (schema == null) {
      return className == null;
    } else if (schema.getName().equals(className)) {
      return true;
    } else {
      return schema.isSubClassOf(entity.getSession(), className);
    }
  }

  public void setOrder(Object order) {
    this.order = order;
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    String spaces = ExecutionStepInternal.getIndent(depth, indent);
    StringBuilder result = new StringBuilder();
    result.append(spaces);
    result.append("+ FETCH NEW RECORDS FROM CURRENT TRANSACTION SCOPE (if any)");
    if (profilingEnabled) {
      result.append(" (").append(getCostFormatted()).append(")");
    }
    return result.toString();
  }

  @Override
  public Result serialize(DatabaseSessionInternal db) {
    ResultInternal result = ExecutionStepInternal.basicSerialize(db, this);
    result.setProperty("className", className);
    return result;
  }

  @Override
  public void deserialize(Result fromResult) {
    try {
      ExecutionStepInternal.basicDeserialize(fromResult, this);
      className = fromResult.getProperty("className");
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
    return new FetchTemporaryFromTxStep(ctx, this.className, profilingEnabled);
  }
}
