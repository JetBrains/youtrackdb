package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.util.Collection;
import java.util.concurrent.Callable;

final class FileFlushTask implements Callable<Void> {

  /**
   *
   */
  private final WOWCache cache;

  private final IntOpenHashSet fileIdSet;

  FileFlushTask(WOWCache WOWCache, final Collection<Integer> fileIds) {
    cache = WOWCache;
    this.fileIdSet = new IntOpenHashSet(fileIds);
  }

  @Override
  public Void call() throws Exception {
    return cache.executeFileFlush(this.fileIdSet);
  }
}
