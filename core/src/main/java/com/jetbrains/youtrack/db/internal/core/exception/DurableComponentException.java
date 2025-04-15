package com.jetbrains.youtrack.db.internal.core.exception;

import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.base.DurableComponent;

/**
 * @since 10/2/2015
 */
public abstract class DurableComponentException extends CoreException {

  public DurableComponentException(DurableComponentException exception) {
    super(exception);
  }

  public DurableComponentException(String dbName, String message,
      DurableComponent component) {
    super(dbName, message, component.getName());
  }
}
