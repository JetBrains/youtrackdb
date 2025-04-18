/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrack.db.internal.core.index;

import com.jetbrains.youtrack.db.internal.common.listener.ProgressListener;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Progress listener for index rebuild.
 */
public class IndexRebuildOutputListener implements ProgressListener {

  private static final Logger logger = LoggerFactory.getLogger(IndexRebuildOutputListener.class);

  private long startTime;
  private long lastDump;
  private long lastCounter = 0;
  private boolean rebuild = false;

  private final Index idx;

  public IndexRebuildOutputListener(Index idx) {
    this.idx = idx;
  }

  @Override
  public void onBegin(final Object iTask, final long iTotal, final Object iRebuild) {
    startTime = System.currentTimeMillis();
    lastDump = startTime;

    rebuild = (Boolean) iRebuild;
    if (iTotal > 0) {
      if (rebuild) {
        LogManager.instance()
            .info(
                this,
                "- Rebuilding index %s.%s (estimated %,d items)...",
                idx.getDatabaseName(),
                idx.getName(),
                iTotal);
      } else {
        LogManager.instance()
            .debug(
                this,
                "- Building index %s.%s (estimated %,d items)...",
                logger, idx.getDatabaseName(),
                idx.getName(),
                iTotal);
      }
    }
  }

  @Override
  public boolean onProgress(final Object iTask, final long iCounter, final float iPercent) {
    final var now = System.currentTimeMillis();
    if (now - lastDump > 10000) {
      // DUMP EVERY 5 SECONDS FOR LARGE INDEXES
      if (rebuild) {
        LogManager.instance()
            .info(
                this,
                "--> %3.2f%% progress, %,d indexed so far (%,d items/sec)",
                iPercent,
                iCounter,
                ((iCounter - lastCounter) / 10));
      } else {
        LogManager.instance()
            .info(
                this,
                "--> %3.2f%% progress, %,d indexed so far (%,d items/sec)",
                iPercent,
                iCounter,
                ((iCounter - lastCounter) / 10));
      }
      lastDump = now;
      lastCounter = iCounter;
    }
    return true;
  }

  @Override
  public void onCompletition(DatabaseSessionEmbedded session, final Object iTask,
      final boolean iSucceed) {
    final var idxSize = idx.size(session);

    if (idxSize > 0) {
      if (rebuild) {
        LogManager.instance()
            .info(
                this,
                "--> OK, indexed %,d items in %,d ms",
                idxSize,
                (System.currentTimeMillis() - startTime));
      } else {
        LogManager.instance()
            .debug(
                this,
                "--> OK, indexed %,d items in %,d ms",
                logger, idxSize,
                (System.currentTimeMillis() - startTime));
      }
    }
  }
}
