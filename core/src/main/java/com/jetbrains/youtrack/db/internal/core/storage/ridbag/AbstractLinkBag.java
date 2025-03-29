package com.jetbrains.youtrack.db.internal.core.storage.ridbag;

import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.common.types.ModifiableInteger;
import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.MultiValueChangeTimeLine;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordElement;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.LinkBagDelegate;
import com.jetbrains.youtrack.db.internal.core.id.ChangeableIdentity;
import com.jetbrains.youtrack.db.internal.core.id.IdentityChangeListener;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.impl.SimpleMultiValueTracker;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.ridbagbtree.EdgeBTree;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public abstract class AbstractLinkBag implements LinkBagDelegate, IdentityChangeListener {

  protected final int counterMaxValue;

  protected final DatabaseSessionInternal session;
  @Nonnull
  protected Map<RID, Change> changes;

  /**
   * Entries with not valid id.
   */
  protected final HashMap<RID, int[]> newEntries = new HashMap<>();
  protected final IdentityHashMap<RID, int[]> newEntriesIdentityMap = new IdentityHashMap<>();

  protected int size;

  protected SimpleMultiValueTracker<RID, RID> tracker = new SimpleMultiValueTracker<>(this);
  protected RecordElement owner;

  protected boolean dirty;
  protected boolean transactionDirty = false;

  public AbstractLinkBag(@Nonnull Map<RID, Change> changes,
      @Nonnull DatabaseSessionInternal session, int size, int counterMaxValue) {
    assert assertIfNotActive();
    this.session = session;
    this.changes = initChanges();
    this.changes.putAll(changes);

    this.size = size;

    this.counterMaxValue = counterMaxValue;
  }

  public AbstractLinkBag(@Nonnull DatabaseSessionInternal session, int counterMaxValue) {
    assert assertIfNotActive();
    this.session = session;
    this.counterMaxValue = counterMaxValue;
    this.changes = initChanges();
  }

  public int getCounterMaxValue() {
    return counterMaxValue;
  }

  @Override
  public RecordElement getOwner() {
    return owner;
  }

  @Override
  public void setOwner(RecordElement owner) {
    if (owner != null && this.owner != null && !this.owner.equals(owner)) {
      throw new IllegalStateException(
          "This data structure is owned by entity "
              + owner
              + " if you want to use it in other entity create new rid bag instance and copy"
              + " content of current one.");
    }

    this.owner = owner;
  }

  protected abstract Map<RID, Change> initChanges();

  @Override
  public void addAll(Collection<RID> values) {
    assert assertIfNotActive();

    for (var identifiable : values) {
      add(identifiable);
    }
  }

  @Override
  public void add(RID rid) {
    if (rid == null) {
      throw new IllegalArgumentException("Impossible to add a null identifiable in a ridbag");
    }

    assert assertIfNotActive();
    if (rid.isPersistent()) {
      var counter = changes.get(rid);
      if (counter == null) {
        changes.put(rid, new DiffChange(1));
      } else {
        if (counter.isUndefined()) {
          counter = getAbsoluteValue(rid);
          changes.put(rid, counter);
        }

        counter.increment(counterMaxValue);
      }
    } else {
      rid = session.refreshRid(rid);

      final var counter = newEntries.get(rid);
      if (counter == null) {
        newEntries.put(rid, new int[]{1});

        if (rid instanceof ChangeableIdentity changeableIdentity
            && changeableIdentity.canChangeIdentity()) {
          changeableIdentity.addIdentityChangeListener(this);
        }
      } else {
        counter[0] = Math.min(counter[0] + 1, counterMaxValue);
      }
    }

    if (size >= 0) {
      size++;
    }

    addEvent(rid, rid);
  }

  @Override
  public void remove(RID rid) {
    assert assertIfNotActive();

    rid = refreshNonPersistentRid(rid);
    if (removeFromNewEntries(rid)) {
      if (size >= 0) {
        size--;
      }
    } else {
      final var counter = changes.get(rid);
      if (counter == null) {
        if (rid.getIdentity().isPersistent()) {
          changes.put(rid, new DiffChange(-1));
          size = -1;
        } else {
          return;
        }
      } else {
        counter.decrement();

        if (size >= 0) {
          if (counter.isUndefined()) {
            size = -1;
          } else {
            size--;
          }
        }
      }
    }

    removeEvent(rid);
  }

  @Override
  public boolean contains(RID rid) {
    assert assertIfNotActive();

    if (newEntries.containsKey(rid)) {
      return true;
    }

    var counter = changes.get(rid);

    if (counter != null) {
      if (counter.isUndefined()) {
        var absoluteValue = getAbsoluteValue(rid);
        changes.put(rid, absoluteValue);
      }
    } else {
      counter = getAbsoluteValue(rid);
    }

    return counter.applyTo(0, counterMaxValue) > 0;
  }

  @Override
  public int size() {
    assert assertIfNotActive();
    if (size >= 0) {
      return size;
    } else {
      updateSize();
      return size;
    }
  }

  public int notUpdateSize() {
    assert assertIfNotActive();
    return size;
  }

  protected abstract void updateSize();

  @Override
  public String toString() {
    if (size >= 0) {
      return getClass().getName() + " [size=" + size + "]";
    }

    return getClass().getName() + " [size=undefined]";
  }

  @Override
  public boolean isEmpty() {
    return size() == 0;
  }

  public void applyNewEntries() {
    assert assertIfNotActive();
    for (var entry : newEntries.entrySet()) {
      var rid = entry.getKey();
      if (!rid.isPersistent()) {
        throw new IllegalStateException("Cannot add non-persistent RID to the tree");
      }

      assert rid instanceof DBRecord;
      var c = changes.get(rid);

      final var delta = entry.getValue()[0];
      if (c == null) {
        changes.put(rid, new DiffChange(delta));
      } else {
        c.applyDiff(delta);
      }
    }

    for (var rid : newEntries.keySet()) {
      if (rid instanceof ChangeableIdentity changeableIdentity) {
        changeableIdentity.removeIdentityChangeListener(this);
      }
    }

    newEntries.clear();
  }

  @Nullable
  protected abstract EdgeBTree<RID, Integer> loadTree();

  protected abstract void releaseTree();

  protected AbsoluteChange getAbsoluteValue(RID rid) {
    final var tree = loadTree();
    try {
      Integer oldValue;

      if (tree == null) {
        oldValue = 0;
      } else {
        oldValue = tree.get(rid);
      }

      if (oldValue == null) {
        oldValue = 0;
      }

      final var change = changes.get(rid);

      return new AbsoluteChange(
          change == null ? oldValue : change.applyTo(oldValue, counterMaxValue));
    } finally {
      releaseTree();
    }
  }

  /**
   * Removes entry with given key from {@link #newEntries}.
   *
   * @param rid key to remove
   * @return true if entry have been removed
   */
  protected boolean removeFromNewEntries(final RID rid) {
    var counter = newEntries.get(rid);
    if (counter == null) {
      return false;
    } else {
      if (counter[0] == 1) {
        newEntries.remove(rid);

        if (rid instanceof ChangeableIdentity changeableIdentity) {
          changeableIdentity.removeIdentityChangeListener(this);
        }
      } else {
        counter[0]--;
      }
      return true;
    }
  }

  @Nullable
  protected Map.Entry<RID, Change> nextChangedNotRemovedEntry(
      Iterator<Entry<RID, Change>> iterator) {
    Map.Entry<RID, Change> entry;

    while (iterator.hasNext()) {
      entry = iterator.next();
      // TODO workaround
      if (entry.getValue().applyTo(0, counterMaxValue) > 0) {
        return entry;
      }
    }

    return null;
  }

  @Override
  public List<RawPair<RID, Change>> getChanges() {
    assert assertIfNotActive();

    var mergedChanges = new ArrayList<RawPair<RID, Change>>(newEntries.size() +
        changes.size());

    for (var entry : newEntries.entrySet()) {
      mergedChanges.add(new RawPair<>(entry.getKey(), new AbsoluteChange(entry.getValue()[0])));
    }

    for (var entry : this.changes.entrySet()) {
      mergedChanges.add(new RawPair<>(entry.getKey(), entry.getValue()));
    }

    return mergedChanges;
  }

  private void addEvent(RID key, RID rid) {
    if (tracker.isEnabled()) {
      tracker.add(key, rid);
    } else {
      setDirty();
    }
  }

  protected void removeEvent(RID removed) {
    if (tracker.isEnabled()) {
      tracker.remove(removed, removed);
    } else {
      setDirty();
    }
  }

  public void enableTracking(RecordElement parent) {
    assert assertIfNotActive();

    if (!tracker.isEnabled()) {
      tracker.enable();
    }
  }

  public void disableTracking(RecordElement entity) {
    assert assertIfNotActive();

    if (tracker.isEnabled()) {
      this.tracker.disable();
      this.dirty = false;
    }
  }

  @Override
  public void transactionClear() {
    assert assertIfNotActive();

    tracker.transactionClear();
    this.transactionDirty = false;
  }

  @Override
  public boolean isModified() {
    assert assertIfNotActive();

    return dirty;
  }

  @Override
  public boolean isTransactionModified() {
    assert assertIfNotActive();

    return transactionDirty;
  }

  @Override
  public MultiValueChangeTimeLine<RID, RID> getTimeLine() {
    assert assertIfNotActive();

    return tracker.getTimeLine();
  }

  @Override
  public void setDirty() {
    assert assertIfNotActive();

    this.dirty = true;
    this.transactionDirty = true;

    if (owner != null) {
      owner.setDirty();
    }
  }

  public void setTransactionModified(boolean transactionDirty) {
    assert assertIfNotActive();

    this.transactionDirty = transactionDirty;
  }

  @Override
  public void setDirtyNoChanged() {
    assert assertIfNotActive();

    if (owner != null) {
      owner.setDirtyNoChanged();
    }
    this.dirty = true;
    this.transactionDirty = true;
  }

  @Override
  public SimpleMultiValueTracker<RID, RID> getTracker() {
    assert assertIfNotActive();

    return tracker;
  }

  @Override
  public void setTracker(SimpleMultiValueTracker<RID, RID> tracker) {
    assert assertIfNotActive();

    this.tracker.sourceFrom(tracker);
  }

  @Override
  public MultiValueChangeTimeLine<RID, RID> getTransactionTimeLine() {
    assert assertIfNotActive();

    return this.tracker.getTransactionTimeLine();
  }

  protected RID refreshNonPersistentRid(RID identifiable) {
    if (!identifiable.isPersistent()) {
      identifiable = session.refreshRid(identifiable);
    }
    return identifiable;
  }

  @Override
  public void onBeforeIdentityChange(Object source) {
    var rid = (RecordId) source;
    var newEntryModificationCounter = newEntries.remove(rid);

    if (newEntryModificationCounter != null) {
      newEntriesIdentityMap.put(rid, newEntryModificationCounter);
    }
  }

  @Override
  public void onAfterIdentityChange(Object source) {
    var rid = (RecordId) source;

    var newEntryModificationCounter = newEntriesIdentityMap.remove(rid);
    newEntries.put(rid, newEntryModificationCounter);
  }

}
