package com.jetbrains.youtrackdb.internal.server;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class ClientSessions {

  private final List<ClientConnection> connections =
      Collections.synchronizedList(new ArrayList<ClientConnection>());
  private final byte[] binaryToken;

  public ClientSessions(byte[] binaryToken) {
    this.binaryToken = binaryToken;
  }

  public void addConnection(ClientConnection conn) {
    this.connections.add(conn);
  }

  public void removeConnection(ClientConnection conn) {
    this.connections.remove(conn);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(binaryToken);
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof ClientSessions)) {
      return false;
    }
    return Arrays.equals(this.binaryToken, ((ClientSessions) obj).binaryToken);
  }

  public boolean isActive() {
    return !connections.isEmpty();
  }

  public List<ClientConnection> getConnections() {
    return connections;
  }
}
