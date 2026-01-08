package com.jetbrains.youtrackdb.internal.core.index.iterator;

import com.jetbrains.youtrackdb.internal.common.comparator.DefaultComparator;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.index.IndexOneValue;
import com.jetbrains.youtrackdb.internal.core.index.comparator.AscComparator;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionIndexChanges;
import java.util.Comparator;
import java.util.Spliterator;
import java.util.function.Consumer;
import javax.annotation.Nullable;

public class PureTxBetweenIndexForwardSpliterator implements Spliterator<RawPair<Object, RID>> {

  /**
   *
   */
  private final IndexOneValue oIndexTxAwareOneValue;

  private final FrontendTransactionIndexChanges indexChanges;
  private Object lastKey;

  private Object nextKey;

  public PureTxBetweenIndexForwardSpliterator(
      IndexOneValue oIndexTxAwareOneValue,
      Object fromKey,
      boolean fromInclusive,
      Object toKey,
      boolean toInclusive,
      FrontendTransactionIndexChanges indexChanges) {
    this.oIndexTxAwareOneValue = oIndexTxAwareOneValue;
    this.indexChanges = indexChanges;

    if (fromKey != null) {
      fromKey =
          this.oIndexTxAwareOneValue.enhanceFromCompositeKeyBetweenAsc(fromKey, fromInclusive);
    }
    if (toKey != null) {
      toKey = this.oIndexTxAwareOneValue.enhanceToCompositeKeyBetweenAsc(toKey, toInclusive);
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
    if (nextKey == null) {
      return false;
    }

    RawPair<Object, RID> result;

    do {
      result = this.oIndexTxAwareOneValue.calculateTxIndexEntry(nextKey, null, indexChanges);
      nextKey = indexChanges.getHigherKey(nextKey);

      if (nextKey != null && DefaultComparator.INSTANCE.compare(nextKey, lastKey) > 0) {
        nextKey = null;
      }

    } while (result == null && nextKey != null);

    if (result == null) {
      return false;
    }

    action.accept(result);
    return true;
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
    return NONNULL | SORTED | ORDERED;
  }

  @Override
  public Comparator<? super RawPair<Object, RID>> getComparator() {
    return AscComparator.INSTANCE;
  }
}
