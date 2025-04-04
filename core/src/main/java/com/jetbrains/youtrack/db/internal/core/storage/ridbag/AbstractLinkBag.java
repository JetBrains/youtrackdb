package com.jetbrains.youtrack.db.internal.core.storage.ridbag;

import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.common.stream.Streams;
import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.internal.common.util.Resettable;
import com.jetbrains.youtrack.db.internal.common.util.Sizeable;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.MultiValueChangeTimeLine;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordElement;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.LinkBagDelegate;
import com.jetbrains.youtrack.db.internal.core.id.ChangeableIdentity;
import com.jetbrains.youtrack.db.internal.core.id.IdentityChangeListener;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.impl.SimpleMultiValueTracker;
import it.unimi.dsi.fastutil.objects.ObjectIntPair;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Spliterator;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.commons.collections4.ResettableIterator;

public abstract class AbstractLinkBag implements LinkBagDelegate, IdentityChangeListener {

  protected long newModificationsCount = 0;
  protected long persistentModificationsCount = 0;

  protected final int counterMaxValue;

  @Nonnull
  protected final DatabaseSessionInternal session;

  @Nonnull
  protected BagChangesContainer localChanges;

  /**
   * New not saved entities. Their identity is subject to change.
   */
  protected final TreeMap<RID, int[]> newEntries = new TreeMap<>();
  protected final IdentityHashMap<RID, int[]> newEntriesIdentityMap = new IdentityHashMap<>();

  protected int size;

  protected SimpleMultiValueTracker<RID, RID> tracker = new SimpleMultiValueTracker<>(this);
  protected RecordElement owner;

  protected boolean dirty;
  protected boolean transactionDirty = false;

  public AbstractLinkBag(@Nonnull DatabaseSessionInternal session, int size, int counterMaxValue) {
    assert assertIfNotActive();
    this.session = session;
    this.localChanges = createChangesContainer();
    this.size = size;

    this.counterMaxValue = counterMaxValue;
  }

  public AbstractLinkBag(@Nonnull DatabaseSessionInternal session, int counterMaxValue) {
    assert assertIfNotActive();
    this.session = session;
    this.counterMaxValue = counterMaxValue;
    this.localChanges = createChangesContainer();
  }

