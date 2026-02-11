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

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.listener.ProgressListener;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.common.util.MultiKey;
import com.jetbrains.youtrackdb.internal.common.util.UncaughtExceptionHandler;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaShared;
import com.jetbrains.youtrackdb.internal.core.metadata.security.SecurityResourceProperty;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.storage.Storage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransaction;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * Manages indexes at database level. A single instance is shared among multiple databases.
 * Contentions are managed by r/w locks.
 */
public class IndexManagerEmbedded extends IndexManagerAbstract {

  private volatile Thread recreateIndexesThread = null;

  volatile boolean rebuildCompleted = false;

  protected final AtomicInteger writeLockNesting = new AtomicInteger();

  private final ReadWriteLock lock = new ReentrantReadWriteLock();

  public IndexManagerEmbedded(Storage storage) {
    super(storage);
  }

  @Override
  public void load(DatabaseSessionEmbedded session) {
    if (!autoRecreateIndexesAfterCrash(session)) {
      session.executeInTxInternal(transaction -> {
        acquireExclusiveLock(transaction);
        try {
          indexManagerIdentity =
              RecordIdInternal.fromString(
                  session.getStorageInfo().getConfiguration().getIndexMgrRecordId(),
                  false);
          var entity = (EntityImpl) session.loadEntity(indexManagerIdentity);
          load(transaction, entity);
        } finally {
          releaseExclusiveLock(session, false);
        }
      });
    }
  }

  @Override
  public void reload(DatabaseSessionEmbedded session) {
    session.executeInTxInternal(
        transaction -> {
          acquireExclusiveLock(transaction);
          try {
            var entity = (EntityImpl) transaction.loadEntity(indexManagerIdentity);
            load(transaction, entity);
          } finally {
            releaseExclusiveLock(session, true);
          }
        });
  }

  public void addCollectionToIndex(
      DatabaseSessionEmbedded session,
      final String collectionName,
      final String indexName,
      boolean requireEmpty
  ) {
    acquireSharedLock();
    try {
      final var index = indexes.get(indexName);
      if (index.getCollections().contains(collectionName)) {
        return;
      }
    } finally {
      releaseSharedLock();
    }

    session.executeInTxInternal(transaction -> {
      acquireExclusiveLock(transaction);
      try {
        final var index = indexes.get(indexName);
        if (index == null) {
          throw new IndexException(session,
              "Index with name " + indexName + " does not exist.");
        }
        if (!index.getCollections().contains(collectionName)) {
          index.addCollection(transaction, collectionName, requireEmpty);
        }
      } finally {
        releaseExclusiveLock(session, true);
      }
    });
  }

  public void removeCollectionFromIndex(DatabaseSessionEmbedded session,
      final String collectionName,
      final String indexName) {
    acquireSharedLock();
    try {
      final var index = indexes.get(indexName);
      if (!index.getCollections().contains(collectionName)) {
        return;
      }
    } finally {
      releaseSharedLock();
    }

    session.executeInTxInternal(transaction -> {
      acquireExclusiveLock(transaction);
      try {
        final var index = indexes.get(indexName);
        if (index == null) {
          throw new IndexException(session.getDatabaseName(),
              "Index with name " + indexName + " does not exist.");
        }
        index.removeCollection(transaction, collectionName);
      } finally {
        releaseExclusiveLock(session, true);
      }
    });
  }

  public void create(DatabaseSessionEmbedded session) {
    var rid = session.computeInTxInternal(transaction -> {
      acquireExclusiveLock(transaction);
      try {
        var entity = session.newInternalInstance();
        indexManagerIdentity = entity.getIdentity();
        return indexManagerIdentity;
      } finally {
        releaseExclusiveLock(session, false);
      }
    });

    session.getStorage().setIndexMgrRecordId(rid.toString());
  }

  @Override
  public void close() {
    indexes.clear();
    classPropertyIndex.clear();
  }

  private void acquireSharedLock() {
    lock.readLock().lock();
  }

  private void releaseSharedLock() {
    lock.readLock().unlock();
  }

  protected void acquireExclusiveLock(FrontendTransaction transaction) {
    lock.writeLock().lock();
    writeLockNesting.incrementAndGet();
  }

