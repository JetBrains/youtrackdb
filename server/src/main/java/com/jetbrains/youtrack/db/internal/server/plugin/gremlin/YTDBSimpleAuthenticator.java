package com.jetbrains.youtrack.db.internal.server.plugin.gremlin;

import static org.apache.tinkerpop.gremlin.groovy.jsr223.dsl.credential.CredentialGraphTokens.PROPERTY_PASSWORD;
import static org.apache.tinkerpop.gremlin.groovy.jsr223.dsl.credential.CredentialGraphTokens.PROPERTY_USERNAME;

import com.jetbrains.youtrack.db.internal.core.security.SecurityUser;
import com.jetbrains.youtrack.db.internal.server.YouTrackDBServer;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.server.auth.AuthenticatedUser;
import org.apache.tinkerpop.gremlin.server.auth.AuthenticationException;
import org.apache.tinkerpop.gremlin.server.auth.Authenticator;
import org.apache.tinkerpop.gremlin.server.auth.SimpleAuthenticator;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class YTDBSimpleAuthenticator implements Authenticator {

  public static final String YTDB_SERVER_PARAM = "com.jetbrains.youtrack.db.server.gremlin.YouTrackDBServer";

  private static final Logger logger = LoggerFactory.getLogger(SimpleAuthenticator.class);
  private static final byte NUL = 0;

  private YouTrackDBServer server;

  @Override
  public boolean requireAuthentication() {
    return true;
  }

  @Override
  public void setup(final Map<String, Object> config) {
    logger.info("Initializing authentication with the {}", YTDBSimpleAuthenticator.class.getName());

    server = (YouTrackDBServer) config.get(YTDB_SERVER_PARAM);
    if (server == null) {
      throw new IllegalStateException("YouTrackDBServer instance is not set in the config.");
    }
  }

  @Override
  public SaslNegotiator newSaslNegotiator(final InetAddress remoteAddress) {
    return new PlainTextSaslAuthenticator();
  }

  @Override
  public AuthenticatedUser authenticate(final Map<String, String> credentials)
      throws AuthenticationException {
    if (!credentials.containsKey(PROPERTY_USERNAME)) {
      throw new IllegalArgumentException(
          String.format("Credentials must contain a %s", PROPERTY_USERNAME));
    }
    if (!credentials.containsKey(PROPERTY_PASSWORD)) {
      throw new IllegalArgumentException(
          String.format("Credentials must contain a %s", PROPERTY_PASSWORD));
    }

    final var username = credentials.get(PROPERTY_USERNAME);
    final var password = credentials.get(PROPERTY_PASSWORD);

    var databases = server.getDatabases();
    var security = server.getSecurity();

    SecurityUser securityUser;
    try (var systemDatabaseSession = databases.getSystemDatabase().openSystemDatabaseSession()) {
      securityUser = security.authenticate(systemDatabaseSession, username, password);

      if (securityUser == null) {
        throw new AuthenticationException("Username and/or password are incorrect");
      }

      return new AuthenticatedUser(securityUser.getName(systemDatabaseSession));
    }
  }

  private class PlainTextSaslAuthenticator implements Authenticator.SaslNegotiator {

    private boolean complete = false;

    private String username;
    private String password;

    @Override
    @Nullable
    public byte[] evaluateResponse(final byte[] clientResponse) throws AuthenticationException {
      decodeCredentials(clientResponse);
      complete = true;
      return null;
    }

    @Override
    public boolean isComplete() {
      return complete;
    }

    @Override
    public AuthenticatedUser getAuthenticatedUser() throws AuthenticationException {
      if (!complete) {
        throw new AuthenticationException("SASL negotiation not complete");
      }
      final Map<String, String> credentials = new HashMap<>();
      credentials.put(PROPERTY_USERNAME, username);
      credentials.put(PROPERTY_PASSWORD, password);
      return authenticate(credentials);
    }

    /**
     * SASL PLAIN mechanism specifies that credentials are encoded in a sequence of UTF-8 bytes,
     * delimited by 0 (US-ASCII NUL). The form is :
     * {code}authzId<NUL>authnId<NUL>password<NUL>{code}.
     *
     * @param bytes encoded credentials string sent by the client
     */
    private void decodeCredentials(byte[] bytes) throws AuthenticationException {
      byte[] user = null;
      byte[] pass = null;

      var end = bytes.length;
      for (var i = bytes.length - 1; i >= 0; i--) {
        if (bytes[i] == NUL) {
          if (pass == null) {
            pass = Arrays.copyOfRange(bytes, i + 1, end);
          } else if (user == null) {
            user = Arrays.copyOfRange(bytes, i + 1, end);
          }
          end = i;
        }
      }

      if (null == user) {
        throw new AuthenticationException("Authentication ID must not be null");
      }

      username = new String(user, StandardCharsets.UTF_8);
      password = new String(pass, StandardCharsets.UTF_8);
    }
  }
}
