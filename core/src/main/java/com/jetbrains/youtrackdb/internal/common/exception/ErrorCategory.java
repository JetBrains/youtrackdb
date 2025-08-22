package com.jetbrains.youtrackdb.internal.common.exception;

/**
 *
 */
public enum ErrorCategory {
  GENERIC(1),

  SQL_GENERIC(2),

  SQL_PARSING(3),

  STORAGE(4),

  CONCURRENCY_RETRY(5),

  VALIDATION(6),

  CONCURRENCY(7);

  final int code;

  ErrorCategory(int code) {
    this.code = code;
  }
}
