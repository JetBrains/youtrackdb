package com.jetbrains.youtrackdb.internal.driver;

import io.netty.channel.socket.SocketChannel;
import org.apache.tinkerpop.gremlin.driver.Channelizer;
import org.apache.tinkerpop.gremlin.driver.Connection;

public final class YTDBDriverWebSocketChannelizer extends Channelizer.WebSocketChannelizer {

  @Override
  public void init(Connection connection) {
    super.init(connection);

    this.gremlinRequestEncoder = new YTDBWebSocketGremlinRequestEncoder(true,
        cluster.getSerializer());
  }

  @Override
  protected void initChannel(SocketChannel socketChannel) throws Exception {
    super.initChannel(socketChannel);

    var pipeline = socketChannel.pipeline();
    var oldGremlinHandler = pipeline.get("gremlin-handler");

    if (!(oldGremlinHandler instanceof YTDBGremlinResponseHandler)) {
      var newGremlinHandler = new YTDBGremlinResponseHandler(pending);
      pipeline.replace(oldGremlinHandler, "gremlin-handler", newGremlinHandler);
    }

    var oldSaslGremlinHandler = pipeline.get("gremlin-sasl-handler");
    if (!(oldSaslGremlinHandler instanceof YTDBGremlinSaslAuthenticationHandler)) {
      var newSaslGremlinHandler = new YTDBGremlinSaslAuthenticationHandler(
          this.cluster.authProperties());
      pipeline.replace(oldSaslGremlinHandler, "gremlin-sasl-handler", newSaslGremlinHandler);
    }
  }
}

