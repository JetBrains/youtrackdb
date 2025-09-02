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

package com.jetbrains.youtrackdb.internal.client.remote;

import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.LinkBagPointer;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.LinkCollectionsBTreeManager;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.IsolatedLinkBagBTree;

public class LinkCollectionsBTreeManagerRemote implements LinkCollectionsBTreeManager {

  public LinkCollectionsBTreeManagerRemote() {
  }


  @Override
  public LinkBagPointer createBTree(
      int collectionId, AtomicOperation atomicOperation,
      DatabaseSessionInternal session) {
    throw new UnsupportedOperationException();
  }

  @Override
  public IsolatedLinkBagBTree<RID, Integer> loadIsolatedBTree(
      LinkBagPointer collectionPointer) {
    throw new UnsupportedOperationException();
  }
}
