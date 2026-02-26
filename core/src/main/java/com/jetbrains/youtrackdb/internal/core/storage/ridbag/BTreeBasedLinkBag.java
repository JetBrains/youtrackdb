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
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.IsolatedLinkBagBTree;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.IsolatedLinkBagBTree.BTreeReadEntry;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.LinkBagValue;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransaction;
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
    for (var pair : this) {
      reverted.add(pair.primaryRid(), pair.secondaryRid());
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
        case ADD -> reverted.remove(event.getKey());
        case REMOVE -> reverted.add(event.getKey(), event.getOldValue());
        default ->
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
  protected AbsoluteChange getAbsoluteChange(RID rid) {
    final var tree = loadTree();
    LinkBagValue oldValue;

    if (tree == null) {
      oldValue = null;
    } else {
      oldValue = tree.get(rid, atomicOperation);
    }

    if (oldValue == null) {
      var change = localChanges.getChange(rid);
      return change;
    }

    int oldCounter = oldValue.counter();
    var secondaryRid = new com.jetbrains.youtrackdb.internal.core.id.RecordId(
        oldValue.secondaryCollectionId(), oldValue.secondaryPosition());

    final var change = localChanges.getChange(rid);

    var newValue = change == null ? oldCounter : change.applyTo(oldCounter, counterMaxValue);
    if (newValue < 0) {
      throw new DatabaseException(
          "More entries were removed from link collection than it contains. For rid : "
              + rid + " collection contains : " + oldCounter + " entries, but " + (
              newValue - oldCounter) +
              " entries were removed.");
    }
    return new AbsoluteChange(newValue, secondaryRid);

  }

  @Nullable
  protected IsolatedLinkBagBTree<RID, LinkBagValue> loadTree() {
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
  protected Spliterator<BTreeReadEntry<RID>> btreeSpliterator(AtomicOperation atomicOperation) {
    Spliterator<BTreeReadEntry<RID>> btreeRecordsSpliterator = null;

    var tree = loadTree();
    if (tree != null) {
      btreeRecordsSpliterator = tree.spliteratorEntriesBetween(new RecordId(0, 0),
          true,
          new RecordId(RID.COLLECTION_MAX, Integer.MAX_VALUE), true, true, atomicOperation);
    }

    return btreeRecordsSpliterator;
  }

}
