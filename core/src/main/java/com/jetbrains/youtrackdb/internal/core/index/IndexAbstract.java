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

import com.jetbrains.youtrackdb.api.exception.RecordDuplicatedException;
import com.jetbrains.youtrackdb.internal.common.concur.lock.OneEntryPerKeyLockManager;
import com.jetbrains.youtrackdb.internal.common.concur.lock.PartitionedLockManager;
import com.jetbrains.youtrackdb.internal.common.listener.ProgressListener;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.exception.ConfigurationException;
import com.jetbrains.youtrackdb.internal.core.exception.InvalidIndexEngineIdException;
import com.jetbrains.youtrackdb.internal.core.index.comparator.AlwaysGreaterKey;
import com.jetbrains.youtrackdb.internal.core.index.comparator.AlwaysLessKey;
import com.jetbrains.youtrackdb.internal.core.index.engine.BaseIndexEngine;
import com.jetbrains.youtrackdb.internal.core.index.iterator.IndexCursorStream;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityHelper;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.storage.Storage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransaction;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionIndexChanges.OPERATION;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionIndexChangesPerKey;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionIndexChangesPerKey.TransactionIndexEntry;
import com.jetbrains.youtrackdb.internal.core.tx.Transaction;
import java.lang.reflect.InvocationTargetException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Handles indexing when records change. The underlying lock manager for keys can be the
 * {@link PartitionedLockManager}, the default one, or the {@link OneEntryPerKeyLockManager} in case
 * of distributed. This is to avoid deadlock situation between nodes where keys have the same hash
 * code.
 */
public abstract class IndexAbstract implements Index {

  private static final AlwaysLessKey ALWAYS_LESS_KEY = new AlwaysLessKey();
  private static final AlwaysGreaterKey ALWAYS_GREATER_KEY = new AlwaysGreaterKey();

  protected static final String CONFIG_MAP_RID = "mapRid";
  private static final String CONFIG_COLLECTIONS = "collections";

  @Nonnull
  protected final AbstractStorage storage;
  @Nonnull
  private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

  protected volatile int indexId = -1;

  @Nonnull
  protected Set<String> collectionsToIndex = new HashSet<>();
  @Nullable
  protected IndexMetadata im;

  @Nullable
  protected RID identity;

  public IndexAbstract(@Nullable RID identity,
      @Nonnull FrontendTransaction transaction, @Nonnull final Storage storage) {
    acquireExclusiveLock();
    try {
      if (!identity.isPersistent()) {
        throw new IllegalStateException(
            "RID passed to index is not persistent and can not be used to load metadata");
      }
      this.identity = identity;
      this.storage = (AbstractStorage) storage;

      load(transaction);
    } finally {
      releaseExclusiveLock();
    }
  }

  public IndexAbstract(@Nonnull final Storage storage) {
    acquireExclusiveLock();
    try {
      this.storage = (AbstractStorage) storage;
    } finally {
      releaseExclusiveLock();
    }
  }

  public static IndexMetadata loadMetadataFromMap(Transaction transaction,
      final Map<String, ?> config) {
    return loadMetadataInternal(transaction,
        config,
        (String) config.get(CONFIG_TYPE),
        (String) config.get(ALGORITHM));
  }

  public static IndexMetadata loadMetadataInternal(
      Transaction transaction, final Map<String, ?> config,
      final String type,
      final String algorithm) {
    final var indexName = (String) config.get(CONFIG_NAME);

    @SuppressWarnings("unchecked") final var indexDefinitionEntity = (Map<String, Object>) config.get(
        INDEX_DEFINITION);
    IndexDefinition loadedIndexDefinition = null;
    if (indexDefinitionEntity != null) {
      try {
        final var indexDefClassName = (String) config.get(INDEX_DEFINITION_CLASS);
        final var indexDefClass = Class.forName(indexDefClassName);
        loadedIndexDefinition =
            (IndexDefinition) indexDefClass.getDeclaredConstructor().newInstance();
        loadedIndexDefinition.fromMap(indexDefinitionEntity);

      } catch (final ClassNotFoundException
                     | IllegalAccessException
                     | InstantiationException
                     | InvocationTargetException
                     | NoSuchMethodException e) {
        throw BaseException.wrapException(
            new IndexException(transaction.getDatabaseSession(),
                "Error during deserialization of index definition"), e,
            transaction.getDatabaseSession());
      }
    }

    @SuppressWarnings("unchecked")
    var collections = (Set<String>) config.get(CONFIG_COLLECTIONS);
    final var indexVersion =
        config.get(INDEX_VERSION) == null
            ? 1
            : (Integer) config.get(INDEX_VERSION);

    @SuppressWarnings("unchecked")
    var metadataEntity = (Map<String, Object>) config.get(METADATA);
    return new IndexMetadata(
        indexName,
        loadedIndexDefinition,
        collections,
        type,
        algorithm,
        indexVersion, metadataEntity);
  }

