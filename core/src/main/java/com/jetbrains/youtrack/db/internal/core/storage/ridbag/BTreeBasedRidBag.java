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

import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrack.db.internal.common.serialization.types.LongSerializer;
import com.jetbrains.youtrack.db.internal.common.types.ModifiableInteger;
import com.jetbrains.youtrack.db.internal.common.util.Resettable;
import com.jetbrains.youtrack.db.internal.common.util.Sizeable;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.MultiValueChangeEvent;
import com.jetbrains.youtrack.db.internal.core.db.record.MultiValueChangeTimeLine;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordElement;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.RidBagDelegate;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.impl.SimpleMultiValueTracker;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.binary.impl.LinkSerializer;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.AbstractPaginatedStorage;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.RecordSerializationContext;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.RidBagDeleteSerializationOperation;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.RidBagUpdateSerializationOperation;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.ridbagbtree.EdgeBTree;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.ridbagbtree.RidBagBucketPointer;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransactionAbstract;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransactionOptimistic;
import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NavigableMap;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentSkipListMap;
import javax.annotation.Nonnull;

/**
 * Persistent Set<Identifiable> implementation that uses the SBTree to handle entries in persistent
 * way.
 */
public class BTreeBasedRidBag implements RidBagDelegate {
  private final BTreeCollectionManager collectionManager;

  @Nonnull
  private final DatabaseSessionInternal session;

  private final ConcurrentSkipListMap<RID, Change> changes =
      new ConcurrentSkipListMap<>();

  /**
   * Entries with not valid id.
   */
  private final IdentityHashMap<RID, ModifiableInteger> newEntries =
      new IdentityHashMap<>();

  private BonsaiCollectionPointer collectionPointer;
  private int size;

  private final SimpleMultiValueTracker<RID, RID> tracker =
      new SimpleMultiValueTracker<>(this);

  private RecordElement owner;
  private boolean dirty;
  private boolean transactionDirty = false;

  public BTreeBasedRidBag(BonsaiCollectionPointer pointer, Map<RID, Change> changes,
      @Nonnull DatabaseSessionInternal session) {
    this.collectionPointer = pointer;
    this.session = session;
    this.changes.putAll(changes);
    this.size = -1;
    this.collectionManager = session.getBTreeCollectionManager();
  }

  public BTreeBasedRidBag(@Nonnull DatabaseSessionInternal session) {
    this.session = session;
    collectionPointer = null;
    this.collectionManager = session.getBTreeCollectionManager();
  }

