package com.jetbrains.youtrack.db.internal.common.exception;

import com.jetbrains.youtrack.db.api.exception.BackupInProgressException;
import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.ConcurrentModificationException;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.exception.QueryParsingException;
import java.lang.reflect.InvocationTargetException;
import javax.annotation.Nullable;

/**
 * Enumeration with the error managed by YouTrackDB. This class has been introduced in v.2.2 and
 * little by little will contain all the YouTrackDB managed errors.
 */
public enum ErrorCode {

  // eg.
  QUERY_PARSE_ERROR(
      ErrorCategory.SQL_PARSING, 1, "query parse error", QueryParsingException.class),

  BACKUP_IN_PROGRESS(
      ErrorCategory.STORAGE,
      2,
      "You are trying to start a backup, but it is already in progress",
      BackupInProgressException.class),

  MVCC_ERROR(
      ErrorCategory.CONCURRENCY_RETRY,
      3,
      "The version of the update is outdated compared to the persistent value, retry",
      ConcurrentModificationException.class),

  VALIDATION_ERROR(ErrorCategory.VALIDATION, 4, "Record validation failure", BaseException.class),

  GENERIC_ERROR(ErrorCategory.SQL_GENERIC, 5, "Generic Error", BaseException.class);

  private static final ErrorCode[] codes = new ErrorCode[6];

  static {
    for (var code : ErrorCode.values()) {
      codes[code.code] = code;
    }
  }

  private final ErrorCategory category;
  private final int code;
  private final String description;
  private final Class<? extends BaseException> exceptionClass;

  ErrorCode(
      ErrorCategory category,
      int code,
      String description,
      Class<? extends BaseException> exceptionClass) {
    this.category = category;
    this.code = code;
    this.description = description;
    this.exceptionClass = exceptionClass;
  }

  public int getCode() {
    return code;
  }

  public void throwException() {
    throwException(this.description, null);
  }

  public void throwException(String message) {
    throwException(message, null);
  }

  public void throwException(Throwable parent) {
    throwException(this.description, parent);
  }

  public void throwException(String message, Throwable parent) {
    var exc = newException(message, parent);
    throw exc;
  }

  @Nullable
  public BaseException newException(String message, Throwable parent) {
    final var fullMessage = String.format("%1$06d_%2$06d - %3$s", category.code, code, message);
    try {
      return BaseException.wrapException(
          exceptionClass.getConstructor(String.class).newInstance(fullMessage), parent,
          (String) null);
    } catch (InstantiationException
             | IllegalAccessException
             | NoSuchMethodException
             | InvocationTargetException e) {
      LogManager.instance().warn(this, "Cannot instantiate exception " + exceptionClass);
    }
    return null;
  }

  public static ErrorCode getErrorCode(int code) {
    return codes[code];
  }
}
