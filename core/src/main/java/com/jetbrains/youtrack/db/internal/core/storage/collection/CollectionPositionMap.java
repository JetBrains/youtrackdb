package com.jetbrains.youtrack.db.internal.core.storage.collection;

import com.jetbrains.youtrack.db.internal.core.storage.impl.local.AbstractPaginatedStorage;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.base.DurableComponent;

public abstract class CollectionPositionMap extends DurableComponent {

  public static final String DEF_EXTENSION = ".cpm";

  public CollectionPositionMap(
      AbstractPaginatedStorage storage, String name, String extension, String lockName) {
    super(storage, name, extension, lockName);
  }
}
