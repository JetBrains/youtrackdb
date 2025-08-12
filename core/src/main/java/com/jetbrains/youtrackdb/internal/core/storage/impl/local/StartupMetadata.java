package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

public final class StartupMetadata {

  final long lastTxId;
  final byte[] txMetadata;

  public StartupMetadata(long lastTxId, byte[] txMetadata) {
    this.lastTxId = lastTxId;
    this.txMetadata = txMetadata;
  }
}
