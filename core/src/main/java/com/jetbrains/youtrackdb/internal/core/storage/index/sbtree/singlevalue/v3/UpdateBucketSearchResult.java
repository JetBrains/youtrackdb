package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3;

import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.longs.LongArrayList;

final class UpdateBucketSearchResult {

  private final IntList insertionIndexes;
  private final LongArrayList path;
  private final int itemIndex;

  public UpdateBucketSearchResult(
      final IntList insertionIndexes, final LongArrayList path, final int itemIndex) {
    this.insertionIndexes = insertionIndexes;
    this.path = path;
    this.itemIndex = itemIndex;
  }

  public long getLastPathItem() {
    return path.getLong(path.size() - 1);
  }

  public LongArrayList getPath() {
    return path;
  }

  public IntList getInsertionIndexes() {
    return insertionIndexes;
  }

  public int getItemIndex() {
    return itemIndex;
  }
}
