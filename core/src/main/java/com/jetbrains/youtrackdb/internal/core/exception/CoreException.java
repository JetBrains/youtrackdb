package com.jetbrains.youtrackdb.internal.core.exception;

import com.jetbrains.youtrackdb.internal.common.exception.ErrorCode;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;

/**
 * @since 9/28/2015
 */
public abstract class CoreException extends BaseException {
  private final ErrorCode errorCode;

  private String componentName;

  public CoreException(final CoreException exception) {
    this(exception, null);
  }

  public CoreException(final CoreException exception, ErrorCode errorCode) {
    super(exception);

    this.componentName = exception.componentName;
    this.errorCode = errorCode;
  }

  public CoreException(final String message) {
    this(null, message, null, null);
  }

  public CoreException(String dbName, final String message) {
    this(dbName, message, null, null);
  }

  public CoreException(DatabaseSessionEmbedded session, final String message) {
    this(session != null ? session.getDatabaseName() : null, message, null, null);
  }


  public CoreException(String dbName, final String message,
      final String componentName) {
    this(dbName, message, componentName, null);
  }

  public CoreException(
      String dbName, final String message, final String componentName,
      final ErrorCode errorCode) {
    super(message, dbName);

    this.errorCode = errorCode;
    this.componentName = componentName;
  }

  public ErrorCode getErrorCode() {
    return errorCode;
  }


  public void setComponentName(String componentName) {
    this.componentName = componentName;
  }

  @Override
  public String getMessage() {
    var msg = super.getMessage();
    final var builder = new StringBuilder(msg != null ? msg : "");

    if (getDbName() != null) {
      builder.append("\r\n\t").append("DB Name=\"").append(getDbName()).append("\"");
    }
    if (componentName != null) {
      builder.append("\r\n\t").append("Component Name=\"").append(componentName).append("\"");
    }
    if (errorCode != null) {
      builder.append("\r\n\t").append("Error Code=\"").append(errorCode.getCode()).append("\"");
    }

    return builder.toString();
  }
}
