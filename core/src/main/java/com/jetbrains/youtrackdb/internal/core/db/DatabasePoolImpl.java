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
package com.jetbrains.youtrackdb.internal.core.db;

import static com.jetbrains.youtrackdb.api.config.GlobalConfiguration.DB_POOL_ACQUIRE_TIMEOUT;
import static com.jetbrains.youtrackdb.api.config.GlobalConfiguration.DB_POOL_MAX;
import static com.jetbrains.youtrackdb.api.config.GlobalConfiguration.DB_POOL_MIN;

import com.jetbrains.youtrackdb.internal.common.concur.resource.ResourcePool;
import com.jetbrains.youtrackdb.internal.common.concur.resource.ResourcePoolListener;
import com.jetbrains.youtrackdb.internal.core.exception.AcquireTimeoutException;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;

public class DatabasePoolImpl implements DatabasePoolInternal {

  private volatile ResourcePool<Void, DatabaseSessionEmbedded> pool;
  private final YouTrackDBInternal factory;
  private final YouTrackDBConfigImpl config;
  private final String databaseName;
  private final String userName;

  private volatile long lastCloseTime = System.currentTimeMillis();


  public DatabasePoolImpl(
      YouTrackDBInternal factory,
      String database,
      String user,
      String password,
      YouTrackDBConfigImpl config) {
    var max = config.getConfiguration().getValueAsInteger(DB_POOL_MAX);
    var min = config.getConfiguration().getValueAsInteger(DB_POOL_MIN);
    this.factory = factory;
    this.config = config;
    this.databaseName = database;
    this.userName = user;

    pool =
        new ResourcePool<>(
            min,
            max,
            new ResourcePoolListener<>() {
              @Override
              public DatabaseSessionEmbedded createNewResource(
                  Void iKey, Object... iAdditionalArgs) {
                return factory.poolOpen(database, user, password, DatabasePoolImpl.this);
              }

              @Override
              public boolean reuseResource(
                  Void iKey, Object[] iAdditionalArgs, DatabaseSessionEmbedded iValue) {
                var polledSession = (PooledSession) iValue;
                if (polledSession.isBackendClosed()) {
                  return false;
                }
                polledSession.reuse();
                return true;
              }
            });
  }

  public DatabasePoolImpl(
      YouTrackDBInternal factory,
      String database,
      String user,
      YouTrackDBConfigImpl config) {

    var max = config.getConfiguration().getValueAsInteger(DB_POOL_MAX);
    var min = config.getConfiguration().getValueAsInteger(DB_POOL_MIN);
    this.factory = factory;
    this.config = config;
    this.databaseName = database;
    this.userName = user;

    pool =
        new ResourcePool<>(
            min,
            max,
            new ResourcePoolListener<>() {
              @Override
              public DatabaseSessionEmbedded createNewResource(
                  Void iKey, Object... iAdditionalArgs) {
                if (factory instanceof YouTrackDBInternalEmbedded embedded) {
                  return embedded.poolOpenNoAuthenticate(database, user,
                      DatabasePoolImpl.this);
                } else {
                  throw new UnsupportedOperationException(
                      "Opening database without password is not supported");
                }
              }

              @Override
              public boolean reuseResource(
                  Void iKey, Object[] iAdditionalArgs, DatabaseSessionEmbedded iValue) {
                var pooledSession = (PooledSession) iValue;
                if (pooledSession.isBackendClosed()) {
                  return false;
                }

                pooledSession.reuse();
                return true;
              }
            });
  }


  @Override
  public DatabaseSessionEmbedded acquire() throws AcquireTimeoutException {
    ResourcePool<Void, DatabaseSessionEmbedded> p;
    synchronized (this) {
      p = pool;
    }
    if (p != null) {
      return p.getResource(
          null, config.getConfiguration().getValueAsLong(DB_POOL_ACQUIRE_TIMEOUT));
    } else {
      throw new DatabaseException("The pool is closed");
    }
  }

  @Override
  public synchronized void close() {
    ResourcePool<Void, DatabaseSessionEmbedded> p;
    synchronized (this) {
      p = pool;
      pool = null;
    }
    if (p != null) {
      for (var res : p.getAllResources()) {
        ((PooledSession) res).realClose();
      }
      p.close();
      factory.removePool(this);
    }
  }

  @Override
  public void release(DatabaseSessionEmbedded database) {
    ResourcePool<Void, DatabaseSessionEmbedded> p;
    synchronized (this) {
      p = pool;
    }
    if (p != null) {
      pool.returnResource(database);
    } else {
      throw new DatabaseException(database.getDatabaseName(), "The pool is closed");
    }
    lastCloseTime = System.currentTimeMillis();
  }

  @Override
  public boolean isUnused() {
    if (pool == null) {
      return true;
    } else {
      return pool.getResourcesOutCount() == 0;
    }
  }

  public int getAvailableResources() {
    return pool.getAvailableResources();
  }

  @Override
  public long getLastCloseTime() {
    return lastCloseTime;
  }

  @Override
  public String getDatabaseName() {
    return databaseName;
  }

  @Override
  public String getUserName() {
    return userName;
  }

  @Override
  public YouTrackDBConfigImpl getConfig() {
    return config;
  }

  @Override
  public boolean isClosed() {
    return pool == null;
  }
}
