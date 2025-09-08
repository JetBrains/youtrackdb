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
import com.jetbrains.youtrackdb.internal.common.comparator.DefaultComparator;
import com.jetbrains.youtrackdb.internal.common.stream.Streams;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.exception.InvalidIndexEngineIdException;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.index.comparator.AscComparator;
import com.jetbrains.youtrackdb.internal.core.index.comparator.DescComparator;
import com.jetbrains.youtrackdb.internal.core.index.iterator.PureTxMultiValueBetweenIndexBackwardSplititerator;
import com.jetbrains.youtrackdb.internal.core.index.iterator.PureTxMultiValueBetweenIndexForwardSpliterator;
import com.jetbrains.youtrackdb.internal.core.index.multivalue.MultiValuesTransformer;
import com.jetbrains.youtrackdb.internal.core.storage.Storage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransaction;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionIndexChanges;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionIndexChanges.OPERATION;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Abstract index implementation that supports multi-values for the same key.
 */
public abstract class IndexMultiValues extends IndexAbstract {

  public IndexMultiValues(@Nullable RID identity, @Nonnull FrontendTransaction transaction,
      @Nonnull Storage storage) {
    super(identity, transaction, storage);
  }

  public IndexMultiValues(@Nonnull Storage storage) {
    super(storage);
  }

  @Deprecated
  @Override
  public Collection<RID> get(DatabaseSessionEmbedded session, Object key) {
    final List<RID> rids;
    try (var stream = getRids(session, key)) {
      rids = stream.collect(Collectors.toList());
    }
    return rids;
  }

  @Override
  public Stream<RID> getRidsIgnoreTx(DatabaseSessionEmbedded session, Object key) {
    final var collatedKey = getCollatingValue(key);
    Stream<RID> backedStream;
    acquireSharedLock();
    try {
      Stream<RID> stream;
      while (true) {
        try {
          stream = storage.getIndexValues(indexId, collatedKey);
          backedStream = IndexStreamSecurityDecorator.decorateRidStream(this, stream, session);
          break;
        } catch (InvalidIndexEngineIdException ignore) {
          doReloadIndexEngine();
        }
      }
    } finally {
      releaseSharedLock();
    }
    return backedStream;
  }

  @Override
  public Stream<RID> getRids(DatabaseSessionEmbedded session, Object key) {
    final var collatedKey = getCollatingValue(key);
    var backedStream = getRidsIgnoreTx(session, key);
    final var indexChanges =
        session.getTransactionInternal().getIndexChangesInternal(getName());
    if (indexChanges == null) {
      return backedStream;
    }
    var txChanges = calculateTxValue(collatedKey, indexChanges);
    if (txChanges == null) {
      txChanges = Collections.emptySet();
    }
    return IndexStreamSecurityDecorator.decorateRidStream(
        this,
        Stream.concat(
            backedStream
                .map((rid) -> calculateTxIndexEntry(collatedKey, rid, indexChanges))
                .filter(Objects::nonNull)
                .map(RawPair::second),
            txChanges.stream().map(Identifiable::getIdentity)), session);
  }

