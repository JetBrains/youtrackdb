package com.jetbrains.youtrackdb.internal.core.index.iterator;

import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.common.comparator.DefaultComparator;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.index.IndexOneValue;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionIndexChanges;
import java.util.Iterator;
import java.util.NoSuchElementException;

public class PureTxBetweenIndexBackwardIterator implements Iterator<RawPair<Object, RID>> {

  private final IndexOneValue indexTxAwareOneValue;

  private final FrontendTransactionIndexChanges indexChanges;
  private Object firstKey;
  private Object nextKey;
  private RawPair<Object, RID> nextResult;

  public PureTxBetweenIndexBackwardIterator(
      IndexOneValue indexTxAwareOneValue,
      Object fromKey,
      boolean fromInclusive,
      Object toKey,
      boolean toInclusive,
      FrontendTransactionIndexChanges indexChanges) {
    this.indexTxAwareOneValue = indexTxAwareOneValue;
    this.indexChanges = indexChanges;

    if (fromKey != null) {
      fromKey =
          this.indexTxAwareOneValue.enhanceFromCompositeKeyBetweenDesc(fromKey, fromInclusive);
    }
    if (toKey != null) {
      toKey = this.indexTxAwareOneValue.enhanceToCompositeKeyBetweenDesc(toKey, toInclusive);
    }

    final var keys = indexChanges.firstAndLastKeys(fromKey, fromInclusive, toKey, toInclusive);
    if (keys.length == 0) {
      nextKey = null;
    } else {
      firstKey = keys[0];
      nextKey = keys[1];
    }
  }

  @Override
  public boolean hasNext() {
    if (nextResult != null) {
      return true;
    }

    if (nextKey == null) {
      return false;
    }

    RawPair<Object, RID> result;
    do {
      result = this.indexTxAwareOneValue.calculateTxIndexEntry(nextKey, null, indexChanges);
      nextKey = indexChanges.getLowerKey(nextKey);

      if (nextKey != null && DefaultComparator.INSTANCE.compare(nextKey, firstKey) < 0) {
        nextKey = null;
      }
    } while (result == null && nextKey != null);

    if (result == null) {
      nextKey = null;
      return false;
    }

    nextResult = result;
    return true;
  }

  @Override
  public RawPair<Object, RID> next() {
    if (!hasNext()) {
      throw new NoSuchElementException();
    }

    var result = nextResult;
    nextResult = null;
    return result;
  }
}
