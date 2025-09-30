/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrackdb.internal.core.index;

import com.jetbrains.youtrackdb.api.record.DBRecord;
import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.common.collection.YTDBIteratorUtils;
import com.jetbrains.youtrackdb.internal.common.comparator.DefaultComparator;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.index.comparator.AscComparator;
import com.jetbrains.youtrackdb.internal.core.index.comparator.DescComparator;
import com.jetbrains.youtrackdb.internal.core.index.iterator.PureTxMultiValueBetweenIndexBackwardIterator;
import com.jetbrains.youtrackdb.internal.core.index.iterator.PureTxMultiValueBetweenIndexForwardIterator;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaIndex;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransaction;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionIndexChanges;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionIndexChanges.OPERATION;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;
import org.jspecify.annotations.NonNull;

/**
 * Abstract index implementation that supports multi-values for the same key.
 */
public abstract class IndexMultiValues extends IndexAbstract {

  public IndexMultiValues(@NonNull SchemaIndex schemaIndex, @NonNull AbstractStorage storage) {
    super(schemaIndex, storage);
  }

  @Override
  public Iterator<RID> getRidsIgnoreTx(DatabaseSessionEmbedded session, Object key) {
    final var collatedKey = getCollatingValue(key);
    Iterator<RID> backedStream;
    Iterator<RID> iterator;
    iterator = storage.getIndexValues(schemaIndex.getId(), collatedKey);
    backedStream = IndexStreamSecurityDecorator.decorateRidIterator(this, iterator, session);

    return backedStream;
  }

  @Override
  public Iterator<RID> getRids(DatabaseSessionEmbedded session, Object key) {
    final var collatedKey = getCollatingValue(key);
    var backedIterator = getRidsIgnoreTx(session, key);
    final var indexChanges =
        session.getTransactionInternal().getIndexChangesInternal(getName());
    if (indexChanges == null) {
      return backedIterator;
    }

    var txChanges = calculateTxValue(collatedKey, indexChanges);
    if (txChanges == null) {
      txChanges = Collections.emptySet();
    }
    return IndexStreamSecurityDecorator.decorateRidIterator(
        this,
        YTDBIteratorUtils.concat(
            YTDBIteratorUtils.map(
                YTDBIteratorUtils.filter(YTDBIteratorUtils.map(backedIterator,
                        rid -> calculateTxIndexEntry(collatedKey, rid, indexChanges)
                    ),
                    Objects::nonNull),
                RawPair::second),
            YTDBIteratorUtils.map(txChanges.iterator(), Identifiable::getIdentity)
        ), session);
  }

  @Override
  public IndexMultiValues put(FrontendTransaction transaction, Object key,
      final Identifiable singleValue) {
    final var rid = (RecordIdInternal) singleValue.getIdentity();

    if (!rid.isValidPosition()) {
      if (!(singleValue instanceof DBRecord)) {
        throw new IllegalArgumentException(
            "Cannot store non persistent RID as index value for key '" + key + "'");
      }
    }

    key = getCollatingValue(key);

    transaction.addIndexEntry(
        this, super.getName(), FrontendTransactionIndexChanges.OPERATION.PUT, key, singleValue);
    return this;
  }

  @Override
  public void doPut(DatabaseSessionInternal session, AbstractStorage storage,
      Object key,
      RID rid) {
    doPutV1(storage, schemaIndex.getId(), key, rid);
  }

  private static void doPutV1(
      AbstractStorage storage, int indexId, Object key, RID identity) {
    storage.putRidIndexEntry(indexId, key, identity);
  }

  @Override
  public void doRemove(DatabaseSessionInternal session, AbstractStorage storage,
      Object key, RID rid) {
    doRemoveV1(schemaIndex.getId(), storage, key, rid);
  }

  private static void doRemoveV1(
      int indexId, AbstractStorage storage, Object key, Identifiable value) {
    storage.removeRidIndexEntry(indexId, key, value.getIdentity());
  }