  @Override
  public boolean hasRangeQuerySupport() {
    acquireSharedLock();
    try {
      while (true) {
        try {
          return storage.hasIndexRangeQuerySupport(indexId);
        } catch (InvalidIndexEngineIdException ignore) {
          doReloadIndexEngine();
        }
      }
    } finally {
      releaseSharedLock();
    }
  }


  /**
   * Creates the index.
   */
  @Override
  public Index create(
      FrontendTransaction transaction, final IndexMetadata indexMetadata) {
    acquireExclusiveLock();
    try {
      this.im = indexMetadata;

      var collectionsToIndex = indexMetadata.getCollectionsToIndex();
      if (collectionsToIndex != null) {
        this.collectionsToIndex = new HashSet<>(collectionsToIndex);
      } else {
        this.collectionsToIndex = new HashSet<>();
      }

      Map<String, String> engineProperties = new HashMap<>();
      indexMetadata.setVersion(im.getVersion());
      indexId = storage.addIndexEngine(indexMetadata, engineProperties);

      assert indexId >= 0;

      onIndexEngineChange(transaction.getDatabaseSession(), indexId);

      save(transaction);
    } catch (Exception e) {
      LogManager.instance().error(this, "Exception during index '%s' creation", e, im.getName());
      // index is created inside of storage
      if (indexId >= 0) {
        doDelete(transaction);
      }
      throw BaseException.wrapException(
          new IndexException(transaction.getDatabaseSession(),
              "Cannot create the index '" + im.getName() + "'"),
          e,
          transaction.getDatabaseSession());
    } finally {
      releaseExclusiveLock();
    }

    return this;
  }

  protected void doReloadIndexEngine() {
    indexId = storage.loadIndexEngine(im.getName());
    if (indexId < 0) {
      throw new IllegalStateException("Index " + im.getName() + " can not be loaded");
    }
  }

  private void load(FrontendTransaction transaction) {
    var entity = transaction.loadEntity(identity);
    final var indexMetadata = loadMetadata(transaction, entity.toMap(false));

    this.im = indexMetadata;
    collectionsToIndex.clear();

    collectionsToIndex.addAll(indexMetadata.getCollectionsToIndex());
    indexId = storage.loadIndexEngine(im.getName());

    if (indexId == -1) {
      Map<String, String> engineProperties = new HashMap<>();
      indexId = storage.loadExternalIndexEngine(indexMetadata, engineProperties);
    }

    if (indexId == -1) {
      throw new IllegalStateException("Index " + im.getName() + " can not be loaded");
    }

    onIndexEngineChange(transaction.getDatabaseSession(), indexId);
  }

