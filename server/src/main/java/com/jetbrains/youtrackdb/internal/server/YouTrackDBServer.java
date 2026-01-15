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
package com.jetbrains.youtrackdb.internal.server;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.common.parser.SystemVariableResolver;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBConstants;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.config.YouTrackDBConfig;
import com.jetbrains.youtrackdb.internal.core.db.DatabasePoolInternal;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseTask;
import com.jetbrains.youtrackdb.internal.core.db.SystemDatabase;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBConfigBuilderImpl;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBConfigImpl;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBInternal;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBInternalEmbedded;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.ConfigurationException;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphFactory;
import com.jetbrains.youtrackdb.internal.core.metadata.security.auth.AuthenticationInfo;
import com.jetbrains.youtrackdb.internal.core.security.SecuritySystem;
import com.jetbrains.youtrackdb.internal.server.config.ServerConfigurationManager;
import com.jetbrains.youtrackdb.internal.server.plugin.gremlin.YTDBSettings;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TimerTask;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.server.GremlinServer;

public class YouTrackDBServer {

  public static final String DEFAULT_ROOT_USER = "root";
  public static final String GUEST_USER = "guest";
  public static final String DEFAULT_GUEST_PASSWORD = "!!!TheGuestPw123";

  public static final String DEFAULT_CONFIG_CLASSPATH = "classpath:com/jetbrains/youtrackdb/internal/server/conf/youtrackdb-server.yaml";
  public static final String PROPERTY_CONFIG_FILE = "youtrackdb.config.file";
  public static final String DEFAULT_CONFIG_LOCATION = "conf/youtrackdb-server.yaml";
  private static final String ROOT_PASSWORD_FILE = "secrets/root_password";

  private CountDownLatch startupLatch;
  private CountDownLatch shutdownLatch;
  private final boolean shutdownEngineOnExit;

  protected ReentrantLock lock = new ReentrantLock();
  protected volatile boolean running = false;
  protected ServerConfigurationManager serverCfg;
  protected ContextConfiguration contextConfiguration;
  protected ServerShutdownHook shutdownHook;
  protected List<ServerLifecycleListener> lifecycleListeners = new ArrayList<>();
  private String serverRootDirectory;
  private String databaseDirectory;

  private YouTrackDBImpl context;
  private YTDBInternalProxy databases;

  private final Set<String> dbNamesCache = ConcurrentHashMap.newKeySet();
  private final ReentrantLock dbNamesCacheLock = new ReentrantLock();
  private GremlinServer gremlinServer;

  public YouTrackDBServer() {
    this(!YouTrackDBEnginesManager.instance().isInsideWebContainer());
  }

  public YouTrackDBServer(boolean shutdownEngineOnExit) {
    final var insideWebContainer = YouTrackDBEnginesManager.instance().isInsideWebContainer();

    if (insideWebContainer && shutdownEngineOnExit) {
      LogManager.instance()
          .warn(
              this,
              "YouTrackDB instance is running inside of web application, it is highly unrecommended"
                  + " to force to shutdown YouTrackDB engine on server shutdown");
    }

    this.shutdownEngineOnExit = shutdownEngineOnExit;

    serverRootDirectory =
        SystemVariableResolver.resolveSystemVariables(
            "${" + YouTrackDBEnginesManager.YOUTRACKDB_HOME + "}", ".");

    LogManager.installCustomFormatter();

    defaultSettings();

    System.setProperty("com.sun.management.jmxremote", "true");

    YouTrackDBEnginesManager.instance().startup();

    if (shutdownEngineOnExit) {
      shutdownHook = new ServerShutdownHook(this);
    }
  }

  @SuppressWarnings("unused")
  public static YouTrackDBServer startFromFileConfig(String config)
      throws ClassNotFoundException, InstantiationException, IOException, IllegalAccessException {
    var server = new YouTrackDBServer(false);
    server.startup(config);
    if (!server.activate()) {
      System.exit(1);
    }
    return server;
  }


