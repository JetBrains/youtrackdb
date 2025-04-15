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
package com.jetbrains.youtrack.db.internal.client.remote;

import com.jetbrains.youtrack.db.api.DatabaseType;
import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseDocumentTxInternal;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBConfigImpl;
import com.jetbrains.youtrack.db.internal.core.exception.StorageException;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;

/**
 * Remote administration class of YouTrackDB Server instances.
 */
@Deprecated
public class ServerAdmin {

  protected StorageRemoteSession session = new StorageRemoteSession(-1);
  protected String clientType = StorageRemote.DRIVER_NAME;
  protected boolean collectStats = true;
  private final RemoteURLs urls;
  private final YouTrackDBRemote remote;
  private String user;
  private String password;
  private final Optional<String> database;

  /**
   * Creates the object passing a remote URL to connect. sessionToken
   *
   * @param iURL URL to connect. It supports only the "remote" storage type.
   * @throws IOException
   */
  @Deprecated
  public ServerAdmin(String iURL) throws IOException {
    var url = iURL;
    if (url.startsWith(EngineRemote.NAME)) {
      url = url.substring(EngineRemote.NAME.length() + 1);
    }

    if (!url.contains("/")) {
      url += "/";
    }

    remote = (YouTrackDBRemote) DatabaseDocumentTxInternal.getOrCreateRemoteFactory(url);
    urls = new RemoteURLs(new String[]{}, remote.getContextConfiguration());
    var name = urls.parseServerUrls(url, remote.getContextConfiguration());
    if (name != null && name.length() != 0) {
      this.database = Optional.of(name);
    } else {
      this.database = Optional.empty();
    }
  }

  public ServerAdmin(YouTrackDBRemote remote, String url) throws IOException {
    this.remote = remote;
    urls = new RemoteURLs(new String[]{}, remote.getContextConfiguration());
    var name = urls.parseServerUrls(url, remote.getContextConfiguration());
    if (name != null && name.length() != 0) {
      this.database = Optional.of(name);
    } else {
      this.database = Optional.empty();
    }
  }

  /**
   * Creates the object starting from an existent remote storage.
   *
   * @param iStorage
   */
  @Deprecated
  public ServerAdmin(final StorageRemote iStorage) {
    this.remote = iStorage.context;
    urls = new RemoteURLs(new String[]{}, remote.getContextConfiguration());
    urls.parseServerUrls(iStorage.getURL(), remote.getContextConfiguration());
    this.database = Optional.ofNullable(iStorage.getName());
  }

  /**
   * Connects to a remote server.
   *
   * @param iUserName     Server's user name
   * @param iUserPassword Server's password for the user name used
   * @return The instance itself. Useful to execute method in chain
   * @throws IOException
   */
  @Deprecated
  public synchronized ServerAdmin connect(final String iUserName, final String iUserPassword)
      throws IOException {

    this.user = iUserName;
    this.password = iUserPassword;

    return this;
  }

  private void checkConnected() {
    if (user == null || password == null) {
      throw new StorageException(null,
          "ServerAdmin not connect use connect before do an operation");
    }
  }

  /**
   * Returns the list of databases on the connected remote server.
   *
   * @throws IOException
   */
  @Deprecated
  public synchronized Map<String, String> listDatabases() throws IOException {
    checkConnected();
    return remote.getDatabases(user, password);
  }

  /**
   * Returns the server information in form of entity.
   */
  @Deprecated
  public synchronized Map<String, Object> getServerInfo() throws IOException {
    checkConnected();
    return remote.getServerInfo(user, password);
  }

  public int getSessionId() {
    return session.getSessionId();
  }

  /**
   * Deprecated. Use the {@link #createDatabase(String, String)} instead.
   */
  @Deprecated
  public synchronized ServerAdmin createDatabase(final String iStorageMode) throws IOException {
    return createDatabase("document", iStorageMode);
  }

  /**
   * Creates a database in a remote server.
   *
   * @param iDatabaseType 'document' or 'graph'
   * @param iStorageMode  local or memory
   * @return The instance itself. Useful to execute method in chain
   * @throws IOException
   */
  @Deprecated
  public synchronized ServerAdmin createDatabase(final String iDatabaseType, String iStorageMode)
      throws IOException {
    return createDatabase(getStorageName(), iDatabaseType, iStorageMode);
  }

  public synchronized String getStorageName() {
    return database.get();
  }

  public synchronized ServerAdmin createDatabase(
      final String iDatabaseName, final String iDatabaseType, final String iStorageMode)
      throws IOException {
    return createDatabase(iDatabaseName, iDatabaseType, iStorageMode, null);
  }

