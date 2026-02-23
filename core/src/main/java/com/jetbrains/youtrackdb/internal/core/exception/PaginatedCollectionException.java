package com.jetbrains.youtrackdb.internal.core.exception;

import com.jetbrains.youtrackdb.internal.core.storage.collection.PaginatedCollection;

/**
 * Exception thrown when an error occurs in a {@link PaginatedCollection} operation.
 *
 * @since 10/2/2015
 */
public class PaginatedCollectionException extends DurableComponentException {

  public PaginatedCollectionException(PaginatedCollectionException exception) {
    super(exception);
  }

  public PaginatedCollectionException(String dbName, String message, PaginatedCollection component) {
    super(dbName, message, component);
  }
}
