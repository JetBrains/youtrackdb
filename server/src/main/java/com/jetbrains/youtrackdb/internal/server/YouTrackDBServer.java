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
import com.jetbrains.youtrackdb.internal.common.console.ConsoleReader;
import com.jetbrains.youtrackdb.internal.common.console.DefaultConsoleReader;
import com.jetbrains.youtrackdb.internal.common.exception.SystemException;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.common.log.AnsiCode;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.common.parser.SystemVariableResolver;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBConstants;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrackdb.internal.core.command.CommandOutputListener;
import com.jetbrains.youtrackdb.internal.core.command.script.ScriptManager;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.config.YouTrackDBConfig;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseLifecycleListener;
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
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphFactory;
import com.jetbrains.youtrackdb.internal.core.metadata.security.auth.AuthenticationInfo;
import com.jetbrains.youtrackdb.internal.core.security.InvalidPasswordException;
import com.jetbrains.youtrackdb.internal.core.security.SecuritySystem;
import com.jetbrains.youtrackdb.internal.core.storage.Storage;
import com.jetbrains.youtrackdb.internal.server.config.ServerConfiguration;
import com.jetbrains.youtrackdb.internal.server.config.ServerConfigurationManager;
import com.jetbrains.youtrackdb.internal.server.handler.ConfigurableHooksManager;
import com.jetbrains.youtrackdb.internal.server.plugin.ServerPlugin;
import com.jetbrains.youtrackdb.internal.server.plugin.ServerPluginInfo;
import com.jetbrains.youtrackdb.internal.server.plugin.ServerPluginManager;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimerTask;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;
import javax.annotation.Nullable;

public class YouTrackDBServer {

  private static final String ROOT_PASSWORD_VAR = "YOUTRACKDB_ROOT_PASSWORD";

  private CountDownLatch startupLatch;
  private CountDownLatch shutdownLatch;
  private final boolean shutdownEngineOnExit;

  protected ReentrantLock lock = new ReentrantLock();
  protected volatile boolean running = false;
  protected ServerConfigurationManager serverCfg;
  protected ContextConfiguration contextConfiguration;
  protected ServerShutdownHook shutdownHook;
  protected List<ServerLifecycleListener> lifecycleListeners = new ArrayList<>();
  protected ServerPluginManager pluginManager;
  protected ConfigurableHooksManager hookManager;
  private String serverRootDirectory;
  private String databaseDirectory;

  private YouTrackDBImpl context;
  private YTDBInternalProxy databases;

