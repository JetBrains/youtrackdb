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
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.index.comparator.AscComparator;
import com.jetbrains.youtrackdb.internal.core.index.comparator.DescComparator;
import com.jetbrains.youtrackdb.internal.core.index.iterator.PureTxBetweenIndexBackwardIterator;
import com.jetbrains.youtrackdb.internal.core.index.iterator.PureTxBetweenIndexForwardIterator;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaIndex;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransaction;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionIndexChanges;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionIndexChanges.OPERATION;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;
import org.apache.commons.collections4.IteratorUtils;
import org.jspecify.annotations.NonNull;

/**
 * Abstract Index implementation that allows only one value for a key.
 */
public abstract class IndexOneValue extends IndexAbstract {

  public IndexOneValue(@NonNull SchemaIndex schemaIndex,
      @NonNull AbstractStorage storage) {
    super(schemaIndex, storage);
  }

  @Override
  public Iterator<RID> getRidsIgnoreTx(DatabaseSessionEmbedded session, Object key) {
    key = getCollatingValue(key);

    var iterator = storage.getIndexValues(schemaIndex.getId(), key);
    iterator = IndexStreamSecurityDecorator.decorateRidIterator(this, iterator, session);

    return iterator;
  }

  @Override
  public Iterator<RID> getRids(DatabaseSessionEmbedded session, Object key) {
    key = getCollatingValue(key);

    var iterator = getRidsIgnoreTx(session, key);

    final var indexChanges =
        session.getTransactionInternal().getIndexChangesInternal(getName());
    if (indexChanges == null) {
      return iterator;
    }

    RID rid;
    if (!indexChanges.cleared) {
      // BEGIN FROM THE UNDERLYING RESULT SET
      //noinspection resource
      if (iterator.hasNext()) {
        rid = iterator.next();
      } else {
        rid = null;
      }
    } else {
      rid = null;
    }

    final var txIndexEntry = calculateTxIndexEntry(key, rid, indexChanges);
    if (txIndexEntry == null) {
      return IteratorUtils.emptyIterator();
    }

    return IndexStreamSecurityDecorator.decorateRidIterator(this,
        IteratorUtils.singletonIterator(txIndexEntry.second()),
        session);
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

    //noinspection resource
    var iterator =
        IndexStreamSecurityDecorator.decorateIterator(
            this,
            YTDBIteratorUtils.filter(
                YTDBIteratorUtils.flatMap(sortedKeys.iterator(),
                    key -> {
                      final var collatedKey = getCollatingValue(key);
                      return YTDBIteratorUtils.map(storage
                              .getIndexValues(schemaIndex.getId(), collatedKey),
                          rid -> new RawPair<>(collatedKey, rid));

                    }),
                Objects::nonNull), session);
    final var indexChanges =
        session.getTransactionInternal().getIndexChangesInternal(getName());
    if (indexChanges == null) {
      return iterator;
    }
    Comparator<RawPair<Object, RID>> keyComparator;
    if (ascSortOrder) {
      keyComparator = AscComparator.INSTANCE;
    } else {
      keyComparator = DescComparator.INSTANCE;
    }

    @SuppressWarnings("resource") final var txResult =
        YTDBIteratorUtils.list(
            YTDBIteratorUtils.filter(
                YTDBIteratorUtils.map(keys.iterator(),
                    key -> calculateTxIndexEntry(getCollatingValue(key), null, indexChanges)),
                Objects::nonNull)
        );
    txResult.sort(keyComparator);
    var txIterator = txResult.iterator();

    if (indexChanges.cleared) {
      return IndexStreamSecurityDecorator.decorateIterator(this, txIterator, session);
    }

    return IndexStreamSecurityDecorator.decorateIterator(
        this, mergeTxAndBackedIterators(indexChanges, txIterator, iterator, ascSortOrder), session);
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
                schemaIndex.getId(), fromKey, fromInclusive, toKey, toInclusive, ascOrder),
            session);

    final var indexChanges =
        session.getTransactionInternal().getIndexChangesInternal(getName());
    if (indexChanges == null) {
      return iterator;
    }

    final Iterator<RawPair<Object, RID>> txIterator;
    if (ascOrder) {
      //noinspection resource
      txIterator =
          new PureTxBetweenIndexForwardIterator(
              this, fromKey, fromInclusive, toKey, toInclusive, indexChanges);
    } else {
      //noinspection resource
      txIterator =
          new PureTxBetweenIndexBackwardIterator(
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
            storage.iterateIndexEntriesMajor(
                schemaIndex.getId(), fromKey, fromInclusive, ascOrder), session);

    final var indexChanges =
        session.getTransactionInternal().getIndexChangesInternal(getName());
    if (indexChanges == null) {
      return iterator;
    }

    fromKey = getCollatingValue(fromKey);

    final Iterator<RawPair<Object, RID>> txIterator;

    final var lastKey = indexChanges.getLastKey();
    if (ascOrder) {
      txIterator =
          new PureTxBetweenIndexForwardIterator(this, fromKey, fromInclusive, lastKey, true,
              indexChanges);
    } else {
      txIterator =
          new PureTxBetweenIndexBackwardIterator(
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
            storage.iterateIndexEntriesMinor(schemaIndex.getId(), toKey, toInclusive, ascOrder
            ),
            session);

    final var indexChanges =
        session.getTransactionInternal().getIndexChangesInternal(getName());
    if (indexChanges == null) {
      return iterator;
    }

    toKey = getCollatingValue(toKey);

    final Iterator<RawPair<Object, RID>> txIterator;

    final var firstKey = indexChanges.getFirstKey();
    if (ascOrder) {
      txIterator =

          new PureTxBetweenIndexForwardIterator(
              this, firstKey, true, toKey, toInclusive, indexChanges);
    } else {
      txIterator =
          new PureTxBetweenIndexBackwardIterator(
              this, firstKey, true, toKey, toInclusive, indexChanges);
    }

    if (indexChanges.cleared) {
      return IndexStreamSecurityDecorator.decorateIterator(this, txIterator, session);
    }

    return IndexStreamSecurityDecorator.decorateIterator(
        this, mergeTxAndBackedIterators(indexChanges, txIterator, iterator, ascOrder), session);
  }

  @Override
  public long size(DatabaseSessionEmbedded session) {
    return storage.getIndexSize(schemaIndex.getId());
  }

  @Override
  public Iterator<RawPair<Object, RID>> ascEntries(DatabaseSessionEmbedded session) {
    Iterator<RawPair<Object, RID>> iterator;
    iterator =
        IndexStreamSecurityDecorator.decorateIterator(
            this, storage.getIndexIterator(schemaIndex.getId()), session);

    final var indexChanges =
        session.getTransactionInternal().getIndexChangesInternal(getName());
    if (indexChanges == null) {
      return iterator;
    }

    final var txStream =
        new PureTxBetweenIndexForwardIterator(this, null, true, null, true, indexChanges);
    if (indexChanges.cleared) {
      return IndexStreamSecurityDecorator.decorateIterator(this, txStream, session);
    }

    return IndexStreamSecurityDecorator.decorateIterator(
        this, mergeTxAndBackedIterators(indexChanges, txStream, iterator, true), session);
  }

  @Override
  public Iterator<RawPair<Object, RID>> descEntries(DatabaseSessionEmbedded session) {
    Iterator<RawPair<Object, RID>> iterator;
    iterator =
        IndexStreamSecurityDecorator.decorateIterator(
            this, storage.getIndexDescIterator(schemaIndex.getId()), session);
    final var indexChanges =
        session.getTransactionInternal().getIndexChangesInternal(getName());
    if (indexChanges == null) {
      return iterator;
    }

    final var txIterator =
        new PureTxBetweenIndexBackwardIterator(this,
            null, true, null, true, indexChanges);
    if (indexChanges.cleared) {
      return IndexStreamSecurityDecorator.decorateIterator(this, txIterator, session);
    }

    return IndexStreamSecurityDecorator.decorateIterator(
        this, mergeTxAndBackedIterators(indexChanges, txIterator, iterator, false), session);
  }

  @Override
  public boolean isUnique() {
    return true;
  }

  @Nullable
  public RawPair<Object, RID> calculateTxIndexEntry(
      Object key, final RID backendValue, final FrontendTransactionIndexChanges indexChanges) {
    key = getCollatingValue(key);
    var result = backendValue;
    final var changesPerKey = indexChanges.getChangesPerKey(key);
    if (changesPerKey.isEmpty()) {
      if (backendValue == null) {
        return null;
      } else {
        return new RawPair<>(key, backendValue);
      }
    }

    for (var entry : changesPerKey.getEntriesAsList()) {
      if (entry.getOperation() == OPERATION.REMOVE) {
        result = null;
      } else if (entry.getOperation() == OPERATION.PUT) {
        result = entry.getValue().getIdentity();
      }
    }

    if (result == null) {
      return null;
    }

    return new RawPair<>(key, result);
  }

  private Iterator<RawPair<Object, RID>> mergeTxAndBackedIterators(
      FrontendTransactionIndexChanges indexChanges,
      Iterator<RawPair<Object, RID>> txIterator,
      Iterator<RawPair<Object, RID>> backedIterator,
      boolean ascSortOrder) {
    Comparator<RawPair<Object, RID>> comparator;
    if (ascSortOrder) {
      comparator = AscComparator.INSTANCE;
    } else {
      comparator = DescComparator.INSTANCE;
    }

    return YTDBIteratorUtils.mergeSortedIterators(
        txIterator,
        YTDBIteratorUtils.filter(
            YTDBIteratorUtils.map(backedIterator,
                entry ->
                    calculateTxIndexEntry(
                        getCollatingValue(entry.first()), entry.second(), indexChanges))
            , Objects::nonNull),
        comparator);
  }

  @Override
  public IndexOneValue put(FrontendTransaction transaction, Object key,
      final Identifiable value) {
    final var rid = (RecordIdInternal) value.getIdentity();

    if (!rid.isValidPosition()) {
      if (!(value instanceof DBRecord)) {
        throw new IllegalArgumentException(
            "Cannot store non persistent RID as index value for key '" + key + "'");
      }
    }
    key = getCollatingValue(key);

    transaction.addIndexEntry(
        this, super.getName(), FrontendTransactionIndexChanges.OPERATION.PUT, key,
        value.getIdentity());
    return this;
  }
}
