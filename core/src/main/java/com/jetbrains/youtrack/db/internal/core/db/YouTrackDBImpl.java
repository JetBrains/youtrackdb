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
import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.DatabaseType;
import com.jetbrains.youtrack.db.api.SessionPool;
import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.api.query.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang.ArrayUtils;


public class YouTrackDBImpl implements YouTrackDB {

  private final ConcurrentLinkedHashMap<DatabasePoolInternal, SessionPoolImpl> cachedPools =
      new ConcurrentLinkedHashMap.Builder<DatabasePoolInternal, SessionPoolImpl>()
          .maximumWeightedCapacity(100)
          .build(); // cache for links to database pools. Avoid create database pool wrapper each
  // time when it is requested

  public YouTrackDBInternal internal;
  public String serverUser;
  public String serverPassword;

  /**
   * Create a new YouTrackDB instance for an embedded deployment with default configuration. For in
   * memory database use any directory name, for example "mydb"
   *
   * @param directoryPath the directory where the database are stored
   */
  public static YouTrackDB embedded(String directoryPath) {
    return embedded(directoryPath, YouTrackDBConfig.defaultConfig());
  }

  /**
   * Create a new YouTrackDB instance for a embedded deployment with custom configuration. For in
   * memory database use any directory name, for example "mydb"
   *
   * @param directoryPath the directory where the database are stored
   * @param config        custom configuration for current environment
   */
  public static YouTrackDB embedded(String directoryPath, YouTrackDBConfig config) {
    return new YouTrackDBImpl(YouTrackDBInternal.embedded(directoryPath, config));
  }

  /**
   * Create a new YouTrackDB instance for a remote deployment with default configuration.
   *
   * @param url            the url for the database server for example "localhost" or
   *                       "localhost:2424"
   * @param serverUser     the server user allowed to manipulate databases.
   * @param serverPassword relative to the server user.
   * @return a new YouTrackDB instance
   */
  public static YouTrackDB remote(String url, String serverUser, String serverPassword) {
    return remote(url, serverUser, serverPassword, YouTrackDBConfig.defaultConfig());
  }

  /**
   * Create a new YouTrackDB instance for a remote deployment with custom configuration.
   *
   * @param url            the url for the database server for example "localhost" or
   *                       "localhost:2424"
   * @param serverUser     the server user allowed to manipulate databases.
   * @param serverPassword relative to the server user.
   * @param config         custom configuration for current environment
   * @return a new YouTrackDB instance
   */
  public static YouTrackDB remote(
      String url, String serverUser, String serverPassword, YouTrackDBConfig config) {
    var youTrackDB =
        new YouTrackDBImpl(
            YouTrackDBInternal.remote(url.substring(url.indexOf(':') + 1).split("[,;]"),
                (YouTrackDBConfigImpl) config));

    youTrackDB.serverUser = serverUser;
    youTrackDB.serverPassword = serverPassword;

    return youTrackDB;
  }

  /**
   * Create a new YouTrackDB instance for a specific environment
   *
   * <p>possible kind of urls 'embedded','remote', for the case of remote and distributed can be
   * specified multiple nodes using comma.
   *
   * <p>Remote Example:
   *
   * <pre>
   * <code>
   * YouTrackDB youTrackDB = new YouTrackDB("remote:localhost");
   * ODatabaseDocument session = youTrackDB.open("test","admin","admin");
   * //...
   * session.close();
   * youTrackDB.close();
   * </code>
   * </pre>
   *
   * <p>Embedded Example:
   *
   * <pre>
   * <code>
   * YouTrackDB youTrackDB = new YouTrackDB("embedded:./databases/");
   * ODatabaseDocument session = youTrackDB.open("test","admin","admin");
   * //...
   * session.close();
   * youTrackDB.close();
   * </code>
   * </pre>
   *
   * @param url           the url for the specific environment.
   * @param configuration configuration for the specific environment for the list of option
   *                      {@see GlobalConfiguration}.
   * @see #embedded(String, YouTrackDBConfig)
   * @see #remote(String, String, String, YouTrackDBConfig)
   * @see #remote(String, String, String)
   * @see #embedded(String)
   */
  public YouTrackDBImpl(String url, YouTrackDBConfig configuration) {
    this(url, null, null, configuration);
  }

