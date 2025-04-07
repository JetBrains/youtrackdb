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
package com.jetbrains.youtrack.db.internal.core.db.record;

import com.jetbrains.youtrack.db.api.exception.SchemaException;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.record.collection.links.LinkList;
import com.jetbrains.youtrack.db.internal.common.util.Sizeable;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.record.impl.SimpleMultiValueTracker;
import java.lang.ref.WeakReference;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.RandomAccess;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class EntityLinkListImpl extends AbstractList<Identifiable> implements
    Sizeable, LinkTrackedMultiValue<Integer>, LinkList, RandomAccess,
    TrackedCollection<Integer, Identifiable> {

  @Nullable
  protected RecordElement sourceRecord;

  private boolean dirty = false;
  private boolean transactionDirty = false;

  @Nonnull
  private final WeakReference<DatabaseSessionInternal> session;

  private final SimpleMultiValueTracker<Integer, Identifiable> tracker = new SimpleMultiValueTracker<>(
      this);

  @Nonnull
  private final ArrayList<RID> list;

  public EntityLinkListImpl(
      @Nonnull final RecordElement record,
      final Collection<Identifiable> iOrigin) {
    this(record);

    if (iOrigin != null && !iOrigin.isEmpty()) {
      addAll(iOrigin);
    }
  }

  public EntityLinkListImpl(@Nonnull final RecordElement sourceRecord) {
    this.list = new ArrayList<>();
    this.sourceRecord = sourceRecord;
    this.session = new WeakReference<>(sourceRecord.getSession());
  }

  public EntityLinkListImpl(@Nonnull final RecordElement sourceRecord, int size) {
    this.list = new ArrayList<>(size);
    this.sourceRecord = sourceRecord;
    this.session = new WeakReference<>(sourceRecord.getSession());
  }

  public EntityLinkListImpl(DatabaseSessionInternal session) {
    this.list = new ArrayList<>();
    tracker.enable();
    this.session = new WeakReference<>(session);
  }

  public EntityLinkListImpl(DatabaseSessionInternal session, int size) {
    this.list = new ArrayList<>(size);
    tracker.enable();
    this.session = new WeakReference<>(session);
  }


  @Nullable
  @Override
  public DatabaseSessionInternal getSession() {
    return this.session.get();
  }


  @Override
  public void setOwner(RecordElement newOwner) {
    LinkTrackedMultiValue.checkEntityAsOwner(newOwner);

    if (newOwner != null) {
      var owner = sourceRecord;
      if (owner != null && !owner.equals(newOwner)) {
        throw new IllegalStateException(
            "This list is already owned by data container "
                + owner
                + " if you want to use it in other data container create new list instance and copy"
                + " content of current one.");
      }

      this.sourceRecord = newOwner;
    } else {
      this.sourceRecord = null;
    }
  }

  @Override
  public RecordElement getOwner() {
    return sourceRecord;
  }

  @Override
  public boolean add(Identifiable element) {
    checkValue(element);
    var rid = convertToRid(element);
    final var result = list.add(rid);

    if (result) {
      addEvent(list.size() - 1, rid);
    }

    return result;
  }

  @Override
  public Identifiable get(int index) {
    return list.get(index);
  }

  @Override
  public int size() {
    return list.size();
  }

  @Override
  public boolean isSizeable() {
    return true;
  }

  public void addInternal(Identifiable element) {
    checkValue(element);
    var rid = convertToRid(element);

    list.add(rid);
    addOwner(element);
  }

  @Override
  public void add(int index, Identifiable element) {
    checkValue(element);

    var rid = convertToRid(element);
    list.add(index, rid);

    addEvent(index, rid);
  }

  @Override
  public Identifiable set(int index, Identifiable element) {
    checkValue(element);
    var rid = convertToRid(element);

    final var oldValue = list.set(index, rid);

    if (oldValue != null && !oldValue.equals(rid)) {
      updateEvent(index, oldValue, rid);
    }

    return oldValue;
  }


  @Override
  public Identifiable remove(int index) {
    final var oldValue = list.remove(index);

    removeEvent(index, oldValue);
    return oldValue;
  }

  private void addEvent(int index, RID added) {
    addOwner(added);

    if (tracker.isEnabled()) {
      tracker.add(index, added);
    } else {
      setDirty();
    }
  }

  private void updateEvent(int index, RID oldValue, RID newValue) {
    removeOwner(oldValue);
    addOwner(newValue);

    if (tracker.isEnabled()) {
      tracker.updated(index, newValue, oldValue);
    } else {
      setDirty();
    }
  }

  private void removeEvent(int index, RID removed) {
    removeOwner(removed);

    if (tracker.isEnabled()) {
      tracker.remove(index, removed);
    } else {
      setDirty();
    }
  }

  @Override
  public boolean remove(Object o) {
    if (!(o instanceof Identifiable identifiable)) {
      return false;
    }

    try {
      checkValue(identifiable);
    } catch (SchemaException | IllegalArgumentException e) {
      return false;
    }

    var rid = convertToRid(identifiable);

    final var index = indexOf(rid);
    if (index >= 0) {
      remove(index);
      return true;
    }

    return false;
  }

  @Override
  public void clear() {
    for (var i = this.size() - 1; i >= 0; i--) {
      final var origValue = list.get(i);
      removeEvent(i, origValue);
    }

    list.clear();
  }

  public void setDirty() {
    this.dirty = true;
    this.transactionDirty = true;

    var sourceRecord = this.sourceRecord;
    if (sourceRecord != null) {
      sourceRecord.setDirty();
    }
  }

  @Override
  public void setDirtyNoChanged() {
    var sourceRecord = this.sourceRecord;
    if (sourceRecord != null) {
      sourceRecord.setDirtyNoChanged();
    }
  }

  public List<Identifiable> returnOriginalState(
      DatabaseSessionInternal session,
      final List<MultiValueChangeEvent<Integer, Identifiable>> multiValueChangeEvents) {
    var reverted = new ArrayList<>(this);
    final var listIterator =
        multiValueChangeEvents.listIterator(multiValueChangeEvents.size());

    while (listIterator.hasPrevious()) {
      final var event = listIterator.previous();
      switch (event.getChangeType()) {
        case ADD:
          reverted.remove(event.getKey().intValue());
          break;
        case REMOVE:
          reverted.add(event.getKey(), event.getOldValue());
          break;
        case UPDATE:
          reverted.set(event.getKey(), event.getOldValue());
          break;
        default:
          throw new IllegalArgumentException("Invalid change type : " + event.getChangeType());
      }
    }

    return reverted;
  }

  public void enableTracking(RecordElement parent) {
    if (!tracker.isEnabled()) {
      tracker.enable();
      TrackedMultiValue.nestedEnabled(this.iterator(), this);
    }

    if (sourceRecord != parent) {
      this.sourceRecord = parent;
    }
  }

  public void disableTracking(RecordElement parent) {
    if (tracker.isEnabled()) {
      tracker.disable();
      TrackedMultiValue.nestedDisable(this.iterator(), this);
    }
    this.dirty = false;

    if (sourceRecord != parent) {
      this.sourceRecord = parent;
    }
  }

  @Override
  public void transactionClear() {
    tracker.transactionClear();
    TrackedMultiValue.nestedTransactionClear(this.iterator());
    this.transactionDirty = false;
  }

  @Override
  public boolean isModified() {
    return dirty || tracker.isEnabled() && tracker.isChanged();
  }

  @Override
  public boolean isTransactionModified() {
    return transactionDirty || tracker.isEnabled() && tracker.isTxChanged();
  }

  @Override
  public MultiValueChangeTimeLine<Integer, Identifiable> getTimeLine() {
    return tracker.getTimeLine();
  }

  public MultiValueChangeTimeLine<Integer, Identifiable> getTransactionTimeLine() {
    return tracker.getTransactionTimeLine();
  }

  @Override
  public int indexOf(Object o) {
    if (!(o instanceof Identifiable identifiable)) {
      return -1;
    }

    try {
      checkValue(identifiable);
    } catch (SchemaException | IllegalArgumentException e) {
      return -1;
    }

    var rid = convertToRid(identifiable);
    return list.indexOf(rid);
  }

  @Override
  public int lastIndexOf(Object o) {
    if (!(o instanceof Identifiable identifiable)) {
      return -1;
    }
    try {
      checkValue(identifiable);
    } catch (SchemaException | IllegalArgumentException e) {
      return -1;
    }

    var rid = convertToRid(identifiable);
    return list.lastIndexOf(rid);
  }

  @Override
  public boolean equals(Object o) {
    if (o == this) {
      return true;
    }
    if (!(o instanceof List)) {
      return false;
    }
    return list.equals(o);
  }

  @Override
  public int hashCode() {
    return list.hashCode();
  }

  @Override
  public boolean isEmpty() {
    return list.isEmpty();
  }

  @Override
  public boolean contains(Object o) {
    if (!(o instanceof Identifiable identifiable)) {
      return false;
    }
    try {
      checkValue(identifiable);
    } catch (SchemaException | IllegalArgumentException e) {
      return false;
    }

    var rid = convertToRid(identifiable);
    return list.contains(rid);
  }

  @Nonnull
  @Override
  public Object[] toArray() {
    return list.toArray();
  }

  @Nonnull
  @Override
  public <T1> T1[] toArray(@Nonnull T1[] a) {
    return list.toArray(a);
  }

  @Override
  public String toString() {
    return list.toString();
  }

  @Override
  public void sort(@Nullable Comparator<? super Identifiable> c) {
    list.sort(c);
  }

  @Override
  public void addFirst(Identifiable t) {
    checkValue(t);
    var rid = convertToRid(t);

    list.addFirst(rid);
    addEvent(0, rid);
  }

  @Override
  public void addLast(Identifiable t) {
    checkValue(t);
    var rid = convertToRid(t);
    list.addLast(rid);
    addEvent(list.size() - 1, rid);
  }

  @Override
  public Identifiable getFirst() {
    return list.getFirst();
  }

  @Override
  public Identifiable getLast() {
    return list.getLast();
  }

  @Override
  public Identifiable removeFirst() {
    var removed = list.removeFirst();
    removeEvent(0, removed);
    return removed;
  }

  @Override
  public Identifiable removeLast() {
    var removed = list.removeLast();
    removeEvent(list.size(), removed);
    return removed;
  }

  @Override
  public <T1> T1[] toArray(@Nonnull IntFunction<T1[]> generator) {
    return list.toArray(generator);
  }

  @Override
  public void forEach(Consumer<? super Identifiable> action) {
    list.forEach(action);
  }
}
