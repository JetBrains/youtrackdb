package com.jetbrains.youtrackdb.api.exception;


import com.jetbrains.youtrackdb.internal.core.exception.CoreException;

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
