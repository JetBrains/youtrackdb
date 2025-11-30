package com.jetbrains.youtrackdb.internal.driver;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import io.netty.util.AttributeMap;
import io.netty.util.ReferenceCountUtil;
import java.net.InetSocketAddress;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;
import javax.security.sasl.AuthorizeCallback;
import javax.security.sasl.Sasl;
import javax.security.sasl.SaslClient;
import javax.security.sasl.SaslException;
import org.apache.tinkerpop.gremlin.driver.AuthProperties;
import org.apache.tinkerpop.gremlin.util.Tokens;
import org.apache.tinkerpop.gremlin.util.message.RequestMessage;
import org.apache.tinkerpop.gremlin.util.message.ResponseMessage;
import org.apache.tinkerpop.gremlin.util.message.ResponseStatusCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class YTDBGremlinSaslAuthenticationHandler extends
    SimpleChannelInboundHandler<ResponseMessage> {

  private static final Logger logger = LoggerFactory.getLogger(
      YTDBGremlinSaslAuthenticationHandler.class);
  private static final AttributeKey<Subject> subjectKey = AttributeKey.valueOf("subject");
  private static final AttributeKey<SaslClient> saslClientKey = AttributeKey.valueOf("saslclient");
  public static final AttributeKey<String> SASL_AUTH_ID = AttributeKey.valueOf("saslAuthId");

  private static final Map<String, String> SASL_PROPERTIES = new HashMap<String, String>() {{
    put(
        Sasl.SERVER_AUTH, "true");
  }};
  private static final byte[] NULL_CHALLENGE = new byte[0];

  private static final Base64.Encoder BASE64_ENCODER = Base64.getEncoder();
  private static final Base64.Decoder BASE64_DECODER = Base64.getDecoder();

  private final AuthProperties authProps;

  public YTDBGremlinSaslAuthenticationHandler(final AuthProperties authProps) {
    this.authProps = authProps;
  }

  @Override
  protected void channelRead0(final ChannelHandlerContext channelHandlerContext,
      final ResponseMessage response) throws Exception {
    // We are only interested in AUTHENTICATE responses here. Everything else can
    // get passed down the pipeline
    if (response.getStatus().getCode() == ResponseStatusCode.AUTHENTICATE) {
      final var saslClient = ((AttributeMap) channelHandlerContext).attr(saslClientKey);
      final var subject = ((AttributeMap) channelHandlerContext).attr(subjectKey);
      final var messageBuilder = RequestMessage.build(Tokens.OPS_AUTHENTICATION);
      // First time through we don't have a sasl client
      if (saslClient.get() == null) {
        subject.set(login());
        try {
          saslClient.set(saslClient(getHostName(channelHandlerContext),
              getAuthorizationId(channelHandlerContext)));
        } catch (SaslException saslException) {
          // push the sasl error into a failure response from the server. this ensures that standard
          // processing for the ResultQueue is kept. without this SaslException trap and subsequent
          // conversion to an authentication failure, the close() of the connection might not
          // succeed as it will appear as though pending messages remain present in the queue on the
          // connection and the shutdown won't proceed
          final var clientSideError = ResponseMessage.build(response.getRequestId())
              .code(ResponseStatusCode.FORBIDDEN).statusMessage(saslException.getMessage())
              .create();
          channelHandlerContext.fireChannelRead(clientSideError);
          return;
        }

        messageBuilder.addArg(Tokens.ARGS_SASL_MECHANISM, getMechanism());
        messageBuilder.addArg(Tokens.ARGS_SASL, saslClient.get().hasInitialResponse() ?
            BASE64_ENCODER.encodeToString(evaluateChallenge(subject, saslClient, NULL_CHALLENGE))
            : null);
      } else {
        // the server sends base64 encoded sasl as well as the byte array. the byte array will eventually be
        // phased out, but is present now for backward compatibility in 3.2.x
        final var base64sasl = response.getStatus().getAttributes().containsKey(Tokens.ARGS_SASL) ?
            response.getStatus().getAttributes().get(Tokens.ARGS_SASL).toString() :
            BASE64_ENCODER.encodeToString((byte[]) response.getResult().getData());

        messageBuilder.addArg(Tokens.ARGS_SASL, BASE64_ENCODER.encodeToString(
            evaluateChallenge(subject, saslClient, BASE64_DECODER.decode(base64sasl))));
      }
      channelHandlerContext.writeAndFlush(messageBuilder.create());
    } else {
      // SimpleChannelInboundHandler will release the frame if we don't retain it explicitly.
      ReferenceCountUtil.retain(response);
      channelHandlerContext.fireChannelRead(response);
    }
  }

  private static byte[] evaluateChallenge(final Attribute<Subject> subject,
      final Attribute<SaslClient> saslClient,
      final byte[] challenge) throws SaslException {

    if (subject.get() == null) {
      return saslClient.get().evaluateChallenge(challenge);
    } else {
      return Subject.callAs(subject.get(), () -> saslClient.get().evaluateChallenge(challenge));
    }
  }

  @Nullable
  private Subject login() throws LoginException {
    // Login if the user provided us with an entry into the JAAS config file
    if (authProps.get(AuthProperties.Property.JAAS_ENTRY) != null) {
      final var login = new LoginContext(authProps.get(AuthProperties.Property.JAAS_ENTRY));
      login.login();
      return login.getSubject();
    }

    return null;
  }

  private SaslClient saslClient(final String hostname, String authorizationId)
      throws SaslException {
    return Sasl.createSaslClient(new String[]{getMechanism()}, authorizationId,
        authProps.get(AuthProperties.Property.PROTOCOL),
        hostname, SASL_PROPERTIES,
        new SaslCallbackData(authProps.get(AuthProperties.Property.USERNAME),
            authProps.get(AuthProperties.Property.PASSWORD), authorizationId)
    );
  }

  private static String getHostName(final ChannelHandlerContext channelHandlerContext) {
    return ((InetSocketAddress) channelHandlerContext.channel().remoteAddress()).getAddress()
        .getHostName();
  }

  private static String getAuthorizationId(final ChannelHandlerContext channelHandlerContext) {
    return channelHandlerContext.attr(SASL_AUTH_ID).get();
  }

  /**
   * Work out the Sasl mechanism based on the user supplied parameters. If we have a username and
   * password use PLAIN otherwise GSSAPI
   * ToDo: have gremlin-server provide the mechanism(s) it is configured with, so that additional mechanisms can
   * be supported in the driver and confusing GSSException messages from the driver are avoided
   */
  private String getMechanism() {
    if ((authProps.get(AuthProperties.Property.USERNAME) != null) &&
        (authProps.get(AuthProperties.Property.PASSWORD) != null)) {
      return "PLAIN";
    } else {
      return "GSSAPI";
    }
  }

  private record SaslCallbackData(String username, String password,
                                  String authorizationId) implements CallbackHandler {

    @Override
    public void handle(Callback[] callbacks) {
      for (var callback : callbacks) {
        switch (callback) {
          case NameCallback nameCallback -> {
            if (username != null) {
              nameCallback.setName(username);
            }
          }
          case PasswordCallback passwordCallback -> {
            if (password != null) {
              passwordCallback.setPassword(password.toCharArray());
            }
          }
          case AuthorizeCallback authorizeCallback -> {
            if (authorizationId != null) {
              authorizeCallback.setAuthorizedID(authorizationId);
              authorizeCallback.setAuthorized(true);
            }
          }
          default -> logger.warn(
              "SASL handler got a callback of type " + callback.getClass().getCanonicalName());
        }
      }
    }
  }
}
