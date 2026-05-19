package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import java.util.concurrent.Callable;

/**
 * Single commit-executor task that drops every {@code writeCachePages} entry whose {@code pageKey}
 * matches {@code (fileId, pageIndex >= minPageIndex)} via
 * {@link WOWCache#doRemoveCachePages(int, int)}.
 *
 * <p>With {@code minPageIndex = 0} this is "drop every dirty entry for this fileId" — the shape
 * used by {@code closeFile} / {@code truncateFile} / {@code deleteFile}. With a positive
 * {@code minPageIndex} this is "drop dirty entries above the truncate target" — the shape used by
 * the recovery-time orphan-truncation pass through {@link WOWCache#shrinkFile(long, long)} so a
 * concurrent flush of an orphan dirty entry cannot re-extend the file past the target.
 */
final class RemoveFilePagesTask implements Callable<Void> {

  private final WOWCache cache;
  private final int fileId;
  private final int minPageIndex;

  RemoveFilePagesTask(final WOWCache cache, final int fileId, final int minPageIndex) {
    this.cache = cache;
    this.fileId = fileId;
    this.minPageIndex = minPageIndex;
  }

  @Override
  public Void call() {
    cache.doRemoveCachePages(fileId, minPageIndex);
    return null;
  }
}
