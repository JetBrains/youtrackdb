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
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionImpl;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * Manages indexes at database level. A single instance is shared among multiple databases.
 * Contentions are managed by r/w locks.
 */
public class IndexManagerEmbedded extends IndexManagerAbstract {

  private volatile Thread recreateIndexesThread = null;

  volatile boolean rebuildCompleted = false;

  protected final AtomicInteger writeLockNesting = new AtomicInteger();

  private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

  /**
   * Whether the current thread holds the index manager's write lock. Used by the metadata-write
   * mutex engage-order assertion to prove the mutex is engaged strictly above this shared metadata
   * lock (never from inside its acquisition), which is what keeps the four-lock order acyclic.
   */
  public boolean isWriteLockHeldByCurrentThread() {
    return lock.isWriteLockedByCurrentThread();
  }

  /**
   * Takes the index-manager write lock for a schema-carrying commit's four-lock acquisition, with
   * none of the schema-mutation side effects {@link #acquireExclusiveLock(FrontendTransaction)}
   * carries. The commit acquires this lock as the third of the four locks (mutex &rarr;
   * {@code SchemaShared.lock} &rarr; this lock &rarr; {@code stateLock.writeLock}) so the order is
   * acyclic and the index-apply path runs under exclusion. It does not touch
   * {@code writeLockNesting} (that counter drives the schema-mutation release's
   * forceSnapshot-and-notify, which the commit owns separately through promotion), so it must be
   * paired with {@link #releaseExclusiveLockForCommit()}, never the public release.
   */
  public void acquireExclusiveLockForCommit() {
    lock.writeLock().lock();
  }

  /**
   * Releases the lock {@link #acquireExclusiveLockForCommit()} took, with no snapshot or listener
   * side effects. The commit promotes the schema and fires its single {@code forceSnapshot}
   * separately, so this release only drops the lock.
   */
  public void releaseExclusiveLockForCommit() {
    lock.writeLock().unlock();
  }