  public SecuritySystem getSecurity() {
    return databases.getSecuritySystem();
  }

  public boolean isActive() {
    return running;
  }

  public YouTrackDBServer startup() throws ConfigurationException, IOException {
    String config = null;
    if (System.getProperty(PROPERTY_CONFIG_FILE) != null) {
      config = System.getProperty(PROPERTY_CONFIG_FILE);
    }
    if (config == null) {
      if (Files.exists(Path.of(DEFAULT_CONFIG_LOCATION))) {
        config = DEFAULT_CONFIG_LOCATION;
      } else {
        config = DEFAULT_CONFIG_CLASSPATH;
      }
    }

    YouTrackDBEnginesManager.instance().startup();

    startup(SystemVariableResolver.resolveSystemVariables(config));

    return this;
  }

  public void startup(final String configuration) {
    try {
      serverCfg = new ServerConfigurationManager(configuration, this);
      startupFromConfiguration();

    } catch (IOException e) {
      final var message =
          "Error on reading server configuration from file: " + configuration;
      LogManager.instance().error(this, message, e);
      throw BaseException.wrapException(new ConfigurationException(message), e, (String) null);
    }
  }

  public void startup(final YTDBSettings iConfiguration)
      throws IllegalArgumentException, SecurityException, IOException {
    iConfiguration.server = this;
    serverCfg = new ServerConfigurationManager(iConfiguration);
    startupFromConfiguration();
  }

  public void startupFromConfiguration() throws IOException {
    LogManager.instance()
        .info(this,
            "YouTrackDB Server v" + YouTrackDBConstants.getVersion() + " is starting up...");

    YouTrackDBEnginesManager.instance();

    if (startupLatch == null) {
      startupLatch = new CountDownLatch(1);
    }
    if (shutdownLatch == null) {
      shutdownLatch = new CountDownLatch(1);
    }

    initFromConfiguration();

    if (contextConfiguration.getValueAsBoolean(
        GlobalConfiguration.ENVIRONMENT_DUMP_CFG_AT_STARTUP)) {
      System.out.println("Dumping environment after server startup...");
      GlobalConfiguration.dumpConfiguration(System.out);
    }

    databaseDirectory =
        contextConfiguration.getValue("server.database.path", serverRootDirectory + "/databases/");
    databaseDirectory =
        FileUtils.getPath(SystemVariableResolver.resolveSystemVariables(databaseDirectory));
    databaseDirectory = databaseDirectory.replace("//", "/");

    // CONVERT IT TO ABSOLUTE PATH
    databaseDirectory = (new File(databaseDirectory)).getCanonicalPath();
    databaseDirectory = FileUtils.getPath(databaseDirectory);

    if (!(!databaseDirectory.isEmpty()
        && databaseDirectory.charAt(databaseDirectory.length() - 1) == '/')) {
      databaseDirectory += "/";
    }

    var builder = (YouTrackDBConfigBuilderImpl) YouTrackDBConfig.builder();
    for (var user : serverCfg.getUsers()) {
      builder.addGlobalUser(user.name, user.password, user.resources);
    }

    YouTrackDBConfig config =
        builder
            .fromContext(contextConfiguration)
            .setSecurityConfig(new ServerSecurityConfig(this, this.serverCfg))
            .build();

    databases = new YTDBInternalProxy(
        YouTrackDBInternal.embedded(this.databaseDirectory,
            config, true));
    context = databases.newYouTrackDb();

    LogManager.instance()
        .info(this, "Databases directory: " + new File(databaseDirectory).getAbsolutePath());

  }

