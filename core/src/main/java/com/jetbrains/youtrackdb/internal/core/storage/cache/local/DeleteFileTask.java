package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import com.jetbrains.youtrackdb.internal.core.storage.cache.local.WOWCache.DeletedFileNames;
import java.util.concurrent.Callable;

final class DeleteFileTask implements Callable<DeletedFileNames> {

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
  public DeletedFileNames call() throws Exception {
    return cache.executeDeleteFile(externalFileId);
  }
}
