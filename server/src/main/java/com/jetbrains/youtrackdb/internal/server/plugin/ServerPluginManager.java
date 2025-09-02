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
package com.jetbrains.youtrackdb.internal.server.plugin;

import com.jetbrains.youtrackdb.api.exception.ConfigurationException;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.common.parser.SystemVariableResolver;
import com.jetbrains.youtrackdb.internal.common.util.CallableFunction;
import com.jetbrains.youtrackdb.internal.common.util.Service;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseLifecycleListener;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.string.JSONSerializerJackson;
import com.jetbrains.youtrackdb.internal.server.YouTrackDBServer;
import com.jetbrains.youtrackdb.internal.server.network.protocol.http.NetworkProtocolHttpAbstract;
import com.jetbrains.youtrackdb.internal.server.network.protocol.http.command.get.ServerCommandGetStaticContent;
import com.jetbrains.youtrackdb.internal.server.network.protocol.http.command.get.ServerCommandGetStaticContent.StaticContent;
import com.jetbrains.youtrackdb.internal.tools.config.ServerParameterConfiguration;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages Server Extensions
 */
public class ServerPluginManager implements Service {

  private static final Logger logger = LoggerFactory.getLogger(ServerPluginManager.class);

  private static final int CHECK_DELAY = 5000;
  private YouTrackDBServer server;
  private final ConcurrentHashMap<String, ServerPluginInfo> activePlugins =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, String> loadedPlugins =
      new ConcurrentHashMap<>();
  private volatile TimerTask autoReloadTimerTask;
  private String directory;

  protected List<PluginLifecycleListener> pluginListeners = new ArrayList<>();

  public void config(YouTrackDBServer iServer) {
    server = iServer;
  }

  @Override
  public void startup() {
    var hotReload = false;
    var dynamic = true;
    var loadAtStartup = true;
    directory =
        SystemVariableResolver.resolveSystemVariables("${YOUTRACKDB_HOME}", ".") + "/plugins/";

    if (server.getConfiguration() != null && server.getConfiguration().properties != null) {
      for (var p : server.getConfiguration().properties) {
        switch (p.name) {
          case "plugin.hotReload" -> hotReload = Boolean.parseBoolean(p.value);
          case "plugin.dynamic" -> dynamic = Boolean.parseBoolean(p.value);
          case "plugin.loadAtStartup" -> loadAtStartup = Boolean.parseBoolean(p.value);
          case "plugin.directory" -> directory = p.value;
        }
      }
    }

    if (!dynamic) {
      return;
    }

    if (loadAtStartup) {
      updatePlugins();
    }

    if (hotReload) {
      autoReloadTimerTask =
          YouTrackDBEnginesManager.instance()
              .getScheduler()
              .scheduleTask(this::updatePlugins, CHECK_DELAY, CHECK_DELAY);
    }
  }

  @Nullable
  public ServerPluginInfo getPluginByName(final String iName) {
    if (iName == null) {
      return null;
    }
    return activePlugins.get(iName);
  }

  public String getPluginNameByFile(final String iFileName) {
    return loadedPlugins.get(iFileName);
  }

  public ServerPluginInfo getPluginByFile(final String iFileName) {
    return getPluginByName(getPluginNameByFile(iFileName));
  }

  public String[] getPluginNames() {
    return activePlugins.keySet().toArray(new String[0]);
  }

  public void registerPlugin(final ServerPluginInfo iPlugin) {
    final var pluginName = iPlugin.getName();

    if (activePlugins.containsKey(pluginName)) {
      throw new IllegalStateException("Plugin '" + pluginName + "' already registered");
    }
    activePlugins.putIfAbsent(pluginName, iPlugin);
  }

  public Collection<ServerPluginInfo> getPlugins() {
    return activePlugins.values();
  }

