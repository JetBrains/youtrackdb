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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

public class DatabasePoolImpl implements DatabasePoolInternal {

  private final AtomicReference<ResourcePool<Void, DatabaseSessionEmbedded>> pool =
      new AtomicReference<>();
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
    this(factory, database, user, config,
        pool -> factory.poolOpen(database, user, password, pool));
  }

  public DatabasePoolImpl(
      YouTrackDBInternal factory,
      String database,
      String user,
      YouTrackDBConfigImpl config) {
    this(factory, database, user, config,
        pool -> {
          if (factory instanceof YouTrackDBInternalEmbedded embedded) {
            return embedded.poolOpenNoAuthenticate(database, user, pool);
          } else {
            throw new UnsupportedOperationException(
                "Opening database without password is not supported");
          }
        });
  }

  private DatabasePoolImpl(
      YouTrackDBInternal factory,
      String database,
      String user,
      YouTrackDBConfigImpl config,
      Function<DatabasePoolInternal, DatabaseSessionEmbedded> sessionFactory) {
    var max = config.getConfiguration().getValueAsInteger(DB_POOL_MAX);
    var min = config.getConfiguration().getValueAsInteger(DB_POOL_MIN);
    this.factory = factory;
    this.config = config;
    this.databaseName = database;
    this.userName = user;

    pool.set(
        new ResourcePool<>(
            min,
            max,
            new ResourcePoolListener<>() {
              @Override
              public DatabaseSessionEmbedded createNewResource(
                  Void iKey, Object... iAdditionalArgs) {
                return sessionFactory.apply(DatabasePoolImpl.this);
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
            }));
  }

  @Override
  public DatabaseSessionEmbedded acquire() throws AcquireTimeoutException {
    var p = pool.get();
    if (p != null) {
      return p.getResource(
          null, config.getConfiguration().getValueAsLong(DB_POOL_ACQUIRE_TIMEOUT));
    } else {
      throw new DatabaseException("The pool is closed");
    }
  }

  @Override
  public void close() {
    var p = pool.getAndSet(null);
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
    var p = pool.get();
    if (p != null) {
      p.returnResource(database);
    } else {
      throw new DatabaseException(database.getDatabaseName(), "The pool is closed");
    }
    lastCloseTime = System.currentTimeMillis();
  }

  @Override
  public boolean isUnused() {
    var p = pool.get();
    if (p == null) {
      return true;
    } else {
      return p.getResourcesOutCount() == 0;
    }
  }

  public int getAvailableResources() {
    var p = pool.get();
    if (p == null) {
      throw new DatabaseException("The pool is closed");
    }
    return p.getAvailableResources();
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
    return pool.get() == null;
  }
}