  @Override
  protected Index createIndexInstance(FrontendTransaction transaction,
      Identifiable indexIdentifiable,
      IndexMetadata newIndexMetadata) {
    return Indexes.createIndexInstance(newIndexMetadata.getType(), newIndexMetadata.getAlgorithm(),
        storage, transaction, indexIdentifiable.getIdentity());
  }


  protected void releaseExclusiveLock(DatabaseSessionEmbedded session, boolean notifyChanges) {
    var val = writeLockNesting.decrementAndGet();
    try {
      if (val == 0) {
        session
            .getSharedContext()
            .getSchema()
            .forceSnapshot();
        session.getMetadata().forceClearThreadLocalSchemaSnapshot();

        if (notifyChanges) {
          var sharedContext = session.getSharedContext();
          for (var listener : sharedContext.browseListeners()) {
            try {
              listener.onIndexManagerUpdate(session, session.getDatabaseName(), this);
            } catch (Exception e) {
              LogManager.instance()
                  .error(this, "Error notifying index manager update for listener %s", e, listener);
            }
          }
        }
      }
    } finally {
      lock.writeLock().unlock();
    }
  }


  void addIndexInternal(DatabaseSessionEmbedded session, FrontendTransaction transaction,
      final Index index, boolean updateEntity) {
    acquireExclusiveLock(transaction);
    try {
      addIndexInternalNoLock(index, transaction, updateEntity);
    } finally {
      releaseExclusiveLock(session, true);
    }
  }


  /**
   * Create a new index with default algorithm.
   *
   * @param iName                - name of index
   * @param iType                - index type. Specified by plugged index factories.
   * @param indexDefinition      metadata that describes index structure
   * @param collectionIdsToIndex ids of collections that index should track for changes.
   * @param progressListener     listener to track task progress.
   * @param metadata             entity with additional properties that can be used by index
   *                             engine.
   * @return a newly created index instance
   */
  @Override
  public Index createIndex(
      DatabaseSessionEmbedded session,
      final String iName,
      final String iType,
      final IndexDefinition indexDefinition,
      final int[] collectionIdsToIndex,
      ProgressListener progressListener,
      Map<String, Object> metadata) {
    return createIndex(
        session,
        iName,
        iType,
        indexDefinition,
        collectionIdsToIndex,
        progressListener,
        metadata,
        null);
  }

  /**
   * Create a new index.
   *
   * <p>May require quite a long time if big amount of data should be indexed.
   *
   * @param iName                name of index
   * @param type                 index type. Specified by plugged index factories.
   * @param indexDefinition      metadata that describes index structure
   * @param collectionIdsToIndex ids of collections that index should track for changes.
   * @param progressListener     listener to track task progress.
   * @param metadata             entity with additional properties that can be used by index
   *                             engine.
   * @param algorithm            tip to an index factory what algorithm to use
   * @return a newly created index instance
   */
  @Override
  public Index createIndex(
      DatabaseSessionEmbedded session,
      final String iName,
      String type,
      final IndexDefinition indexDefinition,
      final int[] collectionIdsToIndex,
      ProgressListener progressListener,
      Map<String, Object> metadata,
      String algorithm) {

    final var manualIndexesAreUsed =
        indexDefinition == null
            || indexDefinition.getClassName() == null
            || indexDefinition.getProperties() == null
            || indexDefinition.getProperties().isEmpty();
    if (manualIndexesAreUsed) {
      throw new UnsupportedOperationException("Manual indexes are not supported: " + iName);
    } else {
      checkSecurityConstraintsForIndexCreate(session, indexDefinition);
    }
    if (session.getTransactionInternal().isActive()) {
      throw new IllegalStateException("Cannot create a new index inside a transaction");
    }

    final var c = SchemaShared.checkIndexNameIfValid(iName);
    if (c != null) {
      throw new IllegalArgumentException(
          "Invalid index name '" + iName + "'. Character '" + c + "' is invalid");
    }

    type = type.toUpperCase();
    if (algorithm == null) {
      algorithm = Indexes.chooseDefaultIndexAlgorithm(type);
    }

    var indexType = type;
    var indexAlgorithm = algorithm;
    var idx = session.computeInTxInternal(transaction -> {
      final Index index;
      acquireExclusiveLock(transaction);
      try {

        if (indexes.containsKey(iName)) {
          throw new IndexException(session.getDatabaseName(),
              "Index with name " + iName + " already exists.");
        }

        var currentIndexMetadata = metadata;
        if (currentIndexMetadata == null) {
          currentIndexMetadata = new HashMap<>();
        }

        final var collectionsToIndex = findCollectionsByIds(collectionIdsToIndex, session);
        var ignoreNullValues = currentIndexMetadata.get("ignoreNullValues");
        if (Boolean.TRUE.equals(ignoreNullValues)) {
          indexDefinition.setNullValuesIgnored(true);
        } else if (Boolean.FALSE.equals(ignoreNullValues)) {
          indexDefinition.setNullValuesIgnored(false);
        } else {
          indexDefinition.setNullValuesIgnored(
              storage
                  .getConfiguration()
                  .getContextConfiguration()
                  .getValueAsBoolean(GlobalConfiguration.INDEX_IGNORE_NULL_VALUES_DEFAULT));
        }

        var im =
            new IndexMetadata(
                iName,
                indexDefinition,
                collectionsToIndex,
                indexType,
                indexAlgorithm,
                -1,
                metadata);

        index = createIndexFromMetadata(transaction, storage, im);
        addIndexInternalNoLock(index, transaction, true);
      } finally {
        releaseExclusiveLock(session, true);
      }
      return (IndexAbstract) index;
    });

    if (progressListener == null)
    // ASSIGN DEFAULT PROGRESS LISTENER
    {
      progressListener = new IndexRebuildOutputListener(idx);
    }

    idx.fillIndex(session, progressListener);

    return idx;
  }

