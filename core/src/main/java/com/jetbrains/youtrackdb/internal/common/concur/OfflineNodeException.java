package com.jetbrains.youtrackdb.internal.common.concur;

import com.jetbrains.youtrackdb.internal.common.exception.SystemException;

/**
 * Exception thrown when an operation is attempted on a node that is offline.
 */
public class OfflineNodeException extends SystemException {

  public OfflineNodeException(OfflineNodeException exception) {
    super(exception);
  }

  public OfflineNodeException(String message) {
    super(message);
  }
}