  protected abstract BagChangesContainer createChangesContainer();

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
      throw new IllegalArgumentException("Impossible to add a null identifiable in a link bag");
    }

    assert assertIfNotActive();

    if (rid.isPersistent()) {
      var counter = localChanges.getChange(rid);

      if (counter == null) {
        localChanges.putChange(rid, new DiffChange(1));
      } else {
        counter.increment(counterMaxValue);
      }

      persistentModificationsCount++;
    } else {
      rid = session.refreshRid(rid);

      newEntries.compute(rid, (key, value) -> {
        newModificationsCount++;

        if (value == null) {
          if (key instanceof ChangeableIdentity changeableIdentity
              && changeableIdentity.canChangeIdentity()) {
            changeableIdentity.addIdentityChangeListener(this);
          }

          return new int[]{1};
        }

        value[0] = Math.min(value[0] + 1, counterMaxValue);
        return value;
      });
    }

    assert size >= 0;
    size++;

    addEvent(rid, rid);
  }

  @Override
  public boolean remove(RID rid) {
    return doRemove(rid, false);
  }

  @Override
  public boolean removeLinks(RID rid) {
    return doRemove(rid, true);
  }

  private boolean doRemove(RID rid, boolean removeAll) {
    if (rid == null) {
      return false;
    }

    assert assertIfNotActive();
    rid = refreshNonPersistentRid(rid);

    var newRidsRemoved = removeFromNewEntries(rid, removeAll);
    if (newRidsRemoved > 0) {
      assert size >= newRidsRemoved;
      size -= newRidsRemoved;

      for (var i = 0; i < newRidsRemoved; i++) {
        removeEvent(rid);
      }

      return true;
    }

    var change = localChanges.getChange(rid);

    if (change == null) {
      if (rid.isPersistent()) {
        return removeAndUpdateAbsoluteValue(rid, removeAll);
      }

      return false;
    } else if (change.getType() == DiffChange.TYPE && removeAll) {
      return removeAndUpdateAbsoluteValue(rid, removeAll);
    }

    if (change.getType() == DiffChange.TYPE && change.getValue() == 0) {
      return removeAndUpdateAbsoluteValue(rid, removeAll);
    }

    if (removeAll) {
      var removed = change.clear();

      if (removed > 0) {
        for (var i = 0; i < removed; i++) {
          removeEvent(rid);
        }

        assert size >= removed;
        size -= removed;

        return true;
      }

      return false;
    }

    if (change.decrement()) {
      assert change.getType() == DiffChange.TYPE && size > 0 || size >= change.getValue();
      size--;

      removeEvent(rid);
      return true;
    }

    return false;
  }

  private boolean removeAndUpdateAbsoluteValue(RID rid, boolean removeAll) {
    var absoluteValue = getAbsoluteValue(rid);

    if (absoluteValue > 0) {
      if (removeAll) {
        localChanges.putChange(rid, new AbsoluteChange(0));
        persistentModificationsCount++;

        assert size >= absoluteValue;
        size -= absoluteValue;

        for (var i = 0; i < absoluteValue; i++) {
          removeEvent(rid);
        }

        return true;
      }

      localChanges.putChange(rid, new AbsoluteChange(absoluteValue - 1));
      persistentModificationsCount++;
      assert size >= absoluteValue;

      size--;
      removeEvent(rid);

      return true;
    }

    return false;
  }

  @Override
  public boolean contains(RID rid) {
    if (rid == null) {
      return false;
    }

    assert assertIfNotActive();
    if (newEntries.containsKey(rid)) {
      return true;
    }

    var change = localChanges.getChange(rid);

    if (change != null) {
      if (change.getType() == DiffChange.TYPE && change.applyTo(0, counterMaxValue) == 0) {
        var absoluteValue = getAbsoluteValue(rid);
        var absoluteChange = new AbsoluteChange(absoluteValue);
        localChanges.putChange(rid, absoluteChange);
        change = absoluteChange;
      }
    } else {
      change = new AbsoluteChange(getAbsoluteValue(rid));
    }

    return change.applyTo(0, counterMaxValue) > 0;
  }

  @Override
  public int size() {
    assert assertIfNotActive();
    assert size >= 0;

    return size;
  }

  @Override
  public boolean isEmpty() {
    return size() == 0;
  }


  protected abstract int getAbsoluteValue(RID rid);

  /**
   * Removes entry with given key from {@link #newEntries}.
   *
   * @param rid key to remove
   * @return number of entries removed
   */
  protected int removeFromNewEntries(final RID rid, boolean removeAll) {
    var counter = newEntries.get(rid);
    if (counter == null) {
      return 0;
    } else {
      if (counter[0] == 1) {
        newEntries.remove(rid);

        if (rid instanceof ChangeableIdentity changeableIdentity) {
          changeableIdentity.removeIdentityChangeListener(this);
        }

        newModificationsCount++;
        return 1;
      }

      if (removeAll) {
        var result = counter[0];
        newEntries.remove(rid);

        if (rid instanceof ChangeableIdentity changeableIdentity) {
          changeableIdentity.removeIdentityChangeListener(this);
        }

        newModificationsCount++;
        return result;
      }

      newModificationsCount++;
      counter[0]--;
      return 1;
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
  public Stream<RawPair<RID, Change>> getChanges() {
    assert assertIfNotActive();

    var mergedChanges = new ArrayList<RawPair<RID, Change>>(newEntries.size() +
        localChanges.size());

    for (var entry : newEntries.entrySet()) {
      mergedChanges.add(new RawPair<>(entry.getKey(), new AbsoluteChange(entry.getValue()[0])));
    }

    for (var entry : this.localChanges) {
      mergedChanges.add(entry);
    }

    if (!localChanges.isEmpty() && !newEntries.isEmpty()) {
      mergedChanges.sort(ArrayBasedBagChangesContainer.COMPARATOR);
    }

    return Streams.mergeSortedSpliterators(
        newEntries.entrySet().stream().map(
            pair ->
                new RawPair<>(pair.getKey(), new AbsoluteChange(pair.getValue()[0]))
        )
        , localChanges.stream(), ArrayBasedBagChangesContainer.COMPARATOR

    );
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
    if (newEntryModificationCounter != null) {
      newEntries.put(rid, newEntryModificationCounter);
    }
  }

  @Override
  public @Nonnull Iterator<RID> iterator() {
    assert assertIfNotActive();
    return new EnhancedIterator();
  }

  @Override
  public Stream<RID> stream() {
    assert assertIfNotActive();
    return StreamSupport.stream(spliterator(), false);
  }

  public void clearChanges() {
    assert assertIfNotActive();
    localChanges.clear();
    for (var rid : newEntries.keySet()) {
      if (rid instanceof ChangeableIdentity changeableIdentity) {
        changeableIdentity.removeIdentityChangeListener(this);
      }
    }
    persistentModificationsCount++;
  }

  @Nullable
  protected abstract Spliterator<ObjectIntPair<RID>> btreeSpliterator();

  @Nullable
  protected abstract Spliterator<ObjectIntPair<RID>> btreeSpliterator(RID after);

  @Override
  public Spliterator<RID> spliterator() {
    return new MergingSpliterator();
  }

  private final class MergingSpliterator implements Spliterator<RID> {

    @Nullable
    private Spliterator<Map.Entry<RID, int[]>> newEntriesSpliterator;
    @Nullable
    private Spliterator<RawPair<RID, Change>> localChangesSpliterator;
    @Nullable
    private Spliterator<ObjectIntPair<RID>> btreeRecordsSpliterator;

    @Nullable
    private RID currentRid;
    private int currentCounter;

    @Nullable
    private RID btreeRid;
    private int btreeCounter;

    @Nullable
    private RID localRid;
    private int localCounter;

    private long savedNewModificationsCount = newModificationsCount;
    private long savedPersistentModificationsCount = persistentModificationsCount;

    public MergingSpliterator() {
      initNewEntriesSpliterator();
      initLocalChangesSpliterator();
      initBTreeRecordsSpliterator();

      nextLocalEntree();
      nextBTreeEntree();

      moveToNextEntry();

    }

    private void moveToNextEntry() {
      assert currentCounter == 0;
      currentRid = null;

      do {
        if (localRid != null && btreeRid != null) {
          var result = localRid.compareTo(btreeRid);

          if (result < 0) {
            currentRid = localRid;
            currentCounter = localCounter;
            nextLocalEntree();
          } else if (result > 0) {
            currentRid = btreeRid;
            currentCounter = btreeCounter;

            nextBTreeEntree();
          } else {
            currentRid = localRid;
            //always overwrites btree counter
            currentCounter = localCounter;

            nextLocalEntree();
            nextBTreeEntree();
          }
        } else if (localRid != null) {
          currentRid = localRid;
          currentCounter = localCounter;

          nextLocalEntree();
        } else if (btreeRid != null) {
          currentRid = btreeRid;
          currentCounter = btreeCounter;

          nextBTreeEntree();
        } else {
          currentRid = null;
          currentCounter = 0;
        }
      } while (currentRid != null && currentCounter == 0);
    }

    private void initLocalChangesSpliterator() {
      if (localChanges.isEmpty()) {
        localChangesSpliterator = null;
      } else {
        localChangesSpliterator = localChanges.spliterator();
      }
    }

    private void initNewEntriesSpliterator() {
      if (newEntries.isEmpty()) {
        newEntriesSpliterator = null;
      } else {
        newEntriesSpliterator = newEntries.entrySet().spliterator();
      }
    }

    private void initBTreeRecordsSpliterator() {
      btreeRecordsSpliterator = btreeSpliterator();
    }

    @Override
    public boolean tryAdvance(Consumer<? super RID> action) {
      assert assertIfNotActive();
      if (currentCounter > 0) {
        acceptActionOnCurrentCounter(action);
        return true;
      }

      if (newEntriesSpliterator != null) {
        if (savedNewModificationsCount != newModificationsCount) {
          if (currentRid == null) {
            initNewEntriesSpliterator();
          } else {
            var tail = newEntries.tailMap(currentRid, false);
            if (tail.isEmpty()) {
              newEntriesSpliterator = null;
            } else {
              newEntriesSpliterator = tail.entrySet().spliterator();
            }
          }

          savedNewModificationsCount = newModificationsCount;
        }

        if (newEntriesSpliterator != null && newEntriesSpliterator.tryAdvance(pair -> {
          currentRid = pair.getKey();
          currentCounter = pair.getValue()[0];
          assert currentCounter > 0;
        })) {
          acceptActionOnCurrentCounter(action);
          return true;
        } else {
          newEntriesSpliterator = null;
          currentRid = null;
          currentCounter = 0;
        }
      }

      moveToNextEntry();
      if (currentCounter > 0) {
        acceptActionOnCurrentCounter(action);
        return true;
      }

      return false;
    }

    private void nextBTreeEntree() {
      if (btreeRecordsSpliterator == null) {
        return;
      }

      if (!btreeRecordsSpliterator.tryAdvance(
          ridChangeRawPair -> {
            btreeRid = ridChangeRawPair.first();
            btreeCounter = ridChangeRawPair.secondInt();
            assert btreeCounter > 0;
          })) {
        btreeRid = null;
        btreeCounter = 0;
        btreeRecordsSpliterator = null;
      }

      assert btreeRid == null && btreeCounter == 0 || btreeCounter > 0;
    }

    private void nextLocalEntree() {
      if (localChangesSpliterator == null) {
        return;
      }

      if (savedPersistentModificationsCount != persistentModificationsCount) {
        if (currentRid == null) {
          initLocalChangesSpliterator();
        } else {
          var tailSpliterator = localChanges.spliterator(currentRid);

          if (tailSpliterator.estimateSize() == 0) {
            localChangesSpliterator = null;
          }
        }

        savedPersistentModificationsCount = persistentModificationsCount;
      }

      if (!localChangesSpliterator.tryAdvance(ridChangeRawPair -> {
        localRid = ridChangeRawPair.first();
        var change = ridChangeRawPair.second();

        if (change.getType() == DiffChange.TYPE) {
          var absoluteValue = getAbsoluteValue(localRid);
          change = new AbsoluteChange(absoluteValue);
        }

        assert change instanceof AbsoluteChange;
        localCounter = change.getValue();
      })) {
        localRid = null;
        localCounter = 0;
        localChangesSpliterator = null;
      }
    }

    private void acceptActionOnCurrentCounter(Consumer<? super RID> action) {
      action.accept(currentRid);
      currentCounter--;
    }

    @Nullable
    @Override
    public Spliterator<RID> trySplit() {
      assert assertIfNotActive();
      return null;
    }

    @Override
    public long estimateSize() {
      assert assertIfNotActive();

      assert size >= 0;
      return size;
    }

    @Override
    public int characteristics() {
      assert assertIfNotActive();
      return Spliterator.SORTED | Spliterator.NONNULL | Spliterator.SIZED | Spliterator.ORDERED;
    }

    @Override
    @Nullable
    public Comparator<? super RID> getComparator() {
      return null;
    }
  }

  private final class EnhancedIterator implements Iterator<RID>, Sizeable,
      ResettableIterator<RID>, Resettable {

    private Iterator<RID> iterator = stream().iterator();
    private RID currentRid;

    @Override
    public boolean isResetable() {
      assert assertIfNotActive();
      return true;
    }

    @Override
    public int size() {
      return AbstractLinkBag.this.size();
    }

    @Override
    public boolean isSizeable() {
      assert assertIfNotActive();
      return true;
    }

    @Override
    public void reset() {
      iterator = stream().iterator();
    }

    @Override
    public boolean hasNext() {
      assert assertIfNotActive();
      return iterator.hasNext();
    }

    @Override
    public RID next() {
      assert assertIfNotActive();
      currentRid = iterator.next();
      return currentRid;
    }

    @Override
    public void remove() {
      if (currentRid == null) {
        throw new IllegalStateException("No current element to remove");
      }
      AbstractLinkBag.this.remove(currentRid);
      currentRid = null;
    }
  }


  @Override
  public String toString() {
    if (size >= 0) {
      return getClass().getName() + " [size=" + size + "]";
    }

    return getClass().getName() + " [size=undefined]";
  }
}
