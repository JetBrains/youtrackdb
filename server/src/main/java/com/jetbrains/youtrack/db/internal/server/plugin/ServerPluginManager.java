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
package com.jetbrains.youtrack.db.internal.server.plugin;

import com.jetbrains.youtrack.db.api.exception.ConfigurationException;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.common.parser.SystemVariableResolver;
import com.jetbrains.youtrack.db.internal.common.util.CallableFunction;
import com.jetbrains.youtrack.db.internal.common.util.Service;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.server.YouTrackDBServer;
import com.jetbrains.youtrack.db.internal.server.config.ServerEntryConfiguration;
import com.jetbrains.youtrack.db.internal.server.config.ServerParameterConfiguration;
import com.jetbrains.youtrack.db.internal.server.network.ServerNetworkListener;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.NetworkProtocolHttpAbstract;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.get.ServerCommandGetStaticContent;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.get.ServerCommandGetStaticContent.StaticContent;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages Server Extensions
 */
public class ServerPluginManager implements Service {

  private static final int CHECK_DELAY = 5000;
  private YouTrackDBServer server;
  private final ConcurrentHashMap<String, ServerPluginInfo> activePlugins =
      new ConcurrentHashMap<String, ServerPluginInfo>();
  private final ConcurrentHashMap<String, String> loadedPlugins =
      new ConcurrentHashMap<String, String>();
  private volatile TimerTask autoReloadTimerTask;
  private String directory;

  protected List<OPluginLifecycleListener> pluginListeners =
      new ArrayList<OPluginLifecycleListener>();

  public void config(YouTrackDBServer iServer) {
    server = iServer;
  }