  public void uninstallPluginByFile(final String iFileName) {
    final var pluginName = loadedPlugins.remove(iFileName);
    if (pluginName != null) {
      LogManager.instance().info(this, "Uninstalling dynamic plugin '%s'...", iFileName);

      final var removedPlugin = activePlugins.remove(pluginName);
      if (removedPlugin != null) {
        callListenerBeforeShutdown(removedPlugin.getInstance());
        removedPlugin.shutdown();
        callListenerAfterShutdown(removedPlugin.getInstance());
      }
    }
  }

  @Override
  public void shutdown() {
    LogManager.instance().info(this, "Shutting down plugins:");
    for (var pluginInfoEntry : activePlugins.entrySet()) {
      LogManager.instance().info(this, "- %s", pluginInfoEntry.getKey());
      final var plugin = pluginInfoEntry.getValue();
      try {
        callListenerBeforeShutdown(plugin.getInstance());
        plugin.shutdown(false);
        if (plugin instanceof DatabaseLifecycleListener databaseLifecycleListener) {
          YouTrackDBEnginesManager.instance().removeDbLifecycleListener(databaseLifecycleListener);
        }
        callListenerAfterShutdown(plugin.getInstance());
      } catch (Exception t) {
        LogManager.instance().error(this, "Error during server plugin %s shutdown", t, plugin);
      }
    }

    if (autoReloadTimerTask != null) {
      autoReloadTimerTask.cancel();
    }
  }

  @Override
  public String getName() {
    return "plugin-manager";
  }

  @Nullable
  protected String updatePlugin(final File pluginFile) {
    final var pluginFileName = pluginFile.getName();

    if (!pluginFile.isDirectory()
        && !pluginFileName.endsWith(".jar")
        && !pluginFileName.endsWith(".zip"))
    // SKIP IT
    {
      return null;
    }

    if (pluginFile.isHidden())
    // HIDDEN FILE, SKIP IT
    {
      return null;
    }

    var currentPluginData = getPluginByFile(pluginFileName);

    final var fileLastModified = pluginFile.lastModified();
    if (currentPluginData != null) {
      if (fileLastModified <= currentPluginData.getLoadedOn())
      // ALREADY LOADED, SKIPT IT
      {
        return pluginFileName;
      }

      // SHUTDOWN PREVIOUS INSTANCE
      try {
        callListenerBeforeShutdown(currentPluginData.getInstance());
        currentPluginData.shutdown();
        callListenerAfterShutdown(currentPluginData.getInstance());
        activePlugins.remove(loadedPlugins.remove(pluginFileName));

      } catch (Exception e) {
        // IGNORE EXCEPTIONS
        LogManager.instance()
            .debug(this, "Error on shutdowning plugin '%s'...", logger, e, pluginFileName);
      }
    }

    installDynamicPlugin(pluginFile);

    return pluginFileName;
  }

  protected void registerStaticDirectory(final ServerPluginInfo pluginData) {
    var pluginWWW = pluginData.getParameter("www");
    if (pluginWWW == null) {
      pluginWWW = pluginData.getName();
    }

    final var httpListener =
        server.getListenerByProtocol(NetworkProtocolHttpAbstract.class);

    if (httpListener == null) {
      throw new ConfigurationException(
          "HTTP listener not registered while installing Static Content command");
    }

    final var command =
        (ServerCommandGetStaticContent)
            httpListener.getCommand(ServerCommandGetStaticContent.class);

    if (command != null) {
      final var wwwURL = pluginData.getClassLoader().findResource("www/");

      final CallableFunction<Object, String> callback;
      if (wwwURL != null) {
        callback = createStaticLinkCallback(pluginData);
      } else
      // LET TO THE COMMAND TO CONTROL IT
      {
        callback =
            argument -> pluginData.getInstance().getContent(argument);
      }

      command.registerVirtualFolder(pluginWWW.toString(), callback);
    }
  }

  protected static CallableFunction<Object, String> createStaticLinkCallback(
      final ServerPluginInfo iPluginData) {
    return iArgument -> {
      var fileName = "www/" + iArgument;
      final var url = iPluginData.getClassLoader().findResource(fileName);

      if (url != null) {
        final var content = new StaticContent();
        content.is =
            new BufferedInputStream(iPluginData.getClassLoader().getResourceAsStream(fileName));
        content.contentSize = -1;
        content.type = ServerCommandGetStaticContent.getContentType(url.getFile());
        return content;
      }
      return null;
    };
  }