  @Override
  public Iterator<RawPair<Object, RID>> entriesBetween(
      DatabaseSessionEmbedded session, Object fromKey, boolean fromInclusive, Object toKey,
      boolean toInclusive, boolean ascOrder) {
    fromKey = getCollatingValue(fromKey);
    toKey = getCollatingValue(toKey);
    Iterator<RawPair<Object, RID>> iterator;
    iterator =
        IndexStreamSecurityDecorator.decorateIterator(
            this,
            storage.iterateIndexEntriesBetween(session,
                schemaIndex.getId(),
                fromKey,
                fromInclusive,
                toKey,
                toInclusive, ascOrder), session);

    final var indexChanges =
        session.getTransactionInternal().getIndexChangesInternal(getName());
    if (indexChanges == null) {
      return iterator;
    }

    final Iterator<RawPair<Object, RID>> txIterator;
    if (ascOrder) {
      txIterator = new PureTxMultiValueBetweenIndexForwardIterator(
          this, fromKey, fromInclusive, toKey, toInclusive, indexChanges);
    } else {
      txIterator = new PureTxMultiValueBetweenIndexBackwardIterator(
          this, fromKey, fromInclusive, toKey, toInclusive, indexChanges);
    }

    if (indexChanges.cleared) {
      return IndexStreamSecurityDecorator.decorateIterator(this, txIterator, session);
    }

    return IndexStreamSecurityDecorator.decorateIterator(
        this, mergeTxAndBackedIterators(indexChanges, txIterator, iterator, ascOrder), session);
  }

  @Override
  public Iterator<RawPair<Object, RID>> entriesMajor(
      DatabaseSessionEmbedded session, Object fromKey, boolean fromInclusive, boolean ascOrder) {
    fromKey = getCollatingValue(fromKey);
    Iterator<RawPair<Object, RID>> iterator;
    iterator =
        IndexStreamSecurityDecorator.decorateIterator(
            this,
            storage.iterateIndexEntriesMajor(schemaIndex.getId(), fromKey, fromInclusive, ascOrder),
            session);

    final var indexChanges =
        session.getTransactionInternal().getIndexChangesInternal(getName());
    if (indexChanges == null) {
      return iterator;
    }

    final Iterator<RawPair<Object, RID>> txIterator;

    final var lastKey = indexChanges.getLastKey();
    if (ascOrder) {
      txIterator =
          new PureTxMultiValueBetweenIndexForwardIterator(
              this, fromKey, fromInclusive, lastKey, true, indexChanges);
    } else {
      txIterator =
          new PureTxMultiValueBetweenIndexBackwardIterator(
              this, fromKey, fromInclusive, lastKey, true, indexChanges);
    }

    if (indexChanges.cleared) {
      return IndexStreamSecurityDecorator.decorateIterator(this, txIterator, session);
    }

    return IndexStreamSecurityDecorator.decorateIterator(
        this, mergeTxAndBackedIterators(indexChanges, txIterator, iterator, ascOrder), session);
  }

  @Override
  public Iterator<RawPair<Object, RID>> entriesMinor(
      DatabaseSessionEmbedded session, Object toKey, boolean toInclusive, boolean ascOrder) {
    toKey = getCollatingValue(toKey);
    Iterator<RawPair<Object, RID>> iterator;

    iterator =
        IndexStreamSecurityDecorator.decorateIterator(
            this,
            storage.iterateIndexEntriesMinor(
                schemaIndex.getId(), toKey, toInclusive, ascOrder),
            session);

    final var indexChanges =
        session.getTransactionInternal().getIndexChangesInternal(getName());
    if (indexChanges == null) {
      return iterator;
    }

    final Iterator<RawPair<Object, RID>> txIterator;

    final var firstKey = indexChanges.getFirstKey();
    if (ascOrder) {
      txIterator = new PureTxMultiValueBetweenIndexForwardIterator(
          this, firstKey, true, toKey, toInclusive, indexChanges);
    } else {
      txIterator = new PureTxMultiValueBetweenIndexBackwardIterator(
          this, firstKey, true, toKey, toInclusive, indexChanges);
    }

    if (indexChanges.cleared) {
      return IndexStreamSecurityDecorator.decorateIterator(this, txIterator, session);
    }

    return IndexStreamSecurityDecorator.decorateIterator(
        this, mergeTxAndBackedIterators(indexChanges, txIterator, iterator, ascOrder), session);
  }