  public IndexManagerEmbedded(AbstractStorage storage) {
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
                  session.getStorage().getIndexMgrRecordId(),
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
      boolean requireEmpty) {
    if (recordMembershipChangeIntoTxLocalView(session, indexName, collectionName, true)) {
      // A schema transaction is in progress: the membership ripple is recorded into the tx-local
      // changed-class set and the overlay's membership-added category instead of being applied
      // eagerly to the shared Index. Applying it here would mutate the shared Index.collectionsToIndex
      // (visible to other sessions and unreverted on rollback) and could name a collection that has
      // only a provisional id during the transaction. The commit promotes the change into the shared
      // index later.
      return;
    }

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
    if (recordMembershipChangeIntoTxLocalView(session, indexName, collectionName, false)) {
      // Mirror of addCollectionToIndex: a schema transaction records the membership change into the
      // tx-local changed-class set and the overlay's membership-removed category rather than mutating
      // the shared Index, so a rollback leaves the shared collectionsToIndex untouched.
      return;
    }

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

  /**
   * Records a collection-membership change into the transaction-local schema view when a user
   * transaction is active, returning {@code true} when it did so (the caller must then skip the
   * eager shared-index apply). Returns {@code false} when no transaction is active, so the caller
   * keeps the legacy top-level apply path. A membership change is itself a schema write, so this
   * seeds the tx-local schema state on the first such change in the transaction (the read-only
   * {@code getTxSchemaState} probe never seeds). It records the index's owning class into the
   * changed-class set (whose per-class record and index membership the commit reconciles) and the
   * (index, collection) pair into the overlay's membership category, so the commit persists the
   * {@code collectionsToIndex} delta and the parent index then covers the new subclass collection.
   * The shared {@code Index} is left untouched until then so the change rolls back for free.
   *
   * @param collectionName the collection being added to or removed from the index's membership.
   * @param isAdd          {@code true} for an add (the {@code addCollectionToIndex} ripple),
   *                       {@code false} for a remove.
   */
  private boolean recordMembershipChangeIntoTxLocalView(
      DatabaseSessionEmbedded session, final String indexName, final String collectionName,
      final boolean isAdd) {
    if (!session.getTransactionInternal().isActive()) {
      return false;
    }
    if (session.isSeedingTxSchemaState()) {
      // The tx-local schema copy is mid-seed: its copyForTx re-parse rebuilds the committed
      // inheritance tree, which ripples a subclass's collection into a superclass index and lands
      // here. Recording it would re-enter ensureTxSchemaState before its marker is set (re-engaging
      // the single-permit mutex on the seeding thread and self-deadlocking) and would pollute the
      // changed-class set with committed classes the seed merely reconstructs. The shared Index is
      // untouched and already carries these committed memberships, so a handled no-op is correct:
      // return true so the caller skips the eager shared apply too.
      return true;
    }
    // Resolve the index's owning class BEFORE seeding the tx-local state so the engage does not run
    // when the change cannot be routed into the tx-local view. The shared read lock here is a read,
    // not a shared-state mutation, so it does not break the in-tx isolation the seam protects.
    acquireSharedLock();
    final String changedClass;
    try {
      final var index = indexes.get(indexName);
      changedClass =
          index != null && index.getDefinition() != null
              ? index.getDefinition().getClassName()
              : null;
    } finally {
      releaseSharedLock();
    }
    if (changedClass == null) {
      // A real class index always carries a class name, so a null here inside an active transaction
      // is a regression. Falling through to the legacy eager apply would mutate the shared Index
      // while the transaction holds the metadata-write mutex (the eager apply takes the write lock
      // and edits the shared collectionsToIndex) — the exact shared-state-in-transaction leak this
      // seam exists to prevent. Fail loudly instead so the broken index surfaces rather than
      // silently corrupting shared schema state mid-transaction.
      throw new IndexException(session,
          "Index " + indexName + " has no owning class to record the membership change against"
              + " inside a transaction; a class index must carry a class name");
    }
    // The membership change is itself a schema write: seed the tx-local schema state (engaging the
    // metadata-write mutex on the first such write) only once the change is known to be routable.
    var txState = session.ensureTxSchemaState();
    txState.markClassChanged(changedClass);
    // Record the (index, collection) delta in the overlay's membership category so the commit
    // persists it to the shared Index. This is a change to which collections a committed index
    // covers, not a change to the set of indexes on the class, so it does not touch the raw index
    // set and needs no snapshot force-rebuild here.
    var overlay = txState.ensureIndexOverlay();
    if (isAdd) {
      overlay.recordMembershipAdded(indexName, collectionName);
    } else {
      overlay.recordMembershipRemoved(indexName, collectionName);
    }
    return true;
  }

  /**
   * The active tx-local index overlay for {@code session}, or {@code null} when no schema/index
   * transaction with an index delta is in progress. A {@code null} result (no active transaction, no
   * seeded tx-local schema state, or a seeded state that has changed no index) means every index
   * read resolves against the committed shared maps exactly as before the overlay existed.
   */
  @Nullable private IndexOverlay activeOverlay(DatabaseSessionEmbedded session) {
    if (!session.getTransactionInternal().isActive()) {
      return null;
    }
    var txState = session.getTxSchemaState();
    if (txState == null) {
      return null;
    }
    var overlay = txState.getIndexOverlay();
    return overlay != null && !overlay.isEmpty() ? overlay : null;
  }

  /**
   * Resolves a class's raw index set against the tx-local overlay when a schema/index transaction is
   * in progress, so the immutable snapshot (which materializes each class's index list through this
   * method at snapshot init) and {@code ClassIndexManager} see the transaction's own view: the
   * committed indexes minus the ones the transaction dropped, plus the ones it created on this class.
   * Outside such a transaction it is the committed behaviour unchanged. This is the per-session
   * routing seam the design calls for: no per-session copy of the manager, just an overlay resolution
   * layered over the shared committed maps at the read path.
   */
  @Override
  public void getClassRawIndexes(
      DatabaseSessionEmbedded session, final String className, final Collection<Index> indexes) {
    var overlay = activeOverlay(session);
    if (overlay == null) {
      super.getClassRawIndexes(session, className, indexes);
      return;
    }
    final Collection<Index> committed = new ArrayList<>();
    super.getClassRawIndexes(session, className, committed);
    indexes.addAll(overlay.resolveClassRawIndexes(className, committed));
  }

  @Override
  public void getClassIndexes(
      DatabaseSessionEmbedded session, final String className, final Collection<Index> indexes) {
    var overlay = activeOverlay(session);
    if (overlay == null) {
      super.getClassIndexes(session, className, indexes);
      return;
    }
    final Collection<Index> committed = new ArrayList<>();
    super.getClassIndexes(session, className, committed);
    indexes.addAll(overlay.resolveClassRawIndexes(className, committed));
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
  protected Index createIndexInstance(FrontendTransactionImpl transaction,
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

    final var c = SchemaShared.checkIndexNameIfValid(iName);
    if (c != null) {
      throw new IllegalArgumentException(
          "Invalid index name '" + iName + "'. Character '" + c + "' is invalid");
    }

    type = type.toUpperCase(Locale.ROOT);
    if (algorithm == null) {
      algorithm = Indexes.chooseDefaultIndexAlgorithm(type);
    }

    if (session.getTransactionInternal().isActive()) {
      // De-guarded: a schema transaction no longer throws here. A create is itself a schema write,
      // so seed the tx-local schema state and record the index definition against its owning class
      // in the changed-class set; the engine is not created and the shared index registry is not
      // mutated now. The commit builds the engine and publishes the definition (the tx-local
      // index-definition overlay and the commit-time engine build are a later track), so a
      // tx-created index is not query-usable until commit and a rollback leaves the shared index
      // manager untouched. A definition-only instance is returned so the caller has a non-null
      // handle; it is intentionally absent from the shared registry.
      var txState = session.ensureTxSchemaState();
      if (indexDefinition.getClassName() != null) {
        txState.markClassChanged(indexDefinition.getClassName());
      }
      var deferredHandle = Indexes.createIndexInstance(type, algorithm, storage);
      // Populate the handle with its definition so the public path (e.g. the SQL CREATE INDEX
      // statement's size() probe) sees a sensible, NPE-free deferred index; the engine is not built
      // and the handle is not registered in the shared manager until commit.
      var deferredCollections = findCollectionsByIds(collectionIdsToIndex, session);
      deferredHandle.markDeferred(
          new IndexMetadata(iName, indexDefinition, deferredCollections, type, algorithm, -1,
              metadata));
      // Record the created index into the tx-local overlay so the owning class's raw index set
      // resolves the new index during the transaction, then force-rebuild the snapshot so the next
      // read re-materializes that set. Without the rebuild a same-transaction insert into the indexed
      // class reads the stale cached set and silently misses the new index.
      txState.ensureIndexOverlay().recordCreated(deferredHandle);
      session.forceRebuildSchemaSnapshotForIndexOverlay();
      return deferredHandle;
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
                .filter(x -> x.isAllClasses() || className.equals(x.getClassName()))
                .filter(x -> indexedFields.contains(x.getPropertyName()))
                .collect(Collectors.toSet());
      }

      if (!indexedAndFilteredProperties.isEmpty()) {
        try (var stream = indexedAndFilteredProperties.stream()) {
          throw new IndexException(database.getDatabaseName(),
              "Cannot create index on "
                  + indexClass
                  + "["
                  + stream
                      .map(SecurityResourceProperty::getPropertyName)
                      .collect(Collectors.joining(", "))
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
      // De-guarded: a schema transaction no longer throws here. A drop is itself a schema write, so
      // seed the tx-local schema state and record the drop against the index's owning class in the
      // changed-class set, leaving the shared index registry and the engine intact now. The commit
      // removes the entry and deletes the engine inside its own atomic operation (the tx-local
      // index-definition overlay that hides the dropped index and the commit-time engine drop are
      // a later track), so a rollback leaves the index in place.
      var txState = session.ensureTxSchemaState();
      boolean recordedDrop = false;
      // An index created earlier in this same transaction lives only in the overlay, never in the
      // shared registry, so a drop of it must be resolved against the overlay first. recordDropped
      // then cancels the pending create (nothing to hide at commit). The changed-class set already
      // carries the class from the create, so no further markClassChanged is needed here.
      var existingOverlay = txState.getIndexOverlay();
      if (existingOverlay != null && existingOverlay.isTxCreated(iIndexName)) {
        existingOverlay.recordDropped(iIndexName);
        recordedDrop = true;
      } else {
        acquireSharedLock();
        try {
          final var idx = indexes.get(iIndexName);
          if (idx != null) {
            if (idx.getDefinition() != null && idx.getDefinition().getClassName() != null) {
              txState.markClassChanged(idx.getDefinition().getClassName());
            }
            // Hide the dropped committed index from the tx-local raw index set so the planner and
            // ClassIndexManager no longer see it during the transaction. The shared Index stays
            // registered until commit, so a rollback keeps it; the commit removes the registry entry
            // and deletes the engine (a later step).
            txState.ensureIndexOverlay().recordDropped(iIndexName);
            recordedDrop = true;
          }
        } finally {
          releaseSharedLock();
        }
      }
      // Force-rebuild the snapshot only when the drop actually changed the tx-local index set, so an
      // unknown-name drop (a no-op that records no changed class) allocates no overlay churn and
      // leaves the snapshot alone.
      if (recordedDrop) {
        session.forceRebuildSchemaSnapshotForIndexOverlay();
      }
      return;
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
        classPropertyIndex.get(indexDefinition.getClassName());

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
      classPropertyIndex.remove(indexDefinition.getClassName());
    } else {
      classPropertyIndex.put(indexDefinition.getClassName(),
          copyPropertyMap(map));
    }
  }

  protected Storage getStorage() {
    return storage;
  }
}
