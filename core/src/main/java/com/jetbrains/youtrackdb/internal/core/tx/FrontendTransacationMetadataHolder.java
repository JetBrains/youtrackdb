package com.jetbrains.youtrackdb.internal.core.tx;

public interface FrontendTransacationMetadataHolder {

  byte[] metadata();

  void notifyMetadataRead();

  FrontendTransactionId getId();

  FrontendTransactionSequenceStatus getStatus();
}
