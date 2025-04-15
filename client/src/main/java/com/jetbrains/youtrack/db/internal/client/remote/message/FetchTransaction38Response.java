package com.jetbrains.youtrack.db.internal.client.remote.message;

import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import java.util.Collection;
import java.util.Map;

public class FetchTransaction38Response extends BeginTransactionResponse {
  public FetchTransaction38Response() {
  }

  public FetchTransaction38Response(
      long txId, Map<RecordId, RecordId> updatedToOldRidMap,
      Collection<RecordOperation> operations,
      DatabaseSessionInternal session) {
    super(txId, updatedToOldRidMap, operations, session);
  }
}
