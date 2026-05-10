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
package com.jetbrains.youtrackdb.internal.core.query.live;

import com.jetbrains.youtrackdb.internal.common.concur.resource.CloseableInStorage;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.RecordOperation;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holder for the V1 live-query dispatcher state. The public-static dispatch entry points
 * (subscribe / unsubscribe / addOp / notifyForTxChanges / removePendingDatabaseOps) had no
 * production callers and were removed; only the {@link LiveQueryOps} container survives because
 * {@code SharedContext} still allocates and closes one per database to keep the dispatcher
 * lifecycle wired through the existing close path.
 */
public class LiveQueryHook {

  public static class LiveQueryOps implements CloseableInStorage {

    protected Map<DatabaseSessionEmbedded, List<RecordOperation>> pendingOps =
        new ConcurrentHashMap<>();
    private LiveQueryQueueThread queueThread = new LiveQueryQueueThread();

    @Override
    public void close() {
      queueThread.stopExecution();
      try {
        queueThread.join();
      } catch (InterruptedException ignore) {
        Thread.currentThread().interrupt();
      }
      pendingOps.clear();
    }

    public LiveQueryQueueThread getQueueThread() {
      return queueThread;
    }
  }
}
