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
package com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated;

import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.BTreeCollectionManager;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.DiffChange;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.LinkBagPointer;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.Change;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.ridbagbtree.IsolatedLinkBagBTree;
import java.io.IOException;
import java.util.stream.Stream;
import javax.annotation.Nonnull;

/**
 * @since 11/26/13
 */
public class LinkBagUpdateSerializationOperation implements RecordSerializationOperation {

  private final Stream<RawPair<RID, Change>> changedValues;

  private final LinkBagPointer collectionPointer;
  private final BTreeCollectionManager collectionManager;
  private final int maxCounterValue;

  public LinkBagUpdateSerializationOperation(
      final Stream<RawPair<RID, Change>> changedValues,
      LinkBagPointer collectionPointer, int maxCounterValue,
      @Nonnull DatabaseSessionInternal session) {
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
        int newCounter;

        if (change.getType() == DiffChange.TYPE) {
          var storedCounter = tree.get(rid);
          newCounter = entry.second().applyTo(storedCounter, Integer.MAX_VALUE);

          if (newCounter > maxCounterValue) {
            throw new DatabaseException(paginatedStorage.getName(),
                "Link collection can not contain more than : " + maxCounterValue +
                    " entries of of the same RID. Current value: " + newCounter + " for rid : "
                    + rid);
          }

          if (storedCounter != null && newCounter < 0) {
            throw new DatabaseException(
                "More entries were removed from link collection than it contains. For rid : "
                    + rid + " collection contains : " + storedCounter + " entries, but " + (
                    newCounter - storedCounter) +
                    " entries were removed.");
          }

          if (storedCounter != null && newCounter == 0) {
            tree.remove(atomicOperation, entry.first());
          } else if (newCounter > 0 && (storedCounter == null || newCounter != storedCounter)) {
            tree.put(atomicOperation, entry.first(), newCounter);
          }
        } else {
          newCounter = change.getValue();
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