  private final Set<String> dbNamesCache = ConcurrentHashMap.newKeySet();
  private final ReentrantLock dbCreationLock = new ReentrantLock();

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
    server.activate();
    return server;
  }

  public static YouTrackDBServer startFromClasspathConfig(String config)
      throws ClassNotFoundException, InstantiationException, IOException, IllegalAccessException {
    var server = new YouTrackDBServer(false);
    server.startup(Thread.currentThread().getContextClassLoader().getResourceAsStream(config));
    server.activate();
    return server;
  }

  public static YouTrackDBServer startFromStreamConfig(InputStream config)
      throws ClassNotFoundException, InstantiationException, IOException, IllegalAccessException {
    var server = new YouTrackDBServer(false);
    server.startup(config);
    server.activate();
    return server;
  }

  public SecuritySystem getSecurity() {
    return databases.getSecuritySystem();
  }

  public boolean isActive() {
    return running;
  }

  /**
   * Load an extension class by name.
   */
  private Class<?> loadClass(final String name) throws ClassNotFoundException {
    var loaded = tryLoadClass(Thread.currentThread().getContextClassLoader(), name);

    if (loaded == null) {
      loaded = tryLoadClass(getClass().getClassLoader(), name);
      if (loaded == null) {
        loaded = Class.forName(name);
      }
    }

    return loaded;
  }

  /**
   * Attempt to load a class from givenstar class-loader.
   */
  @Nullable
  private static Class<?> tryLoadClass(/* @Nullable */ final ClassLoader classLoader,
      final String name) {
    if (classLoader != null) {
      try {
        return classLoader.loadClass(name);
      } catch (ClassNotFoundException e) {
        // ignore
      }
    }
    return null;
  }

  public YouTrackDBServer startup() throws ConfigurationException {
    var config = ServerConfiguration.DEFAULT_CONFIG_FILE;
    if (System.getProperty(ServerConfiguration.PROPERTY_CONFIG_FILE) != null) {
      config = System.getProperty(ServerConfiguration.PROPERTY_CONFIG_FILE);
    }

    YouTrackDBEnginesManager.instance().startup();

    startup(new File(SystemVariableResolver.resolveSystemVariables(config)));

    return this;
  }

  public YouTrackDBServer startup(final File iConfigurationFile) throws ConfigurationException {
    // Startup function split to allow pre-activation changes
    try {
      serverCfg = new ServerConfigurationManager(iConfigurationFile);
      return startupFromConfiguration();

    } catch (IOException e) {
      final var message =
          "Error on reading server configuration from file: " + iConfigurationFile;
      LogManager.instance().error(this, message, e);
      throw BaseException.wrapException(new ConfigurationException(message), e, (String) null);
    }
  }

  public YouTrackDBServer startup(final String iConfiguration) throws IOException {
    return startup(new ByteArrayInputStream(iConfiguration.getBytes()));
  }

  public YouTrackDBServer startup(final InputStream iInputStream) throws IOException {
    if (iInputStream == null) {
      throw new ConfigurationException("Configuration file is null");
    }

    serverCfg = new ServerConfigurationManager(iInputStream);

    // Startup function split to allow pre-activation changes
    return startupFromConfiguration();
  }

  public YouTrackDBServer startup(final ServerConfiguration iConfiguration)
      throws IllegalArgumentException, SecurityException, IOException {
    serverCfg = new ServerConfigurationManager(iConfiguration);
    return startupFromConfiguration();
  }

  public YouTrackDBServer startupFromConfiguration() throws IOException {
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
      builder.addGlobalUser(user.getName(), user.getPassword(), user.getResources());
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

    return this;
  }

  public YouTrackDBServer activate()
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

      final var configuration = serverCfg.getConfiguration();

      try {
        loadStorages();
        loadUsers();
        loadDatabases();
      } catch (IOException e) {
        final var message = "Error on reading server configuration";
        LogManager.instance().error(this, message, e);

        throw BaseException.wrapException(new ConfigurationException(message), e, (String) null);
      }

      registerPlugins();

      for (var l : lifecycleListeners) {
        l.onAfterActivate();
      }

      running = true;

      LogManager.instance()
          .info(
              this,
              "$ANSI{green:italic YouTrackDB Server is active} v" + YouTrackDBConstants.getVersion()
                  + ".");
    } catch (ClassNotFoundException
             | InstantiationException
             | IllegalAccessException
             | RuntimeException e) {
      deinit();
      throw e;
    } finally {
      lock.unlock();
      startupLatch.countDown();
    }

    return this;
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

        if (pluginManager != null) {
          pluginManager.shutdown();
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

  public Map<String, String> getAvailableStorageNames() {
    var dbs = listDatabases();
    Map<String, String> toSend = new HashMap<>();
    for (var dbName : dbs) {
      toSend.put(dbName, dbName);
    }

    return toSend;
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


  public ServerConfiguration getConfiguration() {
    return serverCfg.getConfiguration();
  }

  @Nullable
  public Collection<ServerPluginInfo> getPlugins() {
    return pluginManager != null ? pluginManager.getPlugins() : null;
  }

  public ContextConfiguration getContextConfiguration() {
    return contextConfiguration;
  }

  @SuppressWarnings("unchecked")
  @Nullable
  public <RET extends ServerPlugin> RET getPlugin(final String iName) {
    if (startupLatch == null) {
      throw new DatabaseException("Error on plugin lookup: the server did not start correctly");
    }

    try {
      startupLatch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    if (!running) {
      throw new DatabaseException("Error on plugin lookup: the server did not start correctly");
    }

    final var p = pluginManager.getPluginByName(iName);
    if (p != null) {
      return (RET) p.getInstance();
    }
    return null;
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

  public DatabaseSessionEmbedded openSession(String database) {
    return databases.openNoAuthorization(database);
  }


  public void setServerRootDirectory(final String rootDirectory) {
    this.serverRootDirectory = rootDirectory;
  }

  protected void initFromConfiguration() {
    final var cfg = serverCfg.getConfiguration();

    // FILL THE CONTEXT CONFIGURATION WITH SERVER'S PARAMETERS
    contextConfiguration = new ContextConfiguration();

    if (cfg.properties != null) {
      for (var prop : cfg.properties) {
        contextConfiguration.setValue(prop.name, prop.value);
      }
    }

    hookManager = new ConfigurableHooksManager(cfg);
  }

  protected void loadUsers() throws IOException {
    final var configuration = serverCfg.getConfiguration();

    if (configuration.isAfterFirstTime) {
      return;
    }

    configuration.isAfterFirstTime = true;

    createDefaultServerUsers();
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

  protected void createDefaultServerUsers() throws IOException {

    if (databases.getSecuritySystem() != null
        && !databases.getSecuritySystem().arePasswordsStored()) {
      return;
    }

    var rootPassword = SystemVariableResolver.resolveVariable(ROOT_PASSWORD_VAR);

    if (rootPassword != null) {
      rootPassword = rootPassword.trim();
      if (rootPassword.isEmpty()) {
        rootPassword = null;
      }
    }
    final var systemDbEnabled =
        contextConfiguration.getValueAsBoolean(GlobalConfiguration.DB_SYSTEM_DATABASE_ENABLED);
    var existsRoot =
        serverCfg.existsUser(ServerConfiguration.DEFAULT_ROOT_USER) ||
            systemDbEnabled && existsSystemUser(ServerConfiguration.DEFAULT_ROOT_USER);

    if (rootPassword == null && !existsRoot) {
      try {
        // WAIT ANY LOG IS PRINTED
        Thread.sleep(1000);
      } catch (InterruptedException ignored) {
      }

      System.out.println();
      System.out.println();
      System.out.println(
          AnsiCode.format(
              "$ANSI{yellow +---------------------------------------------------------------+}"));
      System.out.println(
          AnsiCode.format(
              "$ANSI{yellow |                WARNING: FIRST RUN CONFIGURATION               |}"));
      System.out.println(
          AnsiCode.format(
              "$ANSI{yellow +---------------------------------------------------------------+}"));
      System.out.println(
          AnsiCode.format(
              "$ANSI{yellow | This is the first time the server is running. Please type a   |}"));
      System.out.println(
          AnsiCode.format(
              "$ANSI{yellow | password of your choice for the 'root' user or leave it blank |}"));
      System.out.println(
          AnsiCode.format(
              "$ANSI{yellow | to auto-generate it.                                          |}"));
      System.out.println(
          AnsiCode.format(
              "$ANSI{yellow |                                                               |}"));
      System.out.println(
          AnsiCode.format(
              "$ANSI{yellow | To avoid this message set the environment variable or JVM     |}"));
      System.out.println(
          AnsiCode.format(
              "$ANSI{yellow | setting YOUTRACKDB_ROOT_PASSWORD to the root password to use.   |}"));
      System.out.println(
          AnsiCode.format(
              "$ANSI{yellow +---------------------------------------------------------------+}"));

      final ConsoleReader console = new DefaultConsoleReader();

      // ASK FOR PASSWORD + CONFIRM
      do {
        System.out.print(
            AnsiCode.format("\n$ANSI{yellow Root password [BLANK=auto generate it]: }"));
        rootPassword = console.readPassword();

        if (rootPassword != null) {
          rootPassword = rootPassword.trim();
          if (rootPassword.isEmpty()) {
            rootPassword = null;
          }
        }

        if (rootPassword != null) {
          System.out.print(AnsiCode.format("$ANSI{yellow Please confirm the root password: }"));

          var rootConfirmPassword = console.readPassword();
          if (rootConfirmPassword != null) {
            rootConfirmPassword = rootConfirmPassword.trim();
            if (rootConfirmPassword.isEmpty()) {
              rootConfirmPassword = null;
            }
          }

          if (!rootPassword.equals(rootConfirmPassword)) {
            System.out.println(
                AnsiCode.format(
                    "$ANSI{red ERROR: Passwords don't match, please reinsert both of them, or press"
                        + " ENTER to auto generate it}"));
          } else
          // PASSWORDS MATCH

          {
            try {
              if (getSecurity() != null) {
                getSecurity().validatePassword("root", rootPassword);
              }
              // PASSWORD IS STRONG ENOUGH
              break;
            } catch (InvalidPasswordException ex) {
              System.out.println(
                  AnsiCode.format(
                      "$ANSI{red ERROR: Root password does not match the password policies}"));
              if (ex.getMessage() != null) {
                System.out.println(ex.getMessage());
              }
            }
          }
        }

      } while (rootPassword != null);

    } else {
      LogManager.instance()
          .info(
              this,
              "Found YOUTRACKDB_ROOT_PASSWORD variable, using this value as root's password",
              rootPassword);
    }

    if (systemDbEnabled) {
      if (!existsRoot) {
        var password = rootPassword;
        databases.getSystemDatabase().executeWithDB(session -> {
          session.executeInTx(transaction -> {
            var metadata = session.getMetadata();
            var security = metadata.getSecurity();

            if (security.getUser(ServerConfiguration.DEFAULT_ROOT_USER) == null) {
              security.createUser(ServerConfiguration.DEFAULT_ROOT_USER, password, "root");
            }
          });
          return null;
        });
      }

      databases.getSystemDatabase().executeWithDB(session -> {
        session.executeInTx(transaction -> {
          var metadata = session.getMetadata();
          var security = metadata.getSecurity();

          if (security.getUser(ServerConfiguration.GUEST_USER) == null) {
            security.createUser(ServerConfiguration.GUEST_USER,
                ServerConfiguration.DEFAULT_GUEST_PASSWORD, "guest");
          }
        });
        return null;
      });
    }
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

  public ServerPluginManager getPluginManager() {
    return pluginManager;
  }

  protected void registerPlugins()
      throws InstantiationException, IllegalAccessException, ClassNotFoundException {
    pluginManager = new ServerPluginManager();
    pluginManager.config(this);
    pluginManager.startup();

    // PLUGINS CONFIGURED IN XML
    final var configuration = serverCfg.getConfiguration();

    if (configuration.handlers != null) {
      // ACTIVATE PLUGINS
      final List<ServerPlugin> plugins = new ArrayList<>();

      for (var h : configuration.handlers) {
        if (h.parameters != null) {
          // CHECK IF IT'S ENABLED
          var enabled = true;

          for (var p : h.parameters) {
            if (p.name.equals("enabled")) {
              enabled = false;

              var value = SystemVariableResolver.resolveSystemVariables(p.value);
              if (value != null) {
                value = value.trim();

                if ("true".equalsIgnoreCase(value)) {
                  enabled = true;
                  break;
                }
              }
            }
          }

          if (!enabled)
          // SKIP IT
          {
            continue;
          }
        }

        final var plugin = (ServerPlugin) loadClass(h.clazz).newInstance();
        pluginManager.registerPlugin(
            new ServerPluginInfo(plugin.getName(), null, null, null, plugin, null, 0, null));

        try {
          pluginManager.callListenerBeforeConfig(plugin, h.parameters);
          plugin.config(this, h.parameters);
          if (plugin instanceof DatabaseLifecycleListener databaseLifecycleListener) {
            YouTrackDBEnginesManager.instance().addDbLifecycleListener(databaseLifecycleListener);
          }
          pluginManager.callListenerAfterConfig(plugin, h.parameters);

          plugins.add(plugin);
        } catch (Exception e) {
          pluginManager.callListenerAfterConfigError(plugin, e);

          LogManager.instance()
              .error(this, "Error on plugin registration: %s", e, plugin.getName());
        }
      }

      // START ALL THE CONFIGURED PLUGINS
      for (var plugin : plugins) {
        try {
          pluginManager.callListenerBeforeStartup(plugin);
          plugin.startup();
          pluginManager.callListenerAfterStartup(plugin);
        } catch (Exception e) {
          pluginManager.callListenerAfterStartupError(plugin, e);
          var msg = "Error on plugin startup: " + e.getMessage();
          LogManager.instance().error(this, msg, e);
          throw BaseException.wrapException(new SystemException(msg), e, (String) null);
        }
      }
    }
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
    if (databases.exists(databaseName, null, null)) {
      databases.drop(databaseName, null, null);
    } else {
      throw new StorageException(databaseName,
          "Database with name '" + databaseName + "' does not exist");
    }
  }

  public boolean existsDatabase(String databaseName) {
    return databases.exists(databaseName, null, null);
  }

  public Set<String> listDatabases() {
    var dbs = databases.listDatabases(null, null);
    if (dbs.contains(SystemDatabase.SYSTEM_DB_NAME)) {
      var result = new HashSet<>(dbs);
      result.remove(SystemDatabase.SYSTEM_DB_NAME);
      return result;
    }

    return dbs;
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
      dbCreationLock.lock();
      try {
        internal.create(name, user, password, type);
        dbNamesCache.add(name);
      } finally {
        dbCreationLock.unlock();
      }

    }

    @Override
    public void create(String name, String user, String password, DatabaseType type,
        YouTrackDBConfig config) {
      dbCreationLock.lock();
      try {
        internal.create(name, user, password, type, config);
        dbNamesCache.add(name);
      } finally {
        dbCreationLock.unlock();
      }

    }


    @Override
    public boolean exists(String name, String user, String password) {
      //system database is managed inside of embedded instance autonomously
      if (SystemDatabase.SYSTEM_DB_NAME.equals(name)) {
        return internal.exists(name, user, password);
      }

      return dbNamesCache.contains(name);
    }

    @Override
    public void drop(String name, String user, String password) {
      dbCreationLock.lock();
      try {
        internal.drop(name, user, password);
        dbNamesCache.remove(name);
      } finally {
        dbCreationLock.unlock();
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
    public void restore(String name, DatabaseType type, String path,
        YouTrackDBConfig config) {
      dbCreationLock.lock();
      try {
        internal.restore(name, type, path, config);
        dbNamesCache.add(name);
      } finally {
        dbCreationLock.unlock();
      }

    }

    @Override
    public void restore(String name, InputStream in, Map<String, Object> options,
        Callable<Object> callable, CommandOutputListener iListener) {
      dbCreationLock.lock();
      try {
        internal.restore(name, in, options, callable, iListener);
        dbNamesCache.add(name);
      } finally {
        dbCreationLock.unlock();
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
      dbCreationLock.lock();
      try {
        internal.create(name, user, password, type, config, true, createOps);
        dbNamesCache.add(name);
      } finally {
        dbCreationLock.unlock();
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

    public DatabaseSessionEmbedded openNoAuthenticate(String name, String user) {
      return internal.openNoAuthenticate(name, user);
    }

    public <X> Future<X> execute(Callable<X> task) {
      return internal.execute(task);
    }

    public Future<?> execute(Runnable task) {
      return internal.execute(task);
    }

    public Collection<Storage> getStorages() {
      return internal.getStorages();
    }

    public void networkRestore(String name, InputStream in, Callable<Object> callable) {
      internal.networkRestore(name, in, callable);
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

    public ScriptManager getScriptManager() {
      return internal.getScriptManager();
    }
  }
}
