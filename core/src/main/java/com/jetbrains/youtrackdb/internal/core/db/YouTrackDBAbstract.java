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
package com.jetbrains.youtrackdb.internal.core.db;

import com.googlecode.concurrentlinkedhashmap.ConcurrentLinkedHashMap;
import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB;
import com.jetbrains.youtrackdb.api.common.BasicDatabaseSession;
import com.jetbrains.youtrackdb.api.common.SessionPool;
import com.jetbrains.youtrackdb.api.common.query.BasicResult;
import com.jetbrains.youtrackdb.api.common.query.BasicResultSet;
import com.jetbrains.youtrackdb.api.config.YouTrackDBConfig;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraph;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.string.JSONSerializerJackson;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.commons.configuration2.Configuration;
import org.apache.commons.lang.ArrayUtils;

public abstract class YouTrackDBAbstract<R extends BasicResult, S extends BasicDatabaseSession<R, ?>>
    implements AutoCloseable {

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
  public void create(String database, DatabaseType type) {
    create(database, type, YouTrackDBConfig.defaultConfig());
  }

  /// Creates a new database alongside users, passwords and roles.
  ///
  /// If you want to create users during creation of a database you should provide array that
  /// consists of triple strings. Each triple string should contain the username, password and
  /// role.
  ///
  /// For example:
  ///
  /// `youTrackDB.create("test", DatabaseType.DISK, "user1", "password1", "admin","user2",
  /// "password2", "reader");`
  ///
  /// The predefined roles are:
  ///
  ///   - admin: has all privileges on the database
  ///   - reader: can read the data but cannot modify it
  ///   - writer: can read and modify the data but cannot create or delete classes
  ///
  /// @param databaseName    database name
  /// @param type            can be disk or memory
  /// @param userCredentials usernames, passwords and roles provided as a sequence of triple
  ///                        strings
  public void create(@Nonnull String databaseName, @Nonnull DatabaseType type,
      String... userCredentials) {
    var queryString = new StringBuilder("create database ? " + type.name());
    var params = addUsersToCreationScript(userCredentials, queryString);
    execute(queryString.toString(), ArrayUtils.add(params, 0, databaseName)).close();
  }

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
  public boolean createIfNotExists(String database, DatabaseType type) {
    return createIfNotExists(database, type, YouTrackDBConfig.defaultConfig());
  }

  /// Creates a new database alongside users, passwords and roles if such a one does not exist yet.
  ///
  /// If you want to create users during creation of database you should provide array that consists
  /// of triple strings. Each triple string should contain the username, password and role.
  ///
  /// The predefined roles are:
  ///
  ///   - admin: has all privileges on the database
  ///   - reader: can read the data but cannot modify it
  ///   - writer: can read and modify the data but cannot create or delete classes
  ///
  ///
  /// For example:
  ///
  /// `youTrackDB.createIfNotExists("test", DatabaseType.DISK, "user1", "password1","admin",
  /// "user2", "password2", "reader");`
  ///
  /// @param databaseName    database name
  /// @param type            can be disk or memory
  /// @param userCredentials usernames, passwords and roles provided as a sequence of triple
  ///                        strings
  public void createIfNotExists(@Nonnull String databaseName, @Nonnull DatabaseType type,
      String... userCredentials) {
    var queryString =
        new StringBuilder("create database ? " + type.name() + " if not exists");
    var params = addUsersToCreationScript(userCredentials, queryString);
    execute(queryString.toString(), ArrayUtils.add(params, 0, databaseName)).close();
  }

  public void createIfNotExists(@Nonnull String database, @Nonnull DatabaseType type,
      @Nonnull YouTrackDBConfig config, String... userCredentials) {
    var queryString =
        new StringBuilder("create database ? " + type.name() + " if not exists");
    var params = addUsersToCreationScript(userCredentials, queryString);
    addConfigToCreationScript(queryString, config);
    execute(queryString.toString(), ArrayUtils.add(params, 0, database)).close();
  }

  /// Open the YTDB Graph instance by database name, using the current username and password.
  ///
  /// @param databaseName Database name
  /// @param userName     user name
  @Nonnull
  public YTDBGraph openGraph(@Nonnull String databaseName, @Nonnull String userName,
      @Nonnull String userPassword) {
    var sessionPool = cachedPool(databaseName, userName, userPassword);
    return sessionPool.asGraph();
  }

  /// Open the YTDB Graph instance by database name, using the current username and password. This
  /// method allows one to specify database configuration.
  ///
  /// @param databaseName Database name
  /// @param userName     user name
  /// @param config       database configuration
  @Nonnull
  public YTDBGraph openGraph(@Nonnull String databaseName, @Nonnull String userName,
      @Nonnull String userPassword,
      @Nonnull Configuration config) {
    var sessionPool = cachedPool(databaseName, userName, userPassword,
        YouTrackDBConfig.builder().fromApacheConfiguration(config).build());
    return sessionPool.asGraph();
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
  public boolean createIfNotExists(String database, DatabaseType type, YouTrackDBConfig config) {
    if (!this.internal.exists(database, serverUser, serverPassword)) {
      this.internal.create(database, serverUser, serverPassword, type, config);
      return true;
    }
    return false;
  }

  /// Drop a database
  ///
  /// @param databaseName database name
  public void drop(@Nonnull String databaseName) {
    this.internal.drop(databaseName, serverUser, serverPassword);
  }

  /// Check if a database exists
  ///
  /// @param databaseName database name to check
  /// @return boolean true if exist false otherwise.
  public boolean exists(@Nonnull String databaseName) {
    return this.internal.exists(databaseName, serverUser, serverPassword);
  }

  /// List exiting databases in the current environment
  ///
  /// @return a list of existing databases.
  @Nonnull
  public List<String> listDatabases() {
    return new ArrayList<>(this.internal.listDatabases(serverUser, serverPassword));
  }

  /// Close the current YouTrackDB database manager with all related databases and pools.
  @Override
  public void close() {
    this.cachedPools.clear();
    this.internal.close();
  }

  /// Check if the current YouTrackDB database manager is open
  ///
  /// @return boolean true if is open false otherwise.
  public boolean isOpen() {
    return this.internal.isOpen();
  }

  DatabasePoolInternal<S> openPool(
      String database, String user, String password, YouTrackDBConfig config) {
    return this.internal.openPool(database, user, password, config);
  }

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

  public void restore(String name, String path, YouTrackDBConfig config) {
    internal.restore(name, null, null, path, null, config);
  }

  /// Creates a new database alongside users, passwords and roles and also allows to specify
  /// database configuration.
  ///
  /// If you want to create users during creation of a database you should provide an array that
  /// consists of triple strings. Each triple string should contain the username, password and
  /// role.
  ///
  /// For example:
  ///
  /// `youTrackDB.create("test", DatabaseType.DISK, "user1", "password1", "admin","user2",
  /// "password2", "reader");`
  ///
  /// The predefined roles are:
  ///
  ///   - admin: has all privileges on the database
  ///   - reader: can read the data but cannot modify it
  ///   - writer: can read and modify the data but cannot create or delete classes
  ///
  /// @param databaseName     database name
  /// @param type             can be disk or memory
  /// @param youTrackDBConfig database configuration
  /// @param userCredentials  usernames, passwords and roles provided as a sequence of triple
  ///                         strings
  public void create(@Nonnull String databaseName, @Nonnull DatabaseType type,
      @Nonnull Configuration youTrackDBConfig, String... userCredentials) {
    var builder = YouTrackDBConfig.builder().fromApacheConfiguration(youTrackDBConfig);
    create(databaseName, type, builder.build(), userCredentials);
  }

  /// Creates a new database alongside users, passwords and roles if such a one does not exist yet
  /// and also allows to specify database configuration.
  ///
  /// If you want to create users during the creation of a database, you should provide an array
  /// that consists of triple strings. Each triple string should contain the username, password and
  /// role.
  ///
  /// The predefined roles are:
  ///
  ///   - admin: has all privileges on the database
  ///   - reader: can read the data but cannot modify it
  ///   - writer: can read and modify the data but cannot create or delete classes
  ///
  ///
  /// For example:
  ///
  /// `youTrackDB.createIfNotExists("test", DatabaseType.DISK, "user1", "password1","admin",
  /// "user2", "password2", "reader");`
  ///
  /// @param databaseName    database name
  /// @param type            can be disk or memory
  /// @param config          database configuration
  /// @param userCredentials usernames, passwords and roles provided as a sequence of triple
  ///                        strings
  public void createIfNotExists(@Nonnull String databaseName, @Nonnull DatabaseType type,
      @Nonnull Configuration config, String... userCredentials) {
    var builder = YouTrackDBConfig.builder().fromApacheConfiguration(config);
    createIfNotExists(databaseName, type, builder.build(), userCredentials);
  }

  /// Creates a database by restoring it from incremental backup. The backup should be created with
  /// [#incrementalBackup(Path)].
  ///
  /// At the moment only disk-based databases are supported, you cannot restore memory databases.
  ///
  /// @param databaseName Name of a database to be created.
  /// @param path         Path to the backup directory.
  /// @param config       YouTrackDB configuration.
  public void restore(@Nonnull String databaseName,
      @Nonnull String path,
      @Nullable Configuration config) {
    if (config == null) {
      restore(databaseName, path, YouTrackDBConfig.defaultConfig());
    } else {
      var builder = YouTrackDBConfig.builder().fromApacheConfiguration(config);
      restore(databaseName, path, builder.build());
    }
  }

  public void invalidateCachedPools() {
    synchronized (this) {
      cachedPools.forEach((internalPool, pool) -> pool.close());
      cachedPools.clear();
    }
  }

  public BasicResultSet<? extends BasicResult> execute(String script, Map<String, Object> params) {
    return internal.executeServerStatementNamedParams(script, serverUser, serverPassword, params);
  }

  public BasicResultSet<? extends BasicResult> execute(String script, Object... params) {
    return internal.executeServerStatementPositionalParams(script, serverUser, serverPassword,
        params);
  }
}
