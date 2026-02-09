package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3;

import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Spliterator;
import java.util.function.Consumer;
import javax.annotation.Nullable;

final class SpliteratorForward<K> implements Spliterator<RawPair<K, RID>> {
  private final BTree<K> btree;

  private final K fromKey;
  private final K toKey;
  private final boolean fromKeyInclusive;
  private final boolean toKeyInclusive;

  private int pageIndex = -1;
  private int itemIndex = -1;

  private LogSequenceNumber lastLSN = null;

  private final AtomicOperation atomicOperation;

  private final List<RawPair<K, RID>> dataCache = new ArrayList<>();
  private Iterator<RawPair<K, RID>> cacheIterator = Collections.emptyIterator();

  SpliteratorForward(
      BTree<K> BTree,
      final K fromKey,
      final K toKey,
      final boolean fromKeyInclusive,
      final boolean toKeyInclusive, AtomicOperation atomicOperation) {
    btree = BTree;
    this.fromKey = fromKey;
    this.toKey = toKey;

    this.toKeyInclusive = toKeyInclusive;
    this.fromKeyInclusive = fromKeyInclusive;
    this.atomicOperation = atomicOperation;
  }

  @Override
  public boolean tryAdvance(Consumer<? super RawPair<K, RID>> action) {
    if (cacheIterator == null) {
      return false;
    }

    if (cacheIterator.hasNext()) {
      action.accept(cacheIterator.next());
      return true;
    }

    btree.fetchNextForwardCachePortion(this, atomicOperation);

    cacheIterator = dataCache.iterator();

    if (cacheIterator.hasNext()) {
      action.accept(cacheIterator.next());
      return true;
    }

    cacheIterator = null;

    return false;
  }

  @Nullable
  @Override
  public Spliterator<RawPair<K, RID>> trySplit() {
    return null;
  }

  @Override
  public long estimateSize() {
    return Long.MAX_VALUE;
  }

  @Override
  public int characteristics() {
    return SORTED | NONNULL | ORDERED;
  }

  @Override
  public Comparator<? super RawPair<K, RID>> getComparator() {
    return (pairOne, pairTwo) -> btree.comparator.compare(pairOne.first(), pairTwo.first());
  }

  public K getFromKey() {
    return fromKey;
  }

  public K getToKey() {
    return toKey;
  }

  public boolean isFromKeyInclusive() {
    return fromKeyInclusive;
  }

  public boolean isToKeyInclusive() {
    return toKeyInclusive;
  }

  public int getItemIndex() {
    return itemIndex;
  }

  public void setItemIndex(int itemIndex) {
    this.itemIndex = itemIndex;
  }

  public int getPageIndex() {
    return pageIndex;
  }

  public void setPageIndex(int pageIndex) {
    this.pageIndex = pageIndex;
  }

  List<RawPair<K, RID>> getDataCache() {
    return dataCache;
  }

  LogSequenceNumber getLastLSN() {
    return lastLSN;
  }

  void setLastLSN(LogSequenceNumber lastLSN) {
    this.lastLSN = lastLSN;
  }

  public void setCacheIterator(Iterator<RawPair<K, RID>> cacheIterator) {
    this.cacheIterator = cacheIterator;
  }
}
