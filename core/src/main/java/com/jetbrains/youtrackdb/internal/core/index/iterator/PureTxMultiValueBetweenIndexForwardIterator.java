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
import org.apache.tinkerpop.gremlin.structure.util.CloseableIterator;

public class PureTxMultiValueBetweenIndexForwardIterator
    implements CloseableIterator<RawPair<Object, RID>> {

  private final FrontendTransactionIndexChanges indexChanges;
  private Object lastKey;

  private Object nextKey;

  private Iterator<Identifiable> valuesIterator = new EmptyIterator<>();
  private Object key;

  private RawPair<Object, RID> nextResult;

  public PureTxMultiValueBetweenIndexForwardIterator(
      IndexMultiValues index,
      Object fromKey,
      boolean fromInclusive,
      Object toKey,
      boolean toInclusive,
      FrontendTransactionIndexChanges indexChanges) {
    this.indexChanges = indexChanges;

    if (fromKey != null) {
      fromKey = index.enhanceFromCompositeKeyBetweenAsc(fromKey, fromInclusive);
    }
    if (toKey != null) {
      toKey = index.enhanceToCompositeKeyBetweenAsc(toKey, toInclusive);
    }

    final var keys = indexChanges.firstAndLastKeys(fromKey, fromInclusive, toKey, toInclusive);
    if (keys.length == 0) {
      nextKey = null;
    } else {
      var firstKey = keys[0];
      lastKey = keys[1];

      nextKey = firstKey;
    }
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

      nextKey = indexChanges.getHigherKey(nextKey);

      if (nextKey != null && DefaultComparator.INSTANCE.compare(nextKey, lastKey) > 0) {
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

  private RawPair<Object, RID> nextEntryInternal() {
    final var identifiable = valuesIterator.next();
    return new RawPair<>(key, identifiable.getIdentity());
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
