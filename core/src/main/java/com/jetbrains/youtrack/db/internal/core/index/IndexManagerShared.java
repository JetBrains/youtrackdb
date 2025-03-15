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
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.common.listener.ProgressListener;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.common.util.MultiKey;
import com.jetbrains.youtrack.db.internal.common.util.UncaughtExceptionHandler;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.TrackedSet;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaShared;
import com.jetbrains.youtrack.db.internal.core.metadata.security.SecurityResourceProperty;
import com.jetbrains.youtrack.db.internal.core.record.RecordInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.storage.Storage;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.AbstractPaginatedStorage;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * Manages indexes at database level. A single instance is shared among multiple databases.
 * Contentions are managed by r/w locks.
 */
public class IndexManagerShared implements IndexManagerAbstract {

  private transient volatile Thread recreateIndexesThread = null;
  volatile boolean rebuildCompleted = false;
  final Storage storage;
  // values of this Map should be IMMUTABLE !! for thread safety reasons.
  protected final Map<String, Map<MultiKey, Set<Index>>> classPropertyIndex =
      new ConcurrentHashMap<>();
  protected Map<String, Index> indexes = new ConcurrentHashMap<>();
  protected final AtomicInteger writeLockNesting = new AtomicInteger();

  protected final ReadWriteLock lock = new ReentrantReadWriteLock();
  protected RID identity;

  public IndexManagerShared(Storage storage) {
    super();
    this.storage = storage;
  }

  public void load(DatabaseSessionInternal session) {
    if (!autoRecreateIndexesAfterCrash(session)) {
      acquireExclusiveLock(session);
      try {
        if (session.getStorageInfo().getConfiguration().getIndexMgrRecordId() == null)
        // @COMPATIBILITY: CREATE THE INDEX MGR
        {
          create(session);
        }
        identity =
            new RecordId(session.getStorageInfo().getConfiguration().getIndexMgrRecordId());
        // RELOAD IT
        EntityImpl entity = session.load(identity);
        fromStream(session, entity);
        RecordInternal.unsetDirty(entity);
        entity.unload();
      } finally {
        releaseExclusiveLock(session);
      }
    }
  }

  public void reload(DatabaseSessionInternal session) {
    acquireExclusiveLock(session);
    try {
      session.executeInTx(
          () -> {
            EntityImpl entity = session.load(identity);
            fromStream(session, entity);
          });
    } finally {
      releaseExclusiveLock(session);
    }
  }

  public void save(DatabaseSessionInternal session) {
    internalSave(session);
  }

  public void addClusterToIndex(DatabaseSessionInternal session, final String clusterName,
      final String indexName) {
    acquireSharedLock();
    try {
      final var index = indexes.get(indexName);
      if (index.getClusters().contains(clusterName)) {
        return;
      }
    } finally {
      releaseSharedLock();
    }
    acquireExclusiveLock(session);
    try {
      final var index = indexes.get(indexName);
      if (index == null) {
        throw new IndexException(session.getDatabaseName(),
            "Index with name " + indexName + " does not exist.");
      }
      if (!index.getClusters().contains(clusterName)) {
        index.addCluster(session, clusterName);
      }
    } finally {
      releaseExclusiveLock(session, true);
    }
  }

  public void removeClusterFromIndex(DatabaseSessionInternal session, final String clusterName,
      final String indexName) {
    acquireSharedLock();
    try {
      final var index = indexes.get(indexName);
      if (!index.getClusters().contains(clusterName)) {
        return;
      }
    } finally {
      releaseSharedLock();
    }
    acquireExclusiveLock(session);
    try {
      final var index = indexes.get(indexName);
      if (index == null) {
        throw new IndexException(session.getDatabaseName(),
            "Index with name " + indexName + " does not exist.");
      }
      index.removeCluster(session, clusterName);
    } finally {
      releaseExclusiveLock(session, true);
    }
  }

