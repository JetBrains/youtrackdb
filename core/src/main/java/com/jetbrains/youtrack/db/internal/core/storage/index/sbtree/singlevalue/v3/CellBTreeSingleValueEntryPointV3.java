package com.jetbrains.youtrack.db.internal.core.storage.index.sbtree.singlevalue.v3;

import com.jetbrains.youtrack.db.internal.common.serialization.types.ByteSerializer;
import com.jetbrains.youtrack.db.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrack.db.internal.common.serialization.types.LongSerializer;
import com.jetbrains.youtrack.db.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.base.DurablePage;

public final class CellBTreeSingleValueEntryPointV3<K> extends DurablePage {

  private static final int KEY_SERIALIZER_OFFSET = NEXT_FREE_POSITION;
  private static final int KEY_SIZE_OFFSET = KEY_SERIALIZER_OFFSET + ByteSerializer.BYTE_SIZE;
  private static final int TREE_SIZE_OFFSET = KEY_SIZE_OFFSET + IntegerSerializer.INT_SIZE;
  private static final int PAGES_SIZE_OFFSET = TREE_SIZE_OFFSET + LongSerializer.LONG_SIZE;
  private static final int FREE_LIST_HEAD_OFFSET = PAGES_SIZE_OFFSET + IntegerSerializer.INT_SIZE;

  public CellBTreeSingleValueEntryPointV3(final CacheEntry cacheEntry) {
    super(cacheEntry);
  }

  public void init() {
    setLongValue(TREE_SIZE_OFFSET, 0);
    setIntValue(PAGES_SIZE_OFFSET, 1);
    setIntValue(FREE_LIST_HEAD_OFFSET, -1);
  }

  public void setTreeSize(final long size) {
    setLongValue(TREE_SIZE_OFFSET, size);
  }

  public long getTreeSize() {
    return getLongValue(TREE_SIZE_OFFSET);
  }

  public void setPagesSize(final int pages) {
    setIntValue(PAGES_SIZE_OFFSET, pages);
  }

  public int getPagesSize() {
    return getIntValue(PAGES_SIZE_OFFSET);
  }

  public int getFreeListHead() {
    final var head = getIntValue(FREE_LIST_HEAD_OFFSET);

    // fix of binary compatibility.
    // in previous version free list head is absent so 0 is considered as invalid value
    if (head == 0) {
      return -1;
    }

    return head;
  }

  public void setFreeListHead(int freeListHead) {
    setIntValue(FREE_LIST_HEAD_OFFSET, freeListHead);
  }
}
