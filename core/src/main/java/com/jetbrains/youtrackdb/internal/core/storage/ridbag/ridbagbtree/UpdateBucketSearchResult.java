package com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;

public record UpdateBucketSearchResult(IntList insertionIndexes, IntArrayList path, int itemIndex) {

  public long getLastPathItem() {
    return path.get(path.size() - 1);
  }
}
