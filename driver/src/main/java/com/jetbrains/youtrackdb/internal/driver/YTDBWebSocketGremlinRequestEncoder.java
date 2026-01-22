package com.jetbrains.youtrackdb.internal.driver;

import io.netty.channel.ChannelHandlerContext;
import java.util.List;
import java.util.Map;
import org.apache.tinkerpop.gremlin.driver.handler.WebSocketGremlinRequestEncoder;
import org.apache.tinkerpop.gremlin.util.MessageSerializer;
import org.apache.tinkerpop.gremlin.util.Tokens;
import org.apache.tinkerpop.gremlin.util.message.RequestMessage;

public final class YTDBWebSocketGremlinRequestEncoder extends WebSocketGremlinRequestEncoder {

  public YTDBWebSocketGremlinRequestEncoder(final boolean binaryEncoding,
      final MessageSerializer<?> serializer) {
    super(binaryEncoding, serializer);
  }

  @Override
  protected void encode(final ChannelHandlerContext channelHandlerContext,
      final RequestMessage requestMessage, final List<Object> objects) throws Exception {
    super.encode(channelHandlerContext, requestMessage, objects);

    var args = requestMessage.getArgs();
    @SuppressWarnings("unchecked")
    var aliases = (Map<String, String>) args.getOrDefault(Tokens.ARGS_ALIASES,
        Map.<String, String>of());
    var dbName = aliases.get(Tokens.VAL_TRAVERSAL_SOURCE_ALIAS);
    channelHandlerContext.channel().attr(YTDBGremlinSaslAuthenticationHandler.SASL_AUTH_ID)
        .set(dbName);
  }
}