  @Override
  public Iterator<RawPair<Object, RID>> entries(DatabaseSessionEmbedded session,
      Collection<?> keys, boolean ascSortOrder) {
    final List<Object> sortedKeys = new ArrayList<>(keys);
    final Comparator<Object> comparator;
    if (ascSortOrder) {
      comparator = DefaultComparator.INSTANCE;
    } else {
      comparator = Collections.reverseOrder(DefaultComparator.INSTANCE);
    }

    sortedKeys.sort(comparator);

    var iterator =
        IndexStreamSecurityDecorator.decorateIterator(
            this,
            YTDBIteratorUtils.flatMap(sortedKeys.iterator(), this::iteratorForKey),
            session);

    final var indexChanges = session.getTransactionInternal()
        .getIndexChangesInternal(getName());
    if (indexChanges == null) {
      return iterator;
    }

    Comparator<RawPair<Object, RID>> keyComparator;
    if (ascSortOrder) {
      keyComparator = AscComparator.INSTANCE;
    } else {
      keyComparator = DescComparator.INSTANCE;
    }

    final var txList =
        YTDBIteratorUtils.list(YTDBIteratorUtils.filter(
            YTDBIteratorUtils.flatMap(keys.iterator(),
                key -> txIteratorForKey(indexChanges, key)),
            Objects::nonNull));
    txList.sort(keyComparator);

    if (indexChanges.cleared) {
      return IndexStreamSecurityDecorator.decorateIterator(this, txList.iterator(), session);
    }

    return IndexStreamSecurityDecorator.decorateIterator(
        this, mergeTxAndBackedIterators(indexChanges, txList.iterator(), iterator, ascSortOrder),
        session);
  }

  @Nullable
  private Iterator<RawPair<Object, RID>> txIteratorForKey(
      final FrontendTransactionIndexChanges indexChanges, Object key) {
    final var result = calculateTxValue(getCollatingValue(key), indexChanges);
    if (result != null) {
      return YTDBIteratorUtils.map(result.iterator(),
          rid -> new RawPair<>(getCollatingValue(key), rid.getIdentity()));
    }
    return null;
  }

  private Iterator<RawPair<Object, RID>> iteratorForKey(Object key) {
    final var entryKey = getCollatingValue(key);
    return YTDBIteratorUtils.map(
        storage.getIndexValues(schemaIndex.getId(), entryKey),
        rid -> new RawPair<>(entryKey, rid));

  }

  @Nullable
  public static Set<Identifiable> calculateTxValue(
      final Object key, FrontendTransactionIndexChanges indexChanges) {
    final List<Identifiable> result = new ArrayList<>();
    final var changesPerKey = indexChanges.getChangesPerKey(key);
    if (changesPerKey.isEmpty()) {
      return null;
    }

    for (var entry : changesPerKey.getEntriesAsList()) {
      if (entry.getOperation() == OPERATION.REMOVE) {
        if (entry.getValue() == null) {
          result.clear();
        } else {
          result.remove(entry.getValue());
        }
      } else {
        result.add(entry.getValue());
      }
    }

    if (result.isEmpty()) {
      return null;
    }

    return new HashSet<>(result);
  }

