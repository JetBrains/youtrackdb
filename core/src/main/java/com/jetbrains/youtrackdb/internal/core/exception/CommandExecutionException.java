package com.jetbrains.youtrackdb.internal.core.exception;

import com.jetbrains.youtrackdb.internal.core.db.BasicDatabaseSession;

public class CommandExecutionException extends CoreException {

  public CommandExecutionException(CommandExecutionException exception) {
    super(exception);
  }

  public CommandExecutionException(String message) {
    super(message);
  }

  public CommandExecutionException(String dbName, String message) {
    super(dbName, message);
  }

  public CommandExecutionException(BasicDatabaseSession<?, ?> session, String message) {
    super(session, message);
  }
}
