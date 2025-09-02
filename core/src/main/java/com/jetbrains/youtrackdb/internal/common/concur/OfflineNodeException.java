package com.jetbrains.youtrackdb.internal.common.concur;

import com.jetbrains.youtrackdb.internal.common.exception.SystemException;

/**
 *
 */
public class OfflineNodeException extends SystemException {

  public OfflineNodeException(OfflineNodeException exception) {
    super(exception);
  }

  public OfflineNodeException(String message) {
    super(message);
  }
}