  public void startup() {
    boolean hotReload = false;
    boolean dynamic = true;
    boolean loadAtStartup = true;
    directory =
        SystemVariableResolver.resolveSystemVariables("${YOUTRACKDB_HOME}", ".") + "/plugins/";

    if (server.getConfiguration() != null && server.getConfiguration().properties != null) {
      for (ServerEntryConfiguration p : server.getConfiguration().properties) {
        if (p.name.equals("plugin.hotReload")) {
          hotReload = Boolean.parseBoolean(p.value);
        } else if (p.name.equals("plugin.dynamic")) {
          dynamic = Boolean.parseBoolean(p.value);
        } else if (p.name.equals("plugin.loadAtStartup")) {
          loadAtStartup = Boolean.parseBoolean(p.value);
        } else if (p.name.equals("plugin.directory")) {
          directory = p.value;
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
    return activePlugins.keySet().toArray(new String[activePlugins.size()]);
  }

  public void registerPlugin(final ServerPluginInfo iPlugin) {
    final String pluginName = iPlugin.getName();

    if (activePlugins.containsKey(pluginName)) {
      throw new IllegalStateException("Plugin '" + pluginName + "' already registered");
    }
    activePlugins.putIfAbsent(pluginName, iPlugin);
  }

  public Collection<ServerPluginInfo> getPlugins() {
    return activePlugins.values();
  }

  public void uninstallPluginByFile(final String iFileName) {
    final String pluginName = loadedPlugins.remove(iFileName);
    if (pluginName != null) {
      LogManager.instance().info(this, "Uninstalling dynamic plugin '%s'...", iFileName);

      final ServerPluginInfo removedPlugin = activePlugins.remove(pluginName);
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
    for (Entry<String, ServerPluginInfo> pluginInfoEntry : activePlugins.entrySet()) {
      LogManager.instance().info(this, "- %s", pluginInfoEntry.getKey());
      final ServerPluginInfo plugin = pluginInfoEntry.getValue();
      try {
        callListenerBeforeShutdown(plugin.getInstance());
        plugin.shutdown(false);
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

  protected String updatePlugin(final File pluginFile) {
    final String pluginFileName = pluginFile.getName();

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

    ServerPluginInfo currentPluginData = getPluginByFile(pluginFileName);

    final long fileLastModified = pluginFile.lastModified();
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
            .debug(this, "Error on shutdowning plugin '%s'...", e, pluginFileName);
      }
    }

    installDynamicPlugin(pluginFile);

    return pluginFileName;
  }

  protected void registerStaticDirectory(final ServerPluginInfo iPluginData) {
    Object pluginWWW = iPluginData.getParameter("www");
    if (pluginWWW == null) {
      pluginWWW = iPluginData.getName();
    }

    final ServerNetworkListener httpListener =
        server.getListenerByProtocol(NetworkProtocolHttpAbstract.class);

    if (httpListener == null) {
      throw new ConfigurationException(
          "HTTP listener not registered while installing Static Content command");
    }

    final ServerCommandGetStaticContent command =
        (ServerCommandGetStaticContent)
            httpListener.getCommand(ServerCommandGetStaticContent.class);

    if (command != null) {
      final URL wwwURL = iPluginData.getClassLoader().findResource("www/");

      final CallableFunction<Object, String> callback;
      if (wwwURL != null) {
        callback = createStaticLinkCallback(iPluginData, wwwURL);
      } else
      // LET TO THE COMMAND TO CONTROL IT
      {
        callback =
            new CallableFunction<Object, String>() {
              @Override
              public Object call(final String iArgument) {
                return iPluginData.getInstance().getContent(iArgument);
              }
            };
      }

      command.registerVirtualFolder(pluginWWW.toString(), callback);
    }
  }

  protected CallableFunction<Object, String> createStaticLinkCallback(
      final ServerPluginInfo iPluginData, final URL wwwURL) {
    return new CallableFunction<Object, String>() {
      @Override
      public Object call(final String iArgument) {
        String fileName = "www/" + iArgument;
        final URL url = iPluginData.getClassLoader().findResource(fileName);

        if (url != null) {
          final StaticContent content = new StaticContent();
          content.is =
              new BufferedInputStream(iPluginData.getClassLoader().getResourceAsStream(fileName));
          content.contentSize = -1;
          content.type = ServerCommandGetStaticContent.getContentType(url.getFile());
          return content;
        }
        return null;
      }
    };
  }

  @SuppressWarnings("unchecked")
  protected ServerPlugin startPluginClass(
      final String iClassName,
      URLClassLoader pluginClassLoader,
      final ServerParameterConfiguration[] params)
      throws Exception {

    final Class<? extends ServerPlugin> classToLoad =
        (Class<? extends ServerPlugin>) pluginClassLoader.loadClass(iClassName);
    final ServerPlugin instance = classToLoad.newInstance();

    // CONFIG()
    final Method configMethod =
        classToLoad.getDeclaredMethod(
            "config", YouTrackDBServer.class, ServerParameterConfiguration[].class);

    callListenerBeforeConfig(instance, params);

    configMethod.invoke(instance, server, params);

    callListenerAfterConfig(instance, params);

    // STARTUP()
    final Method startupMethod = classToLoad.getDeclaredMethod("startup");

    callListenerBeforeStartup(instance);

    startupMethod.invoke(instance);

    callListenerAfterStartup(instance);

    return instance;
  }

  private void updatePlugins() {
    // load plugins.directory from server configuration or default to $YOUTRACKDB_HOME/plugins
    final File pluginsDirectory = new File(directory);
    if (!pluginsDirectory.exists()) {
      pluginsDirectory.mkdirs();
    }

    final File[] plugins = pluginsDirectory.listFiles();

    final Set<String> currentDynamicPlugins = new HashSet<String>();
    for (Entry<String, String> entry : loadedPlugins.entrySet()) {
      currentDynamicPlugins.add(entry.getKey());
    }

    if (plugins != null) {
      for (File plugin : plugins) {
        final String pluginName = updatePlugin(plugin);
        if (pluginName != null) {
          currentDynamicPlugins.remove(pluginName);
        }
      }
    }

    // REMOVE MISSING PLUGIN
    for (String pluginName : currentDynamicPlugins) {
      uninstallPluginByFile(pluginName);
    }
  }

  private void installDynamicPlugin(final File pluginFile) {
    String pluginName = pluginFile.getName();

    final ServerPluginInfo currentPluginData;
    LogManager.instance().info(this, "Installing dynamic plugin '%s'...", pluginName);

    URLClassLoader pluginClassLoader = null;
    try {
      final URL url = pluginFile.toURI().toURL();

      pluginClassLoader = new URLClassLoader(new URL[]{url}, getClass().getClassLoader());

      // LOAD PLUGIN.JSON FILE
      final URL r = pluginClassLoader.findResource("plugin.json");
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

      final InputStream pluginConfigFile = r.openStream();

      try {
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

        final EntityImpl properties = new EntityImpl().fromJSON(pluginConfigFile);

        if (properties.containsField("name"))
        // OVERWRITE PLUGIN NAME
        {
          pluginName = properties.field("name");
        }

        final String pluginClass = properties.field("javaClass");

        final ServerPlugin pluginInstance;
        final Map<String, Object> parameters;

        if (pluginClass != null) {
          // CREATE PARAMETERS
          parameters = properties.field("parameters");
          final List<ServerParameterConfiguration> params =
              new ArrayList<ServerParameterConfiguration>();
          for (String paramName : parameters.keySet()) {
            params.add(
                new ServerParameterConfiguration(paramName, (String) parameters.get(paramName)));
          }
          final ServerParameterConfiguration[] pluginParams =
              params.toArray(new ServerParameterConfiguration[params.size()]);

          pluginInstance = startPluginClass(pluginClass, pluginClassLoader, pluginParams);
        } else {
          pluginInstance = null;
          parameters = null;
        }

        // REGISTER THE PLUGIN
        currentPluginData =
            new ServerPluginInfo(
                pluginName,
                properties.field("version"),
                properties.field("description"),
                properties.field("web"),
                pluginInstance,
                parameters,
                pluginFile.lastModified(),
                pluginClassLoader);

        registerPlugin(currentPluginData);
        loadedPlugins.put(pluginFile.getName(), pluginName);

        registerStaticDirectory(currentPluginData);
      } finally {
        pluginConfigFile.close();
      }

    } catch (Exception e) {
      LogManager.instance().error(this, "Error on installing dynamic plugin '%s'", e, pluginName);
    }
  }

  public ServerPluginManager registerLifecycleListener(final OPluginLifecycleListener iListener) {
    pluginListeners.add(iListener);
    return this;
  }

  public ServerPluginManager unregisterLifecycleListener(
      final OPluginLifecycleListener iListener) {
    pluginListeners.remove(iListener);
    return this;
  }

  public void callListenerBeforeConfig(
      final ServerPlugin plugin, final ServerParameterConfiguration[] cfg) {
    for (OPluginLifecycleListener l : pluginListeners) {
      try {
        l.onBeforeConfig(plugin, cfg);
      } catch (Exception ex) {
        LogManager.instance().error(this, "callListenerBeforeConfig() ", ex);
      }
    }
  }

  public void callListenerAfterConfig(
      final ServerPlugin plugin, final ServerParameterConfiguration[] cfg) {
    for (OPluginLifecycleListener l : pluginListeners) {
      try {
        l.onAfterConfig(plugin, cfg);
      } catch (Exception ex) {
        LogManager.instance().error(this, "callListenerAfterConfig() ", ex);
      }
    }
  }

  public void callListenerBeforeStartup(final ServerPlugin plugin) {
    for (OPluginLifecycleListener l : pluginListeners) {
      try {
        l.onBeforeStartup(plugin);
      } catch (Exception ex) {
        LogManager.instance().error(this, "callListenerBeforeStartup() ", ex);
      }
    }
  }

  public void callListenerAfterStartup(final ServerPlugin plugin) {
    for (OPluginLifecycleListener l : pluginListeners) {
      try {
        l.onAfterStartup(plugin);
      } catch (Exception ex) {
        LogManager.instance().error(this, "callListenerAfterStartup()", ex);
      }
    }
  }

  public void callListenerBeforeShutdown(final ServerPlugin plugin) {
    for (OPluginLifecycleListener l : pluginListeners) {
      try {
        l.onBeforeShutdown(plugin);
      } catch (Exception ex) {
        LogManager.instance().error(this, "callListenerBeforeShutdown()", ex);
      }
    }
  }

  public void callListenerAfterShutdown(final ServerPlugin plugin) {
    for (OPluginLifecycleListener l : pluginListeners) {
      try {
        l.onAfterShutdown(plugin);
      } catch (Exception ex) {
        LogManager.instance().error(this, "callListenerAfterShutdown()", ex);
      }
    }
  }
}
