package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2;

import com.jetbrains.youtrackdb.internal.common.serialization.types.ByteSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.LongSerializer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.CacheEntryChanges;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;

public final class CellBTreeMultiValueV2EntryPoint<K> extends DurablePage {

  private static final int KEY_SERIALIZER_OFFSET = NEXT_FREE_POSITION;
  private static final int KEY_SIZE_OFFSET = KEY_SERIALIZER_OFFSET + ByteSerializer.BYTE_SIZE;
  private static final int TREE_SIZE_OFFSET = KEY_SIZE_OFFSET + IntegerSerializer.INT_SIZE;
  private static final int PAGES_SIZE_OFFSET = TREE_SIZE_OFFSET + LongSerializer.LONG_SIZE;
  private static final int ENTRY_ID_OFFSET = PAGES_SIZE_OFFSET + IntegerSerializer.INT_SIZE;

  public CellBTreeMultiValueV2EntryPoint(final CacheEntry cacheEntry) {
    super(cacheEntry);
  }

  public void init() {
    setLongValue(TREE_SIZE_OFFSET, 0);
    setIntValue(PAGES_SIZE_OFFSET, 1);
    setLongValue(ENTRY_ID_OFFSET, 0);

    var cacheEntry = getCacheEntry();
    if (cacheEntry instanceof CacheEntryChanges cec) {
      cec.registerPageOperation(
          new BTreeMVEntryPointV2InitOp(
              cacheEntry.getPageIndex(), cacheEntry.getFileId(),
              0, cec.getInitialLSN()));
    }
  }

  public void setTreeSize(final long size) {
    setLongValue(TREE_SIZE_OFFSET, size);

    var cacheEntry = getCacheEntry();
    if (cacheEntry instanceof CacheEntryChanges cec) {
      cec.registerPageOperation(
          new BTreeMVEntryPointV2SetTreeSizeOp(
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
          new BTreeMVEntryPointV2SetPagesSizeOp(
              cacheEntry.getPageIndex(), cacheEntry.getFileId(),
              0, cec.getInitialLSN(), pages));
    }
  }

  public int getPagesSize() {
    return getIntValue(PAGES_SIZE_OFFSET);
  }

  public void setEntryId(final long id) {
    setLongValue(ENTRY_ID_OFFSET, id);

    var cacheEntry = getCacheEntry();
    if (cacheEntry instanceof CacheEntryChanges cec) {
      cec.registerPageOperation(
          new BTreeMVEntryPointV2SetEntryIdOp(
              cacheEntry.getPageIndex(), cacheEntry.getFileId(),
              0, cec.getInitialLSN(), id));
    }
  }

  public long getEntryId() {
    return getLongValue(ENTRY_ID_OFFSET);
  }
}
