package com.jetbrains.youtrack.db.internal.server;

import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Token;
import com.jetbrains.youtrack.db.internal.core.security.ParsedToken;
import com.jetbrains.youtrack.db.internal.core.security.SecurityUser;
import com.jetbrains.youtrack.db.internal.server.network.protocol.NetworkProtocolData;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

/**
 *
 */
public interface TokenHandler {

  @Deprecated
  String TOKEN_HANDLER_NAME = "OTokenHandler";

  // Return null if token is unparseable or fails verification.
  // The returned token should be checked to ensure isVerified == true.
  Token parseWebToken(byte[] tokenBytes)
      throws InvalidKeyException, NoSuchAlgorithmException, IOException;

  ParsedToken parseOnlyWebToken(byte[] tokenBytes);

  Token parseNotVerifyBinaryToken(byte[] tokenBytes);

  Token parseBinaryToken(byte[] tokenBytes);

  ParsedToken parseOnlyBinary(byte[] tokenBytes);

  boolean validateToken(Token token, String command, String database);

  boolean validateToken(ParsedToken token, String command, String database);

  boolean validateBinaryToken(Token token);

  boolean validateBinaryToken(ParsedToken token);

  NetworkProtocolData getProtocolDataFromToken(ClientConnection clientConnection, Token token);

  // Return a byte array representing a signed token
  byte[] getSignedWebToken(DatabaseSessionInternal session, SecurityUser user);

  default byte[] getSignedWebTokenServerUser(SecurityUser user) {
    throw new UnsupportedOperationException();
  }

  default boolean validateServerUserToken(Token token, String command, String database) {
    throw new UnsupportedOperationException();
  }

  byte[] getSignedBinaryToken(
      DatabaseSessionInternal session, SecurityUser user, NetworkProtocolData data);

  byte[] renewIfNeeded(Token token);

  boolean isEnabled();
}
