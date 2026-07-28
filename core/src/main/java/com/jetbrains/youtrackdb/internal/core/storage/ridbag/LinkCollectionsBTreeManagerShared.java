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

package com.jetbrains.youtrackdb.internal.core.storage.ridbag;

import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.impl.LinkSerializer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.AbstractWriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.WOWCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.EdgeKey;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.IsolatedLinkBagBTree;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.IsolatedLinkBagBTreeImpl;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.LinkBagValue;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.LinkBagValueSerializer;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.SharedLinkBagBTree;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared manager for B-tree-backed link collections (RID bags) within the storage engine.
 */
public final class LinkCollectionsBTreeManagerShared implements LinkCollectionsBTreeManager {

  public static final String FILE_EXTENSION = ".grb";
  public static final String FILE_NAME_PREFIX = "global_collection_";

  private final AbstractStorage storage;

  private final ConcurrentHashMap<Integer, SharedLinkBagBTree> fileIdBTreeMap =
      new ConcurrentHashMap<>();

  private final AtomicLong ridBagIdCounter = new AtomicLong();

  public LinkCollectionsBTreeManagerShared(AbstractStorage storage) {
    this.storage = storage;
  }

  public void load(AtomicOperation atomicOperation) {
    final var writeCache = storage.getWriteCache();

    for (final var entry : writeCache.files().entrySet()) {
      final var fileName = entry.getKey();
      if (fileName.endsWith(FILE_EXTENSION) && fileName.startsWith(FILE_NAME_PREFIX)) {
        final var bTree =
            new SharedLinkBagBTree(
                storage,
                fileName.substring(0, fileName.length() - FILE_EXTENSION.length()),
                FILE_EXTENSION);
        bTree.load(atomicOperation);
        fileIdBTreeMap.put(AbstractWriteCache.extractFileId(entry.getValue()), bTree);
        final var edgeKey = bTree.firstKey(atomicOperation);

        if (edgeKey != null && edgeKey.ridBagId < 0 && ridBagIdCounter.get() < -edgeKey.ridBagId) {
          ridBagIdCounter.set(-edgeKey.ridBagId);
        }
      }
    }
  }

  public static boolean isComponentPresent(final AtomicOperation operation,
      final int collectionId) {
    return operation.fileIdByName(generateLockName(collectionId)) >= 0;
  }

  public void createComponent(final AtomicOperation operation, final int collectionId) {
    // lock is already acquired on storage level, during storage open
    final var bTree = new SharedLinkBagBTree(storage, FILE_NAME_PREFIX + collectionId,
        FILE_EXTENSION);
    bTree.create(operation);

    final var intFileId = WOWCache.extractFileId(bTree.getFileId());
    fileIdBTreeMap.put(intFileId, bTree);
  }

  public void deleteComponentByCollectionId(
      final AtomicOperation atomicOperation, final int collectionId) {
    // lock is already acquired on storage level, during collection drop

    final var fileId = atomicOperation.fileIdByName(generateLockName(collectionId));
    final var intFileId = AbstractWriteCache.extractFileId(fileId);
    final var bTree = fileIdBTreeMap.remove(intFileId);
    if (bTree != null) {
      bTree.delete(atomicOperation);
    }
  }

  /**
   * Re-registers the id-named link-bag component of {@code collectionId} into the in-memory
   * {@code fileIdBTreeMap} — the mirror of {@link #deleteComponentByCollectionId} for the
   * failed-schema-commit undo. The delete removes the map entry eagerly (the map is not
   * rollback-aware) while merely buffering the file delete on the atomic operation, so when that
   * operation rolls back the durable {@code .grb} file survives but the map entry is gone — and
   * every link-bag access resolves through the map with no reload branch, so the restored
   * collection's first link-bag operation would fail until a reopen rebuilds the map. No-op when
   * the component file is absent (a collection that never allocated a link bag). Idempotent when
   * an equivalent entry is already present (the slot-reuse undo path, where the failed create's
   * replacement object serves the identical resurrected file id): the tree object carries no state
   * beyond its file id, so the re-put is a harmless replacement.
   */
  public void restoreComponentByCollectionId(
      final AtomicOperation atomicOperation, final int collectionId) {
    // lock is already acquired on storage level, during the failed-commit undo
    final var fileId = atomicOperation.fileIdByName(generateLockName(collectionId));
    if (fileId < 0) {
      return;
    }
    final var bTree = new SharedLinkBagBTree(storage, FILE_NAME_PREFIX + collectionId,
        FILE_EXTENSION);
    bTree.load(atomicOperation);
    fileIdBTreeMap.put(AbstractWriteCache.extractFileId(fileId), bTree);
  }

  private IsolatedLinkBagBTreeImpl doCreateRidBag(AtomicOperation atomicOperation,
      int collectionId) {
    var fileId = atomicOperation.fileIdByName(generateLockName(collectionId));

    // lock is already acquired on storage level, during start fo the transaction so we
    // are thread safe here.
    if (fileId < 0) {
      final var bTree = new SharedLinkBagBTree(storage, FILE_NAME_PREFIX + collectionId,
          FILE_EXTENSION);
      bTree.create(atomicOperation);

      fileId = bTree.getFileId();
      final var nextRidBagId = -ridBagIdCounter.incrementAndGet();

      final var intFileId = AbstractWriteCache.extractFileId(fileId);
      fileIdBTreeMap.put(intFileId, bTree);

      return new IsolatedLinkBagBTreeImpl(
          bTree, intFileId, nextRidBagId, LinkSerializer.INSTANCE,
          LinkBagValueSerializer.INSTANCE);
    } else {
      final var intFileId = AbstractWriteCache.extractFileId(fileId);
      final var bTree = fileIdBTreeMap.get(intFileId);
      final var nextRidBagId = -ridBagIdCounter.incrementAndGet();

      return new IsolatedLinkBagBTreeImpl(
          bTree, intFileId, nextRidBagId, LinkSerializer.INSTANCE,
          LinkBagValueSerializer.INSTANCE);
    }
  }

