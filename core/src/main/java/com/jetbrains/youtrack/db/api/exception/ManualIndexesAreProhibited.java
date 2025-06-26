package com.jetbrains.youtrack.db.api.exception;


import com.jetbrains.youtrack.db.internal.core.exception.CoreException;

/**
 * Exception which is thrown to inform user that manual indexes are prohibited.
 */
public class ManualIndexesAreProhibited extends CoreException implements HighLevelException {

  public ManualIndexesAreProhibited(ManualIndexesAreProhibited exception) {
    super(exception);
  }

  public ManualIndexesAreProhibited(String dbName, String message) {
    super(dbName, message);
  }
}