  @Override
  public void setSize(int size) {
    this.size = size;
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
  public @Nonnull Iterator<RID> iterator() {
    return new RIDBagIterator(
        new IdentityHashMap<>(newEntries),
        changes,
        collectionPointer != null ? new SBTreeMapEntryIterator(1000) : null);
  }

  public void mergeChanges(BTreeBasedRidBag treeRidBag) {
    for (var entry : treeRidBag.newEntries.entrySet()) {
      mergeDiffEntry(refreshNonPersistentRid(entry.getKey()), entry.getValue().getValue());
    }

    for (var entry : treeRidBag.changes.entrySet()) {
      final var rec = refreshNonPersistentRid(entry.getKey());
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
  public void addAll(Collection<RID> values) {
    for (var identifiable : values) {
      add(identifiable);
    }
  }

  @Override
  public boolean addInternal(RID e) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void add(RID rid) {
    if (rid == null) {
      throw new IllegalArgumentException("Impossible to add a null identifiable in a ridbag");
    }

    rid = refreshNonPersistentRid(rid);
    if (((RecordId) rid.getIdentity()).isValid()) {
      var counter = changes.get(rid);
      if (counter == null) {
        changes.put(rid, new DiffChange(1));
      } else {
        if (counter.isUndefined()) {
          counter = getAbsoluteValue(rid);
          changes.put(rid, counter);
        }
        counter.increment();
      }
    } else {
      final var counter = newEntries.get(rid);
      if (counter == null) {
        newEntries.put(rid, new ModifiableInteger(1));
      } else {
        counter.increment();
      }
    }

    if (size >= 0) {
      size++;
    }

    addEvent(rid, rid);
  }

  @Override
  public void remove(RID rid) {
    rid = refreshNonPersistentRid(rid);
    if (removeFromNewEntries(rid)) {
      if (size >= 0) {
        size--;
      }
    } else {
      final var counter = changes.get(rid);
      if (counter == null) {
        // Not persistent keys can only be in changes or newEntries
        if (rid.getIdentity().isPersistent()) {
          changes.put(rid, new DiffChange(-1));
          size = -1;
        } else
        // Return immediately to prevent firing of event
        {
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
    rid = refreshNonPersistentRid(rid);
    if (newEntries.containsKey(rid)) {
      return true;
    }

    var counter = changes.get(rid);

    if (counter != null) {
      var absoluteValue = getAbsoluteValue(rid);

      if (counter.isUndefined()) {
        changes.put(rid, absoluteValue);
      }

      counter = absoluteValue;
    } else {
      counter = getAbsoluteValue(rid);
    }

    return counter.applyTo(0) > 0;
  }

  @Override
  public int size() {
    if (size >= 0) {
      return size;
    } else {
      return updateSize();
    }
  }

  @Override
  public String toString() {
    if (size >= 0) {
      return "[size=" + size + "]";
    }

    return "[...]";
  }

  @Override
  public boolean isEmpty() {
    return size() == 0;
  }

  @Override
  public Class<?> getGenericClass() {
    return Identifiable.class;
  }

  @Override
  public Object returnOriginalState(
      DatabaseSessionInternal session,
      List<MultiValueChangeEvent<RID, RID>> multiValueChangeEvents) {
    final var reverted = new BTreeBasedRidBag(this.session);
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

  @Override
  public int getSerializedSize() {
    var result = 2 * LongSerializer.LONG_SIZE + 3 * IntegerSerializer.INT_SIZE;
    if (session.isRemote()
        || RecordSerializationContext.getContext() == null) {
      result += getChangesSerializedSize();
    }
    return result;
  }

  private void rearrangeChanges() {
    for (var change : this.changes.entrySet()) {
      Identifiable key = change.getKey();
      if (session.getTransaction().isActive()) {
        if (!key.getIdentity().isPersistent()) {
          var record = session.getTransaction().getRecord(key.getIdentity());
          if (record != null && record != FrontendTransactionAbstract.DELETED_RECORD) {
            changes.remove(key);
            changes.put(record.getIdentity(), change.getValue());
          }
        }
      }
    }
  }

  public void handleContextSBTree(
      RecordSerializationContext context, BonsaiCollectionPointer pointer) {
    rearrangeChanges();
    this.collectionPointer = pointer;
    context.push(new RidBagUpdateSerializationOperation(changes, collectionPointer, session));
  }

  @Override
  public int serialize(@Nonnull DatabaseSessionInternal session, byte[] stream, int offset,
      UUID ownerUuid) {
    applyNewEntries();

    final RecordSerializationContext context;

    var tx = session.getTransaction();
    if (!(tx instanceof FrontendTransactionOptimistic)) {
      throw new DatabaseException(session.getDatabaseName(),
          "Changes are not supported outside of transactions");
    }

    var remoteMode = session.isRemote();
    if (remoteMode) {
      context = null;
    } else {
      context = RecordSerializationContext.getContext();
    }

    // make sure that we really save underlying record.
    if (collectionPointer == null) {
      if (context != null) {
        final var clusterId = getHighLevelDocClusterId();
        assert clusterId > -1;
        try {
          final var atomicOperationsManager =
              ((AbstractPaginatedStorage) session.getStorage())
                  .getAtomicOperationsManager();
          final var atomicOperation = atomicOperationsManager.getCurrentOperation();
          assert atomicOperation != null;
          collectionPointer =
              session
                  .getBTreeCollectionManager()
                  .createSBTree(clusterId, atomicOperation, ownerUuid, session);
        } catch (IOException e) {
          throw BaseException.wrapException(
              new DatabaseException(session.getDatabaseName(), "Error during ridbag creation"),
              e, session);
        }
      }
    }

    BonsaiCollectionPointer collectionPointer;
    collectionPointer = Objects.requireNonNullElse(this.collectionPointer,
        BonsaiCollectionPointer.INVALID);

    LongSerializer.serializeLiteral(collectionPointer.getFileId(), stream, offset);
    offset += LongSerializer.LONG_SIZE;

    var rootPointer = collectionPointer.getRootPointer();
    LongSerializer.serializeLiteral(rootPointer.getPageIndex(), stream, offset);
    offset += LongSerializer.LONG_SIZE;

    IntegerSerializer.serializeLiteral(rootPointer.getPageOffset(), stream, offset);
    offset += IntegerSerializer.INT_SIZE;

    // Keep this section for binary compatibility with versions older then 1.7.5
    IntegerSerializer.serializeLiteral(size, stream, offset);
    offset += IntegerSerializer.INT_SIZE;

    if (context == null) {
      ChangeSerializationHelper.serializeChanges(session, changes, stream, offset);
    } else {
      handleContextSBTree(context, collectionPointer);
      // 0-length serialized list of changes
      IntegerSerializer.serializeLiteral(0, stream, offset);
      offset += IntegerSerializer.INT_SIZE;
    }

    return offset;
  }

  public void applyNewEntries() {
    for (var entry : newEntries.entrySet()) {
      var rid = entry.getKey();
      assert rid instanceof DBRecord;
      var c = changes.get(rid);

      final var delta = entry.getValue().intValue();
      if (c == null) {
        changes.put(rid, new DiffChange(delta));
      } else {
        c.applyDiff(delta);
      }
    }
    newEntries.clear();
  }

  public void clearChanges() {
    changes.clear();
  }

  @Override
  public void requestDelete() {
    final var context = RecordSerializationContext.getContext();
    if (context != null && collectionPointer != null) {
      context.push(new RidBagDeleteSerializationOperation(this));
    }
  }

  public void confirmDelete() {
    collectionPointer = null;
    changes.clear();
    newEntries.clear();
    size = 0;
  }

  @Override
  public int deserialize(@Nonnull DatabaseSessionInternal session, byte[] stream, int offset) {
    final var fileId = LongSerializer.deserializeLiteral(stream, offset);
    offset += LongSerializer.LONG_SIZE;

    final var pageIndex = LongSerializer.deserializeLiteral(stream, offset);
    offset += LongSerializer.LONG_SIZE;

    final var pageOffset = IntegerSerializer.deserializeLiteral(stream, offset);
    offset += IntegerSerializer.INT_SIZE;

    // Cached bag size. Not used after 1.7.5
    offset += IntegerSerializer.INT_SIZE;

    if (fileId == -1) {
      collectionPointer = null;
    } else {
      collectionPointer =
          new BonsaiCollectionPointer(fileId, new RidBagBucketPointer(pageIndex, pageOffset));
    }

    this.size = -1;

    changes.putAll(ChangeSerializationHelper.deserializeChanges(session, stream, offset));

    offset +=
        IntegerSerializer.INT_SIZE + (LinkSerializer.RID_SIZE + Change.SIZE) * changes.size();

    return offset;
  }

  public BonsaiCollectionPointer getCollectionPointer() {
    return collectionPointer;
  }

  public void setCollectionPointer(BonsaiCollectionPointer collectionPointer) {
    this.collectionPointer = collectionPointer;
  }

  private EdgeBTree<RID, Integer> loadTree() {
    if (collectionPointer == null) {
      return null;
    }

    return collectionManager.loadSBTree(collectionPointer);
  }

  private void releaseTree() {
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

  private AbsoluteChange getAbsoluteValue(RID rid) {
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

      return new AbsoluteChange(change == null ? oldValue : change.applyTo(oldValue));
    } finally {
      releaseTree();
    }
  }

  /**
   * Recalculates real bag size.
   *
   * @return real size
   */
  private int updateSize() {
    var size = 0;
    if (collectionPointer != null) {
      final var tree = loadTree();
      if (tree == null) {
        throw new IllegalStateException(
            "RidBag is not properly initialized, can not load tree implementation");
      }

      try {
        size = tree.getRealBagSize(changes);
      } finally {
        releaseTree();
      }
    } else {
      for (var change : changes.values()) {
        size += change.applyTo(0);
      }
    }

    for (var diff : newEntries.values()) {
      size += diff.getValue();
    }

    this.size = size;
    return size;
  }

  private int getChangesSerializedSize() {
    Set<Identifiable> changedIds = new HashSet<>(changes.keySet());
    changedIds.addAll(newEntries.keySet());
    return ChangeSerializationHelper.getChangesSerializedSize(changedIds.size());
  }

  private int getHighLevelDocClusterId() {
    var owner = this.owner;
    while (owner != null && owner.getOwner() != null) {
      owner = owner.getOwner();
    }

    if (owner != null) {
      return ((Identifiable) owner).getIdentity().getClusterId();
    }

    return -1;
  }

  /**
   * Removes entry with given key from {@link #newEntries}.
   *
   * @param rid key to remove
   * @return true if entry have been removed
   */
  private boolean removeFromNewEntries(final RID rid) {
    var counter = newEntries.get(rid);
    if (counter == null) {
      return false;
    } else {
      if (counter.getValue() == 1) {
        newEntries.remove(rid);
      } else {
        counter.decrement();
      }
      return true;
    }
  }

  private Map.Entry<RID, Integer> nextChangedNotRemovedSBTreeEntry(
      Iterator<Map.Entry<RID, Integer>> iterator) {
    while (iterator.hasNext()) {
      final var entry = iterator.next();
      final var change = changes.get(entry.getKey());
      if (change == null) {
        return entry;
      }

      final var newValue = change.applyTo(entry.getValue());

      if (newValue > 0) {
        return new IdentifiableIntegerEntry(entry, newValue);
      }
    }

    return null;
  }

  @Override
  public NavigableMap<RID, Change> getChanges() {
    applyNewEntries();
    return changes;
  }

  private void addEvent(RID key, RID rid) {
    if (tracker.isEnabled()) {
      tracker.addNoDirty(key, rid);
    } else {
      setDirtyNoChanged();
    }
  }

  private void removeEvent(RID removed) {
    if (tracker.isEnabled()) {
      tracker.removeNoDirty(removed, removed);
    } else {
      setDirtyNoChanged();
    }
  }

  public void enableTracking(RecordElement parent) {
    if (!tracker.isEnabled()) {
      tracker.enable();
    }
  }

  public void disableTracking(RecordElement entity) {
    if (tracker.isEnabled()) {
      this.tracker.disable();
      this.dirty = false;
    }
  }

  @Override
  public void transactionClear() {
    tracker.transactionClear();
    this.transactionDirty = false;
  }

  @Override
  public boolean isModified() {
    return dirty;
  }

  @Override
  public boolean isTransactionModified() {
    return transactionDirty;
  }

  @Override
  public MultiValueChangeTimeLine<Object, Object> getTimeLine() {
    return tracker.getTimeLine();
  }

  @Override
  public void setDirty() {
    this.dirty = true;
    this.transactionDirty = true;

    if (owner != null) {
      owner.setDirty();
    }
  }

  public void setTransactionModified(boolean transactionDirty) {
    this.transactionDirty = transactionDirty;
  }

  @Override
  public void setDirtyNoChanged() {
    if (owner != null) {
      owner.setDirtyNoChanged();
    }
    this.dirty = true;
    this.transactionDirty = true;
  }

  @Override
  public SimpleMultiValueTracker<RID, RID> getTracker() {
    return tracker;
  }

  @Override
  public void setTracker(SimpleMultiValueTracker<RID, RID> tracker) {
    this.tracker.sourceFrom(tracker);
  }

  @Override
  public MultiValueChangeTimeLine<RID, RID> getTransactionTimeLine() {
    return this.tracker.getTransactionTimeLine();
  }

  private RID refreshNonPersistentRid(RID identifiable) {
    if (!identifiable.isPersistent()) {
      identifiable = session.refreshRid(identifiable);
    }
    return identifiable;
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
    private Iterator<Map.Entry<RID, ModifiableInteger>> newEntryIterator;
    private Iterator<Map.Entry<RID, Change>> changedValuesIterator;
    private Map.Entry<RID, Change> nextChange;
    private Map.Entry<RID, Integer> nextSBTreeEntry;
    private RID currentValue;
    private int currentFinalCounter;
    private int currentCounter;
    private boolean currentRemoved;

    private RIDBagIterator(
        IdentityHashMap<RID, ModifiableInteger> newEntries,
        NavigableMap<RID, Change> changedValues,
        SBTreeMapEntryIterator sbTreeIterator) {
      newEntryIterator = newEntries.entrySet().iterator();
      this.changedValues = changedValues;

      this.changedValuesIterator = changedValues.entrySet().iterator();
      this.sbTreeIterator = sbTreeIterator;

      nextChange = nextChangedNotRemovedEntry(changedValuesIterator);

      if (sbTreeIterator != null) {
        nextSBTreeEntry = nextChangedNotRemovedSBTreeEntry(sbTreeIterator);
      }
    }

    @Override
    public boolean hasNext() {
      return newEntryIterator.hasNext()
          || nextChange != null
          || nextSBTreeEntry != null
          || (currentValue != null && currentCounter < currentFinalCounter);
    }

    @Override
    public RID next() {
      currentRemoved = false;
      if (currentCounter < currentFinalCounter) {
        currentCounter++;
        return currentValue;
      }

      if (newEntryIterator.hasNext()) {
        var entry = newEntryIterator.next();
        currentValue = entry.getKey();
        currentFinalCounter = entry.getValue().intValue();
        currentCounter = 1;
        return currentValue;
      }

      if (nextChange != null && nextSBTreeEntry != null) {
        if (nextChange.getKey().compareTo(nextSBTreeEntry.getKey()) < 0) {
          currentValue = nextChange.getKey();
          currentFinalCounter = nextChange.getValue().applyTo(0);
          currentCounter = 1;

          nextChange = nextChangedNotRemovedEntry(changedValuesIterator);
        } else {
          currentValue = nextSBTreeEntry.getKey();
          currentFinalCounter = nextSBTreeEntry.getValue();
          currentCounter = 1;

          nextSBTreeEntry = nextChangedNotRemovedSBTreeEntry(sbTreeIterator);
          if (nextChange != null && nextChange.getKey().equals(currentValue)) {
            nextChange = nextChangedNotRemovedEntry(changedValuesIterator);
          }
        }
      } else if (nextChange != null) {
        currentValue = nextChange.getKey();
        currentFinalCounter = nextChange.getValue().applyTo(0);
        currentCounter = 1;

        nextChange = nextChangedNotRemovedEntry(changedValuesIterator);
      } else if (nextSBTreeEntry != null) {
        currentValue = nextSBTreeEntry.getKey();
        currentFinalCounter = nextSBTreeEntry.getValue();
        currentCounter = 1;

        nextSBTreeEntry = nextChangedNotRemovedSBTreeEntry(sbTreeIterator);
      } else {
        throw new NoSuchElementException();
      }

      return currentValue;
    }

    @Override
    public void remove() {
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
      newEntryIterator = newEntries.entrySet().iterator();

      this.changedValuesIterator = changedValues.entrySet().iterator();
      if (sbTreeIterator != null) {
        this.sbTreeIterator.reset();
      }

      nextChange = nextChangedNotRemovedEntry(changedValuesIterator);

      if (sbTreeIterator != null) {
        nextSBTreeEntry = nextChangedNotRemovedSBTreeEntry(sbTreeIterator);
      }
    }

    @Override
    public int size() {
      return BTreeBasedRidBag.this.size();
    }

    private static Map.Entry<RID, Change> nextChangedNotRemovedEntry(
        Iterator<Map.Entry<RID, Change>> iterator) {
      Map.Entry<RID, Change> entry;

      while (iterator.hasNext()) {
        entry = iterator.next();
        // TODO workaround
        if (entry.getValue().applyTo(0) > 0) {
          return entry;
        }
      }

      return null;
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
