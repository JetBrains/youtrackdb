package com.jetbrains.youtrackdb.internal.core.exception;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.StorageComponent;

/**
 * Base exception for errors occurring in {@link StorageComponent} operations.
 *
 * @since 10/2/2015
 */
public abstract class StorageComponentException extends CoreException {

  public StorageComponentException(StorageComponentException exception) {
    super(exception);
  }

  public StorageComponentException(String dbName, String message,
      StorageComponent component) {
    super(dbName, message, component.getName());
  }
}
