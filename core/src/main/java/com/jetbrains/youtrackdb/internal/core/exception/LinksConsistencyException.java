package com.jetbrains.youtrackdb.internal.core.exception;

import com.jetbrains.youtrackdb.api.exception.HighLevelException;
import com.jetbrains.youtrackdb.internal.common.concur.NeedRetryException;
import com.jetbrains.youtrackdb.internal.common.exception.ErrorCode;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;


/**
 * This exception is thrown when the links inconsistency is detected in the database during record
 * modification. Some of the links in records contains invalid values, that for example point to
 * non-existing records or to the records that are not allowed to be linked.
 */
public class LinksConsistencyException extends NeedRetryException implements HighLevelException {

  /**
   * This constructor is used by the serialization mechanism to create a new instance of this
   * exception on the client side.
   */
  @SuppressWarnings("unused")
  public LinksConsistencyException(LinksConsistencyException exception) {
    super(exception);
  }

  public LinksConsistencyException(DatabaseSessionEmbedded session,
      String message) {
    super(session.getDatabaseName(), message, ErrorCode.LINKS_CONSISTENCY_ERROR);
  }
}
