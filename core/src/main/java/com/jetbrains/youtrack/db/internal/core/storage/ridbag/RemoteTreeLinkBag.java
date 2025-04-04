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
import com.jetbrains.youtrack.db.internal.core.db.record.MultiValueChangeEvent;
import it.unimi.dsi.fastutil.objects.ObjectIntPair;
import java.util.List;
import java.util.Map;
import java.util.Spliterator;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class RemoteTreeLinkBag extends AbstractLinkBag {

  private final LinkBagPointer collectionPointer;
  private String fieldName;

  public RemoteTreeLinkBag(LinkBagPointer pointer, @Nonnull DatabaseSessionInternal session,
      int counterMaxValue) {
    super(session, counterMaxValue);
    this.collectionPointer = pointer;
  }

  @Override
  protected BagChangesContainer createChangesContainer() {
    return new TreeBasedBagChangesContainer();
  }

  @Override
  protected int getAbsoluteValue(RID rid) {
    throw new UnsupportedOperationException();
  }

  @Nullable
  @Override
  protected Spliterator<ObjectIntPair<RID>> btreeSpliterator() {
    throw new UnsupportedOperationException();
  }

  @Override
  protected Spliterator<ObjectIntPair<RID>> btreeSpliterator(RID after) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Object returnOriginalState(
      DatabaseSessionInternal session,
      List<MultiValueChangeEvent<RID, RID>> multiValueChangeEvents) {
    final var reverted = new RemoteTreeLinkBag(this.collectionPointer, session, counterMaxValue);

    for (var identifiable : this) {
      reverted.add(identifiable);
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

  @Override
  public void requestDelete() {
    throw new UnsupportedOperationException();
  }


  public void setOwnerFieldName(String fieldName) {
    this.fieldName = fieldName;
  }

  public LinkBagPointer getCollectionPointer() {
    return collectionPointer;
  }

  @Override
  public boolean isSizeable() {
    return false;
  }
}
