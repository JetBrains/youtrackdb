package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import java.util.concurrent.Callable;

final class DeleteFileTask implements Callable<RawPair<String, String>> {

  /**
   *
   */
  private final WOWCache cache;

  private final long externalFileId;

  DeleteFileTask(WOWCache WOWCache, long externalFileId) {
    cache = WOWCache;
    this.externalFileId = externalFileId;
  }

  @Override
  public RawPair<String, String> call() throws Exception {
    return cache.executeDeleteFile(externalFileId);
  }
}
