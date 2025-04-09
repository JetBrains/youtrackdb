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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.common.listener.ProgressListener;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.common.types.ModifiableLong;
import com.jetbrains.youtrack.db.internal.common.util.MultiKey;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.storage.StorageInfo;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransaction;
import java.util.ArrayList;
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
import java.util.concurrent.locks.ReentrantLock;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class IndexManagerRemote implements IndexManagerAbstract {

  private final StorageInfo storage;
  // values of this Map should be IMMUTABLE !! for thread safety reasons.
  protected final Map<String, Map<MultiKey, Set<Index>>> classPropertyIndex =
      new ConcurrentHashMap<>();
  protected Map<String, Index> indexes = new ConcurrentHashMap<>();
  protected final ReentrantLock lock = new ReentrantLock();

  private final AtomicInteger updateRequests = new AtomicInteger(0);
  private final ThreadLocal<ModifiableLong> lockNesting = ThreadLocal.withInitial(
      ModifiableLong::new);

  private RID identity;

  private final ThreadLocal<boolean[]> ignoreReloadRequest = ThreadLocal.withInitial(
      () -> new boolean[1]);

  public IndexManagerRemote(StorageInfo storage) {
    super();
    this.storage = storage;
  }

  public void load(DatabaseSessionInternal session) {
    acquireExclusiveLock(session);
    try {
      // RELOAD IT
      identity =
          new RecordId(session.getStorageInfo().getConfiguration().getIndexMgrRecordId());

      session.executeInTx(
          transaction -> {
            EntityImpl entity = transaction.load(identity);
            fromStream(entity, session);
          });
    } finally {
      releaseExclusiveLock(session);
    }
  }

  public void reload(DatabaseSessionInternal session) {
    acquireExclusiveLock(session);
    try {
      identity =
          new RecordId(session.getStorageInfo().getConfiguration().getIndexMgrRecordId());
      session.executeInTx(
          transaction -> {
            EntityImpl entity = session.load(identity);
            fromStream(entity, session);
          });
    } finally {
      releaseExclusiveLock(session);
    }
  }

  public void save(DatabaseSessionInternal session) {
    throw new UnsupportedOperationException();
  }

  public void addCollectionToIndex(DatabaseSessionInternal session, final String collectionName,
      final String indexName) {
    throw new UnsupportedOperationException();
  }

  public void removeCollectionFromIndex(DatabaseSessionInternal session,
      final String collectionName,
      final String indexName) {
    throw new UnsupportedOperationException();
  }

  public void create(DatabaseSessionInternal session) {
    throw new UnsupportedOperationException();
  }

  public Collection<? extends Index> getIndexes(DatabaseSessionInternal session) {
    enterReadAccess(session);
    try {
      final var rawResult = indexes.values();
      final List<Index> result = new ArrayList<>(rawResult.size());
      result.addAll(rawResult);
      return result;
    } finally {
      leaveReadAccess(session);
    }
  }

  public Index getIndex(DatabaseSessionInternal session, final String iName) {
    enterReadAccess(session);
    try {
      return indexes.get(iName);
    } finally {
      leaveReadAccess(session);
    }

  }

  public boolean existsIndex(DatabaseSessionInternal session, final String iName) {
    enterReadAccess(session);
    try {
      return indexes.containsKey(iName);
    } finally {
      leaveReadAccess(session);
    }
  }

  @Override
  public void close() {
    indexes.clear();
    classPropertyIndex.clear();
  }

  public Set<Index> getClassInvolvedIndexes(
      DatabaseSessionInternal session, final String className, Collection<String> fields) {
    enterReadAccess(session);
    try {
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
    } finally {
      leaveReadAccess(session);
    }

  }

  public Set<Index> getClassInvolvedIndexes(
      DatabaseSessionInternal session, final String className, final String... fields) {
    return getClassInvolvedIndexes(session, className, Arrays.asList(fields));
  }

  public boolean areIndexed(DatabaseSessionInternal session, final String className,
      Collection<String> fields) {
    enterReadAccess(session);
    try {
      final var multiKey = new MultiKey(fields);
      final var propertyIndex = getIndexOnProperty(className);

      if (propertyIndex == null) {
        return false;
      }

      return propertyIndex.containsKey(multiKey) && !propertyIndex.get(multiKey).isEmpty();
    } finally {
      leaveReadAccess(session);
    }

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
    getClassRawIndexes(session, className, indexes);
  }

  public void getClassRawIndexes(DatabaseSessionInternal session,
      final String className, final Collection<Index> indexes) {
    enterReadAccess(session);
    try {
      final var propertyIndex = getIndexOnProperty(className);

      if (propertyIndex == null) {
        return;
      }

      for (final var propertyIndexes : propertyIndex.values()) {
        indexes.addAll(propertyIndexes);
      }
    } finally {
      leaveReadAccess(session);
    }
  }

  public IndexUnique getClassUniqueIndex(DatabaseSessionInternal session, final String className) {
    enterReadAccess(session);
    try {
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
    } finally {
      leaveReadAccess(session);
    }
  }

  @Nullable
  public Index getClassIndex(
      DatabaseSessionInternal session, String className, String indexName) {
    enterReadAccess(session);
    try {
      className = className.toLowerCase();
      final var index = indexes.get(indexName);

      if (index != null
          && index.getDefinition() != null
          && index.getDefinition().getClassName() != null
          && className.equals(index.getDefinition().getClassName().toLowerCase())) {
        return index;
      }

      return null;
    } finally {
      leaveReadAccess(session);
    }
  }

  public void requestUpdate() {
    updateRequests.incrementAndGet();
  }

  private void enterReadAccess(DatabaseSessionInternal session) {
    updateIfRequested(session);
    lockNesting.get().increment();
  }

  private void leaveReadAccess(DatabaseSessionInternal session) {
    lockNesting.get().decrement();
    updateIfRequested(session);
  }

  private void updateIfRequested(@Nonnull DatabaseSessionInternal database) {
    var ignoreReloadRequest = this.ignoreReloadRequest.get();
    //stack overflow protection
    if (ignoreReloadRequest[0]) {
      return;
    }

    var lockNesting = this.lockNesting.get().value;
    if (lockNesting > 0) {
      return;
    }

    while (true) {
      var updateReqs = updateRequests.getAndSet(0);

      if (updateReqs > 0) {
        ignoreReloadRequest[0] = true;
        try {
          reload(database);
        } finally {
          ignoreReloadRequest[0] = false;
        }
      } else {
        break;
      }
    }
  }

  private void internalAcquireExclusiveLock(DatabaseSessionInternal session) {
    updateIfRequested(session);

    if (!session.isClosed()) {
      final var metadata = session.getMetadata();
      metadata.makeThreadLocalSchemaSnapshot();
    }

    lock.lock();
    lockNesting.get().increment();
  }

  private void internalReleaseExclusiveLock(DatabaseSessionInternal session) {
    lock.unlock();
    lockNesting.get().decrement();

    updateIfRequested(session);
    if (!session.isClosed()) {
      final var metadata = session.getMetadata();
      metadata.clearThreadLocalSchemaSnapshot();
    }
  }

  private void clearMetadata() {
    indexes.clear();
    classPropertyIndex.clear();
  }

  private void addIndexInternal(final Index index) {
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

  private static Map<MultiKey, Set<Index>> copyPropertyMap(Map<MultiKey, Set<Index>> original) {
    return IndexManagerShared.copyPropertyMap(original);
  }

  private Map<MultiKey, Set<Index>> getIndexOnProperty(final String className) {
    return classPropertyIndex.get(className.toLowerCase());
  }

  public Index createIndex(
      DatabaseSessionInternal session,
      final String iName,
      final String iType,
      final IndexDefinition iIndexDefinition,
      final int[] iCollectionIdsToIndex,
      final ProgressListener progressListener,
      Map<String, Object> metadata,
      String engine) {

    String createIndexDDL;
    if (iIndexDefinition != null) {
      createIndexDDL = iIndexDefinition.toCreateIndexDDL(iName, iType, engine);
    } else {
      createIndexDDL = new SimpleKeyIndexDefinition().toCreateIndexDDL(iName, iType, engine);
    }

    if (metadata != null) {
      var objectMapper = new ObjectMapper();
      var typeRef = objectMapper.getTypeFactory()
          .constructMapType(HashMap.class, String.class, Object.class);

      createIndexDDL +=
          " METADATA " + objectMapper.convertValue(
              metadata, typeRef);
    }

    acquireExclusiveLock(session);
    try {
      if (progressListener != null) {
        progressListener.onBegin(this, 0, false);
      }

      session.execute(createIndexDDL).close();

      if (progressListener != null) {
        progressListener.onCompletition(session, this, true);
      }

      reload(session);

      return indexes.get(iName);
    } catch (CommandExecutionException x) {
      throw new IndexException(x.getMessage());
    } finally {
      releaseExclusiveLock(session);
    }
  }

  @Override
  public Index createIndex(
      DatabaseSessionInternal session,
      String iName,
      String iType,
      IndexDefinition indexDefinition,
      int[] collectionIdsToIndex,
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

  public void dropIndex(DatabaseSessionInternal session, final String iIndexName) {
    throw new UnsupportedOperationException();
  }

  public void save(FrontendTransaction transaction) {
    throw new UnsupportedOperationException("Remote index cannot be streamed");
  }

  @Override
  public RID getIdentity() {
    return identity;
  }

  @Override
  public void markMetadataDirty() {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<Map<String, Object>> getIndexesConfiguration(DatabaseSessionInternal session) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void recreateIndexes(DatabaseSessionInternal session) {
    throw new UnsupportedOperationException("recreateIndexes(DatabaseSessionInternal)");
  }

  public void waitTillIndexRestore() {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean autoRecreateIndexesAfterCrash(DatabaseSessionInternal session) {
    return false;
  }

  public void removeClassPropertyIndex(DatabaseSessionInternal session, Index idx) {
    throw new UnsupportedOperationException();
  }

  private void fromStream(EntityImpl entity, DatabaseSessionInternal session) {
    acquireExclusiveLock(session);
    try {
      clearMetadata();

      final Collection<Map<String, Object>> idxs = entity.getProperty(CONFIG_INDEXES);
      if (idxs != null) {
        for (var configMap : idxs) {
          try {
            final var newIndexMetadata = IndexAbstract.loadMetadataFromMap(
                session.getActiveTransaction(), configMap);

            var type = newIndexMetadata.getType();
            var name = newIndexMetadata.getName();
            var algorithm = newIndexMetadata.getAlgorithm();
            var collectionsToIndex = newIndexMetadata.getCollectionsToIndex();
            var indexDefinition = newIndexMetadata.getIndexDefinition();
            var configMapId = (RID) configMap.get(IndexAbstract.CONFIG_MAP_RID);

            addIndexInternal(
                new IndexRemote(
                    name,
                    type,
                    algorithm,
                    configMapId,
                    indexDefinition,
                    configMap,
                    collectionsToIndex,
                    storage.getName()));
          } catch (Exception e) {
            LogManager.instance()
                .error(this, "Error on loading of index by configuration: %s", e, configMap);
          }
        }
      }
    } finally {
      releaseExclusiveLock(session);
    }
  }

  private void acquireExclusiveLock(DatabaseSessionInternal session) {
    internalAcquireExclusiveLock(session);
  }

  private void releaseExclusiveLock(DatabaseSessionInternal session) {
    session
        .getSharedContext()
        .getSchema()
        .forceSnapshot();
    internalReleaseExclusiveLock(session);
  }

  public EntityImpl getEntity(DatabaseSessionInternal session) {
    throw new UnsupportedOperationException();
  }

}
