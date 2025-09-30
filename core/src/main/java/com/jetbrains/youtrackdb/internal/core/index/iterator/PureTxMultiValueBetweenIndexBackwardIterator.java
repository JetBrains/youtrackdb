package com.jetbrains.youtrackdb.internal.core.index.iterator;

import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.common.comparator.DefaultComparator;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.index.IndexMultiValues;
import com.jetbrains.youtrackdb.internal.core.iterator.EmptyIterator;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionIndexChanges;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;

public class PureTxMultiValueBetweenIndexBackwardIterator
    implements Iterator<RawPair<Object, RID>> {
  private final FrontendTransactionIndexChanges indexChanges;

  private Object firstKey;
  private Object nextKey;

  private Iterator<Identifiable> valuesIterator = new EmptyIterator<>();
  private Object key;

  private RawPair<Object, RID> nextResult;

  public PureTxMultiValueBetweenIndexBackwardIterator(
      IndexMultiValues indexTxAwareMultiValue,
      Object fromKey,
      boolean fromInclusive,
      Object toKey,
      boolean toInclusive,
      FrontendTransactionIndexChanges indexChanges) {
    this.indexChanges = indexChanges;

    if (fromKey != null) {
      fromKey =
          indexTxAwareMultiValue.enhanceFromCompositeKeyBetweenDesc(fromKey, fromInclusive);
    }
    if (toKey != null) {
      toKey = indexTxAwareMultiValue.enhanceToCompositeKeyBetweenDesc(toKey, toInclusive);
    }

    final var keys = indexChanges.firstAndLastKeys(fromKey, fromInclusive, toKey, toInclusive);
    if (keys.length == 0) {
      nextKey = null;
    } else {
      firstKey = keys[0];
      nextKey = keys[1];
    }
  }

  private RawPair<Object, RID> nextEntryInternal() {
    final var identifiable = valuesIterator.next();
    return new RawPair<>(key, identifiable.getIdentity());
  }

  @Override
  public boolean hasNext() {
    if (nextResult != null) {
      return true;
    }

    if (valuesIterator.hasNext()) {
      nextResult = nextEntryInternal();
      return true;
    }

    if (nextKey == null) {
      return false;
    }

    Set<Identifiable> result;
    do {
      result = IndexMultiValues.calculateTxValue(nextKey, indexChanges);
      key = nextKey;

      nextKey = indexChanges.getLowerKey(nextKey);

      if (nextKey != null && DefaultComparator.INSTANCE.compare(nextKey, firstKey) < 0) {
        nextKey = null;
      }
    } while ((result == null || result.isEmpty()) && nextKey != null);

    if (result == null || result.isEmpty()) {
      nextResult = null;
      return false;
    }

    valuesIterator = result.iterator();
    nextResult = nextEntryInternal();
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
