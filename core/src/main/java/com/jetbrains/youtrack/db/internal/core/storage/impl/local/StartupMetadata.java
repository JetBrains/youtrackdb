package com.jetbrains.youtrack.db.internal.core.storage.impl.local;

public final class StartupMetadata {
  final long lastTxId;

  public StartupMetadata(long lastTxId) {
    this.lastTxId = lastTxId;
  }
}
