package com.jetbrains.youtrackdb.internal.server.monitoring;

import jdk.jfr.Category;
import jdk.jfr.Enabled;
import jdk.jfr.Label;
import jdk.jfr.Name;

@Name("com.jetbrains.youtrackdb.server.NetworkConnectionsStats")
@Category({"YouTrackDB", "Server"})
@Label("Network connections statistics")
@Enabled(false)
public class NetworkConnectionsStatsEvent extends jdk.jfr.Event {

  private final int numberOfConnections;

  public NetworkConnectionsStatsEvent(int numberOfConnections) {
    this.numberOfConnections = numberOfConnections;
  }
}
