package com.jetbrains.youtrack.db.api.session;

import java.util.stream.Stream;

public interface Transaction {

  boolean isActive();

  Stream<RecordOperation> getRecordOperations();

  int getRecordOperationsCount();

  int activeTxCount();
}
