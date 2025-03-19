package com.jetbrains.youtrack.db.internal.client.remote.message;

import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import java.util.Collection;
import java.util.Map;

public class SendTransactionStateResponse extends BeginTransactionResponse {

  public SendTransactionStateResponse() {
  }

  public SendTransactionStateResponse(long txId,
      Map<RecordId, RecordId> updatedIds, Collection<RecordOperation> recordOperations,
      DatabaseSessionInternal session) {
    super(txId, updatedIds, recordOperations, session);
  }

}
