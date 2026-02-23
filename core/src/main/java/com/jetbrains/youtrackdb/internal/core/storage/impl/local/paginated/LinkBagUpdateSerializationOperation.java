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
package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.Change;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.LinkBagPointer;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.LinkCollectionsBTreeManager;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.IsolatedLinkBagBTree;
import java.io.IOException;
import java.util.stream.Stream;
import javax.annotation.Nonnull;

/**
 * A serialization operation that applies pending changes to a link bag during record
 * serialization.
 *
 * @since 11/26/13
 */
public class LinkBagUpdateSerializationOperation implements RecordSerializationOperation {

  private final Stream<RawPair<RID, Change>> changedValues;

  private final LinkBagPointer collectionPointer;
  private final LinkCollectionsBTreeManager collectionManager;
  private final int maxCounterValue;

  public LinkBagUpdateSerializationOperation(
      final Stream<RawPair<RID, Change>> changedValues,
      LinkBagPointer collectionPointer, int maxCounterValue,
      @Nonnull DatabaseSessionEmbedded session) {
    this.changedValues = changedValues;
    this.collectionPointer = collectionPointer;
    collectionManager = session.getBTreeCollectionManager();
    this.maxCounterValue = maxCounterValue;
  }

  @Override
  public void execute(
      AtomicOperation atomicOperation, AbstractStorage paginatedStorage) {
    var tree = loadTree();
    changedValues.forEach(entry -> {
      try {
        var rid = entry.first();
        assert rid.isPersistent();

        var change = entry.second();
        var newCounter = change.getValue();

        if (newCounter > maxCounterValue) {
          throw new DatabaseException(paginatedStorage.getName(),
              "Link collection can not contain more than : " + maxCounterValue +
                  " entries of of the same RID. Current value: " + newCounter + " for rid : "
                  + rid);
        }
        if (newCounter < 0) {
          throw new DatabaseException(
              "More entries were removed from link collection than it contains. For rid : "
                  + rid + " collection contains : " + newCounter + " entries");
        }

        if (newCounter == 0) {
          tree.remove(atomicOperation, entry.first());
        } else {
          tree.put(atomicOperation, entry.first(), newCounter);
        }
      } catch (IOException e) {
        throw BaseException.wrapException(
            new DatabaseException(paginatedStorage.getName(), "Error during ridbag update"), e,
            paginatedStorage.getName());
      }
    });
  }

  private IsolatedLinkBagBTree<RID, Integer> loadTree() {
    return collectionManager.loadIsolatedBTree(collectionPointer);
  }
}
