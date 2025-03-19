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
import com.jetbrains.youtrack.db.api.exception.SecurityAccessException;
import com.jetbrains.youtrack.db.internal.common.concur.resource.ReentrantResourcePool;
import com.jetbrains.youtrack.db.internal.core.config.ContextConfiguration;
import java.util.Map;

/**
 * Database pool base class.
 */
public abstract class DatabasePoolBase extends Thread {

  protected final String url;
  protected final String userName;
  protected final String userPassword;
  protected DatabasePoolAbstract dbPool;

  protected DatabasePoolBase() {
    url = userName = userPassword = null;
  }

  protected DatabasePoolBase(
      final String iURL, final String iUserName, final String iUserPassword) {
    url = iURL;
    userName = iUserName;
    userPassword = iUserPassword;
  }

  public DatabasePoolBase setup() {
    if (dbPool == null) {
      setup(
          GlobalConfiguration.DB_POOL_MIN.getValueAsInteger(),
          GlobalConfiguration.DB_POOL_MAX.getValueAsInteger());
    }

    return this;
  }

  public DatabasePoolBase setup(final int iMinSize, final int iMaxSize) {
    if (dbPool == null) {
      setup(iMinSize, iMaxSize, 6, 64);
    }

    return this;
  }

  public DatabasePoolBase setup(
      final int iMinSize,
      final int iMaxSize,
      final long idleTimeout,
      final long timeBetweenEvictionRunsMillis) {
    if (dbPool == null) {
      synchronized (this) {
        if (dbPool == null) {
          dbPool =
              new DatabasePoolAbstract(
                  this, iMinSize, iMaxSize, idleTimeout, timeBetweenEvictionRunsMillis) {

                public void onShutdown() {
                  if (owner instanceof DatabasePoolBase) {
                    ((DatabasePoolBase) owner).close();
                  }
                }

                public DatabaseSession createNewResource(
                    final String iDatabaseName, final Object... iAdditionalArgs) {
                  if (iAdditionalArgs.length < 2) {
                    throw new SecurityAccessException("Username and/or password missed");
                  }

                  return createResource(owner, iDatabaseName, iAdditionalArgs);
                }

                public boolean reuseResource(
                    final String iKey,
                    final Object[] iAdditionalArgs,
                    final DatabaseSession iValue) {
                  var session = (DatabaseSessionInternal) iValue;
                  if (((DatabasePooled) iValue).isUnderlyingOpen()) {
                    ((DatabasePooled) iValue).reuse(owner, iAdditionalArgs);
                    if (session.getStorage().isClosed(session))
                    // STORAGE HAS BEEN CLOSED: REOPEN IT
                    {
                      (session)
                          .getStorage()
                          .open(session,
                              (String) iAdditionalArgs[0],
                              (String) iAdditionalArgs[1], new ContextConfiguration());
                    } else if (!iValue.getCurrentUser()
                        .checkPassword(session, (String) iAdditionalArgs[1])) {
                      throw new SecurityAccessException(
                          iValue.getDatabaseName(),
                          "User or password not valid for database: '" + iValue.getDatabaseName()
                              + "'");
                    }

                    return true;
                  }
                  return false;
                }
              };
        }
      }
    }
    return this;
  }

  /**
   * Acquires a connection from the pool using the configured URL, user-name and user-password. If
   * the pool is empty, then the caller thread will wait for it.
   *
   * @return A pooled database instance
   */
  public DatabaseSession acquire() {
    setup();
    return dbPool.acquire(url, userName, userPassword);
  }

  /**
   * Acquires a connection from the pool. If the pool is empty, then the caller thread will wait for
   * it.
   *
   * @param iName         Database name
   * @param iUserName     User name
   * @param iUserPassword User password
   * @return A pooled database instance
   */
  public DatabaseSession acquire(
      final String iName, final String iUserName, final String iUserPassword) {
    setup();
    return dbPool.acquire(iName, iUserName, iUserPassword);
  }

  /**
   * Returns amount of available connections which you can acquire for given source and user name.
   * Source id is consist of "source name" and "source user name".
   *
   * @param name     Source name.
   * @param userName User name which is used to acquire source.
   * @return amount of available connections which you can acquire for given source and user name.
   */
  public int getAvailableConnections(final String name, final String userName) {
    setup();
    return dbPool.getAvailableConnections(name, userName);
  }

  public int getCreatedInstances(final String name, final String userName) {
    setup();
    return dbPool.getCreatedInstances(name, userName);
  }

  /**
   * Acquires a connection from the pool specifying options. If the pool is empty, then the caller
   * thread will wait for it.
   *
   * @param iName         Database name
   * @param iUserName     User name
   * @param iUserPassword User password
   * @return A pooled database instance
   */
  public DatabaseSession acquire(
      final String iName,
      final String iUserName,
      final String iUserPassword,
      final Map<String, Object> iOptionalParams) {
    setup();
    return dbPool.acquire(iName, iUserName, iUserPassword, iOptionalParams);
  }

  public int getConnectionsInCurrentThread(final String name, final String userName) {
    if (dbPool == null) {
      return 0;
    }
    return dbPool.getConnectionsInCurrentThread(name, userName);
  }

  /**
   * Don't call it directly but use database.close().
   *
   * @param iDatabase
   */
  public void release(final DatabaseSessionInternal iDatabase) {
    if (dbPool != null) {
      dbPool.release(iDatabase);
    }
  }

  /**
   * Closes the entire pool freeing all the connections.
   */
  public void close() {
    if (dbPool != null) {
      dbPool.close();
      dbPool = null;
    }
  }

  /**
   * Returns the maximum size of the pool
   */
  public int getMaxSize() {
    setup();
    return dbPool.getMaxSize();
  }

  /**
   * Returns all the configured pools.
   */
  public Map<String, ReentrantResourcePool<String, DatabaseSession>> getPools() {
    return dbPool.getPools();
  }

  /**
   * Removes a pool by name/user
   */
  public void remove(final String iName, final String iUser) {
    dbPool.remove(iName, iUser);
  }

  @Override
  public void run() {
    close();
  }

  protected abstract DatabaseSessionInternal createResource(
      Object owner, String iDatabaseName, Object... iAdditionalArgs);
}
