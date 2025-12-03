package com.jetbrains.youtrackdb.internal.core.exception;


import com.jetbrains.youtrackdb.api.exception.HighLevelException;

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
