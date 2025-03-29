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
import com.jetbrains.youtrack.db.internal.common.util.Resettable;
import com.jetbrains.youtrack.db.internal.common.util.Sizeable;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.MultiValueChangeEvent;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.ridbagbtree.EdgeBTree;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class EmbeddedLinkBag extends AbstractLinkBag {

  public EmbeddedLinkBag(@Nonnull DatabaseSessionInternal session, int counterMaxValue) {
    super(session, counterMaxValue);
  }

  public EmbeddedLinkBag(@Nonnull Map<RID, Change> changes,
      @Nonnull DatabaseSessionInternal session, int size, int counterMaxValue) {
    super(changes, session, size, counterMaxValue);
  }

  @Override
  public @Nonnull Iterator<RID> iterator() {
    assert assertIfNotActive();
    return new RIDBagIterator(newEntries, changes);
  }

  @Override
  public Object returnOriginalState(
      DatabaseSessionInternal session,
      List<MultiValueChangeEvent<RID, RID>> multiValueChangeEvents) {
    assert assertIfNotActive();
    final var reverted = new EmbeddedLinkBag(session, counterMaxValue);
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
  }

  @Override
  protected Map<RID, Change> initChanges() {
    return new HashMap<>();
  }

  @Override
  protected void updateSize() {
    throw new UnsupportedOperationException();
  }

  @Nullable
  @Override
  protected EdgeBTree<RID, Integer> loadTree() {
    return null;
  }

  @Override
  protected void releaseTree() {
  }

  private final class RIDBagIterator implements Iterator<RID>, Resettable, Sizeable {

    private final Map<RID, Change> changedValues;
    private Iterator<Map.Entry<RID, int[]>> newEntryIterator;
    private Iterator<Map.Entry<RID, Change>> changedValuesIterator;
    private Map.Entry<RID, Change> nextChange;
    private RID currentValue;
    private int currentFinalCounter;
    private int currentCounter;
    private boolean currentRemoved;

    private RIDBagIterator(
        HashMap<RID, int[]> newEntries,
        Map<RID, Change> changedValues) {
      newEntryIterator = newEntries.entrySet().iterator();
      this.changedValues = changedValues;

      this.changedValuesIterator = changedValues.entrySet().iterator();
      nextChange = nextChangedNotRemovedEntry(changedValuesIterator);
    }

    @Override
    public boolean hasNext() {
      assert assertIfNotActive();
      return newEntryIterator.hasNext()
          || nextChange != null
          || (currentValue != null && currentCounter < currentFinalCounter);
    }

    @Override
    public RID next() {
      assert assertIfNotActive();
      currentRemoved = false;
      if (currentCounter < currentFinalCounter) {
        currentCounter++;
        return currentValue;
      }

      if (newEntryIterator.hasNext()) {
        var entry = newEntryIterator.next();
        currentValue = entry.getKey();
        currentFinalCounter = entry.getValue()[0];
        currentCounter = 1;
        return currentValue;
      }

      if (nextChange != null) {
        currentValue = nextChange.getKey();
        currentFinalCounter = nextChange.getValue().applyTo(0, counterMaxValue);
        currentCounter = 1;

        nextChange = nextChangedNotRemovedEntry(changedValuesIterator);
      } else {
        throw new NoSuchElementException();
      }

      return currentValue;
    }

    @Override
    public void remove() {
      assert assertIfNotActive();
      if (currentRemoved) {
        throw new IllegalStateException("Current entity has already been removed");
      }

      if (currentValue == null) {
        throw new IllegalStateException("Next method was not called for given iterator");
      }

      if (removeFromNewEntries(currentValue)) {
        if (size >= 0) {
          size--;
        } else {
          throw new IllegalStateException("Size is not defined for this bag");
        }
      } else {
        var counter = changedValues.get(currentValue);
        counter.decrement();

        if (size >= 0) {
          if (counter.isUndefined()) {
            throw new IllegalStateException("Size is not defined for this bag");
          } else {
            size--;
          }
        } else {
          throw new IllegalStateException("Size is not defined for this bag");
        }
      }

      removeEvent(currentValue);
      currentRemoved = true;
    }

    @Override
    public void reset() {
      assert assertIfNotActive();
      newEntryIterator = newEntries.entrySet().iterator();
      this.changedValuesIterator = changedValues.entrySet().iterator();
      nextChange = nextChangedNotRemovedEntry(changedValuesIterator);
    }

    @Override
    public int size() {
      assert assertIfNotActive();
      return EmbeddedLinkBag.this.size();
    }
  }
}