  public boolean activate()
      throws ClassNotFoundException, InstantiationException, IllegalAccessException {
    lock.lock();
    try {
      // Checks to see if the YouTrackDB System Database exists and creates it if not.
      // Make sure this happens after setSecurity() is called.
      if (contextConfiguration.getValueAsBoolean(GlobalConfiguration.DB_SYSTEM_DATABASE_ENABLED)) {
        initSystemDatabase();
      }

      for (var l : lifecycleListeners) {
        l.onBeforeActivate();
      }

      try {
        loadStorages();
        if (!loadUsers()) {
          return false;
        }
        loadDatabases();
      } catch (IOException e) {
        final var message = "Error on reading server configuration";
        LogManager.instance().error(this, message, e);

        throw BaseException.wrapException(new ConfigurationException(message), e, (String) null);
      }

      for (var l : lifecycleListeners) {
        l.onAfterActivate();
      }

      gremlinServer = new GremlinServer(serverCfg.getConfiguration());
      gremlinServer.start().join();

      running = true;

      LogManager.instance()
          .info(
              this,
              "$ANSI{green:italic YouTrackDB Server is active} v" + YouTrackDBConstants.getVersion()
                  + ".");
    } catch (RuntimeException e) {
      deinit();
      throw e;
    } catch (Exception e) {
      deinit();
      throw new RuntimeException(e);
    } finally {
      lock.unlock();
      startupLatch.countDown();
    }

    return true;
  }

  @SuppressWarnings("unused")
  public void removeShutdownHook() {
    if (shutdownHook != null) {
      shutdownHook.cancel();
      shutdownHook = null;
    }
  }

  public boolean shutdown() {
    try {
      return deinit();
    } finally {
      startupLatch = null;
      if (shutdownLatch != null) {
        shutdownLatch.countDown();
        shutdownLatch = null;
      }
    }
  }

  protected boolean deinit() {
    try {
      running = false;

      if (gremlinServer != null) {
        gremlinServer.stop().join();
      }

      LogManager.instance().info(this, "YouTrackDB Server is shutting down...");

      if (shutdownHook != null) {
        shutdownHook.cancel();
      }

      for (var l : lifecycleListeners) {
        l.onBeforeDeactivate();
      }

      lock.lock();
      try {
        for (var l : lifecycleListeners) {
          try {
            l.onAfterDeactivate();
          } catch (Exception e) {
            LogManager.instance()
                .error(this, "Error during deactivation of server lifecycle listener %s", e, l);
          }
        }
      } finally {
        lock.unlock();
      }

      if (shutdownEngineOnExit && !YouTrackDBEnginesManager.isRegisterDatabaseByPath()) {
        try {
          LogManager.instance().info(this, "Shutting down databases:");
          YouTrackDBEnginesManager.instance().shutdown();
        } catch (Exception e) {
          LogManager.instance().error(this, "Error during YouTrackDB shutdown", e);
        }
      }

      if (context != null) {
        context.close();
        context = null;
      }

      if (databases != null) {
        databases.close();
        databases = null;
      }
    } finally {
      LogManager.instance().info(this, "YouTrackDB Server shutdown complete\n");
      LogManager.flush();
    }

    return true;
  }

  public void waitForShutdown() {
    try {
      if (shutdownLatch != null) {
        shutdownLatch.await();
      }
    } catch (InterruptedException e) {
      LogManager.instance().error(this, "Error during waiting for YouTrackDB shutdown", e);
    }
  }

  /**
   * Opens all the available server's databases.
   */
  protected void loadDatabases() {
    dbNamesCache.clear();
    dbNamesCache.addAll(databases.internal.listDatabases(null, null));

    if (!contextConfiguration.getValueAsBoolean(
        GlobalConfiguration.SERVER_OPEN_ALL_DATABASES_AT_STARTUP)) {
      return;
    }

    databases.loadAllDatabases();
  }

  public String getDatabaseDirectory() {
    return databaseDirectory;
  }

  public String getServerRootDirectory() {
    return serverRootDirectory;
  }


  public YTDBSettings getConfiguration() {
    return serverCfg.getConfiguration();
  }

  public ContextConfiguration getContextConfiguration() {
    return contextConfiguration;
  }

  public GremlinServer getGremlinServer() {
    return gremlinServer;
  }

  @SuppressWarnings("unused")
  public YouTrackDBServer registerLifecycleListener(final ServerLifecycleListener iListener) {
    lifecycleListeners.add(iListener);
    return this;
  }

