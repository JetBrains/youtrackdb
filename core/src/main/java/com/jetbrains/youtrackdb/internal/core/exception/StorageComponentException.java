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
    // The display name, not the raw component name: index-engine components are keyed by
    // internal ie_<fileBaseId> file stems that mean nothing to a user, so their owning engine
    // installs the index's logical name as the display name and user-facing errors report it.
    super(dbName, message, component.getDisplayName());
  }
}
