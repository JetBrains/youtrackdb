package com.jetbrains.youtrack.db.internal.core.storage.ridbag.ridbagbtree;

public final class BucketSearchResult {

  private final int itemIndex;
  private final long pageIndex;

  public BucketSearchResult(final int itemIndex, final long pageIndex) {
    this.itemIndex = itemIndex;
    this.pageIndex = pageIndex;
  }

  public long getPageIndex() {
    return pageIndex;
  }

  public int getItemIndex() {
    return itemIndex;
  }
}
