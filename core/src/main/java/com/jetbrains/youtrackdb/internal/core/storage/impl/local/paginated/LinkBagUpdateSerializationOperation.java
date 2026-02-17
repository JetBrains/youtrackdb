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
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.LinkBagValue;
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
          var secondaryRid = change.getSecondaryRid();
          int secondaryCollectionId =
              secondaryRid != null ? secondaryRid.getCollectionId() : rid.getCollectionId();
          long secondaryPosition =
              secondaryRid != null ? secondaryRid.getCollectionPosition()
                  : rid.getCollectionPosition();
          tree.put(atomicOperation, entry.first(),
              new LinkBagValue(newCounter, secondaryCollectionId, secondaryPosition));
        }
      } catch (IOException e) {
        throw BaseException.wrapException(
            new DatabaseException(paginatedStorage.getName(), "Error during ridbag update"), e,
            paginatedStorage.getName());
      }
    });
  }

  private IsolatedLinkBagBTree<RID, LinkBagValue> loadTree() {
    return collectionManager.loadIsolatedBTree(collectionPointer);
  }
}
