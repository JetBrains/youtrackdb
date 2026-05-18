/*
 *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *
 */
package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import java.util.concurrent.Callable;

/**
 * Range-scoped sibling of {@link RemoveFilePagesTask}: drops every {@code writeCachePages} entry
 * whose {@code pageKey} matches {@code (fileId, pageIndex >= minPageIndex)} via
 * {@link WOWCache#doRemoveCachePagesAtLeast(int, int)} when run on the cache's single-threaded
 * commit executor.
 *
 * <p>Used by the recovery-time orphan-truncation pass through
 * {@link WOWCache#shrinkFile(long, long)} so a concurrent flush of an orphan dirty entry cannot
 * re-extend the file past the truncate target. Dirty entries below {@code minPageIndex} are
 * preserved.
 */
final class RemoveFilePagesAtLeastTask implements Callable<Void> {

  private final WOWCache cache;
  private final int fileId;
  private final int minPageIndex;

  RemoveFilePagesAtLeastTask(final WOWCache cache, final int fileId, final int minPageIndex) {
    this.cache = cache;
    this.fileId = fileId;
    this.minPageIndex = minPageIndex;
  }

  @Override
  public Void call() {
    cache.doRemoveCachePagesAtLeast(fileId, minPageIndex);
    return null;
  }
}
