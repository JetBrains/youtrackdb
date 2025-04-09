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
package com.jetbrains.youtrack.db.internal.core.index;

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.common.listener.ProgressListener;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.common.util.MultiKey;
import com.jetbrains.youtrack.db.internal.common.util.UncaughtExceptionHandler;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaShared;
import com.jetbrains.youtrack.db.internal.core.metadata.security.SecurityResourceProperty;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.storage.Storage;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransaction;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * Manages indexes at database level. A single instance is shared among multiple databases.
 * Contentions are managed by r/w locks.
 */
public class IndexManagerShared implements IndexManagerAbstract {
  private volatile Thread recreateIndexesThread = null;

  volatile boolean rebuildCompleted = false;

  final Storage storage;
  protected final Map<String, Map<MultiKey, Set<Index>>> classPropertyIndex =
      new ConcurrentHashMap<>();
  protected Map<String, Index> indexes = new ConcurrentHashMap<>();
  protected final AtomicInteger writeLockNesting = new AtomicInteger();

  private boolean contentDirty;
  private boolean metadataDirty;

  private final ReadWriteLock lock = new ReentrantReadWriteLock();
  private RID identity;

  public IndexManagerShared(Storage storage) {
    super();
    this.storage = storage;
  }

  public void load(DatabaseSessionInternal session) {
    if (!autoRecreateIndexesAfterCrash(session)) {
      session.executeInTxInternal(transaction -> {
        acquireExclusiveLock(transaction);
        try {
          identity =
              new RecordId(session.getStorageInfo().getConfiguration().getIndexMgrRecordId());
          var entity = (EntityImpl) session.loadEntity(identity);
          fromStream(transaction, entity);
        } finally {
          releaseExclusiveLock(session, transaction);
        }
      });
    }
  }

  public void reload(DatabaseSessionInternal session) {
    session.executeInTxInternal(
        transaction -> {
          acquireExclusiveLock(transaction);
          try {
            var entity = (EntityImpl) transaction.loadEntity(identity);
            fromStream(transaction, entity);
          } finally {
            releaseExclusiveLock(session, transaction);
          }
        });
  }

