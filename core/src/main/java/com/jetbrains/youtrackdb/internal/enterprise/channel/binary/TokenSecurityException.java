package com.jetbrains.youtrackdb.internal.enterprise.channel.binary;

import com.jetbrains.youtrackdb.api.common.BasicDatabaseSession;
import com.jetbrains.youtrackdb.api.exception.SecurityException;

/**
 *
 */
public class TokenSecurityException extends SecurityException {

  public TokenSecurityException(TokenSecurityException exception) {
    super(exception);
  }

  public TokenSecurityException(String dbName, String message) {
    super(dbName, message);
  }

  public TokenSecurityException(BasicDatabaseSession<?, ?> session,
      String message) {
    super(session, message);
  }

  public TokenSecurityException(String message) {
    super(message);
  }
}
