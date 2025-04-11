package com.jetbrains.youtrack.db.internal.core.sql.executor.resultset;

import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.id.ContextualRecordId;
import com.jetbrains.youtrack.db.internal.core.sql.executor.ResultInternal;
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
    ctx.setVariable("$current", result);
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
            if (nextRid instanceof ContextualRecordId) {
              res.addMetadata(((ContextualRecordId) nextRid).getContext());
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
