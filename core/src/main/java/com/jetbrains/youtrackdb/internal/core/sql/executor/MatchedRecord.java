package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.DBRecord;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionImpl;
import javax.annotation.Nullable;

/** Execution-local evidence of a checked read, independent of the row's external RID value. */
public record MatchedRecord(DBRecord record, DatabaseSessionEmbedded session,
    FrontendTransactionImpl transaction) {

  @Nullable public DBRecord reusableIn(@Nullable DatabaseSessionEmbedded activeSession) {
    // Transaction objects are unique per top-level begin. Binding alone does not prove a read.
    if (activeSession != session || session.getActiveTransactionOrNull() != transaction
        || record.isNotBound(session)
        || transaction.isDeletedInTx(record.getIdentity())) {
      return null;
    }
    return record;
  }
}
