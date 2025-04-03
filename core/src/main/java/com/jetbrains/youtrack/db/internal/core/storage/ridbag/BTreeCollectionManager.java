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
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.RidBag;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.ridbagbtree.EdgeBTree;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;

public interface BTreeCollectionManager {

  LinkBagPointer createSBTree(
      int clusterId, AtomicOperation atomicOperation, UUID ownerUUID,
      DatabaseSessionInternal session) throws IOException;

  EdgeBTree<RID, Integer> loadSBTree(LinkBagPointer collectionPointer);

  void releaseSBTree(LinkBagPointer collectionPointer);

  void delete(LinkBagPointer collectionPointer);

  @Nullable
  UUID listenForChanges(RidBag collection, DatabaseSessionInternal session);

  void updateCollectionPointer(UUID uuid, LinkBagPointer pointer,
      DatabaseSessionInternal session);

  void clearPendingCollections();

  Map<UUID, LinkBagPointer> changedIds(DatabaseSessionInternal session);

  void clearChangedIds(DatabaseSessionInternal session);
}
