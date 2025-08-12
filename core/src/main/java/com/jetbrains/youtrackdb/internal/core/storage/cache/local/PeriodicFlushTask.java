package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

public final class PeriodicFlushTask implements Runnable {

  /**
   * @param WOWCache
   */
  private final WOWCache WOWCache;

  public PeriodicFlushTask(WOWCache WOWCache) {
    this.WOWCache = WOWCache;
  }

  @Override
  public void run() {
    this.WOWCache.executePeriodicFlush(this);
  }
}
