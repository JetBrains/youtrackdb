package com.jetbrains.youtrackdb.internal.core.storage.disk;

import com.jetbrains.youtrackdb.internal.common.log.LogManager;

public class PeriodicFuzzyCheckpoint implements Runnable {
  private final DiskStorage storage;

  public PeriodicFuzzyCheckpoint(DiskStorage storage) {
    this.storage = storage;
  }

  @Override
  public final void run() {
    try {
      storage.makeFuzzyCheckpoint();
    } catch (final RuntimeException e) {
      LogManager.instance().error(this, "Error during fuzzy checkpoint", e);
    }
  }
}
