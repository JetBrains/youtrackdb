package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

public final class PeriodicFlushTask implements Runnable {

  private final WOWCache WOWCache;

  /**
   * @param WOWCache the cache instance to periodically flush
   */
  public PeriodicFlushTask(WOWCache WOWCache) {
    this.WOWCache = WOWCache;
  }

  @Override
  public void run() {
    this.WOWCache.executePeriodicFlush(this);
  }
}