  /**
   * Creates a database in a remote server.
   *
   * @param iDatabaseName The database name
   * @param iDatabaseType 'document' or 'graph'
   * @param iStorageMode  local or memory
   * @param backupPath    path to incremental backup which will be used to create database
   *                      (optional)
   * @return The instance itself. Useful to execute method in chain
   * @throws IOException
   */
  public synchronized ServerAdmin createDatabase(
      final String iDatabaseName,
      final String iDatabaseType,
      final String iStorageMode,
      final String backupPath)
      throws IOException {
    checkConnected();
    DatabaseType storageMode;
    if (iStorageMode == null) {
      storageMode = DatabaseType.DISK;
    } else {
      storageMode = DatabaseType.valueOf(iStorageMode.toUpperCase());
    }
    var config =
        (YouTrackDBConfigImpl) YouTrackDBConfig.builder()
            .addGlobalConfigurationParameter(GlobalConfiguration.CREATE_DEFAULT_USERS, true)
            .build();
    if (backupPath != null) {
      remote.restore(iDatabaseName, user, password, storageMode, backupPath, null);
    } else {
      remote.create(iDatabaseName, user, password, storageMode, config);
    }

    return this;
  }

  /**
   * Checks if a database exists in the remote server.
   *
   * @return true if exists, otherwise false
   */
  public synchronized boolean existsDatabase() throws IOException {
    return existsDatabase(database.get(), null);
  }

  /**
   * Checks if a database exists in the remote server.
   *
   * @param iDatabaseName The database name
   * @param storageType   Storage type between "disk" or "memory".
   * @return true if exists, otherwise false
   * @throws IOException
   */
  public synchronized boolean existsDatabase(final String iDatabaseName, final String storageType)
      throws IOException {
    checkConnected();
    return remote.exists(iDatabaseName, user, password);
  }

  /**
   * Checks if a database exists in the remote server.
   *
   * @param storageType Storage type between "disk" or "memory".
   * @return true if exists, otherwise false
   * @throws IOException
   */
  public synchronized boolean existsDatabase(final String storageType) throws IOException {
    checkConnected();
    return existsDatabase(getStorageName(), storageType);
  }

  /**
   * Deprecated. Use dropDatabase() instead.
   *
   * @param storageType Storage type between "disk" or "memory".
   * @return The instance itself. Useful to execute method in chain
   * @throws IOException
   * @see #dropDatabase(String)
   */
  @Deprecated
  public ServerAdmin deleteDatabase(final String storageType) throws IOException {
    return dropDatabase(getStorageName(), storageType);
  }

  /**
   * Drops a database from a remote server instance.
   *
   * @param iDatabaseName The database name
   * @param storageType   Storage type between "disk" or "memory".
   * @return The instance itself. Useful to execute method in chain
   * @throws IOException
   */
  public synchronized ServerAdmin dropDatabase(
      final String iDatabaseName, final String storageType) throws IOException {
    checkConnected();
    remote.drop(iDatabaseName, user, password);
    return this;
  }

  /**
   * Drops a database from a remote server instance.
   *
   * @param storageType Storage type between "disk" or "memory".
   * @return The instance itself. Useful to execute method in chain
   * @throws IOException
   */
  public synchronized ServerAdmin dropDatabase(final String storageType) throws IOException {
    return dropDatabase(getStorageName(), storageType);
  }

  /**
   * Freezes the database by locking it in exclusive mode.
   *
   * @param storageType Storage type between "disk" or "memory".
   * @return
   * @throws IOException
   * @see #releaseDatabase(String)
   */
  public synchronized ServerAdmin freezeDatabase(final String storageType) throws IOException {
    checkConnected();
    remote.freezeDatabase(getStorageName(), user, password);
    return this;
  }

  /**
   * Releases a frozen database.
   *
   * @param storageType Storage type between "disk" or "memory".
   * @return
   * @throws IOException
   * @see #freezeDatabase(String)
   */
  public synchronized ServerAdmin releaseDatabase(final String storageType) throws IOException {
    checkConnected();
    remote.releaseDatabase(getStorageName(), user, password);
    return this;
  }

  public synchronized Map<String, String> getGlobalConfigurations() throws IOException {
    checkConnected();
    return remote.getGlobalConfigurations(user, password);
  }

  public synchronized String getGlobalConfiguration(final GlobalConfiguration config)
      throws IOException {
    checkConnected();
    return remote.getGlobalConfiguration(user, password, config);
  }

  public synchronized ServerAdmin setGlobalConfiguration(
      final GlobalConfiguration config, final Object iValue) throws IOException {
    checkConnected();
    remote.setGlobalConfiguration(user, password, config, iValue.toString());
    return this;
  }

  /**
   * Close the connection if open.
   */
  public synchronized void close() {
  }

  public synchronized void close(boolean iForce) {
  }

  public synchronized String getURL() {
    var url = String.join(";", this.urls.getUrls());
    if (database.isPresent()) {
      url += "/" + database.get();
    }
    return "remote:" + url;
  }

  public boolean isConnected() {
    return user != null && password != null;
  }
}
