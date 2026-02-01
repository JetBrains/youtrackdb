package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3;

import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.longs.LongArrayList;

record UpdateBucketSearchResult(IntList insertionIndexes, LongArrayList path, int itemIndex) {

  public long getLastPathItem() {
    return path.getLong(path.size() - 1);
  }
}
