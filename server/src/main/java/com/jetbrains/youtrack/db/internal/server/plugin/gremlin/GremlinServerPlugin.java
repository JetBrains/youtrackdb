package com.jetbrains.youtrack.db.internal.server.plugin.gremlin;

import com.jetbrains.youtrack.db.internal.common.parser.SystemVariableResolver;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseLifecycleListener;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.server.YouTrackDBServer;
import com.jetbrains.youtrack.db.internal.server.plugin.ServerPluginAbstract;
import com.jetbrains.youtrack.db.internal.tools.config.ServerParameterConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.annotation.Nonnull;
import org.apache.tinkerpop.gremlin.server.GremlinServer;

public class GremlinServerPlugin extends ServerPluginAbstract implements DatabaseLifecycleListener {

  private GremlinServer gremlinServer;
  private YTDBGraphManager graphManager;

  @Override
  public String getName() {
    return "gremlinserver";
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
    if (configFile == null) {
      configFile = SystemVariableResolver.resolveSystemVariables(
          "${YOUTRACKDB_HOME}/config/gremlin-server.yaml");
    }

    var configFilePath = Path.of(configFile);

    YTDBSettings ytdbSettings;
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
    augmentServerSettings(youTrackDBServer, ytdbSettings);

    gremlinServer = new GremlinServer(ytdbSettings);
  }

  private static void augmentServerSettings(YouTrackDBServer youTrackDBServer,
      YTDBSettings ytdbSettings) {
    ytdbSettings.server = youTrackDBServer;
    ytdbSettings.authentication.config.put(YTDBSimpleAuthenticator.YTDB_SERVER_PARAM,
        youTrackDBServer);
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
      gremlinServer.stop();
    }
  }

  @Override
  public void onCreate(@Nonnull DatabaseSessionInternal session) {
    var databaseName = session.getDatabaseName();
    graphManager.openGraph(databaseName, name -> graphManager.newGraph(name));
  }

  @Override
  public void onDrop(@Nonnull DatabaseSessionInternal session) {
    var databaseName = session.getDatabaseName();
    graphManager.removeGraph(databaseName);
  }
}
