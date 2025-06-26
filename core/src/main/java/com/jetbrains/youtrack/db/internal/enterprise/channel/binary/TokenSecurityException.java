package com.jetbrains.youtrack.db.internal.enterprise.channel.binary;

import com.jetbrains.youtrack.db.api.common.BasicDatabaseSession;
import com.jetbrains.youtrack.db.api.exception.SecurityException;

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
