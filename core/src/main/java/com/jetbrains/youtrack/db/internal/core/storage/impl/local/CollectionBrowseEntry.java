package com.jetbrains.youtrack.db.internal.core.storage.impl.local;

import com.jetbrains.youtrack.db.internal.core.storage.RawBuffer;

public class CollectionBrowseEntry {

  private final long collectionPosition;
  private final RawBuffer buffer;

  public CollectionBrowseEntry(long collectionPosition, RawBuffer buffer) {
    this.collectionPosition = collectionPosition;
    this.buffer = buffer;
  }

  public long getCollectionPosition() {
    return collectionPosition;
  }

  public RawBuffer getBuffer() {
    return buffer;
  }
}
