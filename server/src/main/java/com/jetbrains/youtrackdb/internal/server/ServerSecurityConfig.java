package com.jetbrains.youtrackdb.internal.server;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.parser.SystemVariableResolver;
import com.jetbrains.youtrackdb.internal.core.security.SecurityConfig;
import com.jetbrains.youtrackdb.internal.core.security.Syslog;
import com.jetbrains.youtrackdb.internal.server.config.ServerConfigurationManager;

public class ServerSecurityConfig implements SecurityConfig {

  private final YouTrackDBServer server;
  private final ServerConfigurationManager serverCfg;
  private Syslog sysLog;

  public ServerSecurityConfig(YouTrackDBServer server, ServerConfigurationManager serverCfg) {
    super();
    this.server = server;
    this.serverCfg = serverCfg;
  }

  @Override
  public Syslog getSyslog() {
    if (sysLog == null && server != null) {
      if (server.getPluginManager() != null) {
        var syslogPlugin = server.getPluginManager().getPluginByName("syslog");
        if (syslogPlugin != null) {
          sysLog = (Syslog) syslogPlugin.getInstance();
        }
      }
    }
    return sysLog;
  }

  @Override
  public String getConfigurationFile() {
    // Default
    var configFile =
        SystemVariableResolver.resolveSystemVariables("${YOUTRACKDB_HOME}/config/security.json");

    var ssf =
        server
            .getContextConfiguration()
            .getValueAsString(GlobalConfiguration.SERVER_SECURITY_FILE);
    if (ssf != null) {
      configFile = ssf;
    }
    return configFile;
  }
}
