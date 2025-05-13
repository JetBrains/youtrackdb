/*
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
 */
package com.jetbrains.youtrack.db.internal.core.db;

import com.googlecode.concurrentlinkedhashmap.ConcurrentLinkedHashMap;
import com.jetbrains.youtrack.db.api.DatabaseType;
import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.common.BasicDatabaseSession;
import com.jetbrains.youtrack.db.api.common.BasicYouTrackDB;
import com.jetbrains.youtrack.db.api.common.SessionPool;
import com.jetbrains.youtrack.db.api.common.query.BasicResult;
import com.jetbrains.youtrack.db.api.common.query.BasicResultSet;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.string.JSONSerializerJackson;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import org.apache.commons.lang.ArrayUtils;

public abstract class YouTrackDBAbstract<R extends BasicResult, S extends BasicDatabaseSession<R, ?>>
    implements BasicYouTrackDB<R, S> {

  private final ConcurrentLinkedHashMap<DatabasePoolInternal<S>, SessionPoolImpl<S>> cachedPools =
      new ConcurrentLinkedHashMap.Builder<DatabasePoolInternal<S>, SessionPoolImpl<S>>()
          .maximumWeightedCapacity(100)
          .build();

  public YouTrackDBInternal<S> internal;

  public String serverUser;
  public String serverPassword;

  public YouTrackDBAbstract(YouTrackDBInternal<S> internal) {
    this.internal = internal;
  }

  /**
   * Open a database
   *
   * @param database the database to open
   * @param user     username of a database user or a server user allowed to open the database
   * @param password related to the specified username
   * @return the opened database
   */
  @Override
  public S open(String database, String user, String password) {
    return open(database, user, password, YouTrackDBConfig.defaultConfig());
  }

  /**
   * Open a database
   *
   * @param database the database to open
   * @param user     username of a database user or a server user allowed to open the database
   * @param password related to the specified username
   * @param config   custom configuration for current database
   * @return the opened database
   */
  @Override
  public S open(
      String database, String user, String password, YouTrackDBConfig config) {
    return internal.open(database, user, password, config);
  }

  /**
   * Create a new database without users. In case if you want to create users during creation please
   * use {@link YouTrackDB#create(String, DatabaseType, String...)}
   *
   * @param database database name
   * @param type     can be disk or memory
   * @see YouTrackDB#create(String, DatabaseType, String...)
   */
  @Override
  public void create(String database, DatabaseType type) {
    create(database, type, YouTrackDBConfig.defaultConfig());
  }

  /**
   * Creates a new database alongside with users, passwords and roles.
   *
   * <p>If you want to create users during creation of database you should provide array that
   * consist of triple strings. Each triple string should contain user name, password and role.
   *
   * <p>For example:
   *
   * <p>{@code youTrackDB.create("test", DatabaseType.DISK, "user1", "password1", "admin",
   * "user2", "password2", "reader"); }
   *
   * <p>The predefined roles are:
   *
   * <ul>
   *   <li>admin: has all privileges on the database
   *   <li>reader: can read the data but cannot modify it
   *   <li>writer: can read and modify the data but cannot create or delete classes
   * </ul>
   *
   * @param database        database name
   * @param type            can be disk or memory
   * @param userCredentials user names, passwords and roles provided as a sequence of triple
   *                        strings
   */
  @Override
  public void create(@Nonnull String database, @Nonnull DatabaseType type,
      String... userCredentials) {
    var queryString = new StringBuilder("create database ? " + type.name());
    var params = addUsersToCreationScript(userCredentials, queryString);
    execute(queryString.toString(), ArrayUtils.add(params, 0, database)).close();
  }

  @Override
  public void create(@Nonnull String database, @Nonnull DatabaseType type,
      @Nonnull YouTrackDBConfig youTrackDBConfig, String... userCredentials) {
    var queryString = new StringBuilder("create database ? " + type.name());
    var params = addUsersToCreationScript(userCredentials, queryString);
    addConfigToCreationScript(queryString, youTrackDBConfig);
    execute(queryString.toString(), ArrayUtils.add(params, 0, database)).close();
  }

  /**
   * Creates a new database without users. In case if you want to create users during creation
   * please use {@link YouTrackDB#create(String, DatabaseType, String...)}
   *
   * @param database database name
   * @param type     can be disk or memory
   * @param config   custom configuration for current database
   */
  @Override
  public void create(String database, DatabaseType type, YouTrackDBConfig config) {
    this.internal.create(database, serverUser, serverPassword, type, config);
  }

  /**
   * Create a new database without users if it does not exist. In case if you want to create users
   * during creation please use
   * {@link YouTrackDB#createIfNotExists(String, DatabaseType, String...)}
   *
   * @param database database name
   * @param type     can be disk or memory
   * @return true if the database has been created, false if already exists
   */
  @Override
  public boolean createIfNotExists(String database, DatabaseType type) {
    return createIfNotExists(database, type, YouTrackDBConfig.defaultConfig());
  }

  /**
   * Creates a new database alongside with users, passwords and roles if such one does not exist
   * yet.
   *
   * <p>If you want to create users during creation of database you should provide array that
   * consist of triple strings. Each triple string should contain user name, password and role.
   *
   * <p>The predefined roles are:
   *
   * <ul>
   *   <li>admin: has all privileges on the database
   *   <li>reader: can read the data but cannot modify it
   *   <li>writer: can read and modify the data but cannot create or delete classes
   * </ul>
   *
   * <p>For example:
   *
   * <p>{@code youTrackDB.createIfNotExists("test", DatabaseType.DISK, "user1", "password1",
   * "admin", "user2", "password2", "reader"); }
   *
   * @param database        database name
   * @param type            can be disk or memory
   * @param userCredentials user names, passwords and roles provided as a sequence of triple
   *                        strings
   */
  @Override
  public void createIfNotExists(@Nonnull String database, @Nonnull DatabaseType type,
      String... userCredentials) {
    var queryString =
        new StringBuilder("create database ? " + type.name() + " if not exists");
    var params = addUsersToCreationScript(userCredentials, queryString);
    execute(queryString.toString(), ArrayUtils.add(params, 0, database)).close();
  }

  @Override
  public void createIfNotExists(@Nonnull String database, @Nonnull DatabaseType type,
      @Nonnull YouTrackDBConfig config, String... userCredentials) {
    var queryString =
        new StringBuilder("create database ? " + type.name() + " if not exists");
    var params = addUsersToCreationScript(userCredentials, queryString);
    addConfigToCreationScript(queryString, config);
    execute(queryString.toString(), ArrayUtils.add(params, 0, database)).close();
  }

  private static void addConfigToCreationScript(StringBuilder queryString,
      YouTrackDBConfig config) {
    var configInternal = (YouTrackDBConfigImpl) config;
    var contextConfig = configInternal.getConfiguration();
    var configMap = new HashMap<String, Object>();

    for (var key : contextConfig.getContextKeys()) {
      var value = contextConfig.getValue(key, null);
      if (value != null) {
        configMap.put(key, value);
      }
    }

    var jsonMap = new HashMap<String, Object>();
    jsonMap.put("config", configMap);

    var json = JSONSerializerJackson.INSTANCE.mapToJson(jsonMap);
    queryString.append(" ").append(json);
  }

  private static String[] addUsersToCreationScript(
      String[] userCredentials, StringBuilder queryString) {
    if (userCredentials != null && userCredentials.length > 0) {
      if (userCredentials.length % 3 != 0) {
        throw new IllegalArgumentException(
            "User credentials should be provided as a sequence of triple strings");
      }

      queryString.append(" users (");

      var result = new String[2 * userCredentials.length / 3];
      for (var i = 0; i < userCredentials.length / 3; i++) {
        if (i > 0) {
          queryString.append(", ");
        }
        queryString.append("? identified by ? role ").append(userCredentials[i * 3 + 2]);

        result[(i << 1)] = userCredentials[i * 3];
        result[(i << 1) + 1] = userCredentials[i * 3 + 1];
      }

      queryString.append(")");

      return result;
    }

    return new String[0];
  }

  /**
   * Create a new database without users if not exists. In case if you want to create users during
   * creation please use {@link YouTrackDB#createIfNotExists(String, DatabaseType, String...)}
   *
   * @param database database name
   * @param type     can be disk or memory
   * @param config   custom configuration for current database
   * @return true if the database has been created, false if already exists
   */
  @Override
  public boolean createIfNotExists(String database, DatabaseType type, YouTrackDBConfig config) {
    if (!this.internal.exists(database, serverUser, serverPassword)) {
      this.internal.create(database, serverUser, serverPassword, type, config);
      return true;
    }
    return false;
  }

  /**
   * Drop a database
   *
   * @param database database name
   */
  @Override
  public void drop(String database) {
    this.internal.drop(database, serverUser, serverPassword);
  }

  /**
   * Check if a database exists
   *
   * @param database database name to check
   * @return boolean true if exist false otherwise.
   */
  @Override
  public boolean exists(String database) {
    return this.internal.exists(database, serverUser, serverPassword);
  }

  /**
   * List exiting databases in the current environment
   *
   * @return a list of existing databases.
   */
  @Override
  public List<String> listDatabases() {
    return new ArrayList<>(this.internal.listDatabases(serverUser, serverPassword));
  }

  /**
   * Close the current YouTrackDB context with all related databases and pools.
   */
  @Override
  public void close() {
    this.cachedPools.clear();
    this.internal.close();
  }

  /**
   * Check if the current YouTrackDB context is open
   *
   * @return boolean true if is open false otherwise.
   */
  @Override
  public boolean isOpen() {
    return this.internal.isOpen();
  }

  DatabasePoolInternal<S> openPool(
      String database, String user, String password, YouTrackDBConfig config) {
    return this.internal.openPool(database, user, password, config);
  }

  @Override
  public SessionPool<S> cachedPool(String database, String user, String password) {
    return cachedPool(database, user, password, YouTrackDBConfig.defaultConfig());
  }

  /**
   * Retrieve cached database pool with given username and password
   *
   * @param database database name
   * @param user     user name
   * @param password user password
   * @param config   YouTrackDB config for pool if need create it (in case if there is no cached
   *                 pool)
   * @return cached {@link SessionPool}
   */
  @Override
  public SessionPool<S> cachedPool(
      String database, String user, String password, YouTrackDBConfig config) {
    var internalPool = internal.cachedPool(database, user, password, config);

    var pool = cachedPools.get(internalPool);

    if (pool != null) {
      return pool;
    }

    return cachedPools.computeIfAbsent(internalPool,
        key -> new SessionPoolImpl<>(this, internalPool));
  }

  @Override
  public void restore(String name, String user, String password, String path,
      YouTrackDBConfig config) {
    internal.restore(name, user, password, null, path, config);
  }

  public void invalidateCachedPools() {
    synchronized (this) {
      cachedPools.forEach((internalPool, pool) -> pool.close());
      cachedPools.clear();
    }
  }

  @Override
  public BasicResultSet<? extends BasicResult> execute(String script, Map<String, Object> params) {
    return internal.executeServerStatementNamedParams(script, serverUser, serverPassword, params);
  }

  @Override
  public BasicResultSet<? extends BasicResult> execute(String script, Object... params) {
    return internal.executeServerStatementPositionalParams(script, serverUser, serverPassword,
        params);
  }
}
