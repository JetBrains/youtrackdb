package com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree;

import com.jetbrains.youtrackdb.internal.common.serialization.types.LongSerializer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.PageView;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.CacheEntryChanges;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;

public final class EntryPoint extends DurablePage {

  private static final int TREE_SIZE_OFFSET = NEXT_FREE_POSITION;
  private static final int PAGES_SIZE_OFFSET = TREE_SIZE_OFFSET + LongSerializer.LONG_SIZE;
  private static final int RID_BAG_ID_COUNTER_OFFSET =
      PAGES_SIZE_OFFSET + Integer.BYTES;

  public EntryPoint(CacheEntry cacheEntry) {
    super(cacheEntry);
  }

  public EntryPoint(PageView pageView) {
    super(pageView);
  }

  public void init() {
    setLongValue(TREE_SIZE_OFFSET, 0);
    setIntValue(PAGES_SIZE_OFFSET, 1);
    setLongValue(RID_BAG_ID_COUNTER_OFFSET, 0);

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

  public void setRidBagIdCounter(final long counter) {
    setLongValue(RID_BAG_ID_COUNTER_OFFSET, counter);

    var cacheEntry = getCacheEntry();
    if (cacheEntry instanceof CacheEntryChanges cec) {
      cec.registerPageOperation(
          new RidbagEntryPointSetRidBagIdCounterOp(
              cacheEntry.getPageIndex(), cacheEntry.getFileId(),
              0, cec.getInitialLSN(), counter));
    }
  }

  public long getRidBagIdCounter() {
    return getLongValue(RID_BAG_ID_COUNTER_OFFSET);
  }
}