  @Override
  public IndexMultiValues put(FrontendTransaction transaction, Object key,
      final Identifiable singleValue) {
    final var rid = (RecordIdInternal) singleValue.getIdentity();

    if (!rid.isValidPosition()) {
      if (singleValue instanceof DBRecord) {
        // EARLY SAVE IT
      } else {
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
      RID rid)
      throws InvalidIndexEngineIdException {
    doPutV1(storage, indexId, key, rid);
  }

  private static void doPutV1(
      AbstractStorage storage, int indexId, Object key, RID identity)
      throws InvalidIndexEngineIdException {
    storage.putRidIndexEntry(indexId, key, identity);
  }

  @Override
  public boolean doRemove(DatabaseSessionInternal session, AbstractStorage storage,
      Object key, RID rid)
      throws InvalidIndexEngineIdException {
    return doRemoveV1(indexId, storage, key, rid);
  }

  private static boolean doRemoveV1(
      int indexId, AbstractStorage storage, Object key, Identifiable value)
      throws InvalidIndexEngineIdException {
    return storage.removeRidIndexEntry(indexId, key, value.getIdentity());
  }

  @Override
  public Stream<RawPair<Object, RID>> streamEntriesBetween(
      DatabaseSessionEmbedded session, Object fromKey, boolean fromInclusive, Object toKey,
      boolean toInclusive, boolean ascOrder) {
    fromKey = getCollatingValue(fromKey);
    toKey = getCollatingValue(toKey);
    Stream<RawPair<Object, RID>> stream;
    acquireSharedLock();
    try {
      while (true) {
        try {
          stream =
              IndexStreamSecurityDecorator.decorateStream(
                  this,
                  storage.iterateIndexEntriesBetween(session,
                      indexId,
                      fromKey,
                      fromInclusive,
                      toKey,
                      toInclusive, ascOrder, MultiValuesTransformer.INSTANCE), session);
          break;
        } catch (InvalidIndexEngineIdException ignore) {
          doReloadIndexEngine();
        }
      }
    } finally {
      releaseSharedLock();
    }

    final var indexChanges =
        session.getTransactionInternal().getIndexChangesInternal(getName());
    if (indexChanges == null) {
      return stream;
    }

    final Stream<RawPair<Object, RID>> txStream;
    if (ascOrder) {
      txStream =
          StreamSupport.stream(
              new PureTxMultiValueBetweenIndexForwardSpliterator(
                  this, fromKey, fromInclusive, toKey, toInclusive, indexChanges),
              false);
    } else {
      txStream =
          StreamSupport.stream(
              new PureTxMultiValueBetweenIndexBackwardSplititerator(
                  this, fromKey, fromInclusive, toKey, toInclusive, indexChanges),
              false);
    }

    if (indexChanges.cleared) {
      return IndexStreamSecurityDecorator.decorateStream(this, txStream, session);
    }

    return IndexStreamSecurityDecorator.decorateStream(
        this, mergeTxAndBackedStreams(indexChanges, txStream, stream, ascOrder), session);
  }

  @Override
  public Stream<RawPair<Object, RID>> streamEntriesMajor(
      DatabaseSessionEmbedded session, Object fromKey, boolean fromInclusive, boolean ascOrder) {
    fromKey = getCollatingValue(fromKey);
    Stream<RawPair<Object, RID>> stream;
    acquireSharedLock();
    try {
      while (true) {
        try {
          stream =
              IndexStreamSecurityDecorator.decorateStream(
                  this,
                  storage.iterateIndexEntriesMajor(
                      indexId, fromKey, fromInclusive, ascOrder, MultiValuesTransformer.INSTANCE),
                  session);
          break;
        } catch (InvalidIndexEngineIdException ignore) {
          doReloadIndexEngine();
        }
      }
    } finally {
      releaseSharedLock();
    }

    final var indexChanges =
        session.getTransactionInternal().getIndexChangesInternal(getName());
    if (indexChanges == null) {
      return stream;
    }

    final Stream<RawPair<Object, RID>> txStream;

    final var lastKey = indexChanges.getLastKey();
    if (ascOrder) {
      txStream =
          StreamSupport.stream(
              new PureTxMultiValueBetweenIndexForwardSpliterator(
                  this, fromKey, fromInclusive, lastKey, true, indexChanges),
              false);
    } else {
      txStream =
          StreamSupport.stream(
              new PureTxMultiValueBetweenIndexBackwardSplititerator(
                  this, fromKey, fromInclusive, lastKey, true, indexChanges),
              false);
    }

    if (indexChanges.cleared) {
      return IndexStreamSecurityDecorator.decorateStream(this, txStream, session);
    }

    return IndexStreamSecurityDecorator.decorateStream(
        this, mergeTxAndBackedStreams(indexChanges, txStream, stream, ascOrder), session);
  }

  @Override
  public Stream<RawPair<Object, RID>> streamEntriesMinor(
      DatabaseSessionEmbedded session, Object toKey, boolean toInclusive, boolean ascOrder) {
    toKey = getCollatingValue(toKey);
    Stream<RawPair<Object, RID>> stream;

    acquireSharedLock();
    try {
      while (true) {
        try {
          stream =
              IndexStreamSecurityDecorator.decorateStream(
                  this,
                  storage.iterateIndexEntriesMinor(
                      indexId, toKey, toInclusive, ascOrder, MultiValuesTransformer.INSTANCE),
                  session);
          break;
        } catch (InvalidIndexEngineIdException ignore) {
          doReloadIndexEngine();
        }
      }
    } finally {
      releaseSharedLock();
    }

    final var indexChanges =
        session.getTransactionInternal().getIndexChangesInternal(getName());
    if (indexChanges == null) {
      return stream;
    }

    final Stream<RawPair<Object, RID>> txStream;

    final var firstKey = indexChanges.getFirstKey();
    if (ascOrder) {
      txStream =
          StreamSupport.stream(
              new PureTxMultiValueBetweenIndexForwardSpliterator(
                  this, firstKey, true, toKey, toInclusive, indexChanges),
              false);
    } else {
      txStream =
          StreamSupport.stream(
              new PureTxMultiValueBetweenIndexBackwardSplititerator(
                  this, firstKey, true, toKey, toInclusive, indexChanges),
              false);
    }

    if (indexChanges.cleared) {
      return IndexStreamSecurityDecorator.decorateStream(this, txStream, session);
    }

    return IndexStreamSecurityDecorator.decorateStream(
        this, mergeTxAndBackedStreams(indexChanges, txStream, stream, ascOrder), session);
  }

  @Override
  public Stream<RawPair<Object, RID>> streamEntries(DatabaseSessionEmbedded session,
      Collection<?> keys, boolean ascSortOrder) {
    final List<Object> sortedKeys = new ArrayList<>(keys);
    final Comparator<Object> comparator;
    if (ascSortOrder) {
      comparator = DefaultComparator.INSTANCE;
    } else {
      comparator = Collections.reverseOrder(DefaultComparator.INSTANCE);
    }

    sortedKeys.sort(comparator);

    var stream =
        IndexStreamSecurityDecorator.decorateStream(
            this, sortedKeys.stream().flatMap(key1 -> streamForKey(session, key1)), session);

    final var indexChanges = session.getTransactionInternal()
        .getIndexChangesInternal(getName());
    if (indexChanges == null) {
      return stream;
    }

    Comparator<RawPair<Object, RID>> keyComparator;
    if (ascSortOrder) {
      keyComparator = AscComparator.INSTANCE;
    } else {
      keyComparator = DescComparator.INSTANCE;
    }

    final var txStream =
        keys.stream()
            .flatMap((key) -> txStramForKey(indexChanges, key))
            .filter(Objects::nonNull)
            .sorted(keyComparator);

    if (indexChanges.cleared) {
      return IndexStreamSecurityDecorator.decorateStream(this, txStream, session);
    }

    return IndexStreamSecurityDecorator.decorateStream(
        this, mergeTxAndBackedStreams(indexChanges, txStream, stream, ascSortOrder), session);
  }

  @Nullable
  private Stream<RawPair<Object, RID>> txStramForKey(
      final FrontendTransactionIndexChanges indexChanges, Object key) {
    final var result = calculateTxValue(getCollatingValue(key), indexChanges);
    if (result != null) {
      return result.stream()
          .map((rid) -> new RawPair<>(getCollatingValue(key), rid.getIdentity()));
    }
    return null;
  }

  private Stream<RawPair<Object, RID>> streamForKey(DatabaseSessionInternal db,
      Object key) {
    key = getCollatingValue(key);

    final var entryKey = key;
    acquireSharedLock();
    try {
      while (true) {
        try {
          return storage.getIndexValues(indexId, key).map((rid) -> new RawPair<>(entryKey, rid));
        } catch (InvalidIndexEngineIdException ignore) {
          doReloadIndexEngine();
        }
      }

    } finally {
      releaseSharedLock();
    }
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
    acquireSharedLock();
    long tot;
    try {
      while (true) {
        try {
          tot = storage.getIndexSize(indexId, MultiValuesTransformer.INSTANCE);
          break;
        } catch (InvalidIndexEngineIdException ignore) {
          doReloadIndexEngine();
        }
      }
    } finally {
      releaseSharedLock();
    }

    final var indexChanges =
        session.getTransactionInternal().getIndexChanges(getName());
    if (indexChanges != null) {
      try (var stream = stream(session)) {
        return stream.count();
      }
    }

    return tot;
  }

  @Override
  public Stream<RawPair<Object, RID>> stream(DatabaseSessionEmbedded session) {
    Stream<RawPair<Object, RID>> stream;
    acquireSharedLock();
    try {
      while (true) {
        try {
          stream =
              IndexStreamSecurityDecorator.decorateStream(
                  this, storage.getIndexStream(indexId, MultiValuesTransformer.INSTANCE), session);
          break;
        } catch (InvalidIndexEngineIdException ignore) {
          doReloadIndexEngine();
        }
      }

    } finally {
      releaseSharedLock();
    }

    final var indexChanges =
        session.getTransactionInternal().getIndexChangesInternal(getName());
    if (indexChanges == null) {
      return stream;
    }

    final var txStream =
        StreamSupport.stream(
            new PureTxMultiValueBetweenIndexForwardSpliterator(
                this, null, true, null, true, indexChanges),
            false);

    if (indexChanges.cleared) {
      return IndexStreamSecurityDecorator.decorateStream(this, txStream, session);
    }

    return IndexStreamSecurityDecorator.decorateStream(
        this, mergeTxAndBackedStreams(indexChanges, txStream, stream, true), session);
  }

  private Stream<RawPair<Object, RID>> mergeTxAndBackedStreams(
      FrontendTransactionIndexChanges indexChanges,
      Stream<RawPair<Object, RID>> txStream,
      Stream<RawPair<Object, RID>> backedStream,
      boolean ascOrder) {
    Comparator<RawPair<Object, RID>> keyComparator;
    if (ascOrder) {
      keyComparator = AscComparator.INSTANCE;
    } else {
      keyComparator = DescComparator.INSTANCE;
    }
    return Streams.mergeSortedSpliterators(
        txStream,
        backedStream
            .map((entry) -> calculateTxIndexEntry(entry.first(), entry.second(), indexChanges))
            .filter(Objects::nonNull),
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
  public Stream<RawPair<Object, RID>> descStream(DatabaseSessionEmbedded session) {
    Stream<RawPair<Object, RID>> stream;
    acquireSharedLock();
    try {
      while (true) {
        try {
          stream =
              IndexStreamSecurityDecorator.decorateStream(
                  this, storage.getIndexDescStream(indexId, MultiValuesTransformer.INSTANCE),
                  session);
          break;
        } catch (InvalidIndexEngineIdException ignore) {
          doReloadIndexEngine();
        }
      }
    } finally {
      releaseSharedLock();
    }

    final var indexChanges =
        session.getTransactionInternal().getIndexChangesInternal(getName());
    if (indexChanges == null) {
      return stream;
    }

    final var txStream =
        StreamSupport.stream(
            new PureTxMultiValueBetweenIndexBackwardSplititerator(
                this, null, true, null, true, indexChanges),
            false);

    if (indexChanges.cleared) {
      return IndexStreamSecurityDecorator.decorateStream(this, txStream, session);
    }

    return IndexStreamSecurityDecorator.decorateStream(
        this, mergeTxAndBackedStreams(indexChanges, txStream, stream, false), session);
  }
}
