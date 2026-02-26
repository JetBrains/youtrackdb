package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import java.util.concurrent.Callable;

final class FindMinDirtySegment implements Callable<Long> {

  private final WOWCache cache;

  /**
   * @param WOWCache the cache instance to find the minimum dirty segment in
   */
  FindMinDirtySegment(WOWCache WOWCache) {
    cache = WOWCache;
  }

  @Override
  public Long call() {
    return cache.executeFindDirtySegment();
  }
}
