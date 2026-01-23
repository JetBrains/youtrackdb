package org.apache.tinkerpop.gremlin.server;

import java.net.URI;
import org.apache.tinkerpop.gremlin.driver.Cluster;
import org.apache.tinkerpop.gremlin.driver.simple.WebSocketClient;

public final class TestClientFactory {
  public static Cluster.Builder build(int port) {
    return build("localhost", port);
  }

  public static Cluster.Builder build(final String address, int port) {
    return Cluster.build(address).port(port);
  }
}

