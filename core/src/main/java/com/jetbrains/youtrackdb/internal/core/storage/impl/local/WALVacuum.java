package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

final class WALVacuum implements Runnable {

  private final AbstractStorage storage;

  public WALVacuum(AbstractStorage storage) {
    this.storage = storage;
  }

  @Override
  public void run() {
    storage.runWALVacuum();
  }
}