  @Override
  public IsolatedLinkBagBTree<RID, LinkBagValue> loadIsolatedBTree(
      LinkBagPointer collectionPointer) {
    final var intFileId = AbstractWriteCache.extractFileId(collectionPointer.fileId());
    final var bTree = fileIdBTreeMap.get(intFileId);
    return new IsolatedLinkBagBTreeImpl(
        bTree, intFileId, collectionPointer.linkBagId(), LinkSerializer.INSTANCE,
        LinkBagValueSerializer.INSTANCE);
  }

  @Override
  public LinkBagPointer createBTree(
      int collectionId, AtomicOperation atomicOperation,
      DatabaseSessionEmbedded session) {
    final var bonsaiGlobal = doCreateRidBag(atomicOperation, collectionId);
    return bonsaiGlobal.getCollectionPointer();
  }

  public void close() {
    fileIdBTreeMap.clear();
  }

  public boolean delete(
      AtomicOperation atomicOperation, LinkBagPointer collectionPointer,
      String storageName) {
    final var fileId = (int) collectionPointer.fileId();
    final var bTree = fileIdBTreeMap.get(fileId);
    if (bTree == null) {
      throw new StorageException(storageName,
          "RidBag for collection pointer " + collectionPointer + " does not exist");
    }

    var linkBagId = collectionPointer.linkBagId();
    // Intentionally passes the original EdgeKey (with its existing ts) to
    // remove(), which triggers the same-ts physical delete path. This is safe
    // because link bag deletion is a whole-collection operation — no concurrent
    // readers can hold a snapshot of an already-dropped collection.
    //
    // A single iterate-and-remove pass may miss entries when page modifications
    // during removal invalidate the spliterator's LSN-based position tracking.
    // Retry until no entries remain in the range.
    var lowerBound =
        new EdgeKey(linkBagId, Integer.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE);
    var upperBound =
        new EdgeKey(linkBagId, Integer.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
    long removedInPass;
    do {
      removedInPass = 0;
      try (var stream =
          bTree.streamEntriesBetween(
              lowerBound, true, upperBound, true, true, atomicOperation)) {
        for (var iter = stream.iterator(); iter.hasNext();) {
          // SharedLinkBagBTree.remove() returns null without removing for
          // tombstone entries left by prior cross-transaction deletes. Only
          // count actual removals so the loop terminates when only tombstones
          // remain (they will be cleaned by periodic tombstone GC).
          if (bTree.remove(atomicOperation, iter.next().first()) != null) {
            removedInPass++;
          }
        }
      }
    } while (removedInPass > 0);

    return true;
  }

  /**
   * Returns the {@link SharedLinkBagBTree} component for the given collection ID,
   * or {@code null} if no link bag exists for this collection yet.
   */
  public SharedLinkBagBTree getComponentByCollectionId(
      int collectionId, AtomicOperation atomicOperation) {
    var fileId = atomicOperation.fileIdByName(generateLockName(collectionId));
    if (fileId < 0) {
      return null;
    }
    var intFileId = AbstractWriteCache.extractFileId(fileId);
    return fileIdBTreeMap.get(intFileId);
  }

  /**
   * Generates a lock name for the given collection ID.
   *
   * @param collectionId the collection ID to generate the lock name for.
   * @return the generated lock name.
   */
  public static String generateLockName(int collectionId) {
    return FILE_NAME_PREFIX + collectionId + FILE_EXTENSION;
  }

  /**
   * Recovery-pass iteration delegate: invokes
   * {@link SharedLinkBagBTree#verifyAndTruncateOrphans(AtomicOperation, ReadCache, WriteCache)}
   * on every loaded {@link SharedLinkBagBTree} held in {@link #fileIdBTreeMap}.
   *
   * <p>Dispatches in undefined order — each per-component helper is independent (one
   * {@code .grb} file per call, no shared state across SLBB instances) so iteration order
   * does not affect the recovery outcome. When {@code fileIdBTreeMap} is empty (no
   * collections loaded), the method returns without iterating.
   *
   * <p>Per-SLBB failure handling: each dispatch is wrapped in a try/catch that absorbs
   * {@link StorageException} and {@link IOException} with a WARN log and continues with
   * the next SLBB. The orphan-truncation pass is best-effort — one corrupted SLBB must
   * not poison recovery for the rest.
   *
   * <p>This delegate is the orchestrator-facing surface for SLBB orphan truncation; the
   * manager intentionally exposes no public iteration accessor over its internal map.
   *
   * @param atomicOperation the enclosing recovery-pass atomic operation
   * @param readCache       read cache the truncate dispatches through
   * @param writeCache      write cache backing the read cache
   */
  public void verifyAndTruncateAllOrphans(
      final AtomicOperation atomicOperation,
      final ReadCache readCache,
      final WriteCache writeCache) {
    for (final var bTree : fileIdBTreeMap.values()) {
      try {
        bTree.verifyAndTruncateOrphans(atomicOperation, readCache, writeCache);
      } catch (final StorageException | IOException e) {
        LogManager.instance()
            .warn(
                this,
                String.format(
                    "Orphan-truncation skipped for SharedLinkBagBTree '%s' (fileId=%d): %s",
                    bTree.getName(), bTree.getFileId(), e.getMessage()));
      }
    }
  }
}
