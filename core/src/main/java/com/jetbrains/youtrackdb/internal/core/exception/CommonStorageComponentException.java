package com.jetbrains.youtrackdb.internal.core.exception;

public final class CommonStorageComponentException extends CoreException {

  /**
   * This is constructor that used on remote client to restore exception content.
   *
   * @param exception Exception thrown on remote client.
   */
  @SuppressWarnings("unused")
  public CommonStorageComponentException(CommonStorageComponentException exception) {
    super(exception);
  }

  public CommonStorageComponentException(String message,
      String componentName,
      String dbName) {
    super(dbName, message, componentName);
  }
}
