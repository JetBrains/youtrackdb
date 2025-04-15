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
import com.jetbrains.youtrack.db.internal.core.db.record.RecordOperation;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 *
 */
public class LiveQueryQueueThread extends Thread {

  private final BlockingQueue<RecordOperation> queue;
  private final ConcurrentMap<Integer, LiveQueryListener> subscribers;
  private boolean stopped = false;

  private LiveQueryQueueThread(
      BlockingQueue<RecordOperation> queue,
      ConcurrentMap<Integer, LiveQueryListener> subscribers) {
    this.queue = queue;
    this.subscribers = subscribers;
  }

  public LiveQueryQueueThread() {
    this(
        new LinkedBlockingQueue<RecordOperation>(),
        new ConcurrentHashMap<Integer, LiveQueryListener>());
    setName("LiveQueryQueueThread");
    this.setDaemon(true);
  }

  public LiveQueryQueueThread clone() {
    return new LiveQueryQueueThread(this.queue, this.subscribers);
  }

  @Override
  public void run() {
    while (!stopped) {
      RecordOperation next = null;
      try {
        next = queue.take();
      } catch (InterruptedException ignore) {
        break;
      }
      if (next == null) {
        continue;
      }
      for (var listener : subscribers.values()) {
        // TODO filter data
        try {
          listener.onLiveResult(next);
        } catch (Exception e) {
          LogManager.instance().warn(this, "Error executing live query subscriber.", e);
        }
      }
    }
  }

  public void stopExecution() {
    this.stopped = true;
    this.interrupt();
  }

  public void enqueue(RecordOperation item) {
    queue.offer(item);
  }

  public Integer subscribe(Integer id, LiveQueryListener iListener) {
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

  public boolean hasToken(Integer key) {
    return subscribers.containsKey(key);
  }
}
