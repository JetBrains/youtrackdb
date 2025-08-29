package com.jetbrains.youtrackdb.internal.core.storage.collection;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurableComponent;

public abstract class CollectionPositionMap extends DurableComponent {

  public static final String DEF_EXTENSION = ".cpm";

  public CollectionPositionMap(
      AbstractStorage storage, String name, String extension, String lockName) {
    super(storage, name, extension, lockName);
  }
}
