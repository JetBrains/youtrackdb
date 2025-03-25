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
import com.jetbrains.youtrack.db.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrack.db.internal.common.serialization.types.LongSerializer;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.MultiValueChangeEvent;
import com.jetbrains.youtrack.db.internal.core.db.record.MultiValueChangeTimeLine;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordElement;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.RidBagDelegate;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.impl.SimpleMultiValueTracker;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NavigableMap;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentSkipListMap;
import javax.annotation.Nonnull;

public class RemoteTreeRidBag implements RidBagDelegate {

  /**
   * Entries with not valid id.
   */
  private int size;

  private final SimpleMultiValueTracker<RID, RID> tracker =
      new SimpleMultiValueTracker<>(this);

  private transient RecordElement owner;
  private boolean dirty;
  private boolean transactionDirty = false;
  private RecordId ownerRecord;
  private String fieldName;
  private final BonsaiCollectionPointer collectionPointer;
  @Nonnull
  private final DatabaseSessionInternal session;

  public RemoteTreeRidBag(BonsaiCollectionPointer pointer,
      @Nonnull DatabaseSessionInternal session) {
    this.session = session;
    this.size = -1;
    this.collectionPointer = pointer;
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
    var set = loadElements(session);
    return new RemovableIterator(set.iterator());
  }

  private List<RID> loadElements(DatabaseSessionInternal session) {
    List<RID> list;
    try (var result =
        session.query("select list(@this.field(?)) as entities from ?", fieldName,
            ownerRecord)) {
      if (result.hasNext()) {
        list = (result.next().getProperty("entities"));
      } else {
        list = Collections.emptyList();
      }
    }

    //as transaction synchronized with elements already we do not need to postprocess results
    return list;
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
    if (size >= 0) {
      size++;
    }

    addEvent(rid, rid);
  }

  @Override
  public void remove(RID rid) {
    rid = refreshNonPersistentRid(rid);

    size--;
    removeEvent(rid);
  }

  @Override
  public boolean contains(RID rid) {
    rid = refreshNonPersistentRid(rid);
    return loadElements(session).contains(rid);
  }

  @Override
  public int size() {
    return updateSize();
  }

  @Override
  public String toString() {
    if (size >= 0) {
      return "[size=" + size + "]";
    }

    return "[...]";
  }

  @Override
  public NavigableMap<RID, Change> getChanges() {
    return new ConcurrentSkipListMap<>();
  }

  @Override
  public boolean isEmpty() {
    return size() == 0;
  }

  @Override
  public Object returnOriginalState(
      DatabaseSessionInternal session,
      List<MultiValueChangeEvent<RID, RID>> multiValueChangeEvents) {
    final var reverted = new RemoteTreeRidBag(this.collectionPointer, session);
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
  public int getSerializedSize() {
    return 2 * LongSerializer.LONG_SIZE + 3 * IntegerSerializer.INT_SIZE;
  }

  @Override
  public int serialize(@Nonnull DatabaseSessionInternal session, byte[] stream, int offset,
      UUID ownerUuid) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void requestDelete() {
    throw new UnsupportedOperationException();
  }

  @Override
  public int deserialize(@Nonnull DatabaseSessionInternal session, byte[] stream, int offset) {
    throw new UnsupportedOperationException();
  }

  /**
   * Recalculates real bag size.
   *
   * @return real size
   */
  private int updateSize() {
    this.size = loadElements(session).size();
    return size;
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
  public MultiValueChangeTimeLine<RID, RID> getTimeLine() {
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

  public void setRecordAndField(RecordId id, String fieldName) {
    this.ownerRecord = id;
    this.fieldName = fieldName;
  }

  private RID refreshNonPersistentRid(RID identifiable) {
    if (!identifiable.isPersistent()) {
      identifiable = session.refreshRid(identifiable);
    }
    return identifiable;
  }

  public BonsaiCollectionPointer getCollectionPointer() {
    return collectionPointer;
  }


  private class RemovableIterator implements Iterator<RID> {

    private final Iterator<RID> iter;
    private RID next;
    private RID removeNext;

    public RemovableIterator(Iterator<RID> iterator) {
      this.iter = iterator;
    }

    @Override
    public boolean hasNext() {
      if (next != null) {
        return true;
      } else {
        if (iter.hasNext()) {
          next = iter.next();
          return true;
        } else {
          return false;
        }
      }
    }

    @Override
    public RID next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      var val = next;
      removeNext = next;
      next = null;
      return val;
    }

    @Override
    public void remove() {
      if (removeNext != null) {
        RemoteTreeRidBag.this.remove(removeNext);
        removeNext = null;
      } else {
        throw new IllegalStateException();
      }
    }
  }
}