  @SuppressWarnings("unused")
  public YouTrackDBServer unregisterLifecycleListener(final ServerLifecycleListener iListener) {
    lifecycleListeners.remove(iListener);
    return this;
  }

  public void setServerRootDirectory(final String rootDirectory) {
    this.serverRootDirectory = rootDirectory;
  }

  protected void initFromConfiguration() {
    final var cfg = serverCfg.getConfiguration();

    // FILL THE CONTEXT CONFIGURATION WITH SERVER'S PARAMETERS
    contextConfiguration = new ContextConfiguration();

    if (cfg.properties != null) {
      for (var prop : cfg.properties.entrySet()) {
        contextConfiguration.setValue(prop.getKey(), prop.getValue());
      }
    }
  }

  protected boolean loadUsers() throws IOException {
    final var configuration = serverCfg.getConfiguration();

    if (configuration.isAfterFirstTime) {
      return true;
    }

    configuration.isAfterFirstTime = true;

    return createDefaultServerUsers();
  }

  /**
   * Load configured storages.
   */
  protected void loadStorages() {
    final var configuration = serverCfg.getConfiguration();

    if (configuration.storages == null) {
      return;
    }
    for (var stg : configuration.storages) {
      if (stg.loadOnStartup) {
        var url = stg.path;
        if (!url.isEmpty() && url.charAt(url.length() - 1) == '/') {
          url = url.substring(0, url.length() - 1);
        }
        url = url.replace('\\', '/');

        var typeIndex = url.indexOf(':');
        if (typeIndex <= 0) {
          throw new ConfigurationException(
              "Error in database URL: the engine was not specified. Syntax is: "
                  + YouTrackDBEnginesManager.URL_SYNTAX
                  + ". URL was: "
                  + url);
        }

        var remoteUrl = url.substring(typeIndex + 1);
        var index = remoteUrl.lastIndexOf('/');
        String baseUrl;
        if (index > 0) {
          baseUrl = remoteUrl.substring(0, index);
        } else {
          baseUrl = "./";
        }
        databases.initCustomStorage(stg.name, baseUrl);
      }
    }
  }

  protected boolean createDefaultServerUsers() throws IOException {
    if (databases.getSecuritySystem() != null
        && !databases.getSecuritySystem().arePasswordsStored()) {
      return true;
    }
    final var systemDbEnabled =
        contextConfiguration.getValueAsBoolean(GlobalConfiguration.DB_SYSTEM_DATABASE_ENABLED);

    var rootSecretFile = Path.of(ROOT_PASSWORD_FILE);
    String rootSecretFilePassword = null;

    if (Files.exists(rootSecretFile)) {
      rootSecretFilePassword = Files.readString(rootSecretFile).trim();
    }

    var existsRoot =
        serverCfg.existsUser(DEFAULT_ROOT_USER) ||
            systemDbEnabled && existsSystemUser(DEFAULT_ROOT_USER);

    if (!existsRoot) {
      if (rootSecretFilePassword == null || rootSecretFilePassword.isEmpty()) {
        LogManager.instance()
            .error(this,
                "Root password is not set and root user does not exist. "
                    + "Root user credentials has to provided either by server settings or "
                    + "in secrets/root_password file. Exiting...", null);
        return false;
      } else {
        LogManager.instance()
            .info(
                this,
                "Found %s file, using it's content as root's password",
                ROOT_PASSWORD_FILE);
      }
    }

    if (systemDbEnabled) {
      if (!existsRoot) {
        var rootPassword = rootSecretFilePassword;
        databases.getSystemDatabase().executeWithDB(session -> {
          session.executeInTx(transaction -> {
            var metadata = session.getMetadata();
            var security = metadata.getSecurity();

            if (security.getUser(DEFAULT_ROOT_USER) == null) {
              security.createUser(DEFAULT_ROOT_USER,
                  rootPassword, "root");
            }
          });
          return null;
        });
      }

      databases.getSystemDatabase().executeWithDB(session -> {
        session.executeInTx(transaction -> {
          var metadata = session.getMetadata();
          var security = metadata.getSecurity();

          if (security.getUser(GUEST_USER) == null) {
            security.createUser(GUEST_USER,
                DEFAULT_GUEST_PASSWORD, "guest");
          }
        });
        return null;
      });
    }

    return true;
  }