  public void addCollectionToIndex(DatabaseSessionInternal session, final String collectionName,
      final String indexName) {
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
          index.addCollection(transaction.getSession(), collectionName);
        }
      } finally {
        releaseExclusiveLock(session, transaction);
      }
    });
  }

  public void removeCollectionFromIndex(DatabaseSessionInternal session,
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
        index.removeCollection(session, collectionName);
      } finally {
        releaseExclusiveLock(session, transaction);
      }
    });
  }

  public void create(DatabaseSessionInternal session) {
    var rid = session.computeInTxInternal(transaction -> {
      acquireExclusiveLock(transaction);
      try {
        var entity = session.newInternalInstance();
        identity = entity.getIdentity();
        return identity;
      } finally {
        releaseExclusiveLock(session, transaction);
      }
    });

    session.getStorage().setIndexMgrRecordId(rid.toString());
  }

  public Collection<? extends Index> getIndexes(DatabaseSessionInternal session) {
    return indexes.values();
  }

  public Index getIndex(DatabaseSessionInternal session, final String iName) {
    return indexes.get(iName);
  }

  public boolean existsIndex(DatabaseSessionInternal session, final String iName) {
    return indexes.containsKey(iName);
  }

  @Override
  public void close() {
    indexes.clear();
    classPropertyIndex.clear();
  }

  public Set<Index> getClassInvolvedIndexes(
      DatabaseSessionInternal session, final String className, Collection<String> fields) {
    final var multiKey = new MultiKey(fields);

    final var propertyIndex = getIndexOnProperty(className);

    if (propertyIndex == null || !propertyIndex.containsKey(multiKey)) {
      return Collections.emptySet();
    }

    final var rawResult = propertyIndex.get(multiKey);
    final Set<Index> result = new HashSet<>(rawResult.size());
    for (final var index : rawResult) {
      // ignore indexes that ignore null values on partial match
      if (fields.size() == index.getDefinition().getFields().size()
          || !index.getDefinition().isNullValuesIgnored()) {
        result.add(index);
      }
    }

    return result;
  }

  public Set<Index> getClassInvolvedIndexes(
      DatabaseSessionInternal session, final String className, final String... fields) {
    return getClassInvolvedIndexes(session, className, Arrays.asList(fields));
  }

  public boolean areIndexed(DatabaseSessionInternal session, final String className,
      Collection<String> fields) {
    final var multiKey = new MultiKey(fields);

    final var propertyIndex = getIndexOnProperty(className);

    if (propertyIndex == null) {
      return false;
    }

    return propertyIndex.containsKey(multiKey) && !propertyIndex.get(multiKey).isEmpty();
  }

  public boolean areIndexed(DatabaseSessionInternal session, final String className,
      final String... fields) {
    return areIndexed(session, className, Arrays.asList(fields));
  }

  public Set<Index> getClassIndexes(DatabaseSessionInternal session, final String className) {
    final var coll = new HashSet<Index>(4);
    getClassIndexes(session, className, coll);
    return coll;
  }

  public void getClassIndexes(
      DatabaseSessionInternal session, final String className,
      final Collection<Index> indexes) {
    final var propertyIndex = getIndexOnProperty(className);

    if (propertyIndex == null) {
      return;
    }

    for (final var propertyIndexes : propertyIndex.values()) {
      indexes.addAll(propertyIndexes);
    }
  }

  public void getClassRawIndexes(DatabaseSessionInternal session,
      final String className, final Collection<Index> indexes) {
    final var propertyIndex = getIndexOnProperty(className);

    if (propertyIndex == null) {
      return;
    }

    for (final var propertyIndexes : propertyIndex.values()) {
      indexes.addAll(propertyIndexes);
    }
  }

  public IndexUnique getClassUniqueIndex(DatabaseSessionInternal session, final String className) {
    final var propertyIndex = getIndexOnProperty(className);

    if (propertyIndex != null) {
      for (final var propertyIndexes : propertyIndex.values()) {
        for (final var index : propertyIndexes) {
          if (index instanceof IndexUnique) {
            return (IndexUnique) index;
          }
        }
      }
    }

    return null;
  }

  @Nullable
  public Index getClassIndex(
      DatabaseSessionInternal session, String className, String indexName) {
    className = className.toLowerCase();

    final var index = indexes.get(indexName);
    if (index != null
        && index.getDefinition() != null
        && index.getDefinition().getClassName() != null
        && className.equals(index.getDefinition().getClassName().toLowerCase())) {
      return index;
    }
    return null;
  }

  private void acquireSharedLock() {
    lock.readLock().lock();
  }

  private void releaseSharedLock() {
    lock.readLock().unlock();
  }

  protected void acquireExclusiveLock(FrontendTransaction transaction) {
    transaction.getSession().startExclusiveMetadataChange();

    lock.writeLock().lock();
    writeLockNesting.incrementAndGet();
  }


  protected void releaseExclusiveLock(DatabaseSessionInternal session,
      FrontendTransaction transaction) {
    var val = writeLockNesting.decrementAndGet();
    try {
      if (val == 0) {
        if (transaction.isActive()) {
          save(transaction);
        }
      }

      if (val == 0) {
        session
            .getSharedContext()
            .getSchema()
            .forceSnapshot();

      }
    } finally {
      lock.writeLock().unlock();
      session.endExclusiveMetadataChange();
    }

    session.getMetadata().forceClearThreadLocalSchemaSnapshot();
  }


  void addIndexInternal(DatabaseSessionInternal session, FrontendTransaction transaction,
      final Index index) {
    acquireExclusiveLock(transaction);
    try {
      addIndexInternalNoLock(index);
    } finally {
      releaseExclusiveLock(session, transaction);
    }
  }

  private void addIndexInternalNoLock(final Index index) {
    indexes.put(index.getName(), index);

    final var indexDefinition = index.getDefinition();
    if (indexDefinition == null || indexDefinition.getClassName() == null) {
      return;
    }

    var propertyIndex = getIndexOnProperty(indexDefinition.getClassName());

    if (propertyIndex == null) {
      propertyIndex = new HashMap<>();
    } else {
      propertyIndex = new HashMap<>(propertyIndex);
    }

    final var paramCount = indexDefinition.getParamCount();

    for (var i = 1; i <= paramCount; i++) {
      final var fields = indexDefinition.getFields().subList(0, i);
      final var multiKey = new MultiKey(fields);
      var indexSet = propertyIndex.get(multiKey);

      if (indexSet == null) {
        indexSet = new HashSet<>();
      } else {
        indexSet = new HashSet<>(indexSet);
      }

      indexSet.add(index);
      propertyIndex.put(multiKey, indexSet);
    }

    classPropertyIndex.put(
        indexDefinition.getClassName().toLowerCase(), copyPropertyMap(propertyIndex));
  }

  static Map<MultiKey, Set<Index>> copyPropertyMap(Map<MultiKey, Set<Index>> original) {
    final Map<MultiKey, Set<Index>> result = new HashMap<>();

    for (var entry : original.entrySet()) {
      Set<Index> indexes = new HashSet<>(entry.getValue());
      assert indexes.equals(entry.getValue());

      result.put(entry.getKey(), Collections.unmodifiableSet(indexes));
    }

    assert result.equals(original);

    return Collections.unmodifiableMap(result);
  }

  private Map<MultiKey, Set<Index>> getIndexOnProperty(final String className) {
    return classPropertyIndex.get(className.toLowerCase());
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
  public Index createIndex(
      DatabaseSessionInternal session,
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
  public Index createIndex(
      DatabaseSessionInternal session,
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
            || indexDefinition.getFields() == null
            || indexDefinition.getFields().isEmpty();
    if (manualIndexesAreUsed) {
      throw new UnsupportedOperationException("Manual indexes are not supported");
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

        index = createIndexFromMetadata(transaction, storage, im, progressListener);

        addIndexInternalNoLock(index);
        index.markDirty();
        contentDirty = true;
      } finally {
        releaseExclusiveLock(session, transaction);
      }
      return index;
    });

    idx.rebuild(session);

    return idx;
  }

  private Index createIndexFromMetadata(
      FrontendTransaction transaction, Storage storage, IndexMetadata indexMetadata,
      ProgressListener progressListener) {

    var index = Indexes.createIndex(indexMetadata, null, this, storage);
    if (progressListener == null)
    // ASSIGN DEFAULT PROGRESS LISTENER
    {
      progressListener = new IndexRebuildOutputListener(index);
    }
    indexes.put(index.getName(), index);
    try {
      index.create(transaction, indexMetadata, progressListener);
    } catch (Throwable e) {
      indexes.remove(index.getName());
      throw e;
    }

    return index;
  }

  private static void checkSecurityConstraintsForIndexCreate(
      DatabaseSessionInternal database, IndexDefinition indexDefinition) {

    var security = database.getSharedContext().getSecurity();

    var indexClass = indexDefinition.getClassName();
    var indexedFields = indexDefinition.getFields();
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
      int[] collectionIdsToIndex, DatabaseSessionInternal database) {
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

  public void dropIndex(DatabaseSessionInternal session, final String iIndexName) {
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

          var indexEntity = transaction.loadEntity(idx.getIdentity());
          indexEntity.delete();

          contentDirty = true;
        }
      } finally {
        releaseExclusiveLock(session, transaction);
      }
    });
  }

  /**
   * Binds POJO to EntityImpl.
   */
  private void save(FrontendTransaction transaction) {
    if (contentDirty) {
      var entity = (EntityImpl) transaction.loadEntity(identity);
      var indexLinks = transaction.newLinkSet();

      for (final var index : this.indexes.values()) {
        indexLinks.add(index.save(transaction));
      }

      entity.setLinkSet(CONFIG_INDEXES, indexLinks);
    } else if (metadataDirty) {
      for (final var index : this.indexes.values()) {
        index.save(transaction);
      }
    }

    metadataDirty = false;
    contentDirty = false;
  }

  @Override
  public List<Map<String, Object>> getIndexesConfiguration(DatabaseSessionInternal session) {
    acquireSharedLock();
    try {
      return indexes.values().stream()
          .map(index -> index.getConfiguration(session))
          .collect(Collectors.toList());
    } finally {
      releaseSharedLock();
    }
  }

  @Override
  public void recreateIndexes(DatabaseSessionInternal session) {
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
        releaseExclusiveLock(session, transaction);
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

  public boolean autoRecreateIndexesAfterCrash(DatabaseSessionInternal session) {
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

  private void fromStream(FrontendTransaction transaction, EntityImpl entity) {
    indexes.clear();
    classPropertyIndex.clear();

    final var indexEntities = entity.getLinkSet(CONFIG_INDEXES);
    if (indexEntities != null) {
      for (var indexIdentifiable : indexEntities) {
        var indexEntity = transaction.loadEntity(indexIdentifiable);
        final var newIndexMetadata = IndexAbstract.loadMetadataFromMap(transaction,
            indexEntity.toMap(false));

        var index =
            Indexes.createIndex(newIndexMetadata, indexEntity.getIdentity(), this,
                storage);

        index.load(transaction);
        addIndexInternalNoLock(index);
      }
    }
  }

  public void removeClassPropertyIndex(DatabaseSessionInternal session, final Index idx) {
    session.executeInTxInternal(transaction -> {
      acquireExclusiveLock(transaction);
      try {
        removeClassPropertyIndexInternal(idx);
      } finally {
        releaseExclusiveLock(session, transaction);
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
      final var fields = indexDefinition.getFields().subList(0, i);
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

  @Override
  public RID getIdentity() {
    return identity;
  }

  @Override
  public void markMetadataDirty() {
    metadataDirty = true;
  }

  protected Storage getStorage() {
    return storage;
  }
}
