package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import java.util.concurrent.CountDownLatch;

final class ExclusiveFlushTask implements Runnable {

  /**
   *
   */
  private final WOWCache cache;

  private final CountDownLatch cacheBoundaryLatch;
  private final CountDownLatch completionLatch;

  ExclusiveFlushTask(
      WOWCache WOWCache,
      final CountDownLatch cacheBoundaryLatch,
      final CountDownLatch completionLatch) {
    cache = WOWCache;
    this.cacheBoundaryLatch = cacheBoundaryLatch;
    this.completionLatch = completionLatch;
  }

  @Override
  public void run() {
    cache.executeFlush(cacheBoundaryLatch, completionLatch);
  }
}
