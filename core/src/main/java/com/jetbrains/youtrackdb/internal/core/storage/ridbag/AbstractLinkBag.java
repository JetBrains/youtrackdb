package com.jetbrains.youtrackdb.internal.core.storage.ridbag;

import com.jetbrains.youtrackdb.internal.common.stream.Streams;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.common.util.Resettable;
import com.jetbrains.youtrackdb.internal.common.util.Sizeable;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.MultiValueChangeTimeLine;
import com.jetbrains.youtrackdb.internal.core.db.record.RecordElement;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBagDelegate;
import com.jetbrains.youtrackdb.internal.core.id.ChangeableIdentity;
import com.jetbrains.youtrackdb.internal.core.id.IdentityChangeListener;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.record.impl.SimpleMultiValueTracker;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.IsolatedLinkBagBTree.BTreeReadEntry;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
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
  protected long localChangesModificationsCount = 0;

  protected final int counterMaxValue;

  @Nonnull
  protected final DatabaseSessionEmbedded session;
  @Nonnull
  protected final AtomicOperation atomicOperation;

  @Nonnull
  protected BagChangesContainer localChanges;

  protected static final class NewEntryValue {
    int counter;
    @Nullable
    RID secondaryRid;

    NewEntryValue(int counter, @Nullable RID secondaryRid) {
      this.counter = counter;
      this.secondaryRid = secondaryRid;
    }
  }

  protected final TreeMap<RID, NewEntryValue> newEntries = new TreeMap<>();
  protected final IdentityHashMap<RID, NewEntryValue> newEntriesIdentityMap =
      new IdentityHashMap<>();

  protected int size;

  protected SimpleMultiValueTracker<RID, RID> tracker = new SimpleMultiValueTracker<>(this);
  protected RecordElement owner;

  protected boolean dirty;
  protected boolean transactionDirty = false;

  public AbstractLinkBag(@Nonnull DatabaseSessionEmbedded session, int size, int counterMaxValue) {
    assert assertIfNotActive();
    this.session = session;
    var transaction = session.getActiveTransaction();

    this.atomicOperation = transaction.getAtomicOperation();
    this.localChanges = createChangesContainer();
    this.size = size;

    this.counterMaxValue = counterMaxValue;
  }

  public AbstractLinkBag(@Nonnull DatabaseSessionEmbedded session, int counterMaxValue) {
    assert assertIfNotActive();
    this.session = session;
    var transaction = session.getActiveTransaction();
    this.atomicOperation = transaction.getAtomicOperation();
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
  public boolean add(RID rid) {
    return add(rid, rid);
  }

  @Override
  public boolean add(RID primaryRid, RID secondaryRid) {
    if (primaryRid == null) {
      throw new IllegalArgumentException("Impossible to add a null identifiable in a link bag");
    }

    assert assertIfNotActive();

    var added = new boolean[1];
    if (primaryRid.isPersistent()) {
      var counter = localChanges.getChange(primaryRid);
      if (counter == null) {
        var absoluteValue = getAbsoluteValue(primaryRid);

        var absoluteChange = new AbsoluteChange(absoluteValue, secondaryRid);
        added[0] = absoluteChange.increment(counterMaxValue);
        localChanges.putChange(primaryRid, absoluteChange);
      } else {
        assert counter.getValue() >= 0;
        added[0] = counter.increment(counterMaxValue);
        if (counter instanceof AbsoluteChange absoluteChange) {
          absoluteChange.setSecondaryRid(secondaryRid);
        }
      }

      localChangesModificationsCount++;
    } else {
      primaryRid = session.refreshRid(primaryRid);
      secondaryRid = session.refreshRid(secondaryRid);

      final var capturedSecondaryRid = secondaryRid;
      newEntries.compute(primaryRid, (key, value) -> {
        newModificationsCount++;

        if (value == null) {
          if (key instanceof ChangeableIdentity changeableIdentity
              && changeableIdentity.canChangeIdentity()) {
            changeableIdentity.addIdentityChangeListener(this);
          }

          added[0] = true;
          return new NewEntryValue(1, capturedSecondaryRid);
        }

        var oldValue = value.counter;
        value.counter = Math.min(oldValue + 1, counterMaxValue);
        value.secondaryRid = capturedSecondaryRid;
        added[0] = value.counter != oldValue;

        return value;
      });
    }

    assert size >= 0;
    if (added[0]) {
      size++;
      addEvent(primaryRid, secondaryRid);
    }

    return true;
  }

  @Override
  public boolean remove(RID rid) {
    if (rid == null) {
      return false;
    }

    assert assertIfNotActive();
    rid = refreshNonPersistentRid(rid);

    var newEntry = newEntries.get(rid);
    var newRidsRemoved = removeFromNewEntries(rid);
    if (newRidsRemoved) {
      assert size >= 1;
      size--;

      var secondaryRid =
          newEntry != null && newEntry.secondaryRid != null ? newEntry.secondaryRid : rid;
      removeEvent(rid, secondaryRid);
      return true;
    }

    var change = localChanges.getChange(rid);

    if (change == null) {
      if (rid.isPersistent()) {
        var absoluteValue = getAbsoluteValue(rid);

        if (absoluteValue > 0) {
          localChanges.putChange(rid, new AbsoluteChange(absoluteValue - 1));
          localChangesModificationsCount++;

          assert size >= absoluteValue;

          size--;
          removeEvent(rid, rid);

          return true;
        }

        //we mark it as dirty to trigger CME in cases of concurrent modification of underlying tree.
        setDirtyNoChanged();
        return false;
      }

      return false;
    }

    if (change.decrement()) {
      assert size >= change.getValue();
      size--;

      var secondaryRid = change.getSecondaryRid();
      removeEvent(rid, secondaryRid != null ? secondaryRid : rid);
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

    if (change == null) {
      change = new AbsoluteChange(getAbsoluteValue(rid));
    }

    return change.getValue() > 0;
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

  protected boolean removeFromNewEntries(final RID rid) {
    var entryValue = newEntries.get(rid);
    if (entryValue == null) {
      return false;
    } else {
      if (entryValue.counter == 1) {
        newEntries.remove(rid);

        if (rid instanceof ChangeableIdentity changeableIdentity) {
          changeableIdentity.removeIdentityChangeListener(this);
        }

        newModificationsCount++;
        return true;
      }

      newModificationsCount++;
      entryValue.counter--;
      return true;
    }
  }

  @Override
  public Stream<RawPair<RID, Change>> getChanges() {
    assert assertIfNotActive();

    return Streams.mergeSortedSpliterators(
        newEntries.entrySet().stream().map(
            pair -> {
              var change = new AbsoluteChange(pair.getValue().counter,
                  pair.getValue().secondaryRid);
              return new RawPair<RID, Change>(pair.getKey(), change);
            }
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

  protected void removeEvent(RID removed, RID secondaryRid) {
    if (tracker.isEnabled()) {
      tracker.remove(removed, secondaryRid);
    } else {
      setDirty();
    }
  }

  @Override
  public void enableTracking(RecordElement parent) {
    assert assertIfNotActive();

    if (!tracker.isEnabled()) {
      tracker.enable();
    }
  }

  @Override
  public void disableTracking(RecordElement entity) {
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

  @Override
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
    var rid = (RecordIdInternal) source;
    var newEntryValue = newEntries.remove(rid);

    if (newEntryValue != null) {
      newEntriesIdentityMap.put(rid, newEntryValue);
    }
  }

  @Override
  public void onAfterIdentityChange(Object source) {
    var rid = (RecordIdInternal) source;

    var newEntryValue = newEntriesIdentityMap.remove(rid);
    if (newEntryValue != null) {
      newEntries.put(rid, newEntryValue);
    }
  }

  @Override
  public @Nonnull Iterator<RidPair> iterator() {
    assert assertIfNotActive();
    return new EnhancedIterator();
  }

  @Override
  public Stream<RidPair> stream() {
    assert assertIfNotActive();
    return StreamSupport.stream(spliterator(), false);
  }

  @Nullable
  protected abstract Spliterator<BTreeReadEntry<RID>> btreeSpliterator(
      AtomicOperation atomicOperation);

  @Override
  public Spliterator<RidPair> spliterator() {
    return new MergingSpliterator();
  }

  private final class MergingSpliterator implements Spliterator<RidPair> {

    @Nullable
    private Spliterator<Map.Entry<RID, NewEntryValue>> newEntriesSpliterator;
    @Nullable
    private Spliterator<RawPair<RID, Change>> localChangesSpliterator;
    @Nullable
    private Spliterator<BTreeReadEntry<RID>> btreeRecordsSpliterator;

    @Nullable
    private RidPair currentPair;
    private int currentCounter;

    @Nullable
    private RID btreeRid;
    private int btreeCounter;
    private int btreeSecondaryCollectionId;
    private long btreeSecondaryPosition;

    @Nullable
    private RID localRid;
    private int localCounter;
    @Nullable
    private RID localSecondaryRid;

    private long savedNewModificationsCount = newModificationsCount;
    private long savedLocalChangesModificationsCount = localChangesModificationsCount;

    MergingSpliterator() {
      initNewEntriesSpliterator();
      initLocalChangesSpliterator();
      initBTreeRecordsSpliterator();

      nextLocalEntree();
      nextBTreeEntree();

      moveToNextEntry();

    }

    private void moveToNextEntry() {
      assert currentCounter == 0;

      restoreLocalChangesSpliteratorAfterModification();

      do {
        if (localRid != null && btreeRid != null) {
          var result = localRid.compareTo(btreeRid);

          if (result < 0) {
            currentPair = buildLocalPair();
            currentCounter = localCounter;
            nextLocalEntree();
          } else if (result > 0) {
            currentPair = buildBTreePair();
            currentCounter = btreeCounter;

            nextBTreeEntree();
          } else {
            // local overrides btree
            currentPair = buildLocalPair();
            currentCounter = localCounter;

            nextLocalEntree();
            nextBTreeEntree();
          }
        } else if (localRid != null) {
          currentPair = buildLocalPair();
          currentCounter = localCounter;

          nextLocalEntree();
        } else if (btreeRid != null) {
          currentPair = buildBTreePair();
          currentCounter = btreeCounter;

          nextBTreeEntree();
        } else {
          currentPair = null;
          currentCounter = 0;
        }
      } while (currentPair != null && currentCounter == 0);
    }

    private RidPair buildLocalPair() {
      var secondary = localSecondaryRid != null ? localSecondaryRid : localRid;
      return new RidPair(localRid, secondary);
    }

    private RidPair buildBTreePair() {
      var secondaryRid = new com.jetbrains.youtrackdb.internal.core.id.RecordId(
          btreeSecondaryCollectionId, btreeSecondaryPosition);
      return new RidPair(btreeRid, secondaryRid);
    }

    private void restoreLocalChangesSpliteratorAfterModification() {
      if (localChangesModificationsCount != savedLocalChangesModificationsCount) {
        if (currentPair == null) {
          initLocalChangesSpliterator();
        } else {
          var tailSpliterator = localChanges.spliterator(currentPair.primaryRid());
          if (tailSpliterator.estimateSize() == 0) {
            localChangesSpliterator = null;
            localRid = null;
            localCounter = 0;
            localSecondaryRid = null;
          } else {
            localChangesSpliterator = tailSpliterator;
            nextLocalEntree();
          }
        }

        savedLocalChangesModificationsCount = localChangesModificationsCount;
      }
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
      btreeRecordsSpliterator = btreeSpliterator(atomicOperation);
    }

    public void removed(RID rid) {
      if (currentPair != null && rid.equals(currentPair.primaryRid()) && currentCounter > 0) {
        currentCounter--;
      }
    }

    @Override
    public boolean tryAdvance(Consumer<? super RidPair> action) {
      assert assertIfNotActive();
      if (currentCounter > 0) {
        acceptActionOnCurrentCounter(action);
        return true;
      }

      if (newEntriesSpliterator != null) {
        if (savedNewModificationsCount != newModificationsCount) {
          if (currentPair == null) {
            initNewEntriesSpliterator();
          } else {
            var tail = newEntries.tailMap(currentPair.primaryRid(), false);
            if (tail.isEmpty()) {
              newEntriesSpliterator = null;
            } else {
              newEntriesSpliterator = tail.entrySet().spliterator();
            }
          }

          savedNewModificationsCount = newModificationsCount;
        }

        if (newEntriesSpliterator != null && newEntriesSpliterator.tryAdvance(pair -> {
          var secondary = pair.getValue().secondaryRid;
          currentPair = new RidPair(pair.getKey(),
              secondary != null ? secondary : pair.getKey());
          currentCounter = pair.getValue().counter;
          assert currentCounter > 0;
        })) {
          acceptActionOnCurrentCounter(action);
          return true;
        } else {
          newEntriesSpliterator = null;
          currentPair = null;
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
        assert btreeRid == null && btreeCounter == 0;
        return;
      }

      if (!btreeRecordsSpliterator.tryAdvance(
          entry -> {
            btreeRid = entry.primaryRid();
            btreeCounter = entry.counter();
            btreeSecondaryCollectionId = entry.secondaryCollectionId();
            btreeSecondaryPosition = entry.secondaryPosition();
            assert btreeCounter > 0;
          })) {
        btreeRid = null;
        btreeCounter = 0;
        btreeSecondaryCollectionId = -1;
        btreeSecondaryPosition = -1;
        btreeRecordsSpliterator = null;
      }

      assert (btreeRid == null && btreeCounter == 0) || btreeCounter > 0;
    }

    private void nextLocalEntree() {
      if (localChangesSpliterator == null) {
        assert localRid == null && localCounter == 0;
        return;
      }

      if (!localChangesSpliterator.tryAdvance(ridChangeRawPair -> {
        localRid = ridChangeRawPair.first();
        var change = ridChangeRawPair.second();

        assert change instanceof AbsoluteChange;
        localCounter = change.getValue();
        localSecondaryRid = change.getSecondaryRid();
      })) {
        localRid = null;
        localCounter = 0;
        localSecondaryRid = null;
        localChangesSpliterator = null;
      }
    }

    private void acceptActionOnCurrentCounter(Consumer<? super RidPair> action) {
      action.accept(currentPair);
      currentCounter--;
    }

    @Nullable
    @Override
    public Spliterator<RidPair> trySplit() {
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
      return Spliterator.SORTED | Spliterator.NONNULL | Spliterator.ORDERED;
    }

    @Override
    @Nullable
    public Comparator<? super RidPair> getComparator() {
      return null;
    }
  }

  private final class EnhancedIterator implements Iterator<RidPair>, Sizeable,
      ResettableIterator<RidPair>, Resettable {

    private MergingSpliterator spliterator;
    private RidPair nextPair;
    private RidPair currentPair;

    EnhancedIterator() {
      spliterator = new MergingSpliterator();
      spliterator.tryAdvance(pair -> nextPair = pair);
    }

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
      spliterator = new MergingSpliterator();
    }

    @Override
    public boolean hasNext() {
      assert assertIfNotActive();

      return nextPair != null;
    }

    @Override
    public RidPair next() {
      assert assertIfNotActive();
      currentPair = nextPair;
      if (!spliterator.tryAdvance(pair -> nextPair = pair)) {
        nextPair = null;
      }

      return currentPair;
    }

    @Override
    public void remove() {
      if (currentPair == null) {
        throw new IllegalStateException("No current element to remove");
      }
      AbstractLinkBag.this.remove(currentPair.primaryRid());
      spliterator.removed(currentPair.primaryRid());
      currentPair = null;
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
