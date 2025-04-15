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
package com.jetbrains.youtrack.db.internal.core.query.live;

import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.internal.core.query.live.LiveQueryHookV2.LiveQueryOp;
import com.jetbrains.youtrack.db.internal.core.query.live.LiveQueryHookV2.LiveQueryOps;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;

/**
 *
 */
public class LiveQueryQueueThreadV2 extends Thread {

  private static final LogManager logger = LogManager.instance();

  private final LiveQueryOps ops;
  private volatile boolean stopped = false;

  public LiveQueryQueueThreadV2(LiveQueryOps ops) {
    setName("LiveQueryQueueThreadV2");
    this.ops = ops;
    this.setDaemon(true);
  }

  public LiveQueryQueueThreadV2 clone() {
    return new LiveQueryQueueThreadV2(this.ops);
  }

  @Override
  public void run() {
    final var batchSize = GlobalConfiguration.QUERY_REMOTE_RESULTSET_PAGE_SIZE.getValueAsInteger();
    final var queue = ops.getQueue();

    long totalEventsServed = 0;
    while (!stopped) {
      final List<LiveQueryOp> items = new ArrayList<>(batchSize);
      try {
        items.add(queue.take()); // Blocking wait for start of batch
        while (items.size() < batchSize) {
          final var next = queue.poll(); // Fill batch until queue empty
          if (next == null) {
            break;
          }
          items.add(next);
        }
      } catch (InterruptedException ignore) {
        Thread.currentThread().interrupt();
        continue;
      }

      for (var listener : ops.getSubscribers().values()) {
        try {
          listener.onLiveResults(items);
        } catch (Exception e) {
          LogManager.instance().warn(this, "Error executing live query subscriber.", e);
        }

        totalEventsServed++;
        if (totalEventsServed > 0 && totalEventsServed % 100_000 == 0) {
          logger.info(
              this.getClass(),
              "LiveQuery events: %d served, %d in queue",
              totalEventsServed,
              queue.size());
        }
      }
    }
  }

  public void stopExecution() {
    this.stopped = true;
    this.interrupt();
  }
}
