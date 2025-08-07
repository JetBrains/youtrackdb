package com.jetbrains.youtrack.db.internal.driver;

import io.netty.channel.socket.SocketChannel;
import org.apache.tinkerpop.gremlin.driver.Channelizer.HttpChannelizer;

public class YTDBHttpChannelizer extends HttpChannelizer {

  @Override
  protected void initChannel(SocketChannel socketChannel) throws Exception {
    super.initChannel(socketChannel);

    var pipeline = socketChannel.pipeline();
    var oldGremlinHandler = pipeline.get("gremlin-handler");

    if (!(oldGremlinHandler instanceof YTDBGremlinResponseHandler)) {
      var newGremlinHandler = new YTDBGremlinResponseHandler(pending);
      pipeline.replace(oldGremlinHandler, "gremlin-handler", newGremlinHandler);
    }
  }
}
