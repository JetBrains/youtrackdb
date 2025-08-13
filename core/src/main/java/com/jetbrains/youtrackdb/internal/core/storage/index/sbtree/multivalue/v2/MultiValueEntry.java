package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2;

public final class MultiValueEntry implements Comparable<MultiValueEntry> {

  public final long id;
  public final int collectionId;
  public final long collectionPosition;

  MultiValueEntry(final long id, final int collectionId, final long collectionPosition) {
    this.id = id;
    this.collectionId = collectionId;
    this.collectionPosition = collectionPosition;
  }

  @Override
  public int compareTo(final MultiValueEntry o) {
    var result = Long.compare(id, o.id);
    if (result != 0) {
      return result;
    }

    result = Integer.compare(collectionId, o.collectionId);
    if (result != 0) {
      return result;
    }

    return Long.compare(collectionPosition, o.collectionPosition);
  }
}
