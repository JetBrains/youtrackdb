/*
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.jetbrains.youtrack.db.internal.lucene.index;

import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.common.listener.ProgressListener;
import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.exception.InvalidIndexEngineIdException;
import com.jetbrains.youtrack.db.internal.core.index.CompositeKey;
import com.jetbrains.youtrack.db.internal.core.index.Index;
import com.jetbrains.youtrack.db.internal.core.index.IndexAbstract;
import com.jetbrains.youtrack.db.internal.core.index.IndexException;
import com.jetbrains.youtrack.db.internal.core.index.IndexMetadata;
import com.jetbrains.youtrack.db.internal.core.index.IndexStreamSecurityDecorator;
import com.jetbrains.youtrack.db.internal.core.storage.Storage;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransaction;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransactionIndexChanges;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransactionIndexChangesPerKey;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransactionIndexChangesPerKey.TransactionIndexEntry;
import com.jetbrains.youtrack.db.internal.lucene.OLuceneIndex;
import com.jetbrains.youtrack.db.internal.lucene.engine.LuceneIndexEngine;
import com.jetbrains.youtrack.db.internal.lucene.tx.LuceneTxChanges;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.search.IndexSearcher;

public class LuceneIndexNotUnique extends IndexAbstract implements OLuceneIndex {


  public LuceneIndexNotUnique(@Nullable RID identity, @Nonnull FrontendTransaction transaction,
      @Nonnull Storage storage) {
    super(identity, transaction, storage);
  }

  public LuceneIndexNotUnique(@Nonnull Storage storage) {
    super(storage);
  }

  @Override
  public Index create(FrontendTransaction transaction, IndexMetadata indexMetadata) {
    var metadata = indexMetadata.getMetadata();

    if (metadata == null || !metadata.containsKey("analyzer")) {
      HashMap<String, Object> met;
      if (metadata != null) {
        met = new HashMap<>(metadata);
      } else {
        met = new HashMap<>();
      }

      met.put("analyzer", StandardAnalyzer.class.getName());
      indexMetadata.setMetadata(met);
    }

    return super.create(transaction, indexMetadata);
  }

  @Override
  public long rebuild(DatabaseSessionInternal session, ProgressListener progressListener) {
    return super.rebuild(session, progressListener);
  }

  @Override
  public boolean remove(FrontendTransaction transaction, final Object key,
      final Identifiable rid) {

    if (key != null) {
      transaction.addIndexEntry(
          this, super.getName(), FrontendTransactionIndexChanges.OPERATION.REMOVE, encodeKey(key),
          rid);
      var transactionChanges = getTransactionChanges(transaction);
      transactionChanges.remove(transaction.getDatabaseSession(), key, rid);
      return true;
    }
    return true;
  }

  @Override
  public boolean remove(FrontendTransaction transaction, final Object key) {
    if (key != null) {
      transaction.addIndexEntry(
          this, super.getName(), FrontendTransactionIndexChanges.OPERATION.REMOVE, encodeKey(key),
          null);
      var transactionChanges = getTransactionChanges(transaction);
      transactionChanges.remove(transaction.getDatabaseSession(), key, null);
      return true;
    }
    return true;
  }

  @Override
  public Iterable<TransactionIndexEntry> interpretTxKeyChanges(
      FrontendTransactionIndexChangesPerKey changes) {
    return changes.interpret(FrontendTransactionIndexChangesPerKey.Interpretation.NonUnique);
  }

  @Override
  public void doPut(DatabaseSessionInternal session, AbstractStorage storage,
      Object key,
      RID rid) {
    while (true) {
      try {
        storage.callIndexEngine(
            false,
            indexId,
            engine -> {
              try {
                var indexEngine = (LuceneIndexEngine) engine;

                var atomicOperation =
                    storage.getAtomicOperationsManager().getCurrentOperation();
                indexEngine.put(session, atomicOperation, decodeKey(key, session), rid);
                return null;
              } catch (IOException e) {
                throw BaseException.wrapException(
                    new IndexException(session, "Error during commit of index changes"), e,
                    session);
              }
            });
        break;
      } catch (InvalidIndexEngineIdException e) {
        doReloadIndexEngine();
      }
    }
  }

  @Override
  public boolean doRemove(AbstractStorage storage, Object key,
      DatabaseSessionInternal session) {
    while (true) {
      try {
        storage.callIndexEngine(
            false,
            indexId,
            engine -> {
              var indexEngine = (LuceneIndexEngine) engine;
              indexEngine.remove(storage, decodeKey(key, session));
              return true;
            });
        break;
      } catch (InvalidIndexEngineIdException e) {
        doReloadIndexEngine();
      }
    }
    return false;
  }

  @Override
  public boolean doRemove(DatabaseSessionInternal session, AbstractStorage storage,
      Object key, RID rid)
      throws InvalidIndexEngineIdException {
    while (true) {
      try {
        storage.callIndexEngine(
            false,
            indexId,
            engine -> {
              var indexEngine = (LuceneIndexEngine) engine;
              indexEngine.remove(storage, decodeKey(key, session), rid);
              return true;
            });
        break;
      } catch (InvalidIndexEngineIdException e) {
        doReloadIndexEngine();
      }
    }
    return false;
  }

  @Override
  public Object getCollatingValue(Object key) {
    return key;
  }

  public void doDelete(FrontendTransaction transaction) {
    while (true) {
      try {
        storage.deleteIndexEngine(indexId);
        break;
      } catch (InvalidIndexEngineIdException ignore) {
        doReloadIndexEngine();
      }
    }
  }

  @Override
  protected void clearAllEntries(DatabaseSessionInternal session) {
    session.executeInTxInternal(this::doDelete);
  }

  protected Object decodeKey(Object key, DatabaseSessionInternal session) {
    return key;
  }

  @Override
  protected void onIndexEngineChange(DatabaseSessionInternal session, int indexId) {
    while (true) {
      try {
        storage.callIndexEngine(
            false,
            indexId,
            engine -> {
              var oIndexEngine = (LuceneIndexEngine) engine;
              oIndexEngine.init(session, im);
              return null;
            });
        break;
      } catch (InvalidIndexEngineIdException e) {
        doReloadIndexEngine();
      }
    }
  }

  protected Object encodeKey(Object key) {
    return key;
  }

  private LuceneTxChanges getTransactionChanges(FrontendTransaction transaction) {

    var changes = (LuceneTxChanges) transaction.getCustomData(getName());
    if (changes == null) {
      while (true) {
        try {
          changes =
              storage.callIndexEngine(
                  false,
                  indexId,
                  engine -> {
                    var indexEngine = (LuceneIndexEngine) engine;
                    try {
                      return indexEngine.buildTxChanges();
                    } catch (IOException e) {
                      throw BaseException.wrapException(
                          new IndexException(storage.getName(),
                              "Cannot get searcher from index " + getName()), e, storage.getName());
                    }
                  });
          break;
        } catch (InvalidIndexEngineIdException e) {
          doReloadIndexEngine();
        }
      }

      transaction.setCustomData(getName(), changes);
    }
    return changes;
  }

  @Deprecated
  @Override
  public Collection<Identifiable> get(DatabaseSessionInternal session, final Object key) {
    try (var stream = getRids(session, key)) {
      return stream.collect(Collectors.toList());
    }
  }

  @Override
  public Stream<RID> getRidsIgnoreTx(DatabaseSessionInternal session, Object key) {
    while (true) {
      try {
        @SuppressWarnings("unchecked")
        var result = (Set<Identifiable>) storage.getIndexValue(session,
            indexId, key);
        return result.stream().map(Identifiable::getIdentity);
        // TODO filter these results based on security
        //          return new HashSet(IndexInternal.securityFilterOnRead(this, result));
      } catch (InvalidIndexEngineIdException e) {
        doReloadIndexEngine();
      }
    }
  }

  @Override
  public Stream<RID> getRids(DatabaseSessionInternal session, Object key) {
    return session.computeInTx(transaction -> {
      while (true) {
        try {
          return storage
              .callIndexEngine(
                  false,
                  indexId,
                  engine -> {
                    var indexEngine = (LuceneIndexEngine) engine;
                    return indexEngine.getInTx(session, key,
                        getTransactionChanges((FrontendTransaction) transaction));
                  })
              .stream()
              .map(Identifiable::getIdentity);
        } catch (InvalidIndexEngineIdException e) {
          doReloadIndexEngine();
        }
      }
    });
  }

  @Override
  public LuceneIndexNotUnique put(FrontendTransaction transaction, final Object key,
      final Identifiable value) {
    final var rid = value.getIdentity();

    if (key != null) {
      var transactionChanges = getTransactionChanges(transaction);
      transaction.addIndexEntry(
          this, super.getName(), FrontendTransactionIndexChanges.OPERATION.PUT, encodeKey(key),
          value);

      Document luceneDoc;
      while (true) {
        try {
          luceneDoc =
              storage.callIndexEngine(
                  false,
                  indexId,
                  engine -> {
                    var oIndexEngine = (LuceneIndexEngine) engine;
                    return oIndexEngine.buildDocument(transaction.getDatabaseSession(), key, value);
                  });
          break;
        } catch (InvalidIndexEngineIdException e) {
          doReloadIndexEngine();
        }
      }

      transactionChanges.put(key, value, luceneDoc);
    }
    return this;
  }

  @Override
  public long size(DatabaseSessionInternal session) {
    return session.computeInTx(transaction -> {
      while (true) {
        try {
          return storage.callIndexEngine(
              false,
              indexId,
              engine -> {
                var indexEngine = (LuceneIndexEngine) engine;
                return indexEngine.sizeInTx(
                    getTransactionChanges((FrontendTransaction) transaction), storage);
              });
        } catch (InvalidIndexEngineIdException e) {
          doReloadIndexEngine();
        }
      }
    });
  }

  @Override
  public Stream<RawPair<Object, RID>> streamEntries(DatabaseSessionInternal session,
      Collection<?> keys, boolean ascSortOrder) {

    @SuppressWarnings("resource")
    var query =
        (String)
            keys.stream()
                .findFirst()
                .map(k -> (CompositeKey) k)
                .map(CompositeKey::getKeys)
                .orElse(Collections.singletonList("q=*:*"))
                .get(0);
    return IndexStreamSecurityDecorator.decorateStream(
        this, getRids(session, query).map((rid) -> new RawPair<>(query, rid)), session);
  }

  @Override
  public Stream<RawPair<Object, RID>> streamEntriesBetween(
      DatabaseSessionInternal session, Object fromKey, boolean fromInclusive, Object toKey,
      boolean toInclusive, boolean ascOrder) {
    while (true) {
      try {
        return IndexStreamSecurityDecorator.decorateStream(
            this,
            storage.iterateIndexEntriesBetween(session,
                indexId, fromKey, fromInclusive, toKey, toInclusive, ascOrder, null), session);
      } catch (InvalidIndexEngineIdException e) {
        doReloadIndexEngine();
      }
    }
  }

  @Override
  public Stream<RawPair<Object, RID>> streamEntriesMajor(
      DatabaseSessionInternal session, Object fromKey, boolean fromInclusive, boolean ascOrder) {
    while (true) {
      try {
        return IndexStreamSecurityDecorator.decorateStream(
            this,
            storage.iterateIndexEntriesMajor(indexId, fromKey, fromInclusive, ascOrder, null),
            session);
      } catch (InvalidIndexEngineIdException e) {
        doReloadIndexEngine();
      }
    }
  }

  @Override
  public Stream<RawPair<Object, RID>> streamEntriesMinor(
      DatabaseSessionInternal session, Object toKey, boolean toInclusive, boolean ascOrder) {
    while (true) {
      try {
        return IndexStreamSecurityDecorator.decorateStream(
            this, storage.iterateIndexEntriesMinor(indexId, toKey, toInclusive, ascOrder, null),
            session);
      } catch (InvalidIndexEngineIdException e) {
        doReloadIndexEngine();
      }
    }
  }

  @Override
  public Stream<RawPair<Object, RID>> stream(DatabaseSessionInternal session) {
    while (true) {
      try {
        return IndexStreamSecurityDecorator.decorateStream(
            this, storage.getIndexStream(indexId, null), session);
      } catch (InvalidIndexEngineIdException e) {
        doReloadIndexEngine();
      }
    }
  }

  @Override
  public Stream<RawPair<Object, RID>> descStream(DatabaseSessionInternal session) {
    while (true) {
      try {
        return IndexStreamSecurityDecorator.decorateStream(
            this, storage.getIndexStream(indexId, null), session);
      } catch (InvalidIndexEngineIdException e) {
        doReloadIndexEngine();
      }
    }
  }

  @Override
  public boolean supportsOrderedIterations() {
    return false;
  }

  @Override
  public IndexSearcher searcher() {
    while (true) {
      try {
        return storage.callIndexEngine(
            false,
            indexId,
            engine -> {
              final var indexEngine = (LuceneIndexEngine) engine;
              return indexEngine.searcher(storage);
            });
      } catch (final InvalidIndexEngineIdException e) {
        doReloadIndexEngine();
      }
    }
  }

  @Override
  public boolean canBeUsedInEqualityOperators() {
    return false;
  }
}
