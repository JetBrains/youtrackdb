package com.jetbrains.youtrackdb.internal.core.storage.ridbag;

import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.MultiValueChangeEvent;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.IsolatedLinkBagBTree.BTreeReadEntry;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransaction;
import java.util.List;
import java.util.Spliterator;
import javax.annotation.Nonnull;

public class EmbeddedLinkBag extends AbstractLinkBag {

  public EmbeddedLinkBag(@Nonnull DatabaseSessionEmbedded session, int counterMaxValue) {
    super(session, counterMaxValue);
  }


  public EmbeddedLinkBag(@Nonnull List<RawPair<RID, AbsoluteChange>> changes,
      @Nonnull DatabaseSessionEmbedded session, int size, int counterMaxValue) {
    super(session, size, counterMaxValue);
    localChanges.fillAllSorted(changes);
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
    final var reverted = new EmbeddedLinkBag(transaction.getDatabaseSession(), counterMaxValue);
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
      EmbeddedLinkBag reverted) {
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


  @Override
  public void requestDelete(FrontendTransaction transaction) {
  }


  @Override
  protected AbsoluteChange getAbsoluteChange(RID rid) {
    return localChanges.getChange(rid);
  }

  @Override
  protected Spliterator<BTreeReadEntry<RID>> btreeSpliterator(AtomicOperation atomicOperation) {
    return null;
  }

  @Override
  public boolean isSizeable() {
    return true;
  }
}
