package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import java.util.concurrent.Callable;

final class RemoveFilePagesTask implements Callable<Void> {

  /**
   *
   */
  private final WOWCache cache;

  private final int fileId;

  RemoveFilePagesTask(WOWCache WOWCache, final int fileId) {
    cache = WOWCache;
    this.fileId = fileId;
  }

  @Override
  public Void call() {
    cache.doRemoveCachePages(fileId);
    return null;
  }
}