  /**
   * Create a new YouTrackDB instance for a specific environment
   *
   * <p>possible kind of urls 'embedded','remote', for the case of remote and distributed can be
   * specified multiple nodes using comma.
   *
   * <p>Remote Example:
   *
   * <pre>
   * <code>
   * YouTrackDB youTrackDB = new YouTrackDB("remote:localhost","root","root");
   * youTrackDB.create("test",DatabaseType.PLOCAL);
   * ODatabaseDocument session = youTrackDB.open("test","admin","admin");
   * //...
   * session.close();
   * youTrackDB.close();
   * </code>
   * </pre>
   *
   * <p>Embedded Example:
   *
   * <pre>
   * <code>
   * YouTrackDB youTrackDB = new YouTrackDB("embedded:./databases/",null,null);
   * youTrackDB.create("test",DatabaseType.MEMORY);
   * ODatabaseDocument session = youTrackDB.open("test","admin","admin");
   * //...
   * session.close();
   * youTrackDB.close();
   * </code>
   * </pre>
   *
   * @param url            the url for the specific environment.
   * @param serverUser     the server user allowed to manipulate databases.
   * @param serverPassword relative to the server user.
   * @param configuration  configuration for the specific environment for the list of option
   *                       {@see GlobalConfiguration}.
   * @see #embedded(String, YouTrackDBConfig)
   * @see #remote(String, String, String, YouTrackDBConfig)
   * @see #remote(String, String, String)
   * @see #embedded(String)
   */
  public YouTrackDBImpl(
      String url, String serverUser, String serverPassword, YouTrackDBConfig configuration) {
    int pos;
    String what;
    if ((pos = url.indexOf(':')) > 0) {
      what = url.substring(0, pos);
    } else {
      what = url;
    }
    if ("embedded".equals(what) || "memory".equals(what) || "plocal".equals(what)) {
      internal = YouTrackDBInternal.embedded(url.substring(url.indexOf(':') + 1), configuration);
    } else if ("remote".equals(what)) {
      internal =
          YouTrackDBInternal.remote(url.substring(url.indexOf(':') + 1).split("[,;]"),
              (YouTrackDBConfigImpl) configuration);
    } else {
      throw new IllegalArgumentException("Wrong url:`" + url + "`");
    }

    this.serverUser = serverUser;
    this.serverPassword = serverPassword;
  }

  public YouTrackDBImpl(YouTrackDBInternal internal) {
    this.internal = internal;
    this.serverUser = null;
    this.serverPassword = null;
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
  public DatabaseSession open(String database, String user, String password) {
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
  public DatabaseSession open(
      String database, String user, String password, YouTrackDBConfig config) {
    return internal.open(database, user, password, config);
  }

  /**
   * Create a new database without users. In case if you want to create users during creation please
   * use {@link #create(String, DatabaseType, String...)}
   *
   * @param database database name
   * @param type     can be plocal or memory
   * @see #create(String, DatabaseType, String...)
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
   * <p>{@code youTrackDB.create("test", DatabaseType.PLOCAL, "user1", "password1", "admin",
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
   * @param type            can be plocal or memory
   * @param userCredentials user names, passwords and roles provided as a sequence of triple
   *                        strings
   */
  @Override
  public void create(String database, DatabaseType type, String... userCredentials) {
    var queryString = new StringBuilder("create database ? " + type.name());
    var params = addUsersToCreationScript(userCredentials, queryString);
    execute(queryString.toString(), ArrayUtils.add(params, 0, database)).close();
  }

  /**
   * Creates a new database without users. In case if you want to create users during creation
   * please use {@link #create(String, DatabaseType, String...)}
   *
   * @param database database name
   * @param type     can be plocal or memory
   * @param config   custom configuration for current database
   */
  @Override
  public void create(String database, DatabaseType type, YouTrackDBConfig config) {
    this.internal.create(database, serverUser, serverPassword, type, config);
  }

  /**
   * Create a new database without users if it does not exist. In case if you want to create users
   * during creation please use {@link #createIfNotExists(String, DatabaseType, String...)}
   *
   * @param database database name
   * @param type     can be plocal or memory
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
   * <p>{@code youTrackDB.createIfNotExists("test", DatabaseType.PLOCAL, "user1", "password1",
   * "admin", "user2", "password2", "reader"); }
   *
   * @param database        database name
   * @param type            can be plocal or memory
   * @param userCredentials user names, passwords and roles provided as a sequence of triple
   *                        strings
   */
  @Override
  public void createIfNotExists(String database, DatabaseType type, String... userCredentials) {
    var queryString =
        new StringBuilder("create database ? " + type.name() + " if not exists");
    var params = addUsersToCreationScript(userCredentials, queryString);
    execute(queryString.toString(), ArrayUtils.add(params, 0, database)).close();
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

        result[i * 2] = userCredentials[i * 3];
        result[i * 2 + 1] = userCredentials[i * 3 + 1];
      }

      queryString.append(")");

      return result;
    }

    return new String[0];
  }

  /**
   * Create a new database without users if not exists. In case if you want to create users during
   * creation please use {@link #createIfNotExists(String, DatabaseType, String...)}
   *
   * @param database database name
   * @param type     can be plocal or memory
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
  public List<String> list() {
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

  DatabasePoolInternal openPool(
      String database, String user, String password, YouTrackDBConfig config) {
    return this.internal.openPool(database, user, password, config);
  }

  @Override
  public SessionPool cachedPool(String database, String user, String password) {
    return cachedPool(database, user, password, null);
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
  public SessionPool cachedPool(
      String database, String user, String password, YouTrackDBConfig config) {
    var internalPool = internal.cachedPool(database, user, password, config);

    SessionPool pool = cachedPools.get(internalPool);

    if (pool != null) {
      return pool;
    }

    return cachedPools.computeIfAbsent(internalPool,
        key -> new SessionPoolImpl(this, internalPool));
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
  public ResultSet execute(String script, Map<String, Object> params) {
    return internal.executeServerStatementNamedParams(script, serverUser, serverPassword, params);
  }

  @Override
  public ResultSet execute(String script, Object... params) {
    return internal.executeServerStatementPositionalParams(script, serverUser, serverPassword,
        params);
  }
}
