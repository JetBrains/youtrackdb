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

package com.jetbrains.youtrack.db.internal.core.storage.ridbag;

import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBShutdownListener;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBStartupListener;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.RidBag;
import com.jetbrains.youtrack.db.internal.core.exception.StorageException;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.binary.impl.LinkSerializer;
import com.jetbrains.youtrack.db.internal.core.storage.cache.AbstractWriteCache;
import com.jetbrains.youtrack.db.internal.core.storage.cache.local.WOWCache;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.AbstractPaginatedStorage;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.ridbagbtree.LinkBagBTree;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.ridbagbtree.EdgeBTree;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.ridbagbtree.EdgeBTreeImpl;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.ridbagbtree.EdgeKey;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;

/**
 *
 */
public final class BTreeCollectionManagerShared
    implements BTreeCollectionManager, YouTrackDBStartupListener, YouTrackDBShutdownListener {

  public static final String FILE_EXTENSION = ".grb";
  public static final String FILE_NAME_PREFIX = "global_collection_";

  private final AbstractPaginatedStorage storage;

  private final ConcurrentHashMap<Integer, LinkBagBTree> fileIdBTreeMap = new ConcurrentHashMap<>();

  private final AtomicLong ridBagIdCounter = new AtomicLong();

  public BTreeCollectionManagerShared(AbstractPaginatedStorage storage) {
    this.storage = storage;
  }

  public void load() {
    final var writeCache = storage.getWriteCache();

    for (final var entry : writeCache.files().entrySet()) {
      final var fileName = entry.getKey();
      if (fileName.endsWith(FILE_EXTENSION) && fileName.startsWith(FILE_NAME_PREFIX)) {
        final var bTree =
            new LinkBagBTree(
                storage,
                fileName.substring(0, fileName.length() - FILE_EXTENSION.length()),
                FILE_EXTENSION);
        bTree.load();
        fileIdBTreeMap.put(AbstractWriteCache.extractFileId(entry.getValue()), bTree);
        final var edgeKey = bTree.firstKey();

        if (edgeKey != null && edgeKey.ridBagId < 0 && ridBagIdCounter.get() < -edgeKey.ridBagId) {
          ridBagIdCounter.set(-edgeKey.ridBagId);
        }
      }
    }
  }

  public static boolean isComponentPresent(final AtomicOperation operation, final int collectionId) {
    return operation.fileIdByName(generateLockName(collectionId)) >= 0;
  }

  public void createComponent(final AtomicOperation operation, final int collectionId) {
    // lock is already acquired on storage level, during storage open
    final var bTree = new LinkBagBTree(storage, FILE_NAME_PREFIX + collectionId, FILE_EXTENSION);
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

  private EdgeBTreeImpl doCreateRidBag(AtomicOperation atomicOperation, int collectionId) {
    var fileId = atomicOperation.fileIdByName(generateLockName(collectionId));

    // lock is already acquired on storage level, during start fo the transaction so we
    // are thread safe here.
    if (fileId < 0) {
      final var bTree = new LinkBagBTree(storage, FILE_NAME_PREFIX + collectionId, FILE_EXTENSION);
      bTree.create(atomicOperation);

      fileId = bTree.getFileId();
      final var nextRidBagId = -ridBagIdCounter.incrementAndGet();

      final var intFileId = AbstractWriteCache.extractFileId(fileId);
      fileIdBTreeMap.put(intFileId, bTree);

      return new EdgeBTreeImpl(
          bTree, intFileId, nextRidBagId, LinkSerializer.INSTANCE, IntegerSerializer.INSTANCE);
    } else {
      final var intFileId = AbstractWriteCache.extractFileId(fileId);
      final var bTree = fileIdBTreeMap.get(intFileId);
      final var nextRidBagId = -ridBagIdCounter.incrementAndGet();

      return new EdgeBTreeImpl(
          bTree, intFileId, nextRidBagId, LinkSerializer.INSTANCE, IntegerSerializer.INSTANCE);
    }
  }

  @Override
  public EdgeBTree<RID, Integer> loadSBTree(
      LinkBagPointer collectionPointer) {
    final var intFileId = AbstractWriteCache.extractFileId(collectionPointer.fileId());

    final var bTree = fileIdBTreeMap.get(intFileId);

    return new EdgeBTreeImpl(
        bTree, intFileId, collectionPointer.linkBagId(), LinkSerializer.INSTANCE,
        IntegerSerializer.INSTANCE);
  }

  @Override
  public void releaseSBTree(final LinkBagPointer collectionPointer) {
  }

  @Override
  public void delete(final LinkBagPointer collectionPointer) {
  }

  @Override
  public LinkBagPointer createSBTree(
      int collectionId, AtomicOperation atomicOperation, UUID ownerUUID,
      DatabaseSessionInternal session) {
    final var bonsaiGlobal = doCreateRidBag(atomicOperation, collectionId);
    final var pointer = bonsaiGlobal.getCollectionPointer();

    if (ownerUUID != null) {
      var changedPointers =
          session.getCollectionsChanges();
      if (pointer != null && pointer.isValid()) {
        changedPointers.put(ownerUUID, pointer);
      }
    }

    return pointer;
  }

  /**
   * Change UUID to null to prevent its serialization to disk.
   */
  @Nullable
  @Override
  @Nullable
  public UUID listenForChanges(RidBag collection, DatabaseSessionInternal session) {
    var ownerUUID = collection.getTemporaryId();

    if (ownerUUID != null) {
      final var pointer = collection.getPointer();
      var changedPointers = session.getCollectionsChanges();

      if (pointer != null && pointer.isValid()) {
        changedPointers.put(ownerUUID, pointer);
      }
    }

    return null;
  }

  @Override
  public void updateCollectionPointer(UUID uuid, LinkBagPointer pointer,
      DatabaseSessionInternal session) {
  }

  @Override
  public void clearPendingCollections() {
  }

  @Override
  public Map<UUID, LinkBagPointer> changedIds(DatabaseSessionInternal session) {
    return session.getCollectionsChanges();
  }

  @Override
  public void clearChangedIds(DatabaseSessionInternal session) {
    session.getCollectionsChanges().clear();
  }

  @Override
  public void onShutdown() {
  }

  @Override
  public void onStartup() {
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
          "RidBug for with collection pointer " + collectionPointer + " does not exist");
    }

    var linkBagId = collectionPointer.linkBagId();
    try (var stream =
        bTree.iterateEntriesBetween(
            new EdgeKey(linkBagId, Integer.MIN_VALUE, Long.MIN_VALUE),
            true,
            new EdgeKey(linkBagId, Integer.MAX_VALUE, Long.MAX_VALUE),
            true,
            true)) {
      stream.forEach(pair -> bTree.remove(atomicOperation, pair.first));
    }

    return true;
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
