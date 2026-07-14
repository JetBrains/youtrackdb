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

  /**
   * When true, a RID that resolves to a non-existent (deleted / never-allocated) position is
   * skipped and iteration continues to the next RID. When false (the legacy default), the first
   * such RID terminates the stream — the documented contract pinned by
   * {@code LoaderExecutionStreamTest.loaderAbortsScanOnFirstRecordNotFoundAndDropsTail} and
   * {@code FetchFromRidsStepTest.nonExistentRidTerminatesIterationSilently}. Only the class-target
   * {@code @rid IN} fast path opts in, because there a dangling in-class RID must not truncate the
   * RIDs after it (scan parity: a class scan never visits a dangling position).
   */
  private final boolean skipMissing;

  public LoaderExecutionStream(Iterator<? extends Identifiable> iterator) {
    this(iterator, false);
  }

  public LoaderExecutionStream(Iterator<? extends Identifiable> iterator, boolean skipMissing) {
    this.iterator = iterator;
    this.skipMissing = skipMissing;
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
            // Legacy default (skipMissing == false): terminate the stream on the first missing
            // record. When skipMissing is set, skip this dangling RID and continue to the next,
            // which the @rid IN fast path needs for scan parity.
            if (skipMissing) {
              continue;
            }
            return;
          }
        }
      }
    }
  }
}
