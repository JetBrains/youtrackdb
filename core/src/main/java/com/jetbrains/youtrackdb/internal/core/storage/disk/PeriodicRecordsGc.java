package com.jetbrains.youtrackdb.internal.core.storage.disk;

import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;

/**
 * Periodic task that reclaims dead records from collection pages. Scheduled on
 * the {@code fuzzyCheckpointExecutor} with a configurable fixed delay.
 *
 * <p>Delegates to {@link AbstractStorage#periodicRecordsGc()} which
 * opportunistically cleans the snapshot index and then iterates over all
 * collections that exceed the GC trigger threshold.
 */
public class PeriodicRecordsGc implements Runnable {

  private final AbstractStorage storage;

  public PeriodicRecordsGc(AbstractStorage storage) {
    this.storage = storage;
  }

  @Override
  public final void run() {
    try {
      storage.periodicRecordsGc();
    } catch (final RuntimeException e) {
      LogManager.instance().error(this, "Error during periodic records GC", e);
    }
  }
}
