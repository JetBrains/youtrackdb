package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3;

import com.jetbrains.youtrackdb.internal.common.serialization.types.ByteSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.LongSerializer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.PageView;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;

public final class CellBTreeSingleValueEntryPointV3<K> extends DurablePage {

  private static final int KEY_SERIALIZER_OFFSET = NEXT_FREE_POSITION;
  private static final int KEY_SIZE_OFFSET = KEY_SERIALIZER_OFFSET + ByteSerializer.BYTE_SIZE;
  private static final int TREE_SIZE_OFFSET = KEY_SIZE_OFFSET + IntegerSerializer.INT_SIZE;
  private static final int PAGES_SIZE_OFFSET = TREE_SIZE_OFFSET + LongSerializer.LONG_SIZE;
  private static final int FREE_LIST_HEAD_OFFSET =
      PAGES_SIZE_OFFSET + IntegerSerializer.INT_SIZE;
  private static final int APPROXIMATE_ENTRIES_COUNT_OFFSET =
      FREE_LIST_HEAD_OFFSET + IntegerSerializer.INT_SIZE;

  static {
    assert KEY_SIZE_OFFSET == KEY_SERIALIZER_OFFSET + ByteSerializer.BYTE_SIZE
        : "KEY_SIZE_OFFSET overlaps KEY_SERIALIZER";
    assert TREE_SIZE_OFFSET == KEY_SIZE_OFFSET + IntegerSerializer.INT_SIZE
        : "TREE_SIZE_OFFSET overlaps KEY_SIZE";
    assert PAGES_SIZE_OFFSET == TREE_SIZE_OFFSET + LongSerializer.LONG_SIZE
        : "PAGES_SIZE_OFFSET overlaps TREE_SIZE";
    assert FREE_LIST_HEAD_OFFSET == PAGES_SIZE_OFFSET + IntegerSerializer.INT_SIZE
        : "FREE_LIST_HEAD_OFFSET overlaps PAGES_SIZE";
    assert APPROXIMATE_ENTRIES_COUNT_OFFSET
        == FREE_LIST_HEAD_OFFSET + IntegerSerializer.INT_SIZE
        : "APPROXIMATE_ENTRIES_COUNT_OFFSET overlaps FREE_LIST_HEAD";
  }

  public CellBTreeSingleValueEntryPointV3(final CacheEntry cacheEntry) {
    super(cacheEntry);
  }

  public CellBTreeSingleValueEntryPointV3(final PageView pageView) {
    super(pageView);
  }

  public void init() {
    setLongValue(TREE_SIZE_OFFSET, 0);
    setLongValue(APPROXIMATE_ENTRIES_COUNT_OFFSET, 0);
    setIntValue(PAGES_SIZE_OFFSET, 1);
    setIntValue(FREE_LIST_HEAD_OFFSET, -1);
  }

  public void setTreeSize(final long size) {
    setLongValue(TREE_SIZE_OFFSET, size);
  }

  public long getTreeSize() {
    return getLongValue(TREE_SIZE_OFFSET);
  }

  public void setApproximateEntriesCount(final long count) {
    assert count >= 0 : "Negative approximate entries count: " + count;
    setLongValue(APPROXIMATE_ENTRIES_COUNT_OFFSET, count);
  }

  public long getApproximateEntriesCount() {
    return getLongValue(APPROXIMATE_ENTRIES_COUNT_OFFSET);
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
