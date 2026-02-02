package org.apache.tinkerpop.gremlin.server;

import org.apache.tinkerpop.gremlin.driver.Cluster;

public final class TestClientFactory {
  public static Cluster.Builder build(int port) {
    return build("localhost", port);
  }

  public static Cluster.Builder build(final String address, int port) {
    return Cluster.build(address).port(port);
  }
}

