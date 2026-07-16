package com.jetbrains.youtrackdb.internal.core.storage.collection;

import com.jetbrains.youtrackdb.internal.core.storage.cache.ApplyPhaseEpoch;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.StorageComponent;

public abstract class CollectionPositionMap extends StorageComponent {

  public static final String DEF_EXTENSION = ".cpm";

  public CollectionPositionMap(
      AbstractStorage storage, String name, String extension, String lockName,
      boolean durable) {
    super(storage, name, extension, lockName, durable);
  }

  /**
   * Sub-component constructor (YTDB-1203): the position map is owned by a collection and
   * must share the parent collection's apply-phase epoch so one optimistic read spanning
   * collection and position-map files validates a single epoch.
   */
  protected CollectionPositionMap(
      AbstractStorage storage, String name, String extension, String lockName,
      boolean durable, ApplyPhaseEpoch parentEpoch) {
    super(storage, name, extension, lockName, durable, parentEpoch);
  }
}
