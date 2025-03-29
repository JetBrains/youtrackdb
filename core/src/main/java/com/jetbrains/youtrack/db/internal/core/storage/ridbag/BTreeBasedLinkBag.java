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
import com.jetbrains.youtrack.db.internal.core.id.ChangeableIdentity;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.RecordSerializationContext;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.RidBagDeleteSerializationOperation;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.LinkBagUpdateSerializationOperation;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.ridbagbtree.EdgeBTree;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NavigableMap;
import java.util.NoSuchElementException;
import java.util.TreeMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class BTreeBasedLinkBag extends AbstractLinkBag {

  private final BTreeCollectionManager collectionManager;
  private BonsaiCollectionPointer collectionPointer;
  private int initialSize;

  public BTreeBasedLinkBag(BonsaiCollectionPointer pointer, @Nonnull Map<RID, Change> changes,
      @Nonnull DatabaseSessionInternal session, int size, int counterMaxValue) {
    super(changes, session, size, counterMaxValue);

    this.collectionPointer = pointer;
    this.size = size;
    this.initialSize = size;

    this.collectionManager = session.getBTreeCollectionManager();
  }

  public BTreeBasedLinkBag(@Nonnull DatabaseSessionInternal session, int counterMaxValue) {
    super(session, counterMaxValue);
    collectionPointer = null;
    this.collectionManager = session.getBTreeCollectionManager();
  }


  @Override
  protected Map<RID, Change> initChanges() {
    return new TreeMap<>();
  }

  @Override
  public @Nonnull Iterator<RID> iterator() {
    assert assertIfNotActive();
    return new RIDBagIterator(
        newEntries,
        (NavigableMap<RID, Change>) changes,
        collectionPointer != null ? new SBTreeMapEntryIterator(1000) : null);
  }

  public void mergeChanges(BTreeBasedLinkBag treeRidBag) {
    assert assertIfNotActive();

    for (var entry : treeRidBag.newEntries.entrySet()) {
      mergeDiffEntry(refreshNonPersistentRid(entry.getKey()), entry.getValue()[0]);
    }

    for (var entry : treeRidBag.changes.entrySet()) {
      final var rec = entry.getKey();
      assert rec.isPersistent();
      final var change = entry.getValue();
      final int diff;
      if (change instanceof DiffChange) {
        diff = change.getValue();
      } else if (change instanceof AbsoluteChange) {
        diff = change.getValue() - getAbsoluteValue(rec).getValue();
      } else {
        throw new IllegalArgumentException("change type is not supported");
      }

      mergeDiffEntry(rec, diff);
    }
  }

  @Override
  public Object returnOriginalState(
      DatabaseSessionInternal session,
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

  private void refreshNonPersistentRidsInChanges() {
    var tx = session.getTransactionInternal();
    for (var change : this.changes.entrySet()) {
      var key = change.getKey();
      if (tx.isActive()) {
        if (!key.isPersistent()) {
          var rid = session.refreshRid(key);
          if (rid != key) {
            changes.remove(key);
            changes.put(rid, change.getValue());
          }
        }
      }
    }
  }

  public void handleContextSBTree(
      RecordSerializationContext context, BonsaiCollectionPointer pointer) {
    assert assertIfNotActive();
    refreshNonPersistentRidsInChanges();
    this.collectionPointer = pointer;
    context.push(new LinkBagUpdateSerializationOperation((NavigableMap<RID, Change>) changes,
        collectionPointer, counterMaxValue,
        session));
  }

  public void clearChanges() {
    assert assertIfNotActive();
    changes.clear();
  }

  @Override
  public void requestDelete() {
    assert assertIfNotActive();

    final var context = RecordSerializationContext.getContext();
    if (context != null && collectionPointer != null) {
      context.push(new RidBagDeleteSerializationOperation(this));
    }
  }

  public void confirmDelete() {
    assert assertIfNotActive();

    collectionPointer = null;
    changes.clear();
    for (var rid : newEntries.keySet()) {
      if (rid instanceof ChangeableIdentity changeableIdentity) {
        changeableIdentity.removeIdentityChangeListener(this);
      }
    }
    newEntries.clear();
    size = 0;
  }

  public BonsaiCollectionPointer getCollectionPointer() {
    assert assertIfNotActive();

    return collectionPointer;
  }

  public void setCollectionPointer(BonsaiCollectionPointer collectionPointer) {
    assert assertIfNotActive();

    this.collectionPointer = collectionPointer;
  }

  @Nullable
  protected EdgeBTree<RID, Integer> loadTree() {
    if (collectionPointer == null) {
      return null;
    }

    return collectionManager.loadSBTree(collectionPointer);
  }

  protected void releaseTree() {
    if (collectionPointer == null) {
      return;
    }

    collectionManager.releaseSBTree(collectionPointer);
  }

  private void mergeDiffEntry(RID key, int diff) {
    if (diff > 0) {
      for (var i = 0; i < diff; i++) {
        add(key);
      }
    } else {
      for (var i = diff; i < 0; i++) {
        remove(key);
      }
    }
  }

  /**
   * Recalculates real bag size.
   */
  protected void updateSize() {
    assert assertIfNotActive();

    var size = initialSize;
    if (collectionPointer != null) {
      final var tree = loadTree();
      if (tree == null) {
        throw new IllegalStateException(
            "RidBag is not properly initialized, can not load tree implementation");
      }

      try {
        var newChanges = initChanges();
        for (var entry : changes.entrySet()) {
          var oldValue = tree.get(entry.getKey());
          var change = entry.getValue();

          if (oldValue == null) {
            if (!change.isUndefined()) {
              oldValue = 0;
              var newValue = change.applyTo(null, counterMaxValue);
              size = newValue - oldValue;

              if (newValue > 0) {
                newChanges.put(entry.getKey(), new AbsoluteChange(newValue));
              }
            }
          } else {
            var newValue = change.applyTo(oldValue, counterMaxValue);
            size = newValue - oldValue;

            if (newValue > 0) {
              newChanges.put(entry.getKey(), new AbsoluteChange(newValue));
            }
          }
        }

        changes = newChanges;
      } finally {
        releaseTree();
      }
    } else {
      for (var change : changes.values()) {
        size += change.applyTo(0, counterMaxValue);
      }
    }

    for (var diff : newEntries.values()) {
      size += diff[0];
    }

    this.size = size;
    this.initialSize = size;
  }


  @Nullable
  private Map.Entry<RID, Integer> nextChangedNotRemovedInTree(
      Iterator<Map.Entry<RID, Integer>> iterator) {
    while (iterator.hasNext()) {
      final var entry = iterator.next();
      final var change = changes.get(entry.getKey());
      if (change == null) {
        return entry;
      }

      final var newValue = change.applyTo(entry.getValue(), counterMaxValue);

      if (newValue > 0) {
        return new IdentifiableIntegerEntry(entry, newValue);
      }
    }

    return null;
  }

  private static class IdentifiableIntegerEntry implements Entry<RID, Integer> {

    private final Entry<RID, Integer> entry;
    private final int newValue;

    IdentifiableIntegerEntry(Entry<RID, Integer> entry, int newValue) {
      this.entry = entry;
      this.newValue = newValue;
    }

    @Override
    public RID getKey() {
      return entry.getKey();
    }

    @Override
    public Integer getValue() {
      return newValue;
    }

    @Override
    public Integer setValue(Integer value) {
      throw new UnsupportedOperationException();
    }
  }

  private final class RIDBagIterator implements Iterator<RID>, Resettable, Sizeable {

    private final NavigableMap<RID, Change> changedValues;
    private final SBTreeMapEntryIterator sbTreeIterator;
    private Iterator<Map.Entry<RID, int[]>> newEntryIterator;
    private Iterator<Map.Entry<RID, Change>> changedValuesIterator;
    private Map.Entry<RID, Change> nextChange;
    private Map.Entry<RID, Integer> nextSBTreeEntry;
    private RID currentValue;
    private int currentFinalCounter;
    private int currentCounter;
    private boolean currentRemoved;

    private RIDBagIterator(
        HashMap<RID, int[]> newEntries,
        NavigableMap<RID, Change> changedValues,
        SBTreeMapEntryIterator sbTreeIterator) {
      newEntryIterator = newEntries.entrySet().iterator();
      this.changedValues = changedValues;

      this.changedValuesIterator = changedValues.entrySet().iterator();
      this.sbTreeIterator = sbTreeIterator;

      nextChange = nextChangedNotRemovedEntry(changedValuesIterator);

      if (sbTreeIterator != null) {
        nextSBTreeEntry = nextChangedNotRemovedInTree(sbTreeIterator);
      }
    }

    @Override
    public boolean hasNext() {
      assert assertIfNotActive();
      return newEntryIterator.hasNext()
          || nextChange != null
          || nextSBTreeEntry != null
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

      if (nextChange != null && nextSBTreeEntry != null) {
        if (nextChange.getKey().compareTo(nextSBTreeEntry.getKey()) < 0) {
          currentValue = nextChange.getKey();
          currentFinalCounter = nextChange.getValue().applyTo(0, counterMaxValue);
          currentCounter = 1;

          nextChange = nextChangedNotRemovedEntry(changedValuesIterator);
        } else {
          currentValue = nextSBTreeEntry.getKey();
          currentFinalCounter = nextSBTreeEntry.getValue();
          currentCounter = 1;

          nextSBTreeEntry = nextChangedNotRemovedInTree(sbTreeIterator);
          if (nextChange != null && nextChange.getKey().equals(currentValue)) {
            nextChange = nextChangedNotRemovedEntry(changedValuesIterator);
          }
        }
      } else if (nextChange != null) {
        currentValue = nextChange.getKey();
        currentFinalCounter = nextChange.getValue().applyTo(0, counterMaxValue);
        currentCounter = 1;

        nextChange = nextChangedNotRemovedEntry(changedValuesIterator);
      } else if (nextSBTreeEntry != null) {
        currentValue = nextSBTreeEntry.getKey();
        currentFinalCounter = nextSBTreeEntry.getValue();
        currentCounter = 1;

        nextSBTreeEntry = nextChangedNotRemovedInTree(sbTreeIterator);
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
        }
      } else {
        var counter = changedValues.get(currentValue);
        if (counter != null) {
          counter.decrement();
          if (size >= 0) {
            if (counter.isUndefined()) {
              size = -1;
            } else {
              size--;
            }
          }
        } else {
          if (nextChange != null) {
            changedValues.put(currentValue, new DiffChange(-1));
            changedValuesIterator =
                changedValues.tailMap(nextChange.getKey(), false).entrySet().iterator();
          } else {
            changedValues.put(currentValue, new DiffChange(-1));
          }

          size = -1;
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
      if (sbTreeIterator != null) {
        this.sbTreeIterator.reset();
      }

      nextChange = nextChangedNotRemovedEntry(changedValuesIterator);

      if (sbTreeIterator != null) {
        nextSBTreeEntry = nextChangedNotRemovedInTree(sbTreeIterator);
      }
    }

    @Override
    public int size() {
      return BTreeBasedLinkBag.this.size();
    }
  }

  private final class SBTreeMapEntryIterator
      implements Iterator<Map.Entry<RID, Integer>>, Resettable {

    private final int prefetchSize;
    private LinkedList<Map.Entry<RID, Integer>> preFetchedValues;
    private RID firstKey;

    SBTreeMapEntryIterator(int prefetchSize) {
      this.prefetchSize = prefetchSize;

      init();
    }

    @Override
    public boolean hasNext() {
      return preFetchedValues != null;
    }

    @Override
    public Map.Entry<RID, Integer> next() {
      final var entry = preFetchedValues.removeFirst();
      if (preFetchedValues.isEmpty()) {
        prefetchData(false);
      }

      return entry;
    }

    @Override
    public void remove() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void reset() {
      init();
    }

    private void prefetchData(boolean firstTime) {
      final var tree = loadTree();
      if (tree == null) {
        throw new IllegalStateException(
            "RidBag is not properly initialized, can not load tree implementation");
      }

      try {
        tree.loadEntriesMajor(
            firstKey,
            firstTime,
            true,
            entry -> {
              preFetchedValues.add(
                  new Entry<>() {
                    @Override
                    public RID getKey() {
                      return entry.getKey();
                    }

                    @Override
                    public Integer getValue() {
                      return entry.getValue();
                    }

                    @Override
                    public Integer setValue(Integer v) {
                      throw new UnsupportedOperationException("setValue");
                    }
                  });

              return preFetchedValues.size() <= prefetchSize;
            });
      } finally {
        releaseTree();
      }

      if (preFetchedValues.isEmpty()) {
        preFetchedValues = null;
      } else {
        firstKey = preFetchedValues.getLast().getKey();
      }
    }

    private void init() {
      var tree = loadTree();
      if (tree == null) {
        throw new IllegalStateException(
            "RidBag is not properly initialized, can not load tree implementation");
      }

      try {
        firstKey = tree.firstKey();
      } finally {
        releaseTree();
      }

      if (firstKey == null) {
        this.preFetchedValues = null;
        return;
      }

      this.preFetchedValues = new LinkedList<>();
      prefetchData(true);
    }
  }
}
