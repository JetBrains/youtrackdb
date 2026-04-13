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

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.impl.LinkSerializer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.AbstractWriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.WOWCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.EdgeKey;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.IsolatedLinkBagBTree;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.IsolatedLinkBagBTreeImpl;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.LinkBagValue;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.LinkBagValueSerializer;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.SharedLinkBagBTree;
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
}
