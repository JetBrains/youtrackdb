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
package com.jetbrains.youtrack.db.internal.core.db;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.internal.common.concur.lock.AdaptiveLock;
import com.jetbrains.youtrack.db.internal.common.concur.lock.LockException;
import com.jetbrains.youtrack.db.internal.common.concur.resource.ReentrantResourcePool;
import com.jetbrains.youtrack.db.internal.common.concur.resource.ResourcePoolListener;
import com.jetbrains.youtrack.db.internal.common.io.IOUtils;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBListener;
import com.jetbrains.youtrack.db.internal.core.storage.Storage;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

public abstract class DatabasePoolAbstract extends AdaptiveLock
    implements ResourcePoolListener<String, DatabaseSession>, YouTrackDBListener {

  private final HashMap<String, ReentrantResourcePool<String, DatabaseSession>> pools =
      new HashMap<>();
  protected Object owner;
  private final int maxSize;
  private final int timeout;
  private volatile Timer evictionTask;
  private Evictor evictor;

  /**
   * The idle object evictor {@link TimerTask}.
   */
  class Evictor extends TimerTask {

    private final HashMap<String, Object2LongOpenHashMap<DatabaseSessionInternal>> evictionMap =
        new HashMap<>();
    private final long minIdleTime;

    public Evictor(long minIdleTime) {
      this.minIdleTime = minIdleTime;
    }

    /**
     * Run pool maintenance. Evict objects qualifying for eviction
     */
    @Override
    public void run() {
      LogManager.instance().debug(this, "Running Connection Pool Evictor Service...");
      lock();
      try {
        for (var pool :
            this.evictionMap.entrySet()) {
          var poolDbs = pool.getValue();
          Iterator<Object2LongMap.Entry<DatabaseSessionInternal>> iterator =
              poolDbs.object2LongEntrySet().iterator();
          while (iterator.hasNext()) {
            Entry<DatabaseSessionInternal, Long> db = iterator.next();
            if (System.currentTimeMillis() - db.getValue() >= this.minIdleTime) {

              var oResourcePool =
                  pools.get(pool.getKey());
              if (oResourcePool != null) {
                LogManager.instance()
                    .debug(this, "Closing idle pooled database '%s'...",
                        db.getKey().getDatabaseName());
                ((DatabasePooled) db.getKey()).forceClose();
                oResourcePool.remove(db.getKey());
                iterator.remove();
              }
            }
          }
        }
      } finally {
        unlock();
      }
    }

    public void updateIdleTime(final String poolName, final DatabaseSessionInternal iDatabase) {
      var pool = this.evictionMap.get(poolName);
      if (pool == null) {
        pool = new Object2LongOpenHashMap<>();
        pool.defaultReturnValue(-1);

        this.evictionMap.put(poolName, pool);
      }

      pool.put(iDatabase, System.currentTimeMillis());
    }
  }

  public DatabasePoolAbstract(
      final Object iOwner,
      final int iMinSize,
      final int iMaxSize,
      final long idleTimeout,
      final long timeBetweenEvictionRunsMillis) {
    this(
        iOwner,
        iMinSize,
        iMaxSize,
        GlobalConfiguration.CLIENT_CONNECT_POOL_WAIT_TIMEOUT.getValueAsInteger(),
        idleTimeout,
        timeBetweenEvictionRunsMillis);
  }

  public DatabasePoolAbstract(
      final Object iOwner,
      final int iMinSize,
      final int iMaxSize,
      final int iTimeout,
      final long idleTimeoutMillis,
      final long timeBetweenEvictionRunsMillis) {
    super(true, GlobalConfiguration.STORAGE_LOCK_TIMEOUT.getValueAsInteger(), true);

    maxSize = iMaxSize;
    timeout = iTimeout;
    owner = iOwner;
    YouTrackDBEnginesManager.instance().registerListener(this);

    if (idleTimeoutMillis > 0 && timeBetweenEvictionRunsMillis > 0) {
      this.evictionTask = new Timer();
      this.evictor = new Evictor(idleTimeoutMillis);
      this.evictionTask.schedule(
          evictor, timeBetweenEvictionRunsMillis, timeBetweenEvictionRunsMillis);
    }
  }

  public DatabaseSession acquire(
      final String iURL, final String iUserName, final String iUserPassword)
      throws LockException {
    return acquire(iURL, iUserName, iUserPassword, null);
  }

  public DatabaseSession acquire(
      final String iURL,
      final String iUserName,
      final String iUserPassword,
      final Map<String, Object> iOptionalParams)
      throws LockException {
    final var dbPooledName = IOUtils.getUnixFileName(iUserName + "@" + iURL);
    ReentrantResourcePool<String, DatabaseSession> pool;
    lock();
    try {
      pool = pools.get(dbPooledName);
      if (pool == null)
      // CREATE A NEW ONE
      {
        pool = new ReentrantResourcePool<String, DatabaseSession>(maxSize, this);
      }

      // PUT IN THE POOL MAP ONLY IF AUTHENTICATION SUCCEED
      pools.put(dbPooledName, pool);

    } finally {
      unlock();
    }
    return pool.getResource(iURL, timeout, iUserName, iUserPassword, iOptionalParams);
  }

  public int getMaxConnections(final String url, final String userName) {
    final var dbPooledName = IOUtils.getUnixFileName(userName + "@" + url);
    final ReentrantResourcePool<String, DatabaseSession> pool;
    lock();
    try {
      pool = pools.get(dbPooledName);
    } finally {
      unlock();
    }
    if (pool == null) {
      return maxSize;
    }

    return pool.getMaxResources();
  }

  public int getCreatedInstances(String url, String userName) {
    final var dbPooledName = IOUtils.getUnixFileName(userName + "@" + url);
    lock();
    try {
      final var pool = pools.get(dbPooledName);
      if (pool == null) {
        return 0;
      }

      return pool.getCreatedInstances();
    } finally {
      unlock();
    }
  }

  public int getAvailableConnections(final String url, final String userName) {
    final var dbPooledName = IOUtils.getUnixFileName(userName + "@" + url);
    final ReentrantResourcePool<String, DatabaseSession> pool;
    lock();
    try {
      pool = pools.get(dbPooledName);
    } finally {
      unlock();
    }
    if (pool == null) {
      return 0;
    }

    return pool.getAvailableResources();
  }

  public int getConnectionsInCurrentThread(final String url, final String userName) {
    final var dbPooledName = IOUtils.getUnixFileName(userName + "@" + url);
    final ReentrantResourcePool<String, DatabaseSession> pool;
    lock();
    try {
      pool = pools.get(dbPooledName);
    } finally {
      unlock();
    }
    if (pool == null) {
      return 0;
    }

    return pool.getConnectionsInCurrentThread(url);
  }

  public void release(final DatabaseSessionInternal iDatabase) {
    final var dbPooledName =
        iDatabase.getCurrentUser().getName(iDatabase) + "@" + iDatabase.getURL();
    final ReentrantResourcePool<String, DatabaseSession> pool;
    lock();
    try {

      pool = pools.get(dbPooledName);

    } finally {
      unlock();
    }
    if (pool == null) {
      throw new LockException(
          "Cannot release a database URL not acquired before. URL: " + iDatabase.getDatabaseName());
    }

    if (pool.returnResource(iDatabase)) {
      this.notifyEvictor(dbPooledName, iDatabase);
    }
  }

  public Map<String, ReentrantResourcePool<String, DatabaseSession>> getPools() {
    lock();
    try {

      return Collections.unmodifiableMap(pools);

    } finally {
      unlock();
    }
  }

  /**
   * Closes all the databases.
   */
  public void close() {
    lock();
    try {

      if (this.evictionTask != null) {
        this.evictionTask.cancel();
      }

      for (var pool :
          pools.entrySet()) {
        for (var db : pool.getValue().getResources()) {
          pool.getValue().close();
          try {
            LogManager.instance()
                .debug(this, "Closing pooled database '%s'...", db.getDatabaseName());
            ((DatabasePooled) db).forceClose();
            LogManager.instance().debug(this, "OK", db.getDatabaseName());
          } catch (Exception e) {
            LogManager.instance().debug(this, "Error: %d", e.toString());
          }
        }
      }

    } finally {
      unlock();
    }
  }

  public void remove(final String iName, final String iUser) {
    remove(iUser + "@" + iName);
  }

  public void remove(final String iPoolName) {
    lock();
    try {

      final var pool = pools.remove(iPoolName);

      if (pool != null) {
        for (var db : pool.getResources()) {
          final var stg = ((DatabaseSessionInternal) db).getStorage();
          if (stg != null && stg.getStatus() == Storage.STATUS.OPEN) {
            try {
              LogManager.instance()
                  .debug(this, "Closing pooled database '%s'...", db.getDatabaseName());
              ((DatabaseSessionInternal) db).activateOnCurrentThread();
              ((DatabasePooled) db).forceClose();
              LogManager.instance().debug(this, "OK", db.getDatabaseName());
            } catch (Exception e) {
              LogManager.instance().debug(this, "Error: %d", e.toString());
            }
          }
        }
        pool.close();
      }

    } finally {
      unlock();
    }
  }

  public int getMaxSize() {
    return maxSize;
  }

  public void onStorageRegistered(final Storage iStorage) {
  }

  /**
   * Removes from memory the pool associated to the closed storage. This avoids pool open against
   * closed storages.
   */
  public void onStorageUnregistered(final Storage iStorage) {
    final var storageURL = iStorage.getURL();

    lock();
    try {
      Set<String> poolToClose = null;

      for (var e : pools.entrySet()) {
        final var pos = e.getKey().indexOf('@');
        final var dbName = e.getKey().substring(pos + 1);
        if (storageURL.equals(dbName)) {
          if (poolToClose == null) {
            poolToClose = new HashSet<>();
          }

          poolToClose.add(e.getKey());
        }
      }

      if (poolToClose != null) {
        for (var pool : poolToClose) {
          remove(pool);
        }
      }

    } finally {
      unlock();
    }
  }

  @Override
  public void onShutdown() {
    close();
  }

  private void notifyEvictor(final String poolName, final DatabaseSessionInternal iDatabase) {
    if (this.evictor != null) {
      this.evictor.updateIdleTime(poolName, iDatabase);
    }
  }
}
