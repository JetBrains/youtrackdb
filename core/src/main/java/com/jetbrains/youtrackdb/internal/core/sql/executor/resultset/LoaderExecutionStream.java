package com.jetbrains.youtrackdb.internal.core.sql.executor.resultset;

import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.record.record.DBRecord;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.id.ContextualRecordId;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import java.util.Iterator;

public final class LoaderExecutionStream implements ExecutionStream {

  private Result nextResult = null;
  private final Iterator<? extends Identifiable> iterator;

  public LoaderExecutionStream(Iterator<? extends Identifiable> iterator) {
    this.iterator = iterator;
  }

  @Override
  public boolean hasNext(CommandContext ctx) {
    if (nextResult == null) {
      fetchNext(ctx);
    }
    return nextResult != null;
  }

  @Override
  public Result next(CommandContext ctx) {
    if (!hasNext(ctx)) {
      throw new IllegalStateException();
    }

    var result = nextResult;
    nextResult = null;
    ctx.setSystemVariable(CommandContext.VAR_CURRENT, result);
    return result;
  }

  @Override
  public void close(CommandContext ctx) {
  }

  private void fetchNext(CommandContext ctx) {
    if (nextResult != null) {
      return;
    }
    var db = ctx.getDatabaseSession();
    while (iterator.hasNext()) {
      var nextRid = iterator.next();

      if (nextRid != null) {
        if (nextRid instanceof DBRecord record) {
          nextResult = new ResultInternal(db, record);
          return;
        } else {
          try {
            var nextDoc = db.load(nextRid.getIdentity());
            var res = new ResultInternal(db, nextDoc);
            if (nextRid instanceof ContextualRecordId ctxRid) {
              res.addMetadata(ctxRid.getContext());
            }
            nextResult = res;
            return;
          } catch (RecordNotFoundException e) {
            return;
          }
        }
      }
    }
  }
}
