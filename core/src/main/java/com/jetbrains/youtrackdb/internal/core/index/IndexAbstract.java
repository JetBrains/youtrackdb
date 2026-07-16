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
import com.jetbrains.youtrackdb.api.exception.RecordDuplicatedException;
import com.jetbrains.youtrackdb.internal.common.concur.lock.OneEntryPerKeyLockManager;
import com.jetbrains.youtrackdb.internal.common.concur.lock.PartitionedLockManager;
import com.jetbrains.youtrackdb.internal.common.listener.ProgressListener;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
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
import com.jetbrains.youtrackdb.internal.core.index.engine.EquiDepthHistogram;
import com.jetbrains.youtrackdb.internal.core.index.engine.HistogramSnapshot;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexStatistics;
import com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeIndexEngine;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityHelper;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.storage.Storage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransaction;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionImpl;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionIndexChanges.OPERATION;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionIndexChangesPerKey;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionIndexChangesPerKey.TransactionIndexEntry;
import com.jetbrains.youtrackdb.internal.core.tx.Transaction;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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

  private static final String CONFIG_COLLECTIONS = "collections";

  @Nonnull
  protected final AbstractStorage storage;
  @Nonnull
  private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

  protected volatile int indexId = -1;

  @Nonnull
  protected Set<String> collectionsToIndex = new HashSet<>();
  @Nullable protected IndexMetadata im;

  @Nullable protected RID identity;

  public IndexAbstract(@Nullable RID identity,
      @Nonnull FrontendTransactionImpl transaction, @Nonnull final Storage storage) {
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

    @SuppressWarnings("unchecked")
    final var indexDefinitionEntity = (Map<String, Object>) config.get(
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
                "Error during deserialization of index definition"),
            e,
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

  @Override
  public void markDeferred(final IndexMetadata indexMetadata) {
    acquireExclusiveLock();
    try {
      // A transaction-deferred create: record the definition so the handle answers metadata and
      // size() queries on the public path, but do not build or load a storage engine. indexId
      // stays -1 (unbuilt); the engine build and shared registration happen at commit. A deferred
      // handle answers name/definition/collection queries, size() (zero), and value lookups
      // (get()/getRids(), which short-circuit to no rids); any other engine-backed read (the range
      // streams, statistics, the histogram) is unsupported until commit builds the engine, because
      // indexId = -1 cannot be dereferenced by the storage layer.
      this.im = indexMetadata;
      var deferredCollections = indexMetadata.getCollectionsToIndex();
      this.collectionsToIndex =
          deferredCollections != null ? new HashSet<>(deferredCollections) : new HashSet<>();
    } finally {
      releaseExclusiveLock();
    }
  }

  /**
   * Writes this deferred (transaction-created) handle's metadata record into the commit transaction,
   * so the record joins the commit working set before it is gathered. The commit-window counterpart
   * of the {@link #save(FrontendTransaction)} the non-transactional {@link #create} path runs, kept
   * package-visible so {@link IndexManagerEmbedded} can persist the index entity for a tx-created
   * index without going through the {@code stateLock}-taking {@code create} path. Called by the index
   * manager at commit, before the working set is gathered and before the engine is built (the record
   * carries only metadata, no engine dependency); {@link #buildEngineAtCommit} builds the engine
   * after the record apply has assigned persistent RIDs.
   */
  void saveRecordAtCommit(final FrontendTransaction transaction) {
    acquireExclusiveLock();
    try {
      save(transaction);
    } finally {
      releaseExclusiveLock();
    }
  }

  /**
   * Builds the replacement {@link IndexMetadata} of the D17 class-rename re-association: a private
   * copy of this index's metadata whose definition (recursing composites) carries
   * {@code newClassName}. The copy's definition is rebuilt through the same reflective
   * {@code toMap}/{@code fromMap} round trip the loader uses, so no reader-visible object is
   * mutated — the shared definition stays untouched until the commit publishes the replacement
   * wholesale, which is what keeps lock-free readers from ever seeing a torn {@code className}.
   */
  IndexMetadata buildClassReassociatedMetadata(
      final DatabaseSessionEmbedded session, final String newClassName) {
    acquireSharedLock();
    try {
      final var definition = im.getIndexDefinition();
      IndexDefinition replacementDefinition = null;
      if (definition != null) {
        try {
          replacementDefinition =
              definition.getClass().getDeclaredConstructor().newInstance();
          replacementDefinition.fromMap(definition.toMap(session));
        } catch (final ReflectiveOperationException e) {
          throw BaseException.wrapException(
              new IndexException(session,
                  "Error while re-associating index '" + im.getName()
                      + "' with renamed class '" + newClassName + "'"),
              e, session);
        }
        replacementDefinition.setClassName(newClassName);
      }
      return new IndexMetadata(
          im.getName(),
          replacementDefinition,
          new HashSet<>(collectionsToIndex),
          im.getType(),
          im.getAlgorithm(),
          im.getVersion(),
          im.getMetadata());
    } finally {
      releaseSharedLock();
    }
  }

  /**
   * Writes this committed index's metadata record from the given replacement metadata into the
   * commit transaction (the durable half of the D17 class-rename re-association, run in the enroll
   * phase so the record joins the working set). The in-memory metadata is NOT touched here — a
   * failed commit's record write reverts with the rolled-back atomic operation and there is then
   * nothing in memory to undo; the in-memory swap happens in the publish phase
   * ({@link #installReassociatedMetadataAtCommit}) only after {@code commitChanges} succeeded.
   */
  void reassociateClassRecordAtCommit(
      final FrontendTransaction transaction, final IndexMetadata replacement) {
    acquireExclusiveLock();
    try {
      saveFrom(replacement, transaction);
    } finally {
      releaseExclusiveLock();
    }
  }

  /**
   * Installs the replacement metadata built by {@link #buildClassReassociatedMetadata} — the
   * in-memory half of the D17 re-association, run in the publish phase after the records are
   * durable. A single reference swap of a never-shared, fully-built object: readers observe
   * either the old metadata (old class name, fully consistent) or the new one, never a torn mix.
   */
  void installReassociatedMetadataAtCommit(final IndexMetadata replacement) {
    acquireExclusiveLock();
    try {
      this.im = replacement;
    } finally {
      releaseExclusiveLock();
    }
  }

  /**
   * Re-associates a transaction-private DEFERRED handle's definition with a same-transaction class
   * rename, in place: the handle was created before the rename, so its definition still names the
   * old class, and unlike a committed index it is reachable only by the owning transaction — no
   * concurrent reader exists to observe the write, so no replacement copy is needed. Run at the
   * top of the commit's enroll phase, before the handle's record is written.
   */
  void reassociateDeferredClassNameAtCommit(
      final String oldClassName, final String newClassName) {
    acquireExclusiveLock();
    try {
      final var definition = im == null ? null : im.getIndexDefinition();
      if (definition != null && oldClassName.equals(definition.getClassName())) {
        definition.setClassName(newClassName);
      }
    } finally {
      releaseExclusiveLock();
    }
  }

  /**
   * Adds a collection to this committed index's in-memory membership and re-writes its metadata
   * record into the commit transaction (the commit-time membership persistence, run in the enroll
   * phase so the record joins the working set). No emptiness check and no {@code stateLock} — the
   * caller holds the index-manager write lock and {@code stateLock.writeLock()} in the commit window.
   * The mutation is eager (mirroring how {@link IndexManagerEmbedded#enrollReconciledIndexRecords}
   * enrolls other index records before {@code commitChanges}) and reverted on a failed commit through
   * {@link #removeCollectionInMemoryAtCommit}. Returns {@code true} when the collection was actually
   * added, so the caller can record it for the failure-path revert.
   */
  boolean addCollectionRecordAtCommit(
      final FrontendTransaction transaction, final String collectionName) {
    acquireExclusiveLock();
    try {
      if (!collectionsToIndex.add(collectionName)) {
        return false;
      }
      try {
        save(transaction);
      } catch (final RuntimeException | Error e) {
        // The shared in-memory set is mutated before the record write, but the caller records the
        // failure-path revert entry only after this method returns true. A save that throws (for
        // example a record-load failure) would otherwise leave the eager add in place with no
        // revert entry reachable, so the shared committed membership would diverge from the
        // rolled-back record write. Revert locally and rethrow.
        collectionsToIndex.remove(collectionName);
        throw e;
      }
      return true;
    } finally {
      releaseExclusiveLock();
    }
  }

  /**
   * Removes a collection from this committed index's in-memory membership and re-writes its metadata
   * record into the commit transaction (the remove side of the commit-time membership persistence).
   * No emptiness check and no {@code stateLock}, for the same reason as
   * {@link #addCollectionRecordAtCommit}. Returns {@code true} when the collection was actually
   * removed.
   */
  boolean removeCollectionRecordAtCommit(
      final FrontendTransaction transaction, final String collectionName) {
    acquireExclusiveLock();
    try {
      if (!collectionsToIndex.remove(collectionName)) {
        return false;
      }
      try {
        save(transaction);
      } catch (final RuntimeException | Error e) {
        // The mirror of the add-side revert: restore the eagerly-removed collection when the
        // record write throws, so no unrecorded mutation survives on the shared committed index.
        collectionsToIndex.add(collectionName);
        throw e;
      }
      return true;
    } finally {
      releaseExclusiveLock();
    }
  }

  /**
   * Reverts an in-memory membership add applied by {@link #addCollectionRecordAtCommit} on a failed
   * commit, without touching the record (the failed commit's record write reverts with the atomic
   * operation). Package-visible for {@link IndexManagerEmbedded}'s failure-path membership revert.
   */
  void removeCollectionInMemoryAtCommit(final String collectionName) {
    acquireExclusiveLock();
    try {
      collectionsToIndex.remove(collectionName);
    } finally {
      releaseExclusiveLock();
    }
  }

  /**
   * Folds a collection-membership ripple into this deferred (transaction-created, unregistered)
   * handle's covered-collection set — e.g. a subclass created under the indexed class in the same
   * transaction, whose collection the commit-built index must cover. Only the live
   * {@code collectionsToIndex} set is mutated: every commit phase reads it (the v1 emptiness
   * bound, the population scan's coverage set, and the enroll-phase record write), while the
   * deferred metadata's create-time collection snapshot is not re-read after {@link #markDeferred}
   * and intentionally stays untouched. The folded name carries the same committed-or-provisional
   * counter-only ({@code c_<counter>}) shape the create-time resolver produces, so the commit
   * re-resolves
   * it exactly like a create-time covered collection.
   */
  void addCollectionToDeferred(final String collectionName) {
    acquireExclusiveLock();
    try {
      collectionsToIndex.add(collectionName);
    } finally {
      releaseExclusiveLock();
    }
  }

  /**
   * The remove mirror of {@link #addCollectionToDeferred}: a same-tx detach (or subclass drop)
   * takes the collection back out of the deferred handle's covered set before the commit builds
   * the engine.
   */
  void removeCollectionFromDeferred(final String collectionName) {
    acquireExclusiveLock();
    try {
      collectionsToIndex.remove(collectionName);
    } finally {
      releaseExclusiveLock();
    }
  }

  /**
   * Restores an in-memory membership remove applied by {@link #removeCollectionRecordAtCommit} on a
   * failed commit, without touching the record. Package-visible for the failure-path revert.
   */
  void addCollectionInMemoryAtCommit(final String collectionName) {
    acquireExclusiveLock();
    try {
      collectionsToIndex.add(collectionName);
    } finally {
      releaseExclusiveLock();
    }
  }

  /**
   * Deletes this committed index's metadata record inside the commit transaction, so the record
   * deletion joins the commit working set. The commit-window counterpart of the record-delete half of
   * {@link #doDelete(FrontendTransaction)}, split out so the drop's engine deletion (which must run
   * lock-free inside the window) and shared-map removal (deferred past {@code commitChanges}) happen
   * in their own phases. Unlike {@code doDelete} it takes no {@code stateLock} and starts no nested
   * transaction: it only enrols the index entity's deletion into the in-flight commit transaction.
   * Called by {@link IndexManagerEmbedded} in the enroll phase; the engine is deleted in the build
   * phase and the shared registry entry removed in the publish phase.
   */
  void deleteRecordAtCommit(final FrontendTransaction transaction) {
    acquireExclusiveLock();
    try {
      if (identity != null) {
        transaction.loadEntity(identity).delete();
      }
    } finally {
      releaseExclusiveLock();
    }
  }

  /**
   * Builds this deferred (transaction-created) handle's storage engine inside the schema-carry commit
   * window and binds the handle to it, so the handle stops being deferred and becomes a real,
   * query-usable index. Called by {@link IndexManagerEmbedded} at commit, after reconciliation has
   * created the source collections and after the record apply, with {@code stateLock.writeLock()}
   * held and the commit window open. It creates the engine through the storage's commit-window
   * primitive (which allocates a commit-local engine id and buffers the engine files as WAL-reverted
   * intent), sets {@code indexId} to the built engine's id, and wires the engine.
   *
   * <p>The engine is created but not yet populated; {@link IndexManagerEmbedded} runs the final-state
   * re-derivation that feeds the transaction's own records into it right after this returns.
   *
   * @param transaction     the in-flight commit transaction.
   * @param atomicOperation the in-flight commit atomic operation.
   * @param createdEngineExternalIds the plan's created-engine id list; the published engine id is
   *                        recorded here before the engine is wired, so the failure-path undo can
   *                        revert it even when the wiring throws.
   */
  void buildEngineAtCommit(
      final FrontendTransactionImpl transaction, final AtomicOperation atomicOperation,
      final List<Integer> createdEngineExternalIds)
      throws IOException {
    acquireExclusiveLock();
    try {
      final Map<String, String> engineProperties = new HashMap<>();
      indexId = storage.createIndexEngineInCommitWindow(im, engineProperties, atomicOperation);
      assert indexId >= 0;
      // Record the published id before wiring the engine: the in-memory registries already carry
      // it, and onIndexEngineChange can fail with an exception the build loop's
      // InvalidIndexEngineIdException catch does not cover. The failure-path undo reverts exactly
      // the recorded ids, so recording only after a fully-successful build would leave such a
      // failure's engine behind as a phantom registration no revert arm ever removes.
      createdEngineExternalIds.add(indexId);
      onIndexEngineChange(transaction.getDatabaseSession(), indexId);
    } finally {
      releaseExclusiveLock();
    }
  }

  protected void doReloadIndexEngine() {
    indexId = storage.loadIndexEngine(im.getName());
    if (indexId < 0) {
      throw new IllegalStateException("Index " + im.getName() + " can not be loaded");
    }
  }

  private void load(FrontendTransactionImpl transaction) {
    var entity = transaction.loadEntity(identity);
    final var indexMetadata = loadMetadata(transaction, entity.toMap(false));

    this.im = indexMetadata;
    collectionsToIndex.clear();

    collectionsToIndex.addAll(indexMetadata.getCollectionsToIndex());
    indexId = storage.loadIndexEngine(im.getName());

    if (indexId == -1) {
      Map<String, String> engineProperties = new HashMap<>();
      indexId = storage.loadExternalIndexEngine(indexMetadata, engineProperties,
          transaction.getAtomicOperation());
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

      // The old metadata entity was deleted by doDelete() above. Reset identity
      // so save() creates a fresh entity instead of trying to update the deleted one.
      // Then persist the metadata and register it in the IndexManager's CONFIG_INDEXES
      // link set, so the index survives crash + WAL replay.
      identity = null;
      session.executeInTxInternal(tx -> {
        save(tx);
        session.getSharedContext().getIndexManager()
            .addIndexInternal(session, tx, this, true);
      });
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

    // Enable bulk-load mode on the histogram manager to suppress per-put
    // delta accumulation during fillIndex(). The post-fill buildHistogram()
    // computes all statistics from scratch (exact NDV, accurate frequencies).
    setBulkLoading(true);
    try {
      entitiesIndexed = fillIndex(session, progressListener);
    } finally {
      // Always disable bulk-load mode, even on failure, to avoid
      // permanently suppressing delta accumulation if the engine survives.
      setBulkLoading(false);
    }

    buildHistogramAfterFill();

    return entitiesIndexed;
  }

  /**
   * Sets bulk-loading mode on the histogram manager for this index's engine.
   * In bulk-loading mode, onPut/onRemove are no-ops — avoiding O(N log B)
   * overhead from N individual findBucket() calls during population.
   */
  private void setBulkLoading(boolean bulkLoading) {
    while (true) {
      try {
        var engine = storage.getIndexEngine(indexId);
        if (engine instanceof BTreeIndexEngine btreeEngine) {
          var mgr = btreeEngine.getHistogramManager();
          if (mgr != null) {
            mgr.setBulkLoading(bulkLoading);
          }
        }
        break;
      } catch (InvalidIndexEngineIdException ignore) {
        doReloadIndexEngine();
      }
    }
  }

  /**
   * Builds the initial histogram after fillIndex() completes. Runs the
   * build inside a standalone atomic operation so that
   * {@code startToApplyOperations} is called before any B-tree component
   * operations (which generate PageOperation WAL records and trigger
   * {@code flushPendingOperations}).
   *
   * <p>Using {@code executeInsideAtomicOperation} rather than
   * {@code executeInTxInternal} is deliberate: the latter defers
   * {@code startToApplyOperations} until the commit phase, but the
   * B-tree writes inside {@code buildInitialHistogram} happen immediately
   * and require a valid commit timestamp for WAL record emission.
   */
  private void buildHistogramAfterFill() {
    while (true) {
      try {
        var engine = storage.getIndexEngine(indexId);
        if (engine instanceof BTreeIndexEngine btreeEngine) {
          if (btreeEngine.getHistogramManager() != null) {
            try {
              storage.getAtomicOperationsManager()
                  .executeInsideAtomicOperation(
                      atomicOperation -> btreeEngine
                          .buildInitialHistogram(atomicOperation));
            } catch (IOException e) {
              // Histogram build failure must not fail the index rebuild.
              // The histogram will be built lazily on the next rebalance.
              LogManager.instance().warn(
                  IndexAbstract.this,
                  "Failed to build initial histogram for index '%s': %s",
                  im.getName(), e.getMessage());
            }
          }
        }
        break;
      } catch (InvalidIndexEngineIdException ignore) {
        doReloadIndexEngine();
      }
    }
  }

  public long fillIndex(DatabaseSessionEmbedded session,
      ProgressListener progressListener) {
    long entitiesIndexed;
    acquireSharedLock();
    try {
      entitiesIndexed = doFillIndex(session, progressListener);
    } catch (final Exception e) {
      LogManager.instance().error(this, "Error during index rebuild", e);
      var clearSucceeded = false;
      try {
        if (indexId >= 0) {
          storage.clearIndex(indexId);
          clearSucceeded = true;
        }
      } catch (Exception e2) {
        LogManager.instance().error(this,
            "Error during clearIndex after failed rebuild of index '%s' (id=%d)",
            e2, im.getName(), indexId);
        // IGNORE EXCEPTION: IF THE REBUILD WAS LAUNCHED IN CASE OF RID INVALID CLEAR ALWAYS GOES IN
        // ERROR
      }
      LogManager.instance().info(this,
          "fillIndex failed for '%s' (id=%d): clearIndex %s. Cause: %s",
          im.getName(), indexId,
          clearSucceeded ? "succeeded" : "FAILED or skipped",
          e.getClass().getSimpleName());

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
        entitiesTotal += storage.getApproximateRecordsCount(
            storage.getCollectionIdByName(collection));
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
  public boolean doRemove(DatabaseSessionEmbedded session, AbstractStorage storage,
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
      DatabaseSessionEmbedded session)
      throws InvalidIndexEngineIdException {
    var tx = session.getActiveTransaction();
    return storage.removeKeyFromIndex(indexId, key, tx.getAtomicOperation());
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
      var batchSize =
          deletionSession.getConfiguration().getValueAsInteger(GlobalConfiguration.TX_BATCH_SIZE);

      var lastIndexEntry = deletionSession.computeInTxInternal(deletionTx -> {
        RawPair<Object, RID> indexEntry = null;
        try (var indexStream = stream(deletionSession)) {
          var indexIterator = indexStream.iterator();

          for (var i = 0; i < batchSize && indexIterator.hasNext(); i++) {
            indexEntry = indexIterator.next();
            remove(deletionTx, indexEntry.first(), indexEntry.second());
          }

          if (indexIterator.hasNext()) {
            return indexEntry;
          } else {
            return null;
          }
        }
      });

      while (lastIndexEntry != null) {
        var lEntry = lastIndexEntry;
        lastIndexEntry = deletionSession.computeInTxInternal(deletionTx -> {
          try (var indexStream = streamEntriesMajor(deletionSession, lEntry.first(), true, true)) {
            var indexIterator = indexStream.iterator();

            RawPair<Object, RID> indexEntry = null;
            for (var i = 0; i < batchSize && indexIterator.hasNext(); i++) {
              indexEntry = indexIterator.next();
              remove(deletionTx, indexEntry.first(), indexEntry.second());
            }

            if (indexIterator.hasNext()) {
              return indexEntry;
            } else {
              return null;
            }
          }
        });
      }
    } catch (Exception e) {
      if (transaction != null) {
        transaction.rollback();
        throw e;
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
          // INDEX SINGLE COLLECTION — O(1) emptiness check via iterator
          try (var iter = session.browseCollection(collectionName)) {
            if (iter.hasNext()) {
              throw new IndexException("Collection " + collectionName
                  + " is not empty. Please remove all records from it before adding to index");
            }
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
      // O(1) emptiness check via iterator
      try (var iter = session.browseCollection(collectionName)) {
        if (iter.hasNext()) {
          throw new IndexException("Collection " + collectionName
              + " is not empty. Please remove all records from it before removing it from index");
        }
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
    saveFrom(im, transaction);
  }

  /**
   * The record-serialization body of {@link #save}, parameterized on the metadata to serialize so
   * the D17 class-rename re-association can write the record from a replacement metadata object
   * WITHOUT installing it in memory first (the record write must revert with a failed commit
   * while the in-memory install is deferred to the publish phase).
   */
  private void saveFrom(final IndexMetadata source, FrontendTransaction transaction) {
    Entity entity;
    if (identity == null) {
      entity = transaction.getDatabaseSession().newInternalInstance();
    } else {
      entity = transaction.loadEntity(identity);
    }

    entity.setString(CONFIG_TYPE, source.getType());
    entity.setString(CONFIG_NAME, source.getName());
    entity.setInt(INDEX_VERSION, source.getVersion());

    if (source.getIndexDefinition() != null) {
      final var indexDefEntity = source.getIndexDefinition()
          .toMap(transaction.getDatabaseSession());
      entity.setEmbeddedMap(INDEX_DEFINITION, indexDefEntity);
      entity.setString(INDEX_DEFINITION_CLASS, source.getIndexDefinition().getClass().getName());
    }

    var session = transaction.getDatabaseSession();
    entity.setEmbeddedSet(CONFIG_COLLECTIONS, session.newEmbeddedSet(collectionsToIndex));
    entity.setString(ALGORITHM, source.getAlgorithm());

    if (source.getMetadata() != null) {
      entity.setEmbeddedMap(METADATA, session.newEmbeddedMap(source.getMetadata()));
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
  public Map<String, Object> getConfiguration(DatabaseSessionEmbedded session) {
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
  public Stream<Object> keyStream(@Nonnull AtomicOperation operation) {
    acquireSharedLock();
    try {
      while (true) {
        try {
          return storage.getIndexKeyStream(indexId, operation);
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
      if (!(o instanceof IndexAbstract that)) {
        return false;
      }

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
  public boolean acquireAtomicExclusiveLock(AtomicOperation atomicOperation) {
    BaseIndexEngine engine;

    while (true) {
      try {
        engine = storage.getIndexEngine(indexId);
        break;
      } catch (InvalidIndexEngineIdException ignore) {
        doReloadIndexEngine();
      }
    }

    return engine.acquireAtomicExclusiveLock(atomicOperation);
  }

  @Nullable @Override
  public IndexStatistics getStatistics(DatabaseSessionEmbedded session) {
    while (true) {
      try {
        var engine = storage.getIndexEngine(indexId);
        return engine.getStatistics();
      } catch (InvalidIndexEngineIdException ignore) {
        doReloadIndexEngine();
      }
    }
  }

  @Nullable @Override
  public EquiDepthHistogram getHistogram(DatabaseSessionEmbedded session) {
    while (true) {
      try {
        var engine = storage.getIndexEngine(indexId);
        return engine.getHistogram();
      } catch (InvalidIndexEngineIdException ignore) {
        doReloadIndexEngine();
      }
    }
  }

  @Nullable @Override
  public HistogramSnapshot analyzeHistogram(DatabaseSessionEmbedded session) {
    while (true) {
      try {
        var engine = storage.getIndexEngine(indexId);
        if (engine instanceof BTreeIndexEngine btreeEngine) {
          var manager = btreeEngine.getHistogramManager();
          if (manager != null) {
            return manager.analyzeIndex();
          }
        }
        return null;
      } catch (InvalidIndexEngineIdException ignore) {
        doReloadIndexEngine();
      }
    }
  }

  private long[] indexCollection(
      DatabaseSessionEmbedded session, final String collectionName,
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

    var stat = new long[] {documentNum, documentIndexed};

    FrontendTransaction currentTransaction = null;
    if (session.isTxActive()) {
      currentTransaction = session.getTransactionInternal();
    }

    var collectionId = session.getCollectionIdByName(collectionName);
    // O(1) approximate guard — avoids starting a transaction just to check emptiness.
    // False-zero (approximate says 0 when records exist) is theoretically possible after
    // crash recovery but harmless: index rebuild is idempotent and can be retried.
    if (storage.getApproximateRecordsCount(collectionId) > 0) {
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

  protected void onIndexEngineChange(DatabaseSessionEmbedded session, final int indexId) {
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

  @Nullable @Override
  public RID getIdentity() {
    return identity;
  }
}
