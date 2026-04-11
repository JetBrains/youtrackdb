package com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree;

import com.jetbrains.youtrackdb.internal.common.serialization.types.LongSerializer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.PageView;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.CacheEntryChanges;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;

public final class EntryPoint extends DurablePage {

  private static final int TREE_SIZE_OFFSET = NEXT_FREE_POSITION;
  private static final int PAGES_SIZE_OFFSET = TREE_SIZE_OFFSET + LongSerializer.LONG_SIZE;

  public EntryPoint(CacheEntry cacheEntry) {
    super(cacheEntry);
  }

  public EntryPoint(PageView pageView) {
    super(pageView);
  }

  public void init() {
    setLongValue(TREE_SIZE_OFFSET, 0);
    setIntValue(PAGES_SIZE_OFFSET, 1);

    var cacheEntry = getCacheEntry();
    if (cacheEntry instanceof CacheEntryChanges cec) {
      cec.registerPageOperation(
          new RidbagEntryPointInitOp(
              cacheEntry.getPageIndex(), cacheEntry.getFileId(),
              0, cec.getInitialLSN()));
    }
  }

  public void setTreeSize(final long size) {
    setLongValue(TREE_SIZE_OFFSET, size);

    var cacheEntry = getCacheEntry();
    if (cacheEntry instanceof CacheEntryChanges cec) {
      cec.registerPageOperation(
          new RidbagEntryPointSetTreeSizeOp(
              cacheEntry.getPageIndex(), cacheEntry.getFileId(),
              0, cec.getInitialLSN(), size));
    }
  }

  public long getTreeSize() {
    return getLongValue(TREE_SIZE_OFFSET);
  }

  public void setPagesSize(final int pages) {
    setIntValue(PAGES_SIZE_OFFSET, pages);

    var cacheEntry = getCacheEntry();
    if (cacheEntry instanceof CacheEntryChanges cec) {
      cec.registerPageOperation(
          new RidbagEntryPointSetPagesSizeOp(
              cacheEntry.getPageIndex(), cacheEntry.getFileId(),
              0, cec.getInitialLSN(), pages));
    }
  }

  public int getPagesSize() {
    return getIntValue(PAGES_SIZE_OFFSET);
  }
}
