package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3;

import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import org.apache.tinkerpop.gremlin.structure.util.CloseableIterator;

final class IteratorForward<K> implements CloseableIterator<RawPair<K, RID>> {

  private final BTree<K> btree;

  private final K fromKey;
  private final K toKey;
  private final boolean fromKeyInclusive;
  private final boolean toKeyInclusive;

  private int pageIndex = -1;
  private int itemIndex = -1;

  private LogSequenceNumber lastLSN = null;

  private final List<RawPair<K, RID>> dataCache = new ArrayList<>();
  private Iterator<RawPair<K, RID>> cacheIterator = Collections.emptyIterator();

  private RawPair<K, RID> nextResult = null;

  IteratorForward(
      BTree<K> BTree,
      final K fromKey,
      final K toKey,
      final boolean fromKeyInclusive,
      final boolean toKeyInclusive) {
    btree = BTree;
    this.fromKey = fromKey;
    this.toKey = toKey;

    this.toKeyInclusive = toKeyInclusive;
    this.fromKeyInclusive = fromKeyInclusive;
  }

  @Override
  public boolean hasNext() {
    if (nextResult != null) {
      return true;
    }

    if (cacheIterator == null) {
      nextResult = null;
      return false;
    }

    if (cacheIterator.hasNext()) {
      nextResult = cacheIterator.next();
      return true;
    }

    btree.fetchNextForwardCachePortion(this);

    cacheIterator = dataCache.iterator();

    if (cacheIterator.hasNext()) {
      nextResult = cacheIterator.next();
      return true;
    }

    cacheIterator = null;
    nextResult = null;

    return false;
  }

  @Override
  public RawPair<K, RID> next() {
    if (!hasNext()) {
      throw new NoSuchElementException();
    }

    var result = nextResult;
    nextResult = null;

    return result;
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
