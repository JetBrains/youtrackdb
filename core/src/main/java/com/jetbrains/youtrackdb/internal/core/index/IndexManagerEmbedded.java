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
import com.jetbrains.youtrackdb.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.InvalidIndexEngineIdException;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaShared;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.TxSchemaState;
import com.jetbrains.youtrackdb.internal.core.metadata.security.SecurityResourceProperty;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.storage.Storage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransaction;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionImpl;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.io.IOException;
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
      // mutated now. The commit builds the engine and publishes the definition (through the tx-local
      // index-definition overlay recorded below and the commit-time reconciliation phases), so a
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
      // and the handle is not registered in the shared manager until commit. Resolve collection ids
      // through the provisional-aware path: indexing a class created in this same transaction hands
      // a provisional collection id (<= -2), which getCollectionNameById returns null for; the
      // provisional-aware resolver reads the carried <class>_<counter> name off TxSchemaState so the
      // deferred handle stores the right collection name and the commit-time build re-resolves it.
      var deferredCollections =
          resolveDeferredCollectionNames(collectionIdsToIndex, session, txState);
      deferredHandle.markDeferred(
          new IndexMetadata(iName, indexDefinition, deferredCollections, type, algorithm, -1,
              metadata));
      // Record the created index into the tx-local overlay so the owning class's raw index set
      // resolves the new index during the transaction, then force-rebuild the snapshot so the next
      // read re-materializes that set from the overlay. The rebuild refreshes the session's
      // snapshot; it does not by itself restore per-operation same-transaction index tracking off an
      // already-resolved entity, because a cached entity re-resolves its per-class index set only
      // when the snapshot version advances, which this force-rebuild does not do (the snapshot-version
      // invalidation of the whole read chain is a later step). Same-transaction index tracking is
      // instead guaranteed end-to-end by the commit-time index population scan, which re-derives the
      // tx-created index from the final collection state regardless of per-operation tracking.
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

  /**
   * Resolves the collection names for a deferred (transaction-created) index handle, tolerating a
   * provisional collection id (<= -2) that a class created in this same transaction carries. A real
   * committed id (>= 0) resolves through the storage name map as usual; a provisional id resolves to
   * the {@code <class>_<counter>} name the transaction recorded on {@link TxSchemaState} when it
   * allocated the id, because {@code getCollectionNameById} returns null for any negative id and the
   * real collection does not exist until commit. This is why the deferred create no longer throws
   * {@code IndexException("Collection with id -2 does not exist")} when indexing a same-transaction
   * class: the handle stores the generated name, and the commit-time engine build re-resolves it to
   * the real collection once reconciliation has created it.
   */
  private static Set<String> resolveDeferredCollectionNames(
      int[] collectionIdsToIndex, DatabaseSessionEmbedded session, TxSchemaState txState) {
    final Set<String> collectionsToIndex = new HashSet<>();
    if (collectionIdsToIndex == null) {
      return collectionsToIndex;
    }
    for (final var collectionId : collectionIdsToIndex) {
      final String collectionName;
      if (SchemaShared.isProvisionalCollectionId(collectionId)) {
        collectionName = txState.getProvisionalCollectionName(collectionId);
      } else {
        collectionName = session.getCollectionNameById(collectionId);
      }
      if (collectionName == null) {
        throw new IndexException(session.getDatabaseName(),
            "Collection with id " + collectionId + " does not exist.");
      }
      collectionsToIndex.add(collectionName);
    }
    return collectionsToIndex;
  }

  @Override
  public void dropIndex(DatabaseSessionEmbedded session, final String iIndexName) {
    if (session.getTransactionInternal().isActive()) {
      // De-guarded: a schema transaction no longer throws here. A drop is itself a schema write, so
      // seed the tx-local schema state and record the drop against the index's owning class in the
      // changed-class set, leaving the shared index registry and the engine intact now. The overlay
      // hides the dropped index from the tx-local raw index set; the commit removes the shared
      // registry entry and deletes the engine inside its own atomic operation (the drop-side commit
      // half in the reconciliation phases), so a rollback leaves the index in place.
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
      // leaves the snapshot alone. As on the create path, the rebuild refreshes the session's
      // snapshot but does not re-resolve an already-cached entity's per-class index set (that
      // depends on the snapshot-version invalidation added in a later step); the committed index's
      // removal is instead guaranteed at commit, which drops the engine and the registry entry.
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

  /**
   * A committed-index membership change applied in the enroll phase, captured so a failed commit can
   * revert the eager in-memory mutation (the record write reverts with the atomic operation, but the
   * in-memory {@code Index.collectionsToIndex} set was mutated synchronously).
   *
   * @param index          the committed index whose membership changed.
   * @param collectionName the collection added to or removed from the index.
   * @param wasAdd         {@code true} when the change added the collection (revert removes it),
   *                       {@code false} when it removed the collection (revert re-adds it).
   */
  private record AppliedMembership(IndexAbstract index, String collectionName, boolean wasAdd) {

  }

  /**
   * The plan a schema-carrying commit executes for the transaction's index deltas, carried across
   * the three commit phases (record enrollment before the working set, engine build/drop and
   * population after the record apply, shared-map publication after {@code commitChanges} success).
   * Built once by {@link #enrollReconciledIndexRecords} and consumed by
   * {@link #buildAndDropReconciledEngines} and {@link #publishReconciledIndexes}. The failure path
   * reads {@link #createdEngineExternalIds} to undo phantom engine registrations and
   * {@link #appliedMembership} to revert the eager membership mutations.
   *
   * @param created         tx-created deferred handles to build engines for and publish.
   * @param dropped         committed indexes the transaction dropped, to delete engines for and
   *                        unpublish.
   * @param appliedMembership the committed-index membership changes applied eagerly in the enroll
   *                        phase, for the failure-path revert.
   * @param createdEngineExternalIds the built engine ids, filled in during the build phase so the
   *                        failure path can revert the phantom registrations.
   */
  public record ReconciledIndexPlan(
      List<IndexAbstract> created,
      List<Index> dropped,
      List<AppliedMembership> appliedMembership,
      List<Integer> createdEngineExternalIds) {

  }

  /**
   * Phase 1 of the commit-time index reconciliation, run before the commit gathers its record working
   * set: enrolls the changed per-index entity records into the transaction so they join the working
   * set, and enforces the v1 empty-source-collection build bound. For each tx-created index it writes
   * the deferred handle's metadata record and adds its RID to the index-manager entity's link set;
   * for each tx-dropped committed index it removes the link and deletes the index entity record. The
   * shared {@code indexes} / {@code classPropertyIndex} lookup maps are NOT touched here — that
   * publication is deferred to {@link #publishReconciledIndexes} after {@code commitChanges} succeeds,
   * so a failed commit leaves the committed view unchanged.
   *
   * <p>The v1 empty-source-collection bound: a tx-created index whose source collection already
   * holds committed rows is loudly rejected, because the in-commit build would hold the whole
   * collection's rows in heap under the exclusive lock. The populated-class build is YTDB-1064. A
   * source collection created in this same transaction is empty by construction, so a same-transaction
   * class-plus-index passes the bound.
   *
   * <p>Must be called with {@code stateLock.writeLock()} held and the commit window open.
   *
   * @param session         the committing session.
   * @param transaction     the in-flight commit transaction.
   * @param overlay         the transaction's index overlay (its four delta categories).
   * @return the plan the build and publish phases consume, or {@code null} when the overlay carries
   *     no index delta.
   */
  @Nullable public ReconciledIndexPlan enrollReconciledIndexRecords(
      DatabaseSessionEmbedded session, FrontendTransaction transaction, IndexOverlay overlay) {
    if (overlay == null || overlay.isEmpty()) {
      return null;
    }
    final List<IndexAbstract> created = new ArrayList<>();
    final List<Index> dropped = new ArrayList<>();

    var indexManagerEntity = transaction.loadEntity(indexManagerIdentity);
    var indexLinkSet = indexManagerEntity.getOrCreateLinkSet(CONFIG_INDEXES);

    for (final var handle : overlay.getTxCreatedIndexes()) {
      // The v1 build is bounded to an empty source collection: a populated source would hold the
      // whole collection in heap under the exclusive commit lock. Reject loudly and point at the
      // follow-up rather than stalling or silently truncating.
      rejectNonEmptySourceCollection(session, handle);
      final var abstractHandle = (IndexAbstract) handle;
      abstractHandle.saveRecordAtCommit(transaction);
      indexLinkSet.add(abstractHandle.getIdentity());
      created.add(abstractHandle);
    }

    for (final var droppedName : overlay.getTxDroppedNames()) {
      final var committed = indexes.get(droppedName);
      if (committed == null) {
        // A tx-dropped name with no committed index cannot happen: recordDropped cancels a pending
        // create instead of recording a drop, so a tx-dropped name always names a committed index.
        continue;
      }
      // Enroll only the record-level removal here: unlink the index entity from the index-manager
      // link set and delete the index entity record. The engine deletion runs lock-free in the build
      // phase (the public Index.delete re-acquires stateLock and starts a nested transaction, both
      // illegal inside the commit window), and the shared registry-map removal is deferred to the
      // publish phase after commitChanges succeeds.
      indexLinkSet.remove(committed.getIdentity());
      ((IndexAbstract) committed).deleteRecordAtCommit(transaction);
      dropped.add(committed);
    }

    // Persist the committed-index membership deltas here too: the membership change re-writes the
    // committed index's own metadata record (its CONFIG_COLLECTIONS set), which must join the working
    // set, so it belongs in the enroll phase. The in-memory Index.collectionsToIndex set is mutated
    // eagerly (like a created collection published in reconcileCollections) and reverted on a failed
    // commit through the captured AppliedMembership list. The parent index then covers the new
    // subclass collection, so a later insert into that collection tracks the parent index and a
    // polymorphic lookup returns the subclass rows.
    final List<AppliedMembership> appliedMembership = new ArrayList<>();
    for (final var entry : overlay.getMembershipAdded().entrySet()) {
      final var committed = indexes.get(entry.getKey());
      if (committed instanceof IndexAbstract abstractIndex) {
        for (final var collectionName : entry.getValue()) {
          if (abstractIndex.addCollectionRecordAtCommit(transaction, collectionName)) {
            appliedMembership.add(new AppliedMembership(abstractIndex, collectionName, true));
          }
        }
      }
    }
    for (final var entry : overlay.getMembershipRemoved().entrySet()) {
      final var committed = indexes.get(entry.getKey());
      if (committed instanceof IndexAbstract abstractIndex) {
        for (final var collectionName : entry.getValue()) {
          if (abstractIndex.removeCollectionRecordAtCommit(transaction, collectionName)) {
            appliedMembership.add(new AppliedMembership(abstractIndex, collectionName, false));
          }
        }
      }
    }

    return new ReconciledIndexPlan(created, dropped, appliedMembership, new ArrayList<>());
  }

  /**
   * Rejects a tx-created index whose source collection already holds committed rows (the v1
   * v1 empty-class build bound). Reads the collection's approximate record count lock-free (the
   * caller holds {@code stateLock.writeLock()}), so it never re-acquires the non-reentrant state
   * lock. A same-transaction-created source collection is empty by construction and passes.
   */
  private void rejectNonEmptySourceCollection(
      DatabaseSessionEmbedded session, Index handle) {
    final var definition = handle.getDefinition();
    if (definition == null) {
      return;
    }
    for (final var collectionName : handle.getCollections()) {
      final var collectionId = session.getCollectionIdByName(collectionName);
      if (collectionId < 0) {
        continue;
      }
      if (((AbstractStorage) storage).getApproximateRecordsCountInCommitWindow(collectionId) > 0) {
        throw new IndexException(session.getDatabaseName(),
            "Building index '" + handle.getName() + "' inside a transaction is bounded to an empty"
                + " source collection in this version; collection '" + collectionName + "' is not"
                + " empty. The unbounded populated-class in-commit build is tracked by YTDB-1064.");
      }
    }
  }

  /**
   * Phase 2 of the commit-time index reconciliation, run inside the commit window after the record
   * apply has assigned the transaction's records persistent RIDs: builds the engine for each
   * tx-created index and populates it from the transaction's final record state, and deletes the
   * engine for each tx-dropped index. The build feeds {@code doPut} directly with no copied session
   * or nested transaction (both would re-enter the non-reentrant {@code stateLock}). Because the v1
   * bound guarantees an empty source collection, the committed-row population scan is empty and the
   * whole population comes from the final-state re-derivation over the transaction's own record
   * operations: each created or updated entity of the index's collections contributes its key,
   * deletes are skipped.
   *
   * <p>The built engine ids are recorded on the plan so the failure path can revert the phantom
   * engine registrations.
   *
   * <p>Must be called with {@code stateLock.writeLock()} held and the commit window open.
   */
  public void buildAndDropReconciledEngines(
      DatabaseSessionEmbedded session,
      FrontendTransactionImpl transaction,
      AtomicOperation atomicOperation,
      ReconciledIndexPlan plan)
      throws IOException {
    try {
      for (final var handle : plan.created()) {
        final var externalId = handle.buildEngineAtCommit(transaction, atomicOperation);
        plan.createdEngineExternalIds().add(externalId);
        populateTxCreatedIndex(session, transaction, handle);
      }
      for (final var droppedIndex : plan.dropped()) {
        final var engineId = droppedIndex.getIndexId();
        if (engineId >= 0) {
          ((AbstractStorage) storage).deleteIndexEngineInCommitWindow(engineId, atomicOperation);
        }
      }
    } catch (final InvalidIndexEngineIdException e) {
      // Wrap the checked engine-id exception as an unchecked IndexException so the commit's
      // RuntimeException/Error catch reaches the failure-path undo (which reverts the engines this
      // phase already created), mirroring how the collection reconciliation wraps its IOException.
      throw BaseException.wrapException(
          new IndexException(session.getDatabaseName(),
              "Error building or dropping index engines during schema commit"),
          e, session.getDatabaseName());
    }
  }

  /**
   * Populates a freshly built tx-created index from the transaction's final record state (the
   * final-state re-derivation). With the v1 empty-source-collection bound the committed-row scan is
   * empty, so every indexed entry comes from the transaction's own record operations: a created or
   * updated entity whose collection the index covers contributes its key through the index's
   * definition; a deleted record is skipped. Feeds {@code doPut} directly on the built engine.
   */
  private void populateTxCreatedIndex(
      DatabaseSessionEmbedded session, FrontendTransactionImpl transaction, IndexAbstract handle)
      throws InvalidIndexEngineIdException {
    final var definition = handle.getDefinition();
    if (definition == null) {
      return;
    }
    final var coveredCollectionIds = new IntOpenHashSet();
    for (final var collectionName : handle.getCollections()) {
      final var id = session.getCollectionIdByName(collectionName);
      if (id >= 0) {
        coveredCollectionIds.add(id);
      }
    }
    for (final var recordOperation : transaction.getRecordOperationsInternal()) {
      if (recordOperation.type == RecordOperation.DELETED) {
        continue;
      }
      final var record = recordOperation.record;
      if (!(record instanceof EntityImpl entity)) {
        continue;
      }
      final var rid = entity.getIdentity();
      // The record apply has already assigned persistent RIDs; a still-non-persistent RID cannot be
      // indexed (doPut stores the RID as the value), so skip it defensively.
      if (rid == null || !rid.isPersistent()) {
        continue;
      }
      if (!coveredCollectionIds.contains(rid.getCollectionId())) {
        continue;
      }
      final var key = definition.getDocumentValueToIndex(transaction, entity);
      if (key instanceof Collection<?> keys) {
        for (final var keyItem : keys) {
          if (!definition.isNullValuesIgnored() || keyItem != null) {
            handle.doPut(session, (AbstractStorage) storage, keyItem, rid);
          }
        }
      } else if (!definition.isNullValuesIgnored() || key != null) {
        handle.doPut(session, (AbstractStorage) storage, key, rid);
      }
    }
  }

  /**
   * Phase 3 of the commit-time index reconciliation, run after {@code commitChanges} succeeds (the
   * records are durable): publishes the transaction's created and dropped index deltas into the
   * shared {@code indexes} / {@code classPropertyIndex} lookup maps as the design's "replacement
   * objects" — a tx-created index is registered and a tx-dropped index is removed. Deferring this
   * publication past {@code commitChanges} keeps the committed view unchanged on a failed commit,
   * mirroring the schema promotion. Committed-index membership changes are NOT handled here: they
   * mutate a committed index's own membership set (not the lookup maps that key by name), so the
   * enroll phase applies and persists them eagerly and the failure path reverts them. Runs under the
   * index-manager write lock, which the commit already holds through {@code acquireExclusiveLockForCommit}.
   *
   * <p>Must be called with the index-manager write lock and {@code stateLock.writeLock()} held.
   */
  public void publishReconciledIndexes(FrontendTransaction transaction, ReconciledIndexPlan plan) {
    for (final var handle : plan.created()) {
      // The engine is built and the record durable, so register the handle in the shared lookup maps
      // exactly as the non-transactional create's addIndexInternalNoLock does, without re-updating the
      // index-manager entity (its link set was updated in the enroll phase and is already durable).
      addIndexInternalNoLock(handle, transaction, false);
    }
    for (final var droppedIndex : plan.dropped()) {
      removeClassPropertyIndexInternal(droppedIndex);
      indexes.remove(droppedIndex.getName());
    }
  }

  /**
   * Reverts the eager in-memory membership mutations the enroll phase applied, on a failed commit.
   * The membership record writes revert with the rolled-back atomic operation, but the in-memory
   * {@code Index.collectionsToIndex} sets were mutated synchronously, so a membership add is undone
   * (the collection removed again) and a membership remove is undone (the collection re-added). The
   * mirror of {@link #undoReconciledIndexEngines} for the membership category. Runs under the held
   * write locks on the failure path.
   */
  public void undoAppliedMembership(ReconciledIndexPlan plan) {
    for (final var applied : plan.appliedMembership()) {
      if (applied.wasAdd()) {
        applied.index().removeCollectionInMemoryAtCommit(applied.collectionName());
      } else {
        applied.index().addCollectionInMemoryAtCommit(applied.collectionName());
      }
    }
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
