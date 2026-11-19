package com.jetbrains.youtrackdb.internal.core.exception;

import com.jetbrains.youtrackdb.api.exception.HighLevelException;
import com.jetbrains.youtrackdb.internal.core.storage.collection.PaginatedCollection;

/** Refuses collection growth without putting the storage into its error state. */
public class CollectionSizeLimitException extends PaginatedCollectionException
    implements HighLevelException {

  public CollectionSizeLimitException(CollectionSizeLimitException exception) {
    super(exception);
  }

  public CollectionSizeLimitException(String dbName, PaginatedCollection collection,
      int maximumPageIndex) {
    super(dbName, "Collection '" + collection.getName()
        + "' cannot grow past data page index " + maximumPageIndex + ".", collection);
  }
}
