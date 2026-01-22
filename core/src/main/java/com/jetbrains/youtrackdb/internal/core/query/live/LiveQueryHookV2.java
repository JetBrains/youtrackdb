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

import static com.jetbrains.youtrackdb.api.config.GlobalConfiguration.QUERY_LIVE_SUPPORT;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSession;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.common.concur.resource.CloseableInStorage;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

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

    protected final Map<DatabaseSession, List<LiveQueryOp>> pendingOps = new ConcurrentHashMap<>();
    private LiveQueryQueueThreadV2 queueThread = new LiveQueryQueueThreadV2(this);
    private final Object threadLock = new Object();

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

  public static LiveQueryOps getOpsReference(DatabaseSessionInternal db) {
    return db.getSharedContext().getLiveQueryOpsV2();
  }

  public static Integer subscribe(
      Integer token, LiveQueryListenerV2 iListener, DatabaseSessionInternal session) {
    if (Boolean.FALSE.equals(session.getConfiguration().getValue(QUERY_LIVE_SUPPORT))) {
      LogManager.instance()
          .warn(
              session,
              "Live query support is disabled impossible to subscribe a listener, set '%s' to true"
                  + " for enable the live query support",
              QUERY_LIVE_SUPPORT.getKey());
      return -1;
    }
    var ops = getOpsReference(session);
    synchronized (ops.threadLock) {
      if (!ops.queueThread.isAlive()) {
        ops.queueThread = ops.queueThread.clone();
        ops.queueThread.start();
      }
    }

    return ops.subscribe(token, iListener);
  }

  public static void unsubscribe(Integer id, DatabaseSessionInternal db) {
    if (Boolean.FALSE.equals(db.getConfiguration().getValue(QUERY_LIVE_SUPPORT))) {
      LogManager.instance()
          .warn(
              db,
              "Live query support is disabled impossible to unsubscribe a listener, set '%s' to"
                  + " true for enable the live query support",
              QUERY_LIVE_SUPPORT.getKey());
      return;
    }
    try {
      var ops = getOpsReference(db);
      synchronized (ops.threadLock) {
        ops.unsubscribe(id);
      }
    } catch (Exception e) {
      LogManager.instance().warn(LiveQueryHookV2.class, "Error on unsubscribing client", e);
    }
  }

  public static void notifyForTxChanges(DatabaseSession database) {
    var ops = getOpsReference((DatabaseSessionInternal) database);
    if (ops.pendingOps.isEmpty()) {
      return;
    }
    List<LiveQueryOp> list;
    if (Boolean.FALSE.equals(database.getConfiguration().getValue(QUERY_LIVE_SUPPORT))) {
      return;
    }
    synchronized (ops.pendingOps) {
      list = ops.pendingOps.remove(database);
    }
    // TODO sync
    if (list != null) {
      for (var item : list) {
        ops.enqueue(item);
      }
    }
  }

  public static void removePendingDatabaseOps(DatabaseSession database) {
    try {
      if (database.isClosed()
          || Boolean.FALSE.equals(database.getConfiguration().getValue(QUERY_LIVE_SUPPORT))) {
        return;
      }
      var ops = getOpsReference((DatabaseSessionInternal) database);
      synchronized (ops.pendingOps) {
        ops.pendingOps.remove(database);
      }
    } catch (DatabaseException ex) {
      // This catch and log the exception because in some case is suppressing the real exception
      LogManager.instance().error(database, "Error cleaning the live query resources", ex);
    }
  }

  public static void addOp(DatabaseSessionInternal database, EntityImpl entity, byte iType) {
    var ops = getOpsReference(database);
    if (!ops.hasListeners()) {
      return;
    }
    if (Boolean.FALSE.equals(database.getConfiguration().getValue(QUERY_LIVE_SUPPORT))) {
      return;
    }

    var projectionsToLoad = calculateProjections(ops);

    Result before =
        iType == RecordOperation.CREATED ? null
            : calculateBefore(database, entity, projectionsToLoad);
    Result after =
        iType == RecordOperation.DELETED ? null
            : calculateAfter(database, entity, projectionsToLoad);

    var result = new LiveQueryOp(entity, before, after, iType);
    synchronized (ops.pendingOps) {
      var list = ops.pendingOps.computeIfAbsent(database, k -> new ArrayList<>());
      if (result.type == RecordOperation.UPDATED) {
        var prev = prevousUpdate(list, result.originalEntity);
        if (prev == null) {
          list.add(result);
        } else {
          prev.after = result.after;
        }
      } else {
        list.add(result);
      }
    }
  }

  /**
   * get all the projections that are needed by the live queries. Null means all
   *
   * @param ops
   * @return
   */
  @Nullable
  private static Set<String> calculateProjections(LiveQueryOps ops) {
    Set<String> result = new HashSet<>();
    if (ops == null || ops.subscribers == null) {
      return null;
    }
    return result;
  }

  @Nullable
  private static LiveQueryOp prevousUpdate(List<LiveQueryOp> list, EntityImpl entity) {
    for (var liveQueryOp : list) {
      if (liveQueryOp.originalEntity == entity) {
        return liveQueryOp;
      }
    }
    return null;
  }

  public static ResultInternal calculateBefore(
      @Nonnull DatabaseSessionInternal db, EntityImpl entity,
      Set<String> projectionsToLoad) {
    var result = new ResultInternal(db);
    for (var prop : entity.getPropertyNamesInternal(false, true)) {
      if (projectionsToLoad == null || projectionsToLoad.contains(prop)) {
        result.setProperty(prop, unboxRidbags(entity.getPropertyInternal(prop)));
      }
    }
    result.setProperty("@rid", entity.getIdentity());
    result.setProperty("@class", entity.getSchemaClassName());
    result.setProperty("@version", entity.getVersion());
    for (var rawEntry : entity.getRawEntries()) {
      var entry = rawEntry.getValue();
      if (entry.isChanged()) {
        result.setProperty(
            rawEntry.getKey(), convert(entity.getOriginalValue(rawEntry.getKey())));
      } else if (entry.isTrackedModified()) {
        if (entry.value instanceof EntityImpl && ((EntityImpl) entry.value).isEmbedded()) {
          result.setProperty(rawEntry.getKey(),
              calculateBefore(db, (EntityImpl) entry.value, null));
        }
      }
    }
    return result;
  }

  private static Object convert(Object originalValue) {
    if (originalValue instanceof LinkBag) {
      Set result = new LinkedHashSet<>();
      ((LinkBag) originalValue).forEach(result::add);
      return result;
    }
    return originalValue;
  }

  private static ResultInternal calculateAfter(
      DatabaseSessionInternal db, EntityImpl entity, Set<String> projectionsToLoad) {
    var result = new ResultInternal(db);
    for (var prop : entity.getPropertyNamesInternal(false, true)) {
      if (projectionsToLoad == null || projectionsToLoad.contains(prop)) {
        result.setProperty(prop, unboxRidbags(entity.getPropertyInternal(prop)));
      }
    }
    result.setProperty("@rid", entity.getIdentity());
    result.setProperty("@class", entity.getSchemaClassName());
    result.setProperty("@version", entity.getVersion() + 1);
    return result;
  }

  public static Object unboxRidbags(Object value) {
    // TODO move it to some helper class
    if (value instanceof LinkBag) {
      List<Identifiable> result = new ArrayList<>(((LinkBag) value).size());
      for (Identifiable oIdentifiable : (LinkBag) value) {
        result.add(oIdentifiable);
      }
      return result;
    }
    return value;
  }
}