  @SuppressWarnings("SameParameterValue")
  private boolean existsSystemUser(String user) {
    return databases.getSystemDatabase()
        .executeWithDB(session -> session.computeInTx(transaction -> {
          var metadata = session.getMetadata();
          var security = metadata.getSecurity();

          return security.getUser(user) != null;
        }));
  }


  protected void defaultSettings() {
  }

  private void initSystemDatabase() {
    databases.getSystemDatabase().init();
  }

  public YTDBInternalProxy getDatabases() {
    return databases;
  }

  public YouTrackDBImpl getYouTrackDB() {
    return context;
  }

  public void dropDatabase(String databaseName) {
    if (databases.exists(databaseName)) {
      databases.drop(databaseName, null, null);
    } else {
      throw new StorageException(databaseName,
          "Database with name '" + databaseName + "' does not exist");
    }
  }

  public boolean existsDatabase(String databaseName) {
    return databases.exists(databaseName);
  }

  public final class YTDBInternalProxy implements YouTrackDBInternal, ServerAware {

    private final YouTrackDBInternalEmbedded internal;

    private YTDBInternalProxy(YouTrackDBInternalEmbedded internal) {
      this.internal = internal;
    }

    @Override
    public YouTrackDBImpl newYouTrackDb() {
      return YTDBGraphFactory.ytdbInstance(internal.getBasePath(), () -> this);
    }

    @Override
    public DatabaseSessionEmbedded open(String name, String user, String password) {
      return internal.open(name, user, password);
    }

    @Override
    public DatabaseSessionEmbedded open(String name, String user, String password,
        YouTrackDBConfig config) {
      return internal.open(name, user, password, config);
    }

    @Override
    public DatabaseSessionEmbedded open(AuthenticationInfo authenticationInfo,
        YouTrackDBConfig config) {
      return internal.open(authenticationInfo, config);
    }

    @Override
    public void create(String name, String user, String password, DatabaseType type) {
      dbNamesCacheLock.lock();
      try {
        internal.create(name, user, password, type);
        dbNamesCache.add(name);
      } finally {
        dbNamesCacheLock.unlock();
      }

    }

    @Override
    public void create(String name, String user, String password, DatabaseType type,
        YouTrackDBConfig config) {
      dbNamesCacheLock.lock();
      try {
        internal.create(name, user, password, type, config);
        dbNamesCache.add(name);
      } finally {
        dbNamesCacheLock.unlock();
      }

    }


    @Override
    public boolean exists(String name) {
      //system database is managed inside of embedded instance autonomously
      if (SystemDatabase.SYSTEM_DB_NAME.equals(name)) {
        return internal.exists(name);
      }

      return dbNamesCache.contains(name);
    }

    @Override
    public void drop(String name, String user, String password) {
      dbNamesCacheLock.lock();
      try {
        internal.drop(name, user, password);
        dbNamesCache.remove(name);
      } finally {
        dbNamesCacheLock.unlock();
      }

    }

    @Override
    public Set<String> listDatabases(String user, String password) {
      return Collections.unmodifiableSet(dbNamesCache);
    }

    @Override
    public DatabasePoolInternal openPool(String name, String user,
        String password) {
      return internal.openPool(name, user, password);
    }

    @Override
    public DatabasePoolInternal openPool(String name, String user, String password,
        YouTrackDBConfig config) {
      return internal.openPool(name, user, password, config);
    }

    @Override
    public DatabasePoolInternal cachedPool(String database, String user,
        String password) {
      return internal.cachedPool(database, user, password);
    }

    @Override
    public DatabasePoolInternal cachedPool(String database, String user,
        String password, YouTrackDBConfig config) {
      return internal.cachedPool(database, user, password, config);
    }

