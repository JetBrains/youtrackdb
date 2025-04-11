package com.jetbrains.youtrack.db.internal.core.exception;

public final class CommonDurableComponentException extends CoreException {

  /**
   * This is constructor that used on remote client to restore exception content.
   *
   * @param exception Exception thrown on remote client.
   */
  @SuppressWarnings("unused")
  public CommonDurableComponentException(CommonDurableComponentException exception) {
    super(exception);
  }

  public CommonDurableComponentException(String message,
      String componentName,
      String dbName) {
    super(dbName, message, componentName);
  }
}