  private Index createIndexFromMetadata(
      FrontendTransaction transaction, Storage storage, IndexMetadata indexMetadata) {

    var index = Indexes.createIndexInstance(indexMetadata.getType(), indexMetadata.getAlgorithm(),
        storage);

    index.create(transaction, indexMetadata);
    indexes.put(indexMetadata.getName(), index);

    return index;
  }

  private static void checkSecurityConstraintsForIndexCreate(
      DatabaseSessionEmbedded database, IndexDefinition indexDefinition) {

    var security = database.getSharedContext().getSecurity();

    var indexClass = indexDefinition.getClassName();
    var indexedFields = indexDefinition.getProperties();
    if (indexedFields.size() == 1) {
      return;
    }

    Set<String> classesToCheck = new HashSet<>();
    classesToCheck.add(indexClass);
    var clazz = database.getMetadata().getImmutableSchemaSnapshot().getClass(indexClass);
    if (clazz == null) {
      return;
    }
    clazz.getAllSubclasses().forEach(x -> classesToCheck.add(x.getName()));
    clazz.getAllSuperClasses().forEach(x -> classesToCheck.add(x.getName()));
    var allFilteredProperties =
        security.getAllFilteredProperties(database);

    for (var className : classesToCheck) {
      Set<SecurityResourceProperty> indexedAndFilteredProperties;
      try (var stream = allFilteredProperties.stream()) {
        indexedAndFilteredProperties =
            stream
                .filter(x -> x.getClassName().equalsIgnoreCase(className))
                .filter(x -> indexedFields.contains(x.getPropertyName()))
                .collect(Collectors.toSet());
      }

      if (!indexedAndFilteredProperties.isEmpty()) {
        try (var stream = indexedAndFilteredProperties.stream()) {
          throw new IndexException(database.getDatabaseName(),
              "Cannot create index on "
                  + indexClass
                  + "["
                  + (stream
                  .map(SecurityResourceProperty::getPropertyName)
                  .collect(Collectors.joining(", ")))
                  + " because of existing column security rules");
        }
      }
    }
  }


  private static Set<String> findCollectionsByIds(
      int[] collectionIdsToIndex, DatabaseSessionEmbedded database) {
    Set<String> collectionsToIndex = new HashSet<>();
    if (collectionIdsToIndex != null) {
      for (var collectionId : collectionIdsToIndex) {
        final var collectionNameToIndex = database.getCollectionNameById(collectionId);
        if (collectionNameToIndex == null) {
          throw new IndexException(database.getDatabaseName(),
              "Collection with id " + collectionId + " does not exist.");
        }

        collectionsToIndex.add(collectionNameToIndex);
      }
    }
    return collectionsToIndex;
  }

