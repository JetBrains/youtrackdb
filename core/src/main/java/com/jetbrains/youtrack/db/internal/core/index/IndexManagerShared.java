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
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.common.listener.ProgressListener;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.common.util.MultiKey;
import com.jetbrains.youtrack.db.internal.common.util.UncaughtExceptionHandler;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseRecordThreadLocal;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.MetadataUpdateListener;
import com.jetbrains.youtrack.db.internal.core.db.record.TrackedSet;
import com.jetbrains.youtrack.db.internal.core.dictionary.Dictionary;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.metadata.Metadata;
import com.jetbrains.youtrack.db.internal.core.metadata.MetadataDefault;
import com.jetbrains.youtrack.db.internal.core.metadata.MetadataInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaClassImpl;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaShared;
import com.jetbrains.youtrack.db.internal.core.metadata.security.SecurityInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.security.SecurityResourceProperty;
import com.jetbrains.youtrack.db.internal.core.record.RecordInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.sharding.auto.AutoShardingIndexFactory;
import com.jetbrains.youtrack.db.internal.core.storage.Storage;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.AbstractPaginatedStorage;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
  protected String defaultClusterName = MetadataDefault.CLUSTER_INDEX_NAME;
  protected final AtomicInteger writeLockNesting = new AtomicInteger();

  protected final ReadWriteLock lock = new ReentrantReadWriteLock();
  protected RID identity;

  public IndexManagerShared(Storage storage) {
    super();
    this.storage = storage;
  }

  public void load(DatabaseSessionInternal database) {
    if (!autoRecreateIndexesAfterCrash(database)) {
      acquireExclusiveLock();
      try {
        if (database.getStorageInfo().getConfiguration().getIndexMgrRecordId() == null)
        // @COMPATIBILITY: CREATE THE INDEX MGR
        {
          create(database);
        }
        identity =
            new RecordId(database.getStorageInfo().getConfiguration().getIndexMgrRecordId());
        // RELOAD IT
        EntityImpl entity = database.load(identity);
        fromStream(database, entity);
        RecordInternal.unsetDirty(entity);
        entity.unload();
      } finally {
        releaseExclusiveLock(database);
      }
    }
  }

  public void reload(DatabaseSessionInternal session) {
    acquireExclusiveLock();
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
      final Index index = indexes.get(indexName);
      if (index.getInternal().getClusters().contains(clusterName)) {
        return;
      }
    } finally {
      releaseSharedLock();
    }
    acquireExclusiveLock();
    try {
      final Index index = indexes.get(indexName);
      if (index == null) {
        throw new IndexException("Index with name " + indexName + " does not exist.");
      }

      if (index.getInternal() == null) {
        throw new IndexException(
            "Index with name " + indexName + " has no internal presentation.");
      }
      if (!index.getInternal().getClusters().contains(clusterName)) {
        index.getInternal().addCluster(session, clusterName);
      }
    } finally {
      releaseExclusiveLock(session, true);
    }
  }

  public void removeClusterFromIndex(DatabaseSessionInternal session, final String clusterName,
      final String indexName) {
    acquireSharedLock();
    try {
      final Index index = indexes.get(indexName);
      if (!index.getInternal().getClusters().contains(clusterName)) {
        return;
      }
    } finally {
      releaseSharedLock();
    }
    acquireExclusiveLock();
    try {
      final Index index = indexes.get(indexName);
      if (index == null) {
        throw new IndexException("Index with name " + indexName + " does not exist.");
      }
      index.getInternal().removeCluster(session, clusterName);
    } finally {
      releaseExclusiveLock(session, true);
    }
  }

  public void create(DatabaseSessionInternal database) {
    acquireExclusiveLock();
    try {
      EntityImpl entity =
          database.computeInTx(
              () -> database.save(new EntityImpl(), MetadataDefault.CLUSTER_INTERNAL_NAME));
      identity = entity.getIdentity();
      database.getStorage().setIndexMgrRecordId(entity.getIdentity().toString());
    } finally {
      releaseExclusiveLock(database);
    }
  }

  public Collection<? extends Index> getIndexes(DatabaseSessionInternal database) {
    return indexes.values();
  }

  public Index getRawIndex(final String iName) {
    final Index index = indexes.get(iName);
    return index;
  }

  public Index getIndex(DatabaseSessionInternal database, final String iName) {
    final Index index = indexes.get(iName);
    return index;
  }

  public boolean existsIndex(final String iName) {
    return indexes.containsKey(iName);
  }

  public String getDefaultClusterName() {
    acquireSharedLock();
    try {
      return defaultClusterName;
    } finally {
      releaseSharedLock();
    }
  }

  public void setDefaultClusterName(
      DatabaseSessionInternal database, final String defaultClusterName) {
    acquireExclusiveLock();
    try {
      this.defaultClusterName = defaultClusterName;
    } finally {
      releaseExclusiveLock(database);
    }
  }

  public Dictionary<DBRecord> getDictionary(DatabaseSessionInternal database) {
    Index idx;
    acquireSharedLock();
    try {
      idx = getIndex(database, DICTIONARY_NAME);
    } finally {
      releaseSharedLock();
    }
    // we lock exclusively only when Dictionary not found
    if (idx == null) {
      idx = createDictionaryIfNeeded(database);
    }
    return new Dictionary<>(idx);
  }

  public EntityImpl getConfiguration(DatabaseSessionInternal session) {
    acquireSharedLock();

    try {
      return getDocument(session);
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
      DatabaseSessionInternal database, final String className, Collection<String> fields) {
    final MultiKey multiKey = new MultiKey(fields);

    final Map<MultiKey, Set<Index>> propertyIndex = getIndexOnProperty(className);

    if (propertyIndex == null || !propertyIndex.containsKey(multiKey)) {
      return Collections.emptySet();
    }

    final Set<Index> rawResult = propertyIndex.get(multiKey);
    final Set<Index> transactionalResult = new HashSet<>(rawResult.size());
    for (final Index index : rawResult) {
      // ignore indexes that ignore null values on partial match
      if (fields.size() == index.getDefinition().getFields().size()
          || !index.getDefinition().isNullValuesIgnored()) {
        transactionalResult.add(index);
      }
    }

    return transactionalResult;
  }

  public Set<Index> getClassInvolvedIndexes(
      DatabaseSessionInternal database, final String className, final String... fields) {
    return getClassInvolvedIndexes(database, className, Arrays.asList(fields));
  }

  public boolean areIndexed(final String className, Collection<String> fields) {
    final MultiKey multiKey = new MultiKey(fields);

    final Map<MultiKey, Set<Index>> propertyIndex = getIndexOnProperty(className);

    if (propertyIndex == null) {
      return false;
    }

    return propertyIndex.containsKey(multiKey) && !propertyIndex.get(multiKey).isEmpty();
  }

  public boolean areIndexed(final String className, final String... fields) {
    return areIndexed(className, Arrays.asList(fields));
  }

  public Set<Index> getClassIndexes(DatabaseSessionInternal database, final String className) {
    final HashSet<Index> coll = new HashSet<Index>(4);
    getClassIndexes(database, className, coll);
    return coll;
  }

  public void getClassIndexes(
      DatabaseSessionInternal database, final String className,
      final Collection<Index> indexes) {
    final Map<MultiKey, Set<Index>> propertyIndex = getIndexOnProperty(className);

    if (propertyIndex == null) {
      return;
    }

    for (final Set<Index> propertyIndexes : propertyIndex.values()) {
      for (final Index index : propertyIndexes) {
        indexes.add(index);
      }
    }
  }

  public void getClassRawIndexes(final String className, final Collection<Index> indexes) {
    final Map<MultiKey, Set<Index>> propertyIndex = getIndexOnProperty(className);

    if (propertyIndex == null) {
      return;
    }

    for (final Set<Index> propertyIndexes : propertyIndex.values()) {
      indexes.addAll(propertyIndexes);
    }
  }

  public IndexUnique getClassUniqueIndex(final String className) {
    final Map<MultiKey, Set<Index>> propertyIndex = getIndexOnProperty(className);

    if (propertyIndex != null) {
      for (final Set<Index> propertyIndexes : propertyIndex.values()) {
        for (final Index index : propertyIndexes) {
          if (index instanceof IndexUnique) {
            return (IndexUnique) index;
          }
        }
      }
    }

    return null;
  }

  public Index getClassIndex(
      DatabaseSessionInternal database, String className, String indexName) {
    className = className.toLowerCase();

    final Index index = indexes.get(indexName);
    if (index != null
        && index.getDefinition() != null
        && index.getDefinition().getClassName() != null
        && className.equals(index.getDefinition().getClassName().toLowerCase())) {
      return index;
    }
    return null;
  }

  public Index getClassAutoShardingIndex(DatabaseSessionInternal database, String className) {
    className = className.toLowerCase();

    // LOOK FOR INDEX
    for (Index index : indexes.values()) {
      if (index != null
          && AutoShardingIndexFactory.AUTOSHARDING_ALGORITHM.equals(index.getAlgorithm())
          && index.getDefinition() != null
          && index.getDefinition().getClassName() != null
          && className.equals(index.getDefinition().getClassName().toLowerCase())) {
        return index;
      }
    }
    return null;
  }

  private void acquireSharedLock() {
    lock.readLock().lock();
  }

  private void releaseSharedLock() {
    lock.readLock().unlock();
  }

  protected void acquireExclusiveLock() {
    internalAcquireExclusiveLock();
    writeLockNesting.incrementAndGet();
  }

  void internalAcquireExclusiveLock() {
    final DatabaseSessionInternal databaseRecord = getDatabaseIfDefined();
    if (databaseRecord != null && !databaseRecord.isClosed()) {
      final MetadataInternal metadata = databaseRecord.getMetadata();
      if (metadata != null) {
        metadata.makeThreadLocalSchemaSnapshot();
      }
      databaseRecord.startExclusiveMetadataChange();
    }

    lock.writeLock().lock();
  }

  protected void releaseExclusiveLock(DatabaseSessionInternal session) {
    releaseExclusiveLock(session, false);
  }

  protected void releaseExclusiveLock(DatabaseSessionInternal session, boolean save) {
    int val = writeLockNesting.decrementAndGet();
    try {
      if (val == 0) {
        if (save) {
          internalSave(session);
        }
      }
    } finally {
      internalReleaseExclusiveLock();
    }
    if (val == 0) {
      session
          .getSharedContext()
          .getSchema()
          .forceSnapshot(DatabaseRecordThreadLocal.instance().get());

      for (MetadataUpdateListener listener : session.getSharedContext().browseListeners()) {
        listener.onIndexManagerUpdate(session, session.getName(), this);
      }
    }
  }

  void internalReleaseExclusiveLock() {
    lock.writeLock().unlock();

    final DatabaseSessionInternal databaseRecord = getDatabaseIfDefined();
    if (databaseRecord != null && !databaseRecord.isClosed()) {
      databaseRecord.endExclusiveMetadataChange();
      final Metadata metadata = databaseRecord.getMetadata();
      if (metadata != null) {
        ((MetadataInternal) metadata).clearThreadLocalSchemaSnapshot();
      }
    }
  }

  void clearMetadata(DatabaseSessionInternal session) {
    acquireExclusiveLock();
    try {
      indexes.clear();
      classPropertyIndex.clear();
    } finally {
      releaseExclusiveLock(session);
    }
  }

  private static DatabaseSessionInternal getDatabaseIfDefined() {
    return DatabaseRecordThreadLocal.instance().getIfDefined();
  }

  void addIndexInternal(DatabaseSessionInternal session, final Index index) {
    acquireExclusiveLock();
    try {
      addIndexInternalNoLock(index);
    } finally {
      releaseExclusiveLock(session);
    }
  }

  private void addIndexInternalNoLock(final Index index) {
    indexes.put(index.getName(), index);

    final IndexDefinition indexDefinition = index.getDefinition();
    if (indexDefinition == null || indexDefinition.getClassName() == null) {
      return;
    }

    Map<MultiKey, Set<Index>> propertyIndex = getIndexOnProperty(indexDefinition.getClassName());

    if (propertyIndex == null) {
      propertyIndex = new HashMap<>();
    } else {
      propertyIndex = new HashMap<>(propertyIndex);
    }

    final int paramCount = indexDefinition.getParamCount();

    for (int i = 1; i <= paramCount; i++) {
      final List<String> fields = indexDefinition.getFields().subList(0, i);
      final MultiKey multiKey = new MultiKey(fields);
      Set<Index> indexSet = propertyIndex.get(multiKey);

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

    for (Map.Entry<MultiKey, Set<Index>> entry : original.entrySet()) {
      Set<Index> indexes = new HashSet<>(entry.getValue());
      assert indexes.equals(entry.getValue());

      result.put(entry.getKey(), Collections.unmodifiableSet(indexes));
    }

    assert result.equals(original);

    return Collections.unmodifiableMap(result);
  }

  private Index createDictionaryIfNeeded(DatabaseSessionInternal database) {
    acquireExclusiveLock();
    try {
      Index idx = getIndex(database, DICTIONARY_NAME);
      return idx != null ? idx : createDictionary(database);
    } finally {
      releaseExclusiveLock(database, true);
    }
  }

  private Index createDictionary(DatabaseSessionInternal database) {
    return createIndex(
        database,
        DICTIONARY_NAME,
        SchemaClass.INDEX_TYPE.DICTIONARY.toString(),
        new SimpleKeyIndexDefinition(PropertyType.STRING),
        null,
        null,
        null);
  }

  private Map<MultiKey, Set<Index>> getIndexOnProperty(final String className) {
    acquireSharedLock();
    try {
      return classPropertyIndex.get(className.toLowerCase());

    } finally {
      releaseSharedLock();
    }
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
      DatabaseSessionInternal database,
      final String iName,
      final String iType,
      final IndexDefinition indexDefinition,
      final int[] clusterIdsToIndex,
      ProgressListener progressListener,
      Map<String, ?> metadata) {
    return createIndex(
        database,
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
      DatabaseSessionInternal database,
      final String iName,
      String type,
      final IndexDefinition indexDefinition,
      final int[] clusterIdsToIndex,
      ProgressListener progressListener,
      Map<String, ?> metadata,
      String algorithm) {

    final boolean manualIndexesAreUsed =
        indexDefinition == null
            || indexDefinition.getClassName() == null
            || indexDefinition.getFields() == null
            || indexDefinition.getFields().isEmpty();
    if (manualIndexesAreUsed) {
      IndexAbstract.manualIndexesWarning();
    } else {
      checkSecurityConstraintsForIndexCreate(database, indexDefinition);
    }
    if (database.getTransaction().isActive()) {
      throw new IllegalStateException("Cannot create a new index inside a transaction");
    }

    final Character c = SchemaShared.checkIndexNameIfValid(iName);
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

    final String valueContainerAlgorithm = chooseContainerAlgorithm(type);

    final IndexInternal index;
    acquireExclusiveLock();
    try {

      if (indexes.containsKey(iName)) {
        throw new IndexException("Index with name " + iName + " already exists.");
      }

      if (metadata == null) {
        metadata = new HashMap<>();
      }

      final Set<String> clustersToIndex = findClustersByIds(clusterIdsToIndex, database);
      Object ignoreNullValues = metadata.get("ignoreNullValues");
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

      IndexMetadata im =
          new IndexMetadata(
              iName,
              indexDefinition,
              clustersToIndex,
              type,
              algorithm,
              valueContainerAlgorithm,
              -1,
              metadata);

      index = createIndexFromMetadata(database, storage, im, progressListener);

      addIndexInternal(database, index);

    } finally {
      releaseExclusiveLock(database, true);
      notifyInvolvedClasses(database, clusterIdsToIndex);
    }

    return index;
  }

  private IndexInternal createIndexFromMetadata(
      DatabaseSessionInternal session, Storage storage, IndexMetadata indexMetadata,
      ProgressListener progressListener) {

    IndexInternal index = Indexes.createIndex(storage, indexMetadata);
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

    SecurityInternal security = database.getSharedContext().getSecurity();

    String indexClass = indexDefinition.getClassName();
    List<String> indexedFields = indexDefinition.getFields();
    if (indexedFields.size() == 1) {
      return;
    }

    Set<String> classesToCheck = new HashSet<>();
    classesToCheck.add(indexClass);
    SchemaClass clazz = database.getMetadata().getImmutableSchemaSnapshot().getClass(indexClass);
    if (clazz == null) {
      return;
    }
    clazz.getAllSubclasses(database).forEach(x -> classesToCheck.add(x.getName()));
    clazz.getAllSuperClasses(database).forEach(x -> classesToCheck.add(x.getName()));
    Set<SecurityResourceProperty> allFilteredProperties =
        security.getAllFilteredProperties(database);

    for (String className : classesToCheck) {
      Set<SecurityResourceProperty> indexedAndFilteredProperties;
      try (Stream<SecurityResourceProperty> stream = allFilteredProperties.stream()) {
        indexedAndFilteredProperties =
            stream
                .filter(x -> x.getClassName().equalsIgnoreCase(className))
                .filter(x -> indexedFields.contains(x.getPropertyName()))
                .collect(Collectors.toSet());
      }

      if (indexedAndFilteredProperties.size() > 0) {
        try (Stream<SecurityResourceProperty> stream = indexedAndFilteredProperties.stream()) {
          throw new IndexException(
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

  private static void notifyInvolvedClasses(
      DatabaseSessionInternal database, int[] clusterIdsToIndex) {
    if (clusterIdsToIndex == null || clusterIdsToIndex.length == 0) {
      return;
    }

    // UPDATE INVOLVED CLASSES
    final Set<String> classes = new HashSet<>();
    for (int clusterId : clusterIdsToIndex) {
      final SchemaClass cls = database.getMetadata().getSchema().getClassByClusterId(clusterId);
      if (cls instanceof SchemaClassImpl && !classes.contains(cls.getName())) {
        ((SchemaClassImpl) cls).onPostIndexManagement(database);
        classes.add(cls.getName());
      }
    }
  }

  private static Set<String> findClustersByIds(
      int[] clusterIdsToIndex, DatabaseSessionInternal database) {
    Set<String> clustersToIndex = new HashSet<>();
    if (clusterIdsToIndex != null) {
      for (int clusterId : clusterIdsToIndex) {
        final String clusterNameToIndex = database.getClusterNameById(clusterId);
        if (clusterNameToIndex == null) {
          throw new IndexException("Cluster with id " + clusterId + " does not exist.");
        }

        clustersToIndex.add(clusterNameToIndex);
      }
    }
    return clustersToIndex;
  }

  private static String chooseContainerAlgorithm(String type) {
    final String valueContainerAlgorithm;
    if (SchemaClass.INDEX_TYPE.NOTUNIQUE.toString().equals(type)
        || SchemaClass.INDEX_TYPE.NOTUNIQUE_HASH_INDEX.toString().equals(type)
        || SchemaClass.INDEX_TYPE.FULLTEXT.toString().equals(type)) {
      valueContainerAlgorithm = DefaultIndexFactory.SBTREE_BONSAI_VALUE_CONTAINER;
    } else {
      valueContainerAlgorithm = DefaultIndexFactory.NONE_VALUE_CONTAINER;
    }
    return valueContainerAlgorithm;
  }

  public void dropIndex(DatabaseSessionInternal database, final String iIndexName) {
    if (database.getTransaction().isActive()) {
      throw new IllegalStateException("Cannot drop an index inside a transaction");
    }

    int[] clusterIdsToIndex = null;

    acquireExclusiveLock();

    Index idx;
    try {
      idx = indexes.get(iIndexName);
      if (idx != null) {
        final Set<String> clusters = idx.getClusters();
        if (clusters != null && !clusters.isEmpty()) {
          clusterIdsToIndex = new int[clusters.size()];
          int i = 0;
          for (String cl : clusters) {
            clusterIdsToIndex[i++] = database.getClusterIdByName(cl);
          }
        }

        removeClassPropertyIndex(database, idx);

        idx.delete(database);
        indexes.remove(iIndexName);
      }
    } finally {
      releaseExclusiveLock(database, true);
      notifyInvolvedClasses(database, clusterIdsToIndex);
    }
  }

  /**
   * Binds POJO to EntityImpl.
   */
  public EntityImpl toStream(DatabaseSessionInternal session) {
    internalAcquireExclusiveLock();
    try {
      EntityImpl entity = session.load(identity);
      final TrackedSet<EntityImpl> indexes = new TrackedSet<>(entity);

      for (final Index i : this.indexes.values()) {
        var indexInternal = (IndexInternal) i;
        indexes.add(indexInternal.updateConfiguration(session));
      }
      entity.field(CONFIG_INDEXES, indexes, PropertyType.EMBEDDEDSET);
      entity.setDirty();

      return entity;
    } finally {
      internalReleaseExclusiveLock();
    }
  }

  @Override
  public void recreateIndexes(DatabaseSessionInternal database) {
    acquireExclusiveLock();
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

  public boolean autoRecreateIndexesAfterCrash(DatabaseSessionInternal database) {
    if (rebuildCompleted) {
      return false;
    }

    final Storage storage = database.getStorage();
    if (storage instanceof AbstractPaginatedStorage paginatedStorage) {
      return paginatedStorage.wereDataRestoredAfterOpen()
          && paginatedStorage.wereNonTxOperationsPerformedInPreviousOpen();
    }

    return false;
  }

  protected void fromStream(DatabaseSessionInternal session, EntityImpl entity) {
    internalAcquireExclusiveLock();
    try {
      final Map<String, Index> oldIndexes = new HashMap<>(indexes);
      clearMetadata(session);

      final Collection<EntityImpl> indexEntities = entity.field(CONFIG_INDEXES);

      if (indexEntities != null) {
        IndexInternal index;
        boolean configUpdated = false;
        Iterator<EntityImpl> indexConfigurationIterator = indexEntities.iterator();
        while (indexConfigurationIterator.hasNext()) {
          final EntityImpl d = indexConfigurationIterator.next();
          try {

            final IndexMetadata newIndexMetadata = IndexAbstract.loadMetadataFromDoc(d);

            index = Indexes.createIndex(storage, newIndexMetadata);

            final String normalizedName = newIndexMetadata.getName();

            Index oldIndex = oldIndexes.remove(normalizedName);
            if (oldIndex != null) {
              IndexMetadata oldIndexMetadata =
                  oldIndex.getInternal().loadMetadata(oldIndex.getConfiguration(session));

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

        for (Index oldIndex : oldIndexes.values()) {
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
          entity.field(CONFIG_INDEXES, indexEntities);
          save(session);
        }
      }
    } finally {
      internalReleaseExclusiveLock();
    }
  }

  public void removeClassPropertyIndex(DatabaseSessionInternal session, final Index idx) {
    acquireExclusiveLock();
    try {
      final IndexDefinition indexDefinition = idx.getDefinition();
      if (indexDefinition == null || indexDefinition.getClassName() == null) {
        return;
      }

      Map<MultiKey, Set<Index>> map =
          classPropertyIndex.get(indexDefinition.getClassName().toLowerCase());

      if (map == null) {
        return;
      }

      map = new HashMap<>(map);

      final int paramCount = indexDefinition.getParamCount();

      for (int i = 1; i <= paramCount; i++) {
        final List<String> fields = indexDefinition.getFields().subList(0, i);
        final MultiKey multiKey = new MultiKey(fields);

        Set<Index> indexSet = map.get(multiKey);
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

  public EntityImpl toNetworkStream(DatabaseSessionInternal session) {
    EntityImpl entity = new EntityImpl(session);
    internalAcquireExclusiveLock();
    try {
      entity.setTrackingChanges(false);
      final TrackedSet<EntityImpl> indexes = new TrackedSet<>(entity);

      for (final Index i : this.indexes.values()) {
        var indexInternal = (IndexInternal) i;
        indexes.add(indexInternal.updateConfiguration(session));
      }

      entity.field(CONFIG_INDEXES, indexes, PropertyType.EMBEDDEDSET);
      return entity;
    } finally {
      internalReleaseExclusiveLock();
    }
  }

  public Index preProcessBeforeReturn(DatabaseSessionInternal database, final Index index) {
    return index;
  }

  protected Storage getStorage() {
    return storage;
  }

  public EntityImpl getDocument(DatabaseSessionInternal session) {
    return toStream(session);
  }

  private void internalSave(DatabaseSessionInternal session) {
    session.begin();
    EntityImpl entity = toStream(session);
    entity.save();
    session.commit();
  }
}
