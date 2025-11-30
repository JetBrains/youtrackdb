package com.jetbrains.youtrackdb.internal.driver;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import java.util.List;
import java.util.Map;
import org.apache.tinkerpop.gremlin.driver.exception.ResponseException;
import org.apache.tinkerpop.gremlin.util.MessageSerializer;
import org.apache.tinkerpop.gremlin.util.Tokens;
import org.apache.tinkerpop.gremlin.util.message.RequestMessage;
import org.apache.tinkerpop.gremlin.util.message.ResponseStatusCode;
import org.apache.tinkerpop.gremlin.util.ser.MessageTextSerializer;

public final class YTDBWebSocketGremlinRequestEncoder extends
    MessageToMessageEncoder<RequestMessage> {

  private final boolean binaryEncoding;

  private final MessageSerializer<?> serializer;

  public YTDBWebSocketGremlinRequestEncoder(final boolean binaryEncoding,
      final MessageSerializer<?> serializer) {
    this.binaryEncoding = binaryEncoding;
    this.serializer = serializer;
  }

  @Override
  protected void encode(final ChannelHandlerContext channelHandlerContext,
      final RequestMessage requestMessage, final List<Object> objects) throws Exception {
    try {
      if (binaryEncoding) {
        final var encodedMessage = serializer.serializeRequestAsBinary(requestMessage,
            channelHandlerContext.alloc());
        objects.add(new BinaryWebSocketFrame(encodedMessage));
      } else {
        final var textSerializer = (MessageTextSerializer<?>) serializer;
        objects.add(new TextWebSocketFrame(textSerializer.serializeRequestAsString(requestMessage,
            channelHandlerContext.alloc())));
      }

      var args = requestMessage.getArgs();
      @SuppressWarnings("unchecked")
      var aliases = (Map<String, String>) args.getOrDefault(Tokens.ARGS_ALIASES,
          Map.<String, String>of());
      var dbName = aliases.get(Tokens.VAL_TRAVERSAL_SOURCE_ALIAS);
      channelHandlerContext.channel().attr(YTDBGremlinSaslAuthenticationHandler.SASL_AUTH_ID)
          .set(dbName);
    } catch (Exception ex) {
      throw new ResponseException(ResponseStatusCode.REQUEST_ERROR_SERIALIZATION, String.format(
          "An error occurred during serialization of this request [%s] - it could not be sent to the server - Reason: %s",
          requestMessage, ex));
    }
  }
}