  @Override
  public void dropIndex(DatabaseSessionEmbedded session, final String iIndexName) {
    if (session.getTransactionInternal().isActive()) {
      throw new IllegalStateException("Cannot drop an index inside a transaction");
    }

    session.executeInTxInternal(transaction -> {
      acquireExclusiveLock(transaction);
      try {
        Index idx;
        idx = indexes.get(iIndexName);
        if (idx != null) {
          removeClassPropertyIndexInternal(idx);
          idx.delete(transaction);
          indexes.remove(iIndexName);
        }
      } finally {
        releaseExclusiveLock(session, true);
      }
    });
  }

  public List<Map<String, Object>> getIndexesConfiguration(DatabaseSessionEmbedded session) {
    acquireSharedLock();
    try {
      return indexes.values().stream()
          .map(index -> index.getConfiguration(session))
          .collect(Collectors.toList());
    } finally {
      releaseSharedLock();
    }
  }


  public void recreateIndexes(DatabaseSessionEmbedded session) {
    session.executeInTxInternal(transaction -> {
      acquireExclusiveLock(transaction);
      try {
        if (recreateIndexesThread != null && recreateIndexesThread.isAlive())
        // BUILDING ALREADY IN PROGRESS
        {
          return;
        }

        Runnable recreateIndexesTask = new RecreateIndexesTask(this, session.getSharedContext());
        recreateIndexesThread = new Thread(recreateIndexesTask, "YouTrackDB rebuild indexes");
        recreateIndexesThread.setUncaughtExceptionHandler(new UncaughtExceptionHandler());
        recreateIndexesThread.start();
      } finally {
        releaseExclusiveLock(session, true);
      }
    });

    if (session
        .getConfiguration()
        .getValueAsBoolean(GlobalConfiguration.INDEX_SYNCHRONOUS_AUTO_REBUILD)) {
      waitTillIndexRestore();
      session.getMetadata().reload();
    }
  }

  public void waitTillIndexRestore() {
    if (recreateIndexesThread != null && recreateIndexesThread.isAlive()) {
      if (Thread.currentThread().equals(recreateIndexesThread)) {
        return;
      }

      LogManager.instance().info(this, "Wait till indexes restore after crash was finished.");
      while (recreateIndexesThread.isAlive()) {
        try {
          recreateIndexesThread.join();
          LogManager.instance().info(this, "Indexes restore after crash was finished.");
        } catch (InterruptedException e) {
          LogManager.instance().info(this, "Index rebuild task was interrupted.", e);
        }
      }
    }
  }

  public boolean autoRecreateIndexesAfterCrash(DatabaseSessionEmbedded session) {
    if (rebuildCompleted) {
      return false;
    }

    final var storage = session.getStorage();
    if (storage instanceof AbstractStorage paginatedStorage) {
      return paginatedStorage.wereDataRestoredAfterOpen()
          && paginatedStorage.wereNonTxOperationsPerformedInPreviousOpen();
    }

    return false;
  }


  public void removeClassPropertyIndex(DatabaseSessionEmbedded session, final Index idx) {
    session.executeInTxInternal(transaction -> {
      acquireExclusiveLock(transaction);
      try {
        removeClassPropertyIndexInternal(idx);
      } finally {
        releaseExclusiveLock(session, true);
      }
    });
  }

  private void removeClassPropertyIndexInternal(Index idx) {
    final var indexDefinition = idx.getDefinition();
    if (indexDefinition == null || indexDefinition.getClassName() == null) {
      return;
    }

    var map =
        classPropertyIndex.get(indexDefinition.getClassName().toLowerCase());

    if (map == null) {
      return;
    }

    map = new HashMap<>(map);

    final var paramCount = indexDefinition.getParamCount();

    for (var i = 1; i <= paramCount; i++) {
      final var fields = indexDefinition.getProperties().subList(0, i);
      final var multiKey = new MultiKey(fields);

      var indexSet = map.get(multiKey);
      if (indexSet == null) {
        continue;
      }

      indexSet = new HashSet<>(indexSet);
      indexSet.remove(idx);

      if (indexSet.isEmpty()) {
        map.remove(multiKey);
      } else {
        map.put(multiKey, indexSet);
      }
    }

    if (map.isEmpty()) {
      classPropertyIndex.remove(indexDefinition.getClassName().toLowerCase());
    } else {
      classPropertyIndex.put(indexDefinition.getClassName().toLowerCase(),
          copyPropertyMap(map));
    }
  }

  protected Storage getStorage() {
    return storage;
  }
}