  @Override
  public long size(DatabaseSessionEmbedded session) {
    var tot = storage.getIndexSize(schemaIndex.getId());
    final var indexChanges =
        session.getTransactionInternal().getIndexChanges(getName());

    if (indexChanges != null) {
      return YTDBIteratorUtils.count(ascEntries(session));
    }

    return tot;
  }

  @Override
  public Iterator<RawPair<Object, RID>> ascEntries(DatabaseSessionEmbedded session) {
    Iterator<RawPair<Object, RID>> iterator;
    iterator =
        IndexStreamSecurityDecorator.decorateIterator(
            this, storage.getIndexIterator(schemaIndex.getId()),
            session);

    final var indexChanges =
        session.getTransactionInternal().getIndexChangesInternal(getName());
    if (indexChanges == null) {
      return iterator;
    }

    final var txIterator =
        new PureTxMultiValueBetweenIndexForwardIterator(
            this, null, true, null, true, indexChanges);

    if (indexChanges.cleared) {
      return IndexStreamSecurityDecorator.decorateIterator(this, txIterator, session);
    }

    return IndexStreamSecurityDecorator.decorateIterator(
        this, mergeTxAndBackedIterators(indexChanges, txIterator, iterator, true), session);
  }

  private Iterator<RawPair<Object, RID>> mergeTxAndBackedIterators(
      FrontendTransactionIndexChanges indexChanges,
      Iterator<RawPair<Object, RID>> txIterator,
      Iterator<RawPair<Object, RID>> backedIterator,
      boolean ascOrder) {
    Comparator<RawPair<Object, RID>> keyComparator;
    if (ascOrder) {
      keyComparator = AscComparator.INSTANCE;
    } else {
      keyComparator = DescComparator.INSTANCE;
    }
    return YTDBIteratorUtils.mergeSortedIterators(
        txIterator,
        YTDBIteratorUtils.filter(YTDBIteratorUtils.map(backedIterator,
            entry -> calculateTxIndexEntry(entry.first(), entry.second(), indexChanges)
        ), Objects::nonNull),
        keyComparator);
  }

  @Nullable
  private RawPair<Object, RID> calculateTxIndexEntry(
      Object key, final RID backendValue, FrontendTransactionIndexChanges indexChanges) {
    key = getCollatingValue(key);
    final var changesPerKey = indexChanges.getChangesPerKey(key);
    if (changesPerKey.isEmpty()) {
      return new RawPair<>(key, backendValue);
    }

    var putCounter = 1;
    for (var entry : changesPerKey.getEntriesAsList()) {
      if (entry.getOperation() == OPERATION.PUT && entry.getValue().equals(backendValue)) {
        putCounter++;
      } else if (entry.getOperation() == OPERATION.REMOVE) {
        if (entry.getValue() == null) {
          putCounter = 0;
        } else if (entry.getValue().equals(backendValue) && putCounter > 0) {
          putCounter--;
        }
      }
    }

    if (putCounter <= 0) {
      return null;
    }

    return new RawPair<>(key, backendValue);
  }

  @Override
  public Iterator<RawPair<Object, RID>> descEntries(DatabaseSessionEmbedded session) {
    Iterator<RawPair<Object, RID>> iterator;
    iterator =
        IndexStreamSecurityDecorator.decorateIterator(
            this, storage.getIndexDescIterator(schemaIndex.getId()),
            session);

    final var indexChanges =
        session.getTransactionInternal().getIndexChangesInternal(getName());
    if (indexChanges == null) {
      return iterator;
    }

    final var txIterator =
        new PureTxMultiValueBetweenIndexBackwardIterator(
            this, null, true, null, true, indexChanges);

    if (indexChanges.cleared) {
      return IndexStreamSecurityDecorator.decorateIterator(this, txIterator, session);
    }

    return IndexStreamSecurityDecorator.decorateIterator(
        this, mergeTxAndBackedIterators(indexChanges, txIterator, iterator, false), session);
  }
}
