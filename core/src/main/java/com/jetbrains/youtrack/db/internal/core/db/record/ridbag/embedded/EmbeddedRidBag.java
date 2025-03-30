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
package com.jetbrains.youtrack.db.internal.core.db.record.ridbag.embedded;

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrack.db.internal.common.util.CommonConst;
import com.jetbrains.youtrack.db.internal.common.util.Resettable;
import com.jetbrains.youtrack.db.internal.common.util.Sizeable;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.MultiValueChangeEvent;
import com.jetbrains.youtrack.db.internal.core.db.record.MultiValueChangeTimeLine;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordElement;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.RidBagDelegate;
import com.jetbrains.youtrack.db.internal.core.record.impl.SimpleMultiValueTracker;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.binary.impl.LinkSerializer;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.Change;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.NavigableMap;
import java.util.NoSuchElementException;
import java.util.UUID;
import javax.annotation.Nonnull;

public class EmbeddedRidBag implements RidBagDelegate {

  private boolean contentWasChanged = false;

  private Object[] entries = CommonConst.EMPTY_OBJECT_ARRAY;
  private int entriesLength = 0;

  private int size = 0;

  private transient RecordElement owner;

  private boolean dirty = false;
  private boolean transactionDirty = false;

  private SimpleMultiValueTracker<RID, RID> tracker =
      new SimpleMultiValueTracker<>(this);

  @Nonnull
  private final DatabaseSessionInternal session;

  public EmbeddedRidBag(@Nonnull DatabaseSessionInternal session) {
    this.session = session;
  }

  @Override
  public void setSize(int size) {
    this.size = size;
  }


  public Object[] getEntries() {
    return entries;
  }


  @Override
  public RecordElement getOwner() {
    return owner;
  }

  @Override
  public boolean contains(RID rid) {
    if (rid == null) {
      return false;
    }

    rid = refreshNonPersistentRid(rid);

    for (var i = 0; i < entriesLength; i++) {
      if (rid.equals(entries[i])) {
        return true;
      }
    }

    return false;
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
    for (var value : values) {
      add(value);
    }
  }

  @Override
  public void add(RID rid) {
    if (rid == null) {
      throw new IllegalArgumentException("Impossible to add a null identifiable in a ridbag");
    }

    rid = refreshNonPersistentRid(rid);

    addEntry(rid);

    size++;
    contentWasChanged = true;

    addEvent(rid, rid);
  }

  public EmbeddedRidBag copy() {
    final var copy = new EmbeddedRidBag(session);
    copy.contentWasChanged = contentWasChanged;
    copy.entries = entries;
    copy.entriesLength = entriesLength;
    copy.size = size;
    copy.owner = owner;
    copy.tracker = this.tracker;
    return copy;
  }

  @Override
  public void remove(RID rid) {
    if (removeEntry(rid)) {
      size--;
      contentWasChanged = true;

      removeEvent(rid);
    }
  }

  @Override
  public boolean isEmpty() {
    return size == 0;
  }

  @Override
  public @Nonnull Iterator<RID> iterator() {
    return new EntriesIterator();
  }

  @Override
  public int size() {
    return size;
  }

  @Override
  public boolean isSizeable() {
    return true;
  }

  @Override
  public String toString() {
    if (size < 10) {
      final var sb = new StringBuilder(256);
      sb.append('[');
      for (final var it = this.iterator(); it.hasNext(); ) {
        try {
          var e = it.next();
          if (e != null) {
            if (sb.length() > 1) {
              sb.append(", ");
            }

            sb.append(e);
          }
        } catch (NoSuchElementException ignore) {
          // IGNORE THIS
        }
      }
      return sb.append(']').toString();

    } else {
      return "[size=" + size + "]";
    }
  }

  @Override
  public Object returnOriginalState(
      DatabaseSessionInternal session,
      List<MultiValueChangeEvent<RID, RID>> multiValueChangeEvents) {
    final var reverted = new EmbeddedRidBag(session);
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
    int size;

    size = IntegerSerializer.INT_SIZE;

    size += this.size * LinkSerializer.RID_SIZE;

    return size;
  }

  @Override
  public int serialize(@Nonnull DatabaseSessionInternal session, byte[] stream, int offset,
      UUID ownerUuid) {
    IntegerSerializer.serializeLiteral(size, stream, offset);
    offset += IntegerSerializer.INT_SIZE;

    final var totEntries = entries.length;
    for (var i = 0; i < totEntries; ++i) {
      final var entry = entries[i];
      if (entry instanceof RID link) {
        link = refreshNonPersistentRid(link);
        entries[i] = link;
        LinkSerializer.staticSerialize(link, stream, offset);
        offset += LinkSerializer.RID_SIZE;
      }
    }

    return offset;
  }