  @SuppressWarnings("unchecked")
  protected ServerPlugin startPluginClass(
      final String iClassName,
      URLClassLoader pluginClassLoader,
      final ServerParameterConfiguration[] params)
      throws Exception {

    final var classToLoad =
        (Class<? extends ServerPlugin>) pluginClassLoader.loadClass(iClassName);
    final var instance = classToLoad.newInstance();

    // CONFIG()
    final var configMethod =
        classToLoad.getDeclaredMethod(
            "config", YouTrackDBServer.class, ServerParameterConfiguration[].class);

    callListenerBeforeConfig(instance, params);

    configMethod.invoke(instance, server, params);

    callListenerAfterConfig(instance, params);

    // STARTUP()
    final var startupMethod = classToLoad.getDeclaredMethod("startup");

    callListenerBeforeStartup(instance);

    startupMethod.invoke(instance);

    callListenerAfterStartup(instance);

    return instance;
  }

  private void updatePlugins() {
    // load plugins.directory from server configuration or default to $YOUTRACKDB_HOME/plugins
    final var pluginsDirectory = new File(directory);
    if (!pluginsDirectory.exists()) {
      try {
        Files.createDirectories(pluginsDirectory.toPath());
      } catch (IOException e) {
        throw new IllegalStateException("Error during  creation of directory for plugins", e);
      }
    }

    final var plugins = pluginsDirectory.listFiles();

    final Set<String> currentDynamicPlugins = new HashSet<>();
    for (var entry : loadedPlugins.entrySet()) {
      currentDynamicPlugins.add(entry.getKey());
    }

    if (plugins != null) {
      for (var plugin : plugins) {
        final var pluginName = updatePlugin(plugin);
        if (pluginName != null) {
          currentDynamicPlugins.remove(pluginName);
        }
      }
    }

    // REMOVE MISSING PLUGIN
    for (var pluginName : currentDynamicPlugins) {
      uninstallPluginByFile(pluginName);
    }
  }

  @SuppressWarnings("unchecked")
  private void installDynamicPlugin(final File pluginFile) {
    var pluginName = pluginFile.getName();

    final ServerPluginInfo currentPluginData;
    LogManager.instance().info(this, "Installing dynamic plugin '%s'...", pluginName);

    URLClassLoader pluginClassLoader = null;
    try {
      final var url = pluginFile.toURI().toURL();

      pluginClassLoader = new URLClassLoader(new URL[]{url}, getClass().getClassLoader());

      // LOAD PLUGIN.JSON FILE
      final var r = pluginClassLoader.findResource("plugin.json");
      if (r == null) {
        LogManager.instance()
            .error(
                this,
                "Plugin definition file ('plugin.json') is not found for dynamic plugin '%s'",
                null,
                pluginName);
        throw new IllegalArgumentException(
            String.format(
                "Plugin definition file ('plugin.json') is not found for dynamic plugin '%s'",
                pluginName));
      }

      try (var pluginConfigFile = r.openStream()) {
        if (pluginConfigFile == null || pluginConfigFile.available() == 0) {
          LogManager.instance()
              .error(
                  this,
                  "Error on loading 'plugin.json' file for dynamic plugin '%s'",
                  null,
                  pluginName);
          throw new IllegalArgumentException(
              String.format(
                  "Error on loading 'plugin.json' file for dynamic plugin '%s'", pluginName));
        }

        final var properties = JSONSerializerJackson.INSTANCE.mapFromJson(pluginConfigFile);

        if (properties.containsKey("name"))
        // OVERWRITE PLUGIN NAME
        {
          pluginName = (String) properties.get("name");
        }

        final var pluginClass = (String) properties.get("javaClass");

        final ServerPlugin pluginInstance;
        final Map<String, Object> parameters;

        if (pluginClass != null) {
          // CREATE PARAMETERS
          parameters = (Map<String, Object>) properties.get("parameters");
          final List<ServerParameterConfiguration> params =
              new ArrayList<>();
          for (var entry : parameters.entrySet()) {
            params.add(
                new ServerParameterConfiguration(entry.getKey(), (String) entry.getValue()));
          }
          final var pluginParams =
              params.toArray(new ServerParameterConfiguration[0]);

          pluginInstance = startPluginClass(pluginClass, pluginClassLoader, pluginParams);
        } else {
          pluginInstance = null;
          parameters = null;
        }

        // REGISTER THE PLUGIN
        currentPluginData =
            new ServerPluginInfo(
                pluginName,
                (String) properties.get("version"),
                (String) properties.get("description"),
                (String) properties.get("web"),
                pluginInstance,
                parameters,
                pluginFile.lastModified(),
                pluginClassLoader);

        registerPlugin(currentPluginData);
        loadedPlugins.put(pluginFile.getName(), pluginName);

        registerStaticDirectory(currentPluginData);
      }

    } catch (Exception e) {
      LogManager.instance().error(this, "Error on installing dynamic plugin '%s'", e, pluginName);
    }
  }

