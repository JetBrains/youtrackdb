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

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrackdb.internal.core.command.CommandOutputListener;
import com.jetbrains.youtrackdb.internal.core.config.YouTrackDBConfig;
import com.jetbrains.youtrackdb.internal.core.metadata.security.auth.AuthenticationInfo;
import com.jetbrains.youtrackdb.internal.core.security.SecuritySystem;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;


public interface YouTrackDBInternal extends AutoCloseable, SchedulerInternal {

  YouTrackDBImpl newYouTrackDb();


  /**
   * Create a new Embedded factory
   *
   * @param directoryPath base path where the database are hosted
   * @param config        configuration for the specific factory for the list of option
   *                      {@see GlobalConfiguration}
   * @param serverMode
   * @return a new embedded databases factory
   */
  static YouTrackDBInternalEmbedded embedded(
      String directoryPath,
      YouTrackDBConfig config,
      boolean serverMode) {
    return new YouTrackDBInternalEmbedded(directoryPath, config,
        YouTrackDBEnginesManager.instance(), serverMode);
  }


  /**
   * Open a database specified by name using the username and password if needed
   *
   * @param name     of the database to open
   * @param user     the username allowed to open the database
   * @param password related to the specified username
   * @return the opened database
   */
  DatabaseSessionEmbedded open(String name, String user, String password);

  /**
   * Open a database specified by name using the username and password if needed, with specific
   * configuration
   *
   * @param name     of the database to open
   * @param user     the username allowed to open the database
   * @param password related to the specified username
   * @param config   database specific configuration that override the factory global settings where
   *                 needed.
   * @return the opened database
   */
  DatabaseSessionEmbedded open(String name, String user, String password,
      YouTrackDBConfig config);

  /**
   * Open a database specified by name using the authentication info provided, with specific
   * configuration
   *
   * @param authenticationInfo authentication informations provided for the authentication.
   * @param config             database specific configuration that override the factory global
   *                           settings where needed.
   * @return the opened database
   */
  DatabaseSessionEmbedded open(AuthenticationInfo authenticationInfo, YouTrackDBConfig config);

  /**
   * Create a new database
   *
   * @param name     database name
   * @param user     the username of a user allowed to create a database, in case of remote is a
   *                 server user for embedded it can be left empty
   * @param password the password relative to the user
   * @param type     can be disk or memory
   */
  void create(String name, String user, String password, DatabaseType type);

  /**
   * Create a new database
   *
   * @param name     database name
   * @param user     the username of a user allowed to create a database, in case of remote is a
   *                 server user for embedded it can be left empty
   * @param password the password relative to the user
   * @param config   database specific configuration that override the factory global settings where
   *                 needed.
   * @param type     can be disk or memory
   */
  void create(String name, String user, String password, DatabaseType type,
      YouTrackDBConfig config);

  /**
   * Check if a database exists
   *
   * @param name     database name to check
   * @param user     the username of a user allowed to check the database existence, in case of
   *                 remote is a server user for embedded it can be left empty.
   * @param password the password relative to the user
   * @return boolean true if exist false otherwise.
   */
  boolean exists(String name, String user, String password);

  /**
   * Drop a database
   *
   * @param name     database name
   * @param user     the username of a user allowed to drop a database, in case of remote is a
   *                 server user for embedded it can be left empty
   * @param password the password relative to the user
   */
  void drop(String name, String user, String password);

  /**
   * List of database exiting in the current environment
   *
   * @param user     the username of a user allowed to list databases, in case of remote is a server
   *                 user for embedded it can be left empty
   * @param password the password relative to the user
   * @return a set of databases names.
   */
  Set<String> listDatabases(String user, String password);

  /**
   * Open a pool of databases, similar to open but with multiple instances.
   *
   * @param name     database name
   * @param user     the username allowed to open the database
   * @param password the password relative to the user
   * @return a new pool of databases.
   */
  DatabasePoolInternal openPool(String name, String user, String password);

  /**
   * Open a pool of databases, similar to open but with multiple instances.
   *
   * @param name     database name
   * @param user     the username allowed to open the database
   * @param password the password relative to the user
   * @param config   database specific configuration that override the factory global settings where
   *                 needed.
   * @return a new pool of databases.
   */
  DatabasePoolInternal openPool(String name, String user, String password,
      YouTrackDBConfig config);

  DatabasePoolInternal cachedPool(String database, String user, String password);

  DatabasePoolInternal cachedPool(
      String database, String user, String password, YouTrackDBConfig config);

  DatabasePoolInternal cachedPoolNoAuthentication(String database, String user,
      YouTrackDBConfig config);

  /**
   * Internal api for request to open a database with a pool
   */
  DatabaseSessionEmbedded poolOpen(
      String name, String user, String password, DatabasePoolInternal pool);


  void restore(
      String name,
      DatabaseType type,
      String path,
      YouTrackDBConfig config);

  void restore(
      String name,
      InputStream in,
      Map<String, Object> options,
      Callable<Object> callable,
      CommandOutputListener iListener);

  /**
   * Close the factory with all related databases and pools.
   */
  @Override
  void close();

  /**
   * Should be called only by shutdown listeners
   */
  void internalClose();

  /**
   * Internal API for pool close
   */
  void removePool(DatabasePoolInternal toRemove);

  /**
   * Check if the current instance is open
   */
  boolean isOpen();

  boolean isEmbedded();

  default boolean isMemoryOnly() {
    return false;
  }

  static YouTrackDBInternal extract(
      YouTrackDBImpl youTrackDB) {
    return youTrackDB.internal;
  }

  void removeShutdownHook();

  void forceDatabaseClose(String databaseName);

  default SystemDatabase getSystemDatabase() {
    throw new UnsupportedOperationException();
  }

  default String getBasePath() {
    throw new UnsupportedOperationException();
  }

  void create(
      String name,
      String user,
      String password,
      DatabaseType type,
      YouTrackDBConfig config,
      boolean failIfExists,
      DatabaseTask<Void> createOps);

  YouTrackDBConfigImpl getConfiguration();

  SecuritySystem getSecuritySystem();

  String getConnectionUrl();
}
