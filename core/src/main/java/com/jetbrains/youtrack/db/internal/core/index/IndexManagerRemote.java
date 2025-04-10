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
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.common.listener.ProgressListener;
import com.jetbrains.youtrack.db.internal.common.types.ModifiableLong;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.storage.Storage;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransaction;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class IndexManagerRemote extends IndexManagerAbstract {

  private static final String QUERY_DROP = "drop index `%s` if exists";

  protected final ReentrantLock lock = new ReentrantLock();
  private final AtomicInteger updateRequests = new AtomicInteger(0);
  private final ThreadLocal<ModifiableLong> lockNesting = ThreadLocal.withInitial(
      ModifiableLong::new);
  private final ThreadLocal<boolean[]> ignoreReloadRequest = ThreadLocal.withInitial(
      () -> new boolean[1]);

  public IndexManagerRemote(Storage storage) {
    super(storage);
  }

  public void load(DatabaseSessionInternal session) {
    acquireExclusiveLock(session);
    try {
      // RELOAD IT
      indexManagerIdentity =
          new RecordId(session.getStorageInfo().getConfiguration().getIndexMgrRecordId());

      session.executeInTxInternal(
          transaction -> {
            var entity = (EntityImpl) transaction.loadEntity(indexManagerIdentity);
            load(transaction, entity);
          });
    } finally {
      releaseExclusiveLock(session);
    }
  }

  public void reload(DatabaseSessionInternal session) {
    acquireExclusiveLock(session);
    try {
      indexManagerIdentity =
          new RecordId(session.getStorageInfo().getConfiguration().getIndexMgrRecordId());
      session.executeInTxInternal(
          transaction -> {
            var entity = (EntityImpl) session.loadEntity(indexManagerIdentity);
            load(transaction, entity);
          });
    } finally {
      releaseExclusiveLock(session);
    }
  }

  public Collection<? extends Index> getIndexes(DatabaseSessionInternal session) {
    enterReadAccess(session);
    try {
      return super.getIndexes(session);
    } finally {
      leaveReadAccess(session);
    }
  }

  public Index getIndex(DatabaseSessionInternal session, final String iName) {
    enterReadAccess(session);
    try {
      return super.getIndex(session, iName);
    } finally {
      leaveReadAccess(session);
    }

  }

  public boolean existsIndex(DatabaseSessionInternal session, final String iName) {
    enterReadAccess(session);
    try {
      return super.existsIndex(session, iName);
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
      return super.getClassInvolvedIndexes(session, className, fields);
    } finally {
      leaveReadAccess(session);
    }
  }

  @Override
  public Set<Index> getClassInvolvedIndexes(DatabaseSessionInternal session, String className,
      String... fields) {
    enterReadAccess(session);
    try {
      return super.getClassInvolvedIndexes(session, className, fields);
    } finally {
      leaveReadAccess(session);
    }
  }

  @Override
  public boolean areIndexed(DatabaseSessionInternal session, String className,
      String... fields) {
    enterReadAccess(session);
    try {
      return super.areIndexed(session, className, fields);
    } finally {
      leaveReadAccess(session);
    }
  }


  public boolean areIndexed(DatabaseSessionInternal session, final String className,
      Collection<String> fields) {
    enterReadAccess(session);
    try {
      return super.areIndexed(session, className, fields);
    } finally {
      leaveReadAccess(session);
    }
  }

  public Set<Index> getClassIndexes(DatabaseSessionInternal session, final String className) {
    enterReadAccess(session);
    try {
      return super.getClassIndexes(session, className);
    } finally {
      leaveReadAccess(session);
    }
  }

  public void getClassIndexes(
      DatabaseSessionInternal session, final String className,
      final Collection<Index> indexes) {
    enterReadAccess(session);
    try {
      super.getClassIndexes(session, className, indexes);
    } finally {
      leaveReadAccess(session);
    }
    getClassRawIndexes(session, className, indexes);
  }

  public void getClassRawIndexes(DatabaseSessionInternal session,
      final String className, final Collection<Index> indexes) {
    enterReadAccess(session);
    try {
      super.getClassRawIndexes(session, className, indexes);
    } finally {
      leaveReadAccess(session);
    }
  }

  public IndexUnique getClassUniqueIndex(DatabaseSessionInternal session, final String className) {
    enterReadAccess(session);
    try {
      return super.getClassUniqueIndex(session, className);
    } finally {
      leaveReadAccess(session);
    }
  }

  @Nullable
  public Index getClassIndex(
      DatabaseSessionInternal session, String className, String indexName) {
    enterReadAccess(session);
    try {
      return super.getClassIndex(session, className, indexName);
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

  public void dropIndex(DatabaseSessionInternal session, final String iIndexName) {
    acquireExclusiveLock(session);
    try {
      final var text = String.format(QUERY_DROP, iIndexName);
      session.command(text);

      // REMOVE THE INDEX LOCALLY
      indexes.remove(iIndexName);
      reload(session);

    } finally {
      releaseExclusiveLock(session);
    }
  }

  @Override
  protected Index createIndexInstance(FrontendTransaction transaction,
      Identifiable indexIdentifiable, IndexMetadata newIndexMetadata) {
    return new IndexRemote(newIndexMetadata.getName(), newIndexMetadata.getType(),
        newIndexMetadata.getAlgorithm(), indexIdentifiable.getIdentity(),
        newIndexMetadata.getIndexDefinition(), null,
        newIndexMetadata.getCollectionsToIndex(),
        transaction.getDatabaseSession().getDatabaseName());
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
}