    @Override
    public DatabasePoolInternal cachedPoolNoAuthentication(String database,
        String user, YouTrackDBConfig config) {
      return internal.cachedPoolNoAuthentication(database, user, config);
    }

    @Override
    public DatabaseSessionEmbedded poolOpen(String name, String user, String password,
        DatabasePoolInternal pool) {
      return internal.poolOpen(name, user, password, pool);
    }

    @Override
    public void restore(String name, String path,
        YouTrackDBConfig config) {
      dbNamesCacheLock.lock();
      try {
        internal.restore(name, path, config);
        dbNamesCache.add(name);
      } finally {
        dbNamesCacheLock.unlock();
      }
    }

    @Override
    public void restore(String name, String path,
        @Nullable String expectedUUID, YouTrackDBConfig config) {
      dbNamesCacheLock.lock();
      try {
        internal.restore(name, path, expectedUUID, config);
        dbNamesCache.add(name);
      } finally {
        dbNamesCacheLock.unlock();
      }
    }

    @Override
    public void restore(String name, Supplier<Iterator<String>> ibuFilesSupplier,
        Function<String, InputStream> ibuInputStreamSupplier, @Nullable String expectedUUID,
        YouTrackDBConfig config) {
      dbNamesCacheLock.lock();
      try {
        internal.restore(name, ibuFilesSupplier, ibuInputStreamSupplier, expectedUUID, config);
        dbNamesCache.add(name);
      } finally {
        dbNamesCacheLock.unlock();
      }
    }

    @Override
    public void close() {
      internal.close();
    }

    @Override
    public void internalClose() {
      internal.internalClose();
    }

    @Override
    public void removePool(DatabasePoolInternal toRemove) {
      internal.removePool(toRemove);
    }

    @Override
    public boolean isOpen() {
      return internal.isOpen();
    }

    @Override
    public boolean isEmbedded() {
      return internal.isEmbedded();
    }

    @Override
    public void removeShutdownHook() {
      internal.removeShutdownHook();
    }

    @Override
    public void forceDatabaseClose(String databaseName) {
      internal.forceDatabaseClose(databaseName);
    }

    @Override
    public void create(String name, String user, String password, DatabaseType type,
        YouTrackDBConfig config, boolean failIfExists, DatabaseTask<Void> createOps) {
      dbNamesCacheLock.lock();
      try {
        internal.create(name, user, password, type, config, true, createOps);
        dbNamesCache.add(name);
      } finally {
        dbNamesCacheLock.unlock();
      }
    }


    @Override
    public YouTrackDBConfigImpl getConfiguration() {
      return internal.getConfiguration();
    }

    @Override
    public SecuritySystem getSecuritySystem() {
      return internal.getSecuritySystem();
    }

    @Override
    public String getConnectionUrl() {
      return internal.getConnectionUrl();
    }

    @Override
    public void schedule(TimerTask task, long delay, long period) {
      internal.schedule(task, delay, period);
    }

    @Override
    public void scheduleOnce(TimerTask task, long delay) {
      internal.scheduleOnce(task, delay);
    }

    @Override
    public YouTrackDBServer getServer() {
      return YouTrackDBServer.this;
    }

    public DatabaseSessionEmbedded openNoAuthorization(String name) {
      return internal.openNoAuthorization(name);
    }

    public void loadAllDatabases() {
      internal.loadAllDatabases();
    }

    public void initCustomStorage(String name, String path) {
      internal.initCustomStorage(name, path);
    }

    public <X> Future<X> execute(Callable<X> task) {
      return internal.execute(task);
    }

    public Future<?> execute(Runnable task) {
      return internal.execute(task);
    }

    @Override
    public SystemDatabase getSystemDatabase() {
      return internal.getSystemDatabase();
    }

    @Override
    public boolean isMemoryOnly() {
      return internal.isMemoryOnly();
    }

    @Override
    public String getBasePath() {
      return internal.getBasePath();
    }

  }
}
