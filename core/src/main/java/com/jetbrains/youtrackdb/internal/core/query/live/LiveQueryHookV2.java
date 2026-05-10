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
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import javax.annotation.Nullable;

/**
 * Holder for the V2 live-query dispatcher state and the surviving {@link #unboxRidbags} helper.
 *
 * <p>The public-static dispatch entry points (subscribe / unsubscribe / addOp /
 * notifyForTxChanges / removePendingDatabaseOps) and their private snapshot helpers
 * (calculateBefore / calculateAfter / calculateProjections / convert / prevousUpdate) had no
 * production callers and were removed; only the {@link LiveQueryOps} + {@link LiveQueryOp}
 * containers and the {@link #unboxRidbags} unwrap (called from
 * {@code CopyRecordContentBeforeUpdateStep}) survive.
 */
public class LiveQueryHookV2 {

  public static class LiveQueryOp {

    public Result before;
    public Result after;
    public byte type;
    protected EntityImpl originalEntity;

    LiveQueryOp(EntityImpl originalEntity, @Nullable Result before, @Nullable Result after,
        byte type) {
      this.originalEntity = originalEntity;
      this.type = type;
      if (before != null) {
        this.before = before.detach();
      } else {
        this.before = null;
      }
      if (after != null) {
        this.after = after.detach();
      } else {
        this.after = null;
      }
    }
  }

  public static class LiveQueryOps implements CloseableInStorage {

    protected final Map<DatabaseSessionEmbedded, List<LiveQueryOp>> pendingOps =
        new ConcurrentHashMap<>();
    private LiveQueryQueueThreadV2 queueThread = new LiveQueryQueueThreadV2(this);

    private final BlockingQueue<LiveQueryOp> queue = new LinkedBlockingQueue<LiveQueryOp>();
    private final ConcurrentMap<Integer, LiveQueryListenerV2> subscribers =
        new ConcurrentHashMap<Integer, LiveQueryListenerV2>();

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

    public Map<Integer, LiveQueryListenerV2> getSubscribers() {
      return subscribers;
    }

    public BlockingQueue<LiveQueryOp> getQueue() {
      return queue;
    }

    public void enqueue(LiveQueryOp item) {
      queue.offer(item);
    }

    public Integer subscribe(Integer id, LiveQueryListenerV2 iListener) {
      subscribers.put(id, iListener);
      return id;
    }

    public void unsubscribe(Integer id) {
      var res = subscribers.remove(id);
      if (res != null) {
        res.onLiveResultEnd();
      }
    }

    public boolean hasListeners() {
      return !subscribers.isEmpty();
    }
  }

  public static Object unboxRidbags(Object value) {
    // TODO move it to some helper class
    if (value instanceof LinkBag linkBag) {
      List<Identifiable> result = new ArrayList<>(linkBag.size());
      for (var ridPair : linkBag) {
        result.add(ridPair.primaryRid());
      }
      return result;
    }
    return value;
  }
}
