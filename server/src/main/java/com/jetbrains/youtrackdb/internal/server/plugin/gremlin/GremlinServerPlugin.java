package com.jetbrains.youtrackdb.internal.server.plugin.gremlin;

import com.jetbrains.youtrackdb.internal.common.parser.SystemVariableResolver;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseLifecycleListener;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.SystemDatabase;
import com.jetbrains.youtrackdb.internal.core.gremlin.GremlinUtils;
import com.jetbrains.youtrackdb.internal.server.YouTrackDBServer;
import com.jetbrains.youtrackdb.internal.server.plugin.ServerPluginAbstract;
import com.jetbrains.youtrackdb.internal.tools.config.ServerParameterConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import javax.annotation.Nonnull;
import org.apache.tinkerpop.gremlin.server.GremlinServer;

public class GremlinServerPlugin extends ServerPluginAbstract implements DatabaseLifecycleListener {
  public static final String DEFAULT_GREMLIN_SERVER_CONFIG_NAME = "gremlin-server.yaml";
  public static final String NAME = "gremlinserver";

  private GremlinServer gremlinServer;
  private YTDBGraphManager graphManager;

  @Override
  public String getName() {
    return GremlinServerPlugin.NAME;
  }

  @Override
  public void config(YouTrackDBServer youTrackDBServer, ServerParameterConfiguration[] params)
      throws Exception {
    String configFile = null;
    for (var param : params) {
      if (param.name.equalsIgnoreCase("config")) {
        configFile = SystemVariableResolver.resolveSystemVariables(param.value);
      }
    }

    YTDBSettings ytdbSettings;
    if (configFile == null) {
      configFile = Path.of(youTrackDBServer.getServerRootDirectory()).resolve("confing")
          .resolve(DEFAULT_GREMLIN_SERVER_CONFIG_NAME).toAbsolutePath().toString();
    }

    if (configFile.startsWith("classpath:")) {
      configFile = configFile.substring("classpath:".length());
      var configStream = GremlinServerPlugin.class.getClassLoader().getResourceAsStream(configFile);
      if (configStream != null) {
        ytdbSettings = YTDBSettings.read(configStream);
      } else {
        throw new IllegalStateException("Gremlin server configuration file not found");
      }
    } else {
      var configFilePath = Path.of(configFile);
      if (Files.exists(configFilePath)) {
        ytdbSettings = YTDBSettings.read(Files.newInputStream(configFilePath));
      } else {
        var configStream = GremlinServerPlugin.class.getClassLoader()
            .getResourceAsStream("gremlin-server.yaml");
        if (configStream != null) {
          ytdbSettings = YTDBSettings.read(configStream);
        } else {
          throw new IllegalStateException("Gremlin server configuration file not found");
        }
      }
    }

    augmentServerSettings(youTrackDBServer, ytdbSettings);

    gremlinServer = new GremlinServer(ytdbSettings);
  }

  private static void augmentServerSettings(YouTrackDBServer youTrackDBServer,
      YTDBSettings ytdbSettings) {
    ytdbSettings.server = youTrackDBServer;
    var config = ytdbSettings.authentication.config;
    if (config == null) {
      config = new HashMap<>();
      ytdbSettings.authentication.config = config;
    }
    config.put(YTDBSimpleAuthenticator.YTDB_SERVER_PARAM, youTrackDBServer);
  }

  @Override
  public void startup() throws Exception {
    if (gremlinServer != null) {
      gremlinServer.start();
      graphManager = (YTDBGraphManager) gremlinServer.getServerGremlinExecutor().getGraphManager();
    }
  }

  @Override
  public void shutdown() {
    if (gremlinServer != null) {
      gremlinServer.stop().join();
    }
  }

  @Override
  public void onCreate(@Nonnull DatabaseSessionInternal session) {
    if (session.getDatabaseName().equals(SystemDatabase.SYSTEM_DB_NAME)) {
      return;
    }

    var databaseName = session.getDatabaseName();
    var config = GremlinUtils.createBaseConfiguration(session);
    graphManager.openGraph(databaseName,
        name -> graphManager.newGraphProxyInstance(databaseName, config));
  }

  @Override
  public void onDrop(@Nonnull DatabaseSessionInternal session) {
    if (session.getDatabaseName().equals(SystemDatabase.SYSTEM_DB_NAME)) {
      return;
    }

    var databaseName = session.getDatabaseName();
    graphManager.removeGraph(databaseName);
  }

  public GremlinServer getGremlinServer() {
    return gremlinServer;
  }
}
