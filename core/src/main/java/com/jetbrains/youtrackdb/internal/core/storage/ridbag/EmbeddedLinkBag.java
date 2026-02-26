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

import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.MultiValueChangeEvent;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransaction;
import it.unimi.dsi.fastutil.objects.ObjectIntPair;
import java.util.List;
import java.util.Spliterator;
import javax.annotation.Nonnull;

public class EmbeddedLinkBag extends AbstractLinkBag {

  public EmbeddedLinkBag(@Nonnull DatabaseSessionEmbedded session, int counterMaxValue) {
    super(session, counterMaxValue);
  }


  public EmbeddedLinkBag(@Nonnull List<RawPair<RID, Change>> changes,
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
    for (var identifiable : this) {
      reverted.add(identifiable);
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
        case REMOVE -> reverted.add(event.getOldValue());
        default ->
            throw new IllegalArgumentException("Invalid change type : " + event.getChangeType());
      }
    }
  }


  @Override
  public void requestDelete(FrontendTransaction transaction) {
  }


  @Override
  protected int getAbsoluteValue(RID rid) {
    var change = localChanges.getChange(rid);
    if (change == null) {
      return 0;
    }
    return change.getValue();
  }

  @Override
  protected Spliterator<ObjectIntPair<RID>> btreeSpliterator(AtomicOperation atomicOperation) {
    return null;
  }

  @Override
  public boolean isSizeable() {
    return true;
  }
}
