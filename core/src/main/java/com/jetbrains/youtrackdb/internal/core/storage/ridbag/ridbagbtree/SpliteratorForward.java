package com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree;

import com.jetbrains.youtrackdb.internal.common.util.RawPairObjectInteger;
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

public final class SpliteratorForward implements Spliterator<RawPairObjectInteger<EdgeKey>> {

  private final SharedLinkBagBTree bTree;

  private final EdgeKey fromKey;
  private final EdgeKey toKey;
  private final boolean fromKeyInclusive;
  private final boolean toKeyInclusive;

  private int pageIndex = -1;
  private int itemIndex = -1;

  private LogSequenceNumber lastLSN = null;

  private final List<RawPairObjectInteger<EdgeKey>> dataCache = new ArrayList<>();
  private Iterator<RawPairObjectInteger<EdgeKey>> cacheIterator = Collections.emptyIterator();

  private final AtomicOperation atomicOperation;

  SpliteratorForward(
      SharedLinkBagBTree bTree,
      final EdgeKey fromKey,
      final EdgeKey toKey,
      final boolean fromKeyInclusive,
      final boolean toKeyInclusive, AtomicOperation atomicOperation) {
    this.bTree = bTree;
    this.fromKey = fromKey;
    this.toKey = toKey;

    this.toKeyInclusive = toKeyInclusive;
    this.fromKeyInclusive = fromKeyInclusive;
    this.atomicOperation = atomicOperation;
  }

  @Override
  public boolean tryAdvance(Consumer<? super RawPairObjectInteger<EdgeKey>> action) {
    if (cacheIterator == null) {
      return false;
    }

    if (cacheIterator.hasNext()) {
      action.accept(cacheIterator.next());
      return true;
    }

    this.bTree.fetchNextCachePortionForward(this, atomicOperation);

    cacheIterator = dataCache.iterator();

    if (cacheIterator.hasNext()) {
      action.accept(cacheIterator.next());
      return true;
    }

    cacheIterator = null;

    return false;
  }

  public void clearCache() {
    dataCache.clear();
    cacheIterator = Collections.emptyIterator();
  }

  @Nullable
  @Override
  public Spliterator<RawPairObjectInteger<EdgeKey>> trySplit() {
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
  public Comparator<? super RawPairObjectInteger<EdgeKey>> getComparator() {
    return Comparator.comparing(pair -> pair.first);
  }

  EdgeKey getFromKey() {
    return fromKey;
  }

  EdgeKey getToKey() {
    return toKey;
  }

  boolean isFromKeyInclusive() {
    return fromKeyInclusive;
  }

  boolean isToKeyInclusive() {
    return toKeyInclusive;
  }

  public List<RawPairObjectInteger<EdgeKey>> getDataCache() {
    return dataCache;
  }

  public int getPageIndex() {
    return pageIndex;
  }

  public void setPageIndex(int pageIndex) {
    this.pageIndex = pageIndex;
  }

  public int getItemIndex() {
    return itemIndex;
  }

  public void setItemIndex(int itemIndex) {
    this.itemIndex = itemIndex;
  }

  public void incrementItemIndex() {
    this.itemIndex++;
  }

  LogSequenceNumber getLastLSN() {
    return lastLSN;
  }

  void setLastLSN(LogSequenceNumber lastLSN) {
    this.lastLSN = lastLSN;
  }
}
