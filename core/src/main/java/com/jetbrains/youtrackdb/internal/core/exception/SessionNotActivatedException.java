package com.jetbrains.youtrackdb.internal.core.exception;

public class SessionNotActivatedException extends CoreException {

  public SessionNotActivatedException(String dbName) {
    super(dbName, "Session is not activated on current thread for database.");
  }

  public SessionNotActivatedException(SessionNotActivatedException exception) {
    super(exception);
  }
}
