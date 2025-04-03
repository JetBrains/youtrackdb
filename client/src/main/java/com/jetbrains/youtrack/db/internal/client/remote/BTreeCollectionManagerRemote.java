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

package com.jetbrains.youtrack.db.internal.client.remote;

import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.common.concur.resource.CloseableInStorage;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBShutdownListener;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBStartupListener;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.RidBag;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.BTreeCollectionManager;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.LinkBagPointer;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.ridbagbtree.EdgeBTree;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 *
 */
public class BTreeCollectionManagerRemote
    implements CloseableInStorage,
    BTreeCollectionManager,
    YouTrackDBStartupListener,
    YouTrackDBShutdownListener {

  private volatile ThreadLocal<Map<UUID, WeakReference<RidBag>>> pendingCollections =
      new PendingCollectionsThreadLocal();

  public BTreeCollectionManagerRemote() {

    YouTrackDBEnginesManager.instance().registerWeakYouTrackDBStartupListener(this);
    YouTrackDBEnginesManager.instance().registerWeakYouTrackDBShutdownListener(this);
  }

  @Override
  public void onShutdown() {
    pendingCollections = null;
  }

  @Override
  public void onStartup() {
    if (pendingCollections == null) {
      pendingCollections = new PendingCollectionsThreadLocal();
    }
  }

  protected EdgeBTree<RID, Integer> createEdgeTree(
      AtomicOperation atomicOperation, final int collectionId) {
    throw new UnsupportedOperationException(
        "Creation of SB-Tree from remote storage is not allowed");
  }

  protected EdgeBTree<RID, Integer> loadTree(
      LinkBagPointer collectionPointer) {
    throw new UnsupportedOperationException();
  }

  @Override
  public UUID listenForChanges(RidBag collection, DatabaseSessionInternal session) {
    var id = collection.getTemporaryId();
    if (id == null) {
      id = UUID.randomUUID();
    }

    pendingCollections.get().put(id, new WeakReference<RidBag>(collection));

    return id;
  }

  @Override
  public void updateCollectionPointer(UUID uuid, LinkBagPointer pointer,
      DatabaseSessionInternal session) {
    final var reference = pendingCollections.get().get(uuid);
    if (reference == null) {
      LogManager.instance()
          .warn(this, "Update of collection pointer is received but collection is not registered");
      return;
    }

    final var collection = reference.get();

    if (collection != null) {
      collection.notifySaved(pointer, session);
    }
  }

  @Override
  public void clearPendingCollections() {
    pendingCollections.get().clear();
  }

  @Override
  public Map<UUID, LinkBagPointer> changedIds(DatabaseSessionInternal session) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void clearChangedIds(DatabaseSessionInternal session) {
    throw new UnsupportedOperationException();
  }

  private static class PendingCollectionsThreadLocal
      extends ThreadLocal<Map<UUID, WeakReference<RidBag>>> {

    @Override
    protected Map<UUID, WeakReference<RidBag>> initialValue() {
      return new HashMap<UUID, WeakReference<RidBag>>();
    }
  }

  @Override
  public LinkBagPointer createSBTree(
      int collectionId, AtomicOperation atomicOperation, UUID ownerUUID,
      DatabaseSessionInternal session) throws IOException {
    var tree = createEdgeTree(atomicOperation, collectionId);
    return tree.getCollectionPointer();
  }

  @Override
  public EdgeBTree<RID, Integer> loadSBTree(
      LinkBagPointer collectionPointer) {

    final EdgeBTree<RID, Integer> tree;
    tree = loadTree(collectionPointer);

    return tree;
  }

  @Override
  public void releaseSBTree(LinkBagPointer collectionPointer) {
  }

  @Override
  public void delete(LinkBagPointer collectionPointer) {
  }

  @Override
  public void close() {
    clear();
  }

  public void clear() {
  }

  void clearCollectionCache(final long fileId, String fileName) {
  }

  int size() {
    return 0;
  }
}
