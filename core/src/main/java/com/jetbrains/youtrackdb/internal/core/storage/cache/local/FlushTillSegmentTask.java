package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import java.util.concurrent.Callable;

final class FlushTillSegmentTask implements Callable<Void> {

  /**
   *
   */
  private final WOWCache cache;

  private final long segmentId;

  FlushTillSegmentTask(WOWCache WOWCache, final long segmentId) {
    cache = WOWCache;
    this.segmentId = segmentId;
  }

  @Override
  public Void call() throws Exception {
    return cache.executeFlushTillSegment(this.segmentId);
  }
}