  public void create(DatabaseSessionInternal session) {
    acquireExclusiveLock(session);
    try {
      var entity = session.computeInTx(session::newInternalInstance);
      identity = entity.getIdentity();
      session.getStorage().setIndexMgrRecordId(entity.getIdentity().toString());
    } finally {
      releaseExclusiveLock(session);
    }
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

  public EntityImpl getConfiguration(DatabaseSessionInternal session) {
    acquireSharedLock();
    try {
      return getEntity(session);
    } finally {
      releaseSharedLock();
    }
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
    final Set<Index> transactionalResult = new HashSet<>(rawResult.size());
    for (final var index : rawResult) {
      // ignore indexes that ignore null values on partial match
      if (fields.size() == index.getDefinition().getFields().size()
          || !index.getDefinition().isNullValuesIgnored()) {
        transactionalResult.add(index);
      }
    }

    return transactionalResult;
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

  protected void acquireExclusiveLock(DatabaseSessionInternal db) {
    internalAcquireExclusiveLock(db);
    writeLockNesting.incrementAndGet();
  }

  void internalAcquireExclusiveLock(DatabaseSessionInternal db) {
    if (!db.isClosed()) {
      final var metadata = db.getMetadata();
      if (metadata != null) {
        metadata.makeThreadLocalSchemaSnapshot();
      }
      db.startExclusiveMetadataChange();
    }

    lock.writeLock().lock();
  }

  protected void releaseExclusiveLock(DatabaseSessionInternal session) {
    releaseExclusiveLock(session, false);
  }

  protected void releaseExclusiveLock(DatabaseSessionInternal session, boolean save) {
    var val = writeLockNesting.decrementAndGet();
    try {
      if (val == 0) {
        if (save) {
          internalSave(session);
        }
      }
    } finally {
      internalReleaseExclusiveLock(session);
    }
    if (val == 0) {
      session
          .getSharedContext()
          .getSchema()
          .forceSnapshot(session);
    }
  }

  void internalReleaseExclusiveLock(DatabaseSessionInternal db) {
    lock.writeLock().unlock();

    if (!db.isClosed()) {
      db.endExclusiveMetadataChange();
      final var metadata = db.getMetadata();
      if (metadata != null) {
        metadata.clearThreadLocalSchemaSnapshot();
      }
    }
  }

  void clearMetadata(DatabaseSessionInternal session) {
    acquireExclusiveLock(session);
    try {
      indexes.clear();
      classPropertyIndex.clear();
    } finally {
      releaseExclusiveLock(session);
    }
  }


  void addIndexInternal(DatabaseSessionInternal session, final Index index) {
    acquireExclusiveLock(session);
    try {
      addIndexInternalNoLock(index);
    } finally {
      releaseExclusiveLock(session);
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
   * @param iName             - name of index
   * @param iType             - index type. Specified by plugged index factories.
   * @param indexDefinition   metadata that describes index structure
   * @param clusterIdsToIndex ids of clusters that index should track for changes.
   * @param progressListener  listener to track task progress.
   * @param metadata          entity with additional properties that can be used by index engine.
   * @return a newly created index instance
   */
  public Index createIndex(
      DatabaseSessionInternal session,
      final String iName,
      final String iType,
      final IndexDefinition indexDefinition,
      final int[] clusterIdsToIndex,
      ProgressListener progressListener,
      Map<String, Object> metadata) {
    return createIndex(
        session,
        iName,
        iType,
        indexDefinition,
        clusterIdsToIndex,
        progressListener,
        metadata,
        null);
  }

  /**
   * Create a new index.
   *
   * <p>May require quite a long time if big amount of data should be indexed.
   *
   * @param iName             name of index
   * @param type              index type. Specified by plugged index factories.
   * @param indexDefinition   metadata that describes index structure
   * @param clusterIdsToIndex ids of clusters that index should track for changes.
   * @param progressListener  listener to track task progress.
   * @param metadata          entity with additional properties that can be used by index engine.
   * @param algorithm         tip to an index factory what algorithm to use
   * @return a newly created index instance
   */
  public Index createIndex(
      DatabaseSessionInternal session,
      final String iName,
      String type,
      final IndexDefinition indexDefinition,
      final int[] clusterIdsToIndex,
      ProgressListener progressListener,
      Map<String, Object> metadata,
      String algorithm) {

    final var manualIndexesAreUsed =
        indexDefinition == null
            || indexDefinition.getClassName() == null
            || indexDefinition.getFields() == null
            || indexDefinition.getFields().isEmpty();
    if (manualIndexesAreUsed) {
      IndexAbstract.manualIndexesWarning(session.getDatabaseName());
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

    if (indexDefinition == null) {
      throw new IllegalArgumentException("Index definition cannot be null");
    }

    type = type.toUpperCase();
    if (algorithm == null) {
      algorithm = Indexes.chooseDefaultIndexAlgorithm(type);
    }

    final Index index;
    acquireExclusiveLock(session);
    try {

      if (indexes.containsKey(iName)) {
        throw new IndexException(session.getDatabaseName(),
            "Index with name " + iName + " already exists.");
      }

      if (metadata == null) {
        metadata = new HashMap<>();
      }

      final var clustersToIndex = findClustersByIds(clusterIdsToIndex, session);
      var ignoreNullValues = metadata.get("ignoreNullValues");
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
              clustersToIndex,
              type,
              algorithm,
              -1,
              metadata);

      index = createIndexFromMetadata(session, storage, im, progressListener);

      addIndexInternal(session, index);

    } finally {
      releaseExclusiveLock(session, true);
    }

    return index;
  }

  private Index createIndexFromMetadata(
      DatabaseSessionInternal session, Storage storage, IndexMetadata indexMetadata,
      ProgressListener progressListener) {

    var index = Indexes.createIndex(storage, indexMetadata);
    if (progressListener == null)
    // ASSIGN DEFAULT PROGRESS LISTENER
    {
      progressListener = new IndexRebuildOutputListener(index);
    }
    indexes.put(index.getName(), index);
    try {
      index.create(session, indexMetadata, true, progressListener);
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


  private static Set<String> findClustersByIds(
      int[] clusterIdsToIndex, DatabaseSessionInternal database) {
    Set<String> clustersToIndex = new HashSet<>();
    if (clusterIdsToIndex != null) {
      for (var clusterId : clusterIdsToIndex) {
        final var clusterNameToIndex = database.getClusterNameById(clusterId);
        if (clusterNameToIndex == null) {
          throw new IndexException(database.getDatabaseName(),
              "Cluster with id " + clusterId + " does not exist.");
        }

        clustersToIndex.add(clusterNameToIndex);
      }
    }
    return clustersToIndex;
  }

  public void dropIndex(DatabaseSessionInternal session, final String iIndexName) {
    if (session.getTransactionInternal().isActive()) {
      throw new IllegalStateException("Cannot drop an index inside a transaction");
    }

    int[] clusterIdsToIndex;

    acquireExclusiveLock(session);

    Index idx;
    try {
      idx = indexes.get(iIndexName);
      if (idx != null) {
        final var clusters = idx.getClusters();
        if (clusters != null && !clusters.isEmpty()) {
          clusterIdsToIndex = new int[clusters.size()];
          var i = 0;
          for (var cl : clusters) {
            clusterIdsToIndex[i++] = session.getClusterIdByName(cl);
          }
        }

        removeClassPropertyIndex(session, idx);

        idx.delete(session);
        indexes.remove(iIndexName);
      }
    } finally {
      releaseExclusiveLock(session, true);
    }
  }

  /**
   * Binds POJO to EntityImpl.
   */
  public EntityImpl toStream(DatabaseSessionInternal session) {
    internalAcquireExclusiveLock(session);
    try {
      EntityImpl entity = session.load(identity);
      final var indexes = new TrackedSet<Map<String, ?>>(entity);

      for (final var index : this.indexes.values()) {
        indexes.add(index.updateConfiguration(session));
      }

      entity.setProperty(CONFIG_INDEXES, indexes, PropertyType.EMBEDDEDSET);
      entity.setDirty();

      return entity;
    } finally {
      internalReleaseExclusiveLock(session);
    }
  }

  @Override
  public void recreateIndexes(DatabaseSessionInternal database) {
    acquireExclusiveLock(database);
    try {
      if (recreateIndexesThread != null && recreateIndexesThread.isAlive())
      // BUILDING ALREADY IN PROGRESS
      {
        return;
      }

      Runnable recreateIndexesTask = new RecreateIndexesTask(this, database.getSharedContext());
      recreateIndexesThread = new Thread(recreateIndexesTask, "YouTrackDB rebuild indexes");
      recreateIndexesThread.setUncaughtExceptionHandler(new UncaughtExceptionHandler());
      recreateIndexesThread.start();
    } finally {
      releaseExclusiveLock(database);
    }

    if (database
        .getConfiguration()
        .getValueAsBoolean(GlobalConfiguration.INDEX_SYNCHRONOUS_AUTO_REBUILD)) {
      waitTillIndexRestore();

      database.getMetadata().reload();
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
    if (storage instanceof AbstractPaginatedStorage paginatedStorage) {
      return paginatedStorage.wereDataRestoredAfterOpen()
          && paginatedStorage.wereNonTxOperationsPerformedInPreviousOpen();
    }

    return false;
  }

  protected void fromStream(DatabaseSessionInternal session, EntityImpl entity) {
    internalAcquireExclusiveLock(session);
    try {
      final Map<String, Index> oldIndexes = new HashMap<>(indexes);
      clearMetadata(session);

      final Collection<Map<String, ?>> indexEntities = entity.getProperty(CONFIG_INDEXES);

      if (indexEntities != null) {
        Index index;
        var configUpdated = false;
        var indexConfigurationIterator = indexEntities.iterator();
        while (indexConfigurationIterator.hasNext()) {
          final var d = indexConfigurationIterator.next();
          try {

            final var newIndexMetadata = IndexAbstract.loadMetadataFromMap(session, d);

            index = Indexes.createIndex(storage, newIndexMetadata);

            final var normalizedName = newIndexMetadata.getName();

            var oldIndex = oldIndexes.remove(normalizedName);
            if (oldIndex != null) {
              var oldIndexMetadata =
                  oldIndex.loadMetadata(session, oldIndex.getConfiguration(session));

              if (!(oldIndexMetadata.equals(newIndexMetadata)
                  || newIndexMetadata.getIndexDefinition() == null)) {
                oldIndex.delete(session);
              }

              if (index.loadFromConfiguration(session, d)) {
                addIndexInternalNoLock(index);
              } else {
                indexConfigurationIterator.remove();
                configUpdated = true;
              }
            } else {
              if (index.loadFromConfiguration(session, d)) {
                addIndexInternalNoLock(index);
              } else {
                indexConfigurationIterator.remove();
                configUpdated = true;
              }
            }
          } catch (RuntimeException e) {
            indexConfigurationIterator.remove();
            configUpdated = true;
            LogManager.instance().error(this, "Error on loading index by configuration: %s", e, d);
          }
        }

        for (var oldIndex : oldIndexes.values()) {
          try {
            LogManager.instance()
                .warn(
                    this,
                    "Index '%s' was not found after reload and will be removed",
                    oldIndex.getName());

            oldIndex.delete(session);
          } catch (Exception e) {
            LogManager.instance()
                .error(this, "Error on deletion of index '%s'", e, oldIndex.getName());
          }
        }

        if (configUpdated) {
          entity.setProperty(CONFIG_INDEXES, indexEntities);
          save(session);
        }
      }
    } finally {
      internalReleaseExclusiveLock(session);
    }
  }

  public void removeClassPropertyIndex(DatabaseSessionInternal session, final Index idx) {
    acquireExclusiveLock(session);
    try {
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
        classPropertyIndex.put(indexDefinition.getClassName().toLowerCase(), copyPropertyMap(map));
      }

    } finally {
      releaseExclusiveLock(session);
    }
  }

  @Override
  public RID getIdentity() {
    return identity;
  }

  protected Storage getStorage() {
    return storage;
  }

  @Override
  public EntityImpl getEntity(DatabaseSessionInternal session) {
    return toStream(session);
  }

  private void internalSave(DatabaseSessionInternal session) {
    session.begin();
    var entity = toStream(session);

    session.commit();
  }

}