  @Override
  public int deserialize(@Nonnull DatabaseSessionInternal session, final byte[] stream,
      int offset) {
    this.size = IntegerSerializer.deserializeLiteral(stream, offset);
    var entriesSize = IntegerSerializer.deserializeLiteral(stream, offset);
    offset += IntegerSerializer.INT_SIZE;

    for (var i = 0; i < entriesSize; i++) {
      var rid = refreshNonPersistentRid(LinkSerializer.staticDeserialize(stream, offset));

      offset += LinkSerializer.RID_SIZE;
      addInternal(rid);
    }

    return offset;
  }

  @Override
  public void requestDelete() {
  }

  public boolean addInternal(final RID rid) {
    addEntry(rid);
    return true;
  }

  private void addEntry(final RID rid) {
    if (entries.length == entriesLength) {
      if (entriesLength == 0) {
        final var cfgValue =
            GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD.getValueAsInteger();
        entries = new Object[cfgValue > 0 ? Math.min(cfgValue, 40) : 40];
      } else {
        final var oldEntries = entries;
        entries = new Object[entries.length << 1];
        System.arraycopy(oldEntries, 0, entries, 0, oldEntries.length);
      }
    }
    entries[entriesLength] = rid;
    entriesLength++;
  }

  private boolean removeEntry(RID identifiable) {
    var i = 0;
    for (; i < entriesLength; i++) {
      final var entry = entries[i];
      if (entry.equals(identifiable)) {
        entries[i] = Tombstone.TOMBSTONE;
        break;
      }
    }

    return i < entriesLength;
  }

  @Override
  public NavigableMap<RID, Change> getChanges() {
    return null;
  }

  private void addEvent(final RID key, final RID rid) {
    if (tracker.isEnabled()) {
      tracker.add(key, rid);
    } else {
      setDirty();
    }
  }

  private void removeEvent(RID removed) {
    if (tracker.isEnabled()) {
      tracker.remove(removed, removed);
    } else {
      setDirty();
    }
  }

  public void enableTracking(final RecordElement parent) {
    if (!tracker.isEnabled()) {
      tracker.enable();
    }
  }

  public void disableTracking(final RecordElement entity) {
    if (tracker.isEnabled()) {
      tracker.disable();
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
    return tracker.getTransactionTimeLine();
  }

  private RID refreshNonPersistentRid(RID identifiable) {
    if (!identifiable.isPersistent()) {
      identifiable = session.refreshRid(identifiable);
    }
    return identifiable;
  }

  private enum Tombstone {
    TOMBSTONE
  }


  private final class EntriesIterator implements Iterator<RID>, Resettable, Sizeable {

    private int currentIndex = -1;
    private int nextIndex = -1;
    private boolean currentRemoved;

    private EntriesIterator() {
      reset();
    }

    @Override
    public boolean hasNext() {
      // we may remove items in ridbag during iteration so we need to be sure that pointed item is
      // not removed.
      if (nextIndex > -1) {
        if (entries[nextIndex] instanceof RID) {
          return true;
        }

        nextIndex = nextIndex();
      }

      return nextIndex > -1;
    }

    @Override
    public RID next() {
      currentRemoved = false;

      currentIndex = nextIndex;
      if (currentIndex == -1) {
        throw new NoSuchElementException();
      }

      var nextValue = entries[currentIndex];

      // we may remove items in ridbag during iteration so we need to be sure that pointed item is
      // not removed.
      if (!(nextValue instanceof RID)) {
        nextIndex = nextIndex();

        currentIndex = nextIndex;
        if (currentIndex == -1) {
          throw new NoSuchElementException();
        }

        nextValue = entries[currentIndex];
      }

      if (nextValue != null) {
        if (((RID) nextValue).getIdentity().isPersistent()) {
          entries[currentIndex] = ((RID) nextValue).getIdentity();
        }
      }

      nextIndex = nextIndex();

      assert nextValue != null;
      return (RID) nextValue;
    }

    @Override
    public void remove() {
      if (currentRemoved) {
        throw new IllegalStateException("Current entity has already been removed");
      }

      if (currentIndex == -1) {
        throw new IllegalStateException("Next method was not called for given iterator");
      }

      currentRemoved = true;

      final var nextValue = (RID) entries[currentIndex];
      entries[currentIndex] = Tombstone.TOMBSTONE;

      size--;
      contentWasChanged = true;
      removeEvent(nextValue);
    }

    @Override
    public void reset() {
      currentIndex = -1;
      nextIndex = -1;
      currentRemoved = false;

      nextIndex = nextIndex();
    }

    @Override
    public boolean isResetable() {
      return true;
    }

    @Override
    public int size() {
      return size;
    }

    @Override
    public boolean isSizeable() {
      return true;
    }

    private int nextIndex() {
      for (var i = currentIndex + 1; i < entriesLength; i++) {
        var entry = entries[i];
        if (entry instanceof RID) {
          return i;
        }
      }

      return -1;
    }
  }
}
