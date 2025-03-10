package com.jetbrains.youtrack.db.internal.server.monitoring;

import jdk.jfr.Category;
import jdk.jfr.Enabled;
import jdk.jfr.Label;
import jdk.jfr.Name;

@Name("com.jetbrains.youtrack.db.server.NetworkConnectionsStats")
@Category({"YouTrackDB", "Server"})
@Label("Network connections statistics")
@Enabled(false)
public class NetworkConnectionsStatsEvent extends jdk.jfr.Event {

  private final int numberOfConnections;

  public NetworkConnectionsStatsEvent(int numberOfConnections) {
    this.numberOfConnections = numberOfConnections;
  }
}
