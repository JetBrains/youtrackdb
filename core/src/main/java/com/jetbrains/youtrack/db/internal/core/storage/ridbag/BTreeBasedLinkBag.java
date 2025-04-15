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

import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.MultiValueChangeEvent;
import com.jetbrains.youtrack.db.internal.core.id.ChangeableIdentity;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.LinkBagDeleteSerializationOperation;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.LinkBagUpdateSerializationOperation;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.RecordSerializationContext;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.ridbagbtree.IsolatedLinkBagBTree;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransaction;
import it.unimi.dsi.fastutil.objects.ObjectIntPair;
import java.util.List;
import java.util.Spliterator;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class BTreeBasedLinkBag extends AbstractLinkBag {

  private final LinkCollectionsBTreeManager collectionManager;
  private LinkBagPointer collectionPointer;

  public BTreeBasedLinkBag(@Nonnull DatabaseSessionInternal session, int counterMaxValue) {
    super(session, counterMaxValue);
    collectionPointer = null;
    this.collectionManager = session.getBTreeCollectionManager();
  }

  public BTreeBasedLinkBag(@Nonnull DatabaseSessionInternal session, LinkBagPointer linkBagPointer,
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

    return reverted;
  }

  public void handleContextSBTree(
      RecordSerializationContext context, LinkBagPointer pointer) {
    assert assertIfNotActive();
    this.collectionPointer = pointer;
    context.push(new LinkBagUpdateSerializationOperation(getChanges(), collectionPointer,
        counterMaxValue, session));
  }


  @Override
  public void requestDelete() {
    final var context = RecordSerializationContext.getContext();
    if (context != null && collectionPointer != null) {
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

  @Override
  protected boolean isEmbedded() {
    return true;
  }

}