  public ServerPluginManager registerLifecycleListener(final PluginLifecycleListener iListener) {
    pluginListeners.add(iListener);
    return this;
  }

  public ServerPluginManager unregisterLifecycleListener(
      final PluginLifecycleListener iListener) {
    pluginListeners.remove(iListener);
    return this;
  }

  public void callListenerBeforeConfig(
      final ServerPlugin plugin, final ServerParameterConfiguration[] cfg) {
    for (var l : pluginListeners) {
      try {
        l.onBeforeConfig(plugin, cfg);
      } catch (Exception ex) {
        LogManager.instance().error(this, "callListenerBeforeConfig() ", ex);
      }
    }
  }

  public void callListenerAfterConfig(
      final ServerPlugin plugin, final ServerParameterConfiguration[] cfg) {
    for (var l : pluginListeners) {
      try {
        l.onAfterConfig(plugin, cfg);
      } catch (Exception ex) {
        LogManager.instance().error(this, "callListenerAfterConfig() ", ex);
      }
    }
  }

  public void callListenerAfterConfigError(ServerPlugin plugin, Throwable e) {
    for (var l : pluginListeners) {
      try {
        l.onAfterConfigError(plugin, e);
      } catch (Exception ex) {
        LogManager.instance().error(this, "callListenerAfterShutdown()", ex);
      }
    }
  }

  public void callListenerBeforeStartup(final ServerPlugin plugin) {
    for (var l : pluginListeners) {
      try {
        l.onBeforeStartup(plugin);
      } catch (Exception ex) {
        LogManager.instance().error(this, "callListenerBeforeStartup() ", ex);
      }
    }
  }

  public void callListenerAfterStartup(final ServerPlugin plugin) {
    for (var l : pluginListeners) {
      try {
        l.onAfterStartup(plugin);
      } catch (Exception ex) {
        LogManager.instance().error(this, "callListenerAfterStartup()", ex);
      }
    }
  }

  public void callListenerAfterStartupError(final ServerPlugin plugin, Throwable error) {
    for (var l : pluginListeners) {
      try {
        l.onAfterStartup(plugin);
      } catch (Exception ex) {
        LogManager.instance().error(this, "callListenerAfterStartup()", ex);
      }
    }
  }

  public void callListenerBeforeShutdown(final ServerPlugin plugin) {
    for (var l : pluginListeners) {
      try {
        l.onBeforeShutdown(plugin);
      } catch (Exception ex) {
        LogManager.instance().error(this, "callListenerBeforeShutdown()", ex);
      }
    }
  }


  public void callListenerAfterShutdown(final ServerPlugin plugin) {
    for (var l : pluginListeners) {
      try {
        l.onAfterShutdown(plugin);
      } catch (Exception ex) {
        LogManager.instance().error(this, "callListenerAfterShutdown()", ex);
      }
    }
  }


}
