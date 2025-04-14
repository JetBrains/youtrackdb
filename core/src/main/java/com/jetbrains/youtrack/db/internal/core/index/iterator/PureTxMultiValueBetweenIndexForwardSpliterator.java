package com.jetbrains.youtrack.db.internal.core.index.iterator;

import com.jetbrains.youtrack.db.internal.common.comparator.DefaultComparator;
import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.core.index.IndexMultiValues;
import com.jetbrains.youtrack.db.internal.core.iterator.EmptyIterator;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransactionIndexChanges;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Set;
import java.util.Spliterator;
import java.util.function.Consumer;
import javax.annotation.Nullable;

public class PureTxMultiValueBetweenIndexForwardSpliterator
    implements Spliterator<RawPair<Object, RID>> {

  /**
   *
   */
  private final IndexMultiValues index;

  private final FrontendTransactionIndexChanges indexChanges;
  private Object lastKey;

  private Object nextKey;

  private Iterator<Identifiable> valuesIterator = new EmptyIterator<>();
  private Object key;

  public PureTxMultiValueBetweenIndexForwardSpliterator(
      IndexMultiValues index,
      Object fromKey,
      boolean fromInclusive,
      Object toKey,
      boolean toInclusive,
      FrontendTransactionIndexChanges indexChanges) {
    this.index = index;
    this.indexChanges = indexChanges;

    if (fromKey != null) {
      fromKey = this.index.enhanceFromCompositeKeyBetweenAsc(fromKey, fromInclusive);
    }
    if (toKey != null) {
      toKey = this.index.enhanceToCompositeKeyBetweenAsc(toKey, toInclusive);
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
  public boolean tryAdvance(Consumer<? super RawPair<Object, RID>> action) {
    if (valuesIterator.hasNext()) {
      final var entry = nextEntryInternal();
      action.accept(entry);
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
      return false;
    }

    valuesIterator = result.iterator();
    final var entry = nextEntryInternal();
    action.accept(entry);

    return true;
  }

  private RawPair<Object, RID> nextEntryInternal() {
    final var identifiable = valuesIterator.next();
    return new RawPair<>(key, identifiable.getIdentity());
  }

  @Nullable
  @Override
  public Spliterator<RawPair<Object, RID>> trySplit() {
    return null;
  }

  @Override
  public long estimateSize() {
    return Long.MAX_VALUE;
  }

  @Override
  public int characteristics() {
    return NONNULL | ORDERED | SORTED;
  }

  @Override
  public Comparator<? super RawPair<Object, RID>> getComparator() {
    return (entryOne, entryTwo) ->
        DefaultComparator.INSTANCE.compare(entryOne.first(), entryTwo.first());
  }
}
