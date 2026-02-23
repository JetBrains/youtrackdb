package com.jetbrains.youtrackdb.internal.core.exception;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurableComponent;

/**
 * Base exception for errors occurring in {@link DurableComponent} operations.
 *
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
