package org.apache.tinkerpop.gremlin.server;

import java.net.URI;
import org.apache.tinkerpop.gremlin.driver.Cluster;
import org.apache.tinkerpop.gremlin.driver.simple.WebSocketClient;

public final class TestClientFactory {
  public static final int PORT = 45940;
  public static final URI WEBSOCKET_URI = URI.create("ws://localhost:" + PORT + "/gremlin");
  public static final URI SSL_WEBSOCKET_URI = URI.create("wss://localhost:" + PORT + "/gremlin");
  public static final String HTTP = "http://localhost:" + PORT;
  public static final String RESOURCE_PATH = "conf/remote-objects.yaml";

  public static Cluster.Builder build() {
    return build("localhost");
  }

  public static Cluster.Builder build(final String address) {
    return Cluster.build(address).port(PORT);
  }

  public static Cluster open() {
    return build().create();
  }

  public static WebSocketClient createWebSocketClient() {
    return new WebSocketClient(WEBSOCKET_URI);
  }

  public static WebSocketClient createSSLWebSocketClient() {
    return new WebSocketClient(SSL_WEBSOCKET_URI);
  }

  public static String createURLString() {
    return createURLString("");
  }

  public static String createURLString(final String suffix) {
    return HTTP + suffix;
  }
}