  @Override
  public IndexMetadata loadMetadata(FrontendTransaction transaction,
      final Map<String, Object> config) {
    return loadMetadataInternal(transaction,
        config, (String) config.get(CONFIG_TYPE), (String) config.get(ALGORITHM));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public long rebuild(DatabaseSessionEmbedded session) {
    return rebuild(session, new IndexRebuildOutputListener(this));
  }

  @Override
  public void close() {
  }

  /**
   * @return number of entries in the index.
   */
  @Override
  @Deprecated
  public long getSize(DatabaseSessionEmbedded session) {
    return size(session);
  }

  /**
   * Counts the entries for the key.
   */
  @Override
  @Deprecated
  public long count(DatabaseSessionEmbedded session, Object iKey) {
    try (var stream =
        streamEntriesBetween(session, iKey, true, iKey, true, true)) {
      return stream.count();
    }
  }

  /**
   * @return Number of keys in index
   */
  @Override
  @Deprecated
  public long getKeySize() {
    try (var stream = keyStream()) {
      return stream.distinct().count();
    }
  }

  /**
   * Flushes in-memory changes to disk.
   */
  @Override
  @Deprecated
  public void flush() {
    // do nothing
  }

  @Override
  @Deprecated
  public long getRebuildVersion() {
    return 0;
  }

  /**
   * @return Indicates whether index is rebuilding at the moment.
   * @see #getRebuildVersion()
   */
  @Override
  @Deprecated
  public boolean isRebuilding() {
    return false;
  }

  @Override
  @Deprecated
  @Nullable
  public Object getFirstKey() {
    try (final var stream = keyStream()) {
      final var iterator = stream.iterator();
      if (iterator.hasNext()) {
        return iterator.next();
      }

      return null;
    }
  }

  @Override
  @Deprecated
  @Nullable
  public Object getLastKey(DatabaseSessionEmbedded session) {
    try (final var stream = descStream(session)) {
      final var iterator = stream.iterator();
      if (iterator.hasNext()) {
        return iterator.next().first();
      }

      return null;
    }
  }

  @Override
  @Deprecated
  public IndexCursor cursor(DatabaseSessionEmbedded session) {
    return new IndexCursorStream(stream(session));
  }

  @Deprecated
  @Override
  public IndexCursor descCursor(DatabaseSessionEmbedded session) {
    return new IndexCursorStream(descStream(session));
  }

  @Deprecated
  @Override
  public IndexKeyCursor keyCursor() {
    return new IndexKeyCursor() {
      private final Iterator<Object> keyIterator = keyStream().iterator();

      @Override
      @Nullable
      public Object next(int prefetchSize) {
        if (keyIterator.hasNext()) {
          return keyIterator.next();
        }

        return null;
      }
    };
  }

  @Deprecated
  @Override
  public IndexCursor iterateEntries(DatabaseSessionEmbedded session, Collection<?> keys,
      boolean ascSortOrder) {
    return new IndexCursorStream(streamEntries(session, keys, ascSortOrder));
  }

  @Deprecated
  @Override
  public IndexCursor iterateEntriesBetween(
      DatabaseSessionEmbedded session, Object fromKey, boolean fromInclusive, Object toKey,
      boolean toInclusive, boolean ascOrder) {
    return new IndexCursorStream(
        streamEntriesBetween(session, fromKey, fromInclusive, toKey, toInclusive, ascOrder));
  }

  @Deprecated
  @Override
  public IndexCursor iterateEntriesMajor(DatabaseSessionEmbedded session, Object fromKey,
      boolean fromInclusive, boolean ascOrder) {
    return new IndexCursorStream(streamEntriesMajor(session, fromKey, fromInclusive, ascOrder));
  }

  @Deprecated
  @Override
  public IndexCursor iterateEntriesMinor(DatabaseSessionEmbedded session, Object toKey,
      boolean toInclusive, boolean ascOrder) {
    return new IndexCursorStream(streamEntriesMajor(session, toKey, toInclusive, ascOrder));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public long rebuild(DatabaseSessionEmbedded session,
      final ProgressListener progressListener) {
    long entitiesIndexed;

    acquireExclusiveLock();
    try {
      try {
        if (indexId >= 0) {
          session.executeInTxInternal(this::doDelete);
        }
      } catch (Exception e) {
        LogManager.instance().error(this, "Error during index '%s' delete", e, im.getName());
      }

      Map<String, String> engineProperties = new HashMap<>();
      indexId = storage.addIndexEngine(im, engineProperties);

      onIndexEngineChange(session, indexId);
    } catch (Exception e) {
      try {
        if (indexId >= 0) {
          storage.clearIndex(indexId);
        }
      } catch (Exception e2) {
        LogManager.instance().error(this, "Error during index rebuild", e2);
        // IGNORE EXCEPTION: IF THE REBUILD WAS LAUNCHED IN CASE OF RID INVALID CLEAR ALWAYS GOES IN
        // ERROR
      }

      throw BaseException.wrapException(
          new IndexException(session,
              "Error on rebuilding the index for collections: " + collectionsToIndex),
          e, session);
    } finally {
      releaseExclusiveLock();
    }

    entitiesIndexed = fillIndex(session, progressListener);

    return entitiesIndexed;
  }

  public long fillIndex(DatabaseSessionEmbedded session,
      ProgressListener progressListener) {
    long entitiesIndexed;
    acquireSharedLock();
    try {
      entitiesIndexed = doFillIndex(session, progressListener);
    } catch (final Exception e) {
      LogManager.instance().error(this, "Error during index rebuild", e);
      try {
        if (indexId >= 0) {
          storage.clearIndex(indexId);
        }
      } catch (Exception e2) {
        LogManager.instance().error(this, "Error during index rebuild", e2);
        // IGNORE EXCEPTION: IF THE REBUILD WAS LAUNCHED IN CASE OF RID INVALID CLEAR ALWAYS GOES IN
        // ERROR
      }

      throw BaseException.wrapException(
          new IndexException(session,
              "Error on rebuilding the index for collections: " + collectionsToIndex),
          e, session);
    } finally {
      releaseSharedLock();
    }
    return entitiesIndexed;
  }

  private long doFillIndex(DatabaseSessionEmbedded session,
      final ProgressListener iProgressListener) {
    long entitiesIndexed = 0;
    try {
      long entityNum = 0;
      long entitiesTotal = 0;

      for (final var collection : collectionsToIndex) {
        entitiesTotal += storage.count(session, storage.getCollectionIdByName(collection));
      }

      if (iProgressListener != null) {
        iProgressListener.onBegin(this, entitiesTotal, true);
      }

      if (entitiesTotal > 0) {
        // INDEX ALL COLLECTIONS
        for (final var collectionName : collectionsToIndex) {
          final var metrics =
              indexCollection(session, collectionName, iProgressListener, entityNum,
                  entitiesIndexed, entitiesTotal);
          entityNum = metrics[0];
          entitiesIndexed = metrics[1];
        }
      }

      if (iProgressListener != null) {
        iProgressListener.onCompletition(session, this, true);
      }
    } catch (final RuntimeException e) {
      if (iProgressListener != null) {
        iProgressListener.onCompletition(session, this, false);
      }
      throw e;
    }
    return entitiesIndexed;
  }

  @Override
  public boolean doRemove(DatabaseSessionInternal session, AbstractStorage storage,
      Object key, RID rid)
      throws InvalidIndexEngineIdException {
    return doRemove(storage, key, session);
  }

  @Override
  public boolean remove(FrontendTransaction transaction, Object key, final Identifiable rid) {
    key = getCollatingValue(key);
    transaction.addIndexEntry(this, getName(), OPERATION.REMOVE, key, rid);
    return true;
  }

  @Override
  public boolean remove(FrontendTransaction transaction, Object key) {
    key = getCollatingValue(key);

    transaction.addIndexEntry(this, getName(), OPERATION.REMOVE, key, null);
    return true;
  }

  @Override
  public boolean doRemove(AbstractStorage storage, Object key,
      DatabaseSessionInternal session)
      throws InvalidIndexEngineIdException {
    return storage.removeKeyFromIndex(indexId, key);
  }

  @Override
  public Index delete(FrontendTransaction transaction) {
    acquireExclusiveLock();

    try {
      doDelete(transaction);
      var session = transaction.getDatabaseSession();
      // REMOVE THE INDEX ALSO FROM CLASS MAP
      session.getSharedContext().getIndexManager()
          .removeClassPropertyIndex(session, this);

      return this;
    } finally {
      releaseExclusiveLock();
    }
  }

  protected void doDelete(FrontendTransaction transaction) {
    while (true) {
      try {
        try {
          clearAllEntries(transaction.getDatabaseSession());
        } catch (IndexEngineException e) {
          throw e;
        } catch (RuntimeException e) {
          LogManager.instance().error(this, "Error Dropping Index %s", e, getName());
          // Just log errors of removing keys while dropping and keep dropping
        }

        storage.deleteIndexEngine(indexId);
        break;
      } catch (InvalidIndexEngineIdException ignore) {
        doReloadIndexEngine();
      }
    }

    var entity = transaction.loadEntity(identity);
    entity.delete();
  }

  private void clearAllEntries(DatabaseSessionEmbedded session) {
    FrontendTransaction transaction = null;
    if (session.isTxActive()) {
      transaction = session.getTransactionInternal();
    }

    try (var containsStream = stream(session)) {
      if (containsStream.findAny().isEmpty()) {
        return;
      }
    }

    try (var deletionSession = session.copy()) {
      try (final var stream = stream(deletionSession)) {
        deletionSession.executeInTxBatchesInternal(stream,
            (deletionTransaction, entry) ->
                remove(deletionTransaction, entry.first(),
                    entry.second()));
      } catch (Exception e) {
        if (transaction != null) {
          transaction.rollback();
          throw e;
        }
      }
    }
  }

  @Override
  public String getName() {
    return im.getName();
  }

  @Override
  public String getType() {
    return im.getType();
  }

  @Override
  public String getAlgorithm() {
    acquireSharedLock();
    try {
      return im.getAlgorithm();
    } finally {
      releaseSharedLock();
    }
  }

  @Override
  public String toString() {
    acquireSharedLock();
    try {
      return im.getName();
    } finally {
      releaseSharedLock();
    }
  }

  @Override
  public Set<String> getCollections() {
    acquireSharedLock();
    try {
      return Collections.unmodifiableSet(collectionsToIndex);
    } finally {
      releaseSharedLock();
    }
  }

  @Override
  public IndexAbstract addCollection(FrontendTransaction transaction, final String collectionName,
      boolean requireEmpty) {
    acquireExclusiveLock();
    try {
      var session = transaction.getDatabaseSession();
      if (collectionsToIndex.add(collectionName)) {

        if (requireEmpty) {
          // INDEX SINGLE COLLECTION
          var collectionId = session.getCollectionIdByName(collectionName);
          if (session.countCollectionElements(collectionId) > 0) {
            throw new IndexException("Collection " + collectionName
                + " is not empty. Please remove all records from it before adding to index");
          }
        }

        save(transaction);
      }
      return this;
    } finally {
      releaseExclusiveLock();
    }
  }

  @Override
  public void removeCollection(FrontendTransaction transaction, String collectionName) {
    acquireExclusiveLock();
    try {
      var session = transaction.getDatabaseSession();
      var collectionId = session.getCollectionIdByName(collectionName);
      if (session.countCollectionElements(collectionId) > 0) {
        throw new IndexException("Collection " + collectionName
            + " is not empty. Please remove all records from it before removing it from index");
      }

      if (collectionsToIndex.remove(collectionName)) {
        save(transaction);
      }
    } finally {
      releaseExclusiveLock();
    }
  }

  @Override
  public int getVersion() {
    return im.getVersion();
  }

  private void save(FrontendTransaction transaction) {
    Entity entity;
    if (identity == null) {
      entity = transaction.getDatabaseSession().newInternalInstance();
    } else {
      entity = transaction.loadEntity(identity);
    }

    entity.setString(CONFIG_TYPE, im.getType());
    entity.setString(CONFIG_NAME, im.getName());
    entity.setInt(INDEX_VERSION, im.getVersion());

    if (im.getIndexDefinition() != null) {
      final var indexDefEntity = im.getIndexDefinition().toMap(transaction.getDatabaseSession());
      entity.setEmbeddedMap(INDEX_DEFINITION, indexDefEntity);
      entity.setString(INDEX_DEFINITION_CLASS, im.getIndexDefinition().getClass().getName());
    }

    var session = transaction.getDatabaseSession();
    entity.setEmbeddedSet(CONFIG_COLLECTIONS, session.newEmbeddedSet(collectionsToIndex));
    entity.setString(ALGORITHM, im.getAlgorithm());

    if (im.getMetadata() != null) {
      entity.setEmbeddedMap(METADATA, session.newEmbeddedMap(im.getMetadata()));
    }

    identity = entity.getIdentity();
  }

  /**
   * Interprets transaction index changes for a certain key. Override it to customize index
   * behaviour on interpreting index changes. This may be viewed as an optimization, but in some
   * cases this is a requirement. For example, if you put multiple values under the same key during
   * the transaction for single-valued/unique index, but remove all of them except one before
   * commit, there is no point in throwing {@link RecordDuplicatedException} while applying index
   * changes.
   *
   * @param changes the changes to interpret.
   * @return the interpreted index key changes.
   */
  @Override
  public Iterable<TransactionIndexEntry> interpretTxKeyChanges(
      FrontendTransactionIndexChangesPerKey changes) {
    return changes.getEntriesAsList();
  }

  @Override
  public Map<String, Object> getConfiguration(DatabaseSessionInternal session) {
    acquireSharedLock();
    try {
      var map = new HashMap<String, Object>();
      map.put(CONFIG_TYPE, im.getType());
      map.put(CONFIG_NAME, im.getName());
      map.put(INDEX_VERSION, im.getVersion());

      if (im.getIndexDefinition() != null) {
        final var indexDefEntity = im.getIndexDefinition().toMap(session);
        map.put(INDEX_DEFINITION, indexDefEntity);
        map.put(INDEX_DEFINITION_CLASS, im.getIndexDefinition().getClass().getName());
      }

      map.put(CONFIG_COLLECTIONS, session.newEmbeddedSet(collectionsToIndex));
      map.put(ALGORITHM, im.getAlgorithm());

      if (im.getMetadata() != null) {
        map.put(METADATA, session.newEmbeddedMap(im.getMetadata()));
      }

      map.put(EntityHelper.ATTRIBUTE_RID, identity);
      return map;
    } finally {
      releaseSharedLock();
    }
  }

  @Override
  public Map<String, Object> getMetadata() {
    return im.getMetadata();
  }

  @Override
  public boolean isUnique() {
    return false;
  }

  @Override
  public boolean isAutomatic() {
    acquireSharedLock();
    try {
      return im.getIndexDefinition() != null && im.getIndexDefinition().getClassName() != null;
    } finally {
      releaseSharedLock();
    }
  }

  @Override
  public PropertyTypeInternal[] getKeyTypes() {
    acquireSharedLock();
    try {
      if (im.getIndexDefinition() == null) {
        return null;
      }

      return im.getIndexDefinition().getTypes();
    } finally {
      releaseSharedLock();
    }
  }

  @Override
  public Stream<Object> keyStream() {
    acquireSharedLock();
    try {
      while (true) {
        try {
          return storage.getIndexKeyStream(indexId);
        } catch (InvalidIndexEngineIdException ignore) {
          doReloadIndexEngine();
        }
      }
    } finally {
      releaseSharedLock();
    }
  }

  @Override
  public IndexDefinition getDefinition() {
    return im.getIndexDefinition();
  }

  @Override
  public boolean equals(final Object o) {
    acquireSharedLock();
    try {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }

      final var that = (IndexAbstract) o;

      return im.getName().equals(that.im.getName());
    } finally {
      releaseSharedLock();
    }
  }

  @Override
  public int hashCode() {
    acquireSharedLock();
    try {
      return im.getName().hashCode();
    } finally {
      releaseSharedLock();
    }
  }

  @Override
  public int getIndexId() {
    return indexId;
  }

  @Override
  public String getDatabaseName() {
    return storage.getName();
  }

  @Override
  public Object getCollatingValue(final Object key) {
    if (key != null && im.getIndexDefinition() != null) {
      return im.getIndexDefinition().getCollate().transform(key);
    }
    return key;
  }

  @Override
  public int compareTo(Index index) {
    acquireSharedLock();
    try {
      final var name = index.getName();
      return this.im.getName().compareTo(name);
    } finally {
      releaseSharedLock();
    }
  }

  @Override
  public boolean acquireAtomicExclusiveLock() {
    BaseIndexEngine engine;

    while (true) {
      try {
        engine = storage.getIndexEngine(indexId);
        break;
      } catch (InvalidIndexEngineIdException ignore) {
        doReloadIndexEngine();
      }
    }

    return engine.acquireAtomicExclusiveLock();
  }

  private long[] indexCollection(
      DatabaseSessionInternal session, final String collectionName,
      final ProgressListener iProgressListener,
      long documentNum,
      long documentIndexed,
      long documentTotal) {
    if (im.getIndexDefinition() == null) {
      throw new ConfigurationException(
          session, "Index '"
          + im.getName()
          + "' cannot be rebuilt because has no a valid definition ("
          + im.getIndexDefinition()
          + ")");
    }

    var stat = new long[]{documentNum, documentIndexed};

    FrontendTransaction currentTransaction = null;
    if (session.isTxActive()) {
      currentTransaction = session.getTransactionInternal();
    }

    var collectionId = session.getCollectionIdByName(collectionName);
    var collectionCount = session.countCollectionElements(collectionId);

    if (collectionCount > 0) {
      try (var fillSession = session.copy()) {
        var collectionIterator = fillSession.browseCollection(collectionName);
        fillSession.executeInTxBatchesInternal(collectionIterator, (fillTransaction, record) -> {
          if (Thread.interrupted()) {
            throw new CommandExecutionException(session,
                "The index rebuild has been interrupted");
          }

          if (record instanceof EntityImpl entity) {
            ClassIndexManager.reIndex(fillTransaction, entity, this);
            ++stat[1];
          }

          stat[0]++;

          if (iProgressListener != null) {
            iProgressListener.onProgress(
                this, documentNum, (float) (documentNum * 100.0 / documentTotal));
          }
        });
      } catch (Exception e) {
        if (currentTransaction != null) {
          currentTransaction.rollback();
        }
        throw e;
      }
    }

    return stat;
  }

  protected void releaseExclusiveLock() {
    rwLock.writeLock().unlock();
  }

  protected void acquireExclusiveLock() {
    rwLock.writeLock().lock();
  }

  protected void releaseSharedLock() {
    rwLock.readLock().unlock();
  }

  protected void acquireSharedLock() {
    rwLock.readLock().lock();
  }

  protected void onIndexEngineChange(DatabaseSessionInternal session, final int indexId) {
    while (true) {
      try {
        storage.callIndexEngine(
            false,
            indexId,
            engine -> {
              engine.init(session, im);
              return null;
            });
        break;
      } catch (InvalidIndexEngineIdException ignore) {
        doReloadIndexEngine();
      }
    }
  }


  /**
   * Indicates search behavior in case of {@link CompositeKey} keys that have less amount of
   * internal keys are used, whether lowest or highest partially matched key should be used. Such
   * keys is allowed to use only in
   */
  public enum PartialSearchMode {
    /**
     * Any partially matched key will be used as search result.
     */
    NONE,
    /**
     * The biggest partially matched key will be used as search result.
     */
    HIGHEST_BOUNDARY,

    /**
     * The smallest partially matched key will be used as search result.
     */
    LOWEST_BOUNDARY
  }

  private static Object enhanceCompositeKey(
      Object key, PartialSearchMode partialSearchMode, IndexDefinition definition) {
    if (!(key instanceof CompositeKey compositeKey)) {
      return key;
    }

    final var keySize = definition.getParamCount();

    if (!(keySize == 1
        || compositeKey.getKeys().size() == keySize
        || partialSearchMode.equals(PartialSearchMode.NONE))) {
      final var fullKey = new CompositeKey(compositeKey);
      var itemsToAdd = keySize - fullKey.getKeys().size();

      final Comparable<?> keyItem;
      if (partialSearchMode.equals(PartialSearchMode.HIGHEST_BOUNDARY)) {
        keyItem = ALWAYS_GREATER_KEY;
      } else {
        keyItem = ALWAYS_LESS_KEY;
      }

      for (var i = 0; i < itemsToAdd; i++) {
        fullKey.addKey(keyItem);
      }

      return fullKey;
    }

    return key;
  }

  public Object enhanceToCompositeKeyBetweenAsc(Object keyTo, boolean toInclusive) {
    PartialSearchMode partialSearchModeTo;
    if (toInclusive) {
      partialSearchModeTo = PartialSearchMode.HIGHEST_BOUNDARY;
    } else {
      partialSearchModeTo = PartialSearchMode.LOWEST_BOUNDARY;
    }

    keyTo = enhanceCompositeKey(keyTo, partialSearchModeTo, getDefinition());
    return keyTo;
  }

  public Object enhanceFromCompositeKeyBetweenAsc(Object keyFrom, boolean fromInclusive) {
    PartialSearchMode partialSearchModeFrom;
    if (fromInclusive) {
      partialSearchModeFrom = PartialSearchMode.LOWEST_BOUNDARY;
    } else {
      partialSearchModeFrom = PartialSearchMode.HIGHEST_BOUNDARY;
    }

    keyFrom = enhanceCompositeKey(keyFrom, partialSearchModeFrom, getDefinition());
    return keyFrom;
  }

  public Object enhanceToCompositeKeyBetweenDesc(Object keyTo, boolean toInclusive) {
    PartialSearchMode partialSearchModeTo;
    if (toInclusive) {
      partialSearchModeTo = PartialSearchMode.HIGHEST_BOUNDARY;
    } else {
      partialSearchModeTo = PartialSearchMode.LOWEST_BOUNDARY;
    }

    keyTo = enhanceCompositeKey(keyTo, partialSearchModeTo, getDefinition());
    return keyTo;
  }

  public Object enhanceFromCompositeKeyBetweenDesc(Object keyFrom, boolean fromInclusive) {
    PartialSearchMode partialSearchModeFrom;
    if (fromInclusive) {
      partialSearchModeFrom = PartialSearchMode.LOWEST_BOUNDARY;
    } else {
      partialSearchModeFrom = PartialSearchMode.HIGHEST_BOUNDARY;
    }

    keyFrom = enhanceCompositeKey(keyFrom, partialSearchModeFrom, getDefinition());
    return keyFrom;
  }

  @Nullable
  @Override
  public RID getIdentity() {
    return identity;
  }
}
