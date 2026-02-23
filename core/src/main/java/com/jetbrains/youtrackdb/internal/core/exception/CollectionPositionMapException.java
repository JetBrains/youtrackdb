package com.jetbrains.youtrackdb.internal.core.exception;

import com.jetbrains.youtrackdb.internal.core.storage.collection.CollectionPositionMap;

/**
 * Exception thrown when an error occurs in a {@link CollectionPositionMap} operation.
 *
 * @since 10/2/2015
 */
public class CollectionPositionMapException extends DurableComponentException {

  @SuppressWarnings("unused")
  public CollectionPositionMapException(CollectionPositionMapException exception) {
    super(exception);
  }

  public CollectionPositionMapException(String dbName, String message, CollectionPositionMap component) {
    super(dbName, message, component);
  }
}
