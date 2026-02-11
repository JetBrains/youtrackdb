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
import com.jetbrains.youtrackdb.internal.core.db.record.MultiValueChangeEvent;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.id.ChangeableIdentity;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.LinkBagDeleteSerializationOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.LinkBagUpdateSerializationOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.RecordSerializationContext;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.IsolatedLinkBagBTree;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransaction;
import it.unimi.dsi.fastutil.objects.ObjectIntPair;
import java.util.List;
import java.util.Spliterator;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class BTreeBasedLinkBag extends AbstractLinkBag {

  private final LinkCollectionsBTreeManager collectionManager;
  private LinkBagPointer collectionPointer;

  public BTreeBasedLinkBag(@Nonnull DatabaseSessionEmbedded session, int counterMaxValue) {
    super(session, counterMaxValue);
    collectionPointer = null;
    this.collectionManager = session.getBTreeCollectionManager();
  }

  public BTreeBasedLinkBag(@Nonnull DatabaseSessionEmbedded session, LinkBagPointer linkBagPointer,
      int size,
      int counterMaxValue) {
    super(session, size, counterMaxValue);
    collectionPointer = linkBagPointer;
    this.collectionManager = session.getBTreeCollectionManager();
  }

  @Override
  protected BagChangesContainer createChangesContainer() {
    return new ArrayBasedBagChangesContainer();
  }

  @Override
  public Object returnOriginalState(
      FrontendTransaction transaction,
      List<MultiValueChangeEvent<RID, RID>> multiValueChangeEvents) {
    assert assertIfNotActive();

    final var reverted = new BTreeBasedLinkBag(this.session, counterMaxValue);
    for (var rid : this) {
      reverted.add(rid);
    }

    doRollBackChanges(multiValueChangeEvents, reverted);

    return reverted;
  }

  @Override
  public void rollbackChanges(FrontendTransaction transaction) {
    if (!tracker.isEnabled()) {
      throw new DatabaseException(transaction.getDatabaseSession(),
          "Changes are not tracked so it is impossible to rollback them");
    }

    var timeLine = tracker.getTimeLine();
    //no changes were performed
    if (timeLine == null) {
      return;
    }
    var changeEvents = timeLine.getMultiValueChangeEvents();
    //no changes were performed
    if (changeEvents == null || changeEvents.isEmpty()) {
      return;
    }

    doRollBackChanges(changeEvents, this);
  }

  private static void doRollBackChanges(
      List<MultiValueChangeEvent<RID, RID>> multiValueChangeEvents,
      BTreeBasedLinkBag reverted) {
    multiValueChangeEvents = List.copyOf(multiValueChangeEvents);
    final var listIterator =
        multiValueChangeEvents.listIterator(multiValueChangeEvents.size());

    while (listIterator.hasPrevious()) {
      final var event = listIterator.previous();
      switch (event.getChangeType()) {
        case ADD:
          reverted.remove(event.getKey());
          break;
        case REMOVE:
          reverted.add(event.getOldValue());
          break;
        default:
          throw new IllegalArgumentException("Invalid change type : " + event.getChangeType());
      }
    }
  }

  public void handleContextBTree(
      RecordSerializationContext context, LinkBagPointer pointer) {
    assert assertIfNotActive();
    this.collectionPointer = pointer;
    context.push(new LinkBagUpdateSerializationOperation(getChanges(), collectionPointer,
        counterMaxValue, session));
  }


  @Override
  public void requestDelete(FrontendTransaction transaction) {
    if (collectionPointer != null) {
      final var context = transaction.getRecordSerializationContext();
      context.push(new LinkBagDeleteSerializationOperation(this));
    }
  }

  public void confirmDelete() {
    collectionPointer = null;
    localChanges.clear();
    for (var rid : newEntries.keySet()) {
      if (rid instanceof ChangeableIdentity changeableIdentity) {
        changeableIdentity.removeIdentityChangeListener(this);
      }
    }
    newEntries.clear();
    size = 0;
    localChangesModificationsCount++;
    newModificationsCount++;
  }

  public LinkBagPointer getCollectionPointer() {
    return collectionPointer;
  }

  public void setCollectionPointer(LinkBagPointer collectionPointer) {
    this.collectionPointer = collectionPointer;
  }

  @Override
  protected int getAbsoluteValue(RID rid) {
    final var tree = loadTree();
    Integer oldValue;

    if (tree == null) {
      oldValue = 0;
    } else {
      oldValue = tree.get(rid);
    }

    if (oldValue == null) {
      oldValue = 0;
    }

    final var change = localChanges.getChange(rid);

    var newValue = change == null ? oldValue : change.applyTo(oldValue, counterMaxValue);
    if (newValue < 0) {
      throw new DatabaseException(
          "More entries were removed from link collection than it contains. For rid : "
              + rid + " collection contains : " + oldValue + " entries, but " + (
              newValue - oldValue) +
              " entries were removed.");
    }
    return newValue;

  }

  @Nullable
  protected IsolatedLinkBagBTree<RID, Integer> loadTree() {
    if (collectionPointer == null) {
      return null;
    }

    return collectionManager.loadIsolatedBTree(collectionPointer);
  }

  @Override
  public boolean isSizeable() {
    return true;
  }

  @Override
  protected Spliterator<ObjectIntPair<RID>> btreeSpliterator() {
    Spliterator<ObjectIntPair<RID>> btreeRecordsSpliterator = null;

    var tree = loadTree();
    if (tree != null) {
      btreeRecordsSpliterator = tree.spliteratorEntriesBetween(new RecordId(0, 0),
          true,
          new RecordId(RID.COLLECTION_MAX, Integer.MAX_VALUE), true, true);
    }

    return btreeRecordsSpliterator;
  }

}
