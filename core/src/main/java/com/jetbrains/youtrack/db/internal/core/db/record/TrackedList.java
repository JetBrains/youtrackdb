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

import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.record.impl.SimpleMultiValueTracker;
import java.io.Serializable;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Implementation of ArrayList bound to a source Record object to keep track of changes for literal
 * types. This avoids to call the makeDirty() by hand when the list is changed.
 */
public class TrackedList<T> extends AbstractList<T>
    implements RecordElement, TrackedMultiValue<Integer, T>, Serializable {

  @Nullable
  protected RecordElement sourceRecord;
  protected Class<?> genericClass;

  private final boolean embeddedCollection;
  private boolean dirty = false;
  private boolean transactionDirty = false;

  private final SimpleMultiValueTracker<Integer, T> tracker = new SimpleMultiValueTracker<>(this);

  @Nonnull
  private final ArrayList<T> list;

  public TrackedList(
      @Nonnull final RecordElement iRecord,
      final Collection<? extends T> iOrigin,
      final Class<?> iGenericClass) {
    this(iRecord);
    genericClass = iGenericClass;

    if (iOrigin != null && !iOrigin.isEmpty()) {
      addAll(iOrigin);
    }
  }

  public TrackedList(@Nonnull final RecordElement iSourceRecord) {
    this.list = new ArrayList<>();
    this.sourceRecord = iSourceRecord;
    embeddedCollection = this.getClass().equals(TrackedList.class);
  }

  public TrackedList(@Nonnull final RecordElement iSourceRecord, int size) {
    this.list = new ArrayList<>(size);
    this.sourceRecord = iSourceRecord;
    embeddedCollection = this.getClass().equals(TrackedList.class);
  }

  public TrackedList() {
    this.list = new ArrayList<>();
    embeddedCollection = this.getClass().equals(TrackedList.class);
    tracker.enable();
  }

  public TrackedList(int size) {
    this.list = new ArrayList<>(size);
    embeddedCollection = this.getClass().equals(TrackedList.class);
    tracker.enable();
  }

  @Override
  public void setOwner(RecordElement newOwner) {
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
  public boolean isEmbeddedContainer() {
    return embeddedCollection;
  }

  @Override
  public RecordElement getOwner() {
    return sourceRecord;
  }

  @Override
  public boolean add(T element) {
    checkValue(element);
    final var result = list.add(element);

    if (result) {
      addEvent(list.size() - 1, element);
    }

    return result;
  }

  @Override
  public T get(int index) {
    return list.get(index);
  }

  @Override
  public int size() {
    return list.size();
  }

  public boolean addInternal(T element) {
    checkValue(element);
    final var result = list.add(element);

    if (result) {
      addOwner(element);
    }

    return result;
  }

  @Override
  public void add(int index, T element) {
    checkValue(element);
    list.add(index, element);
    addEvent(index, element);
  }

  public void setInternal(int index, T element) {
    checkValue(element);
    final var oldValue = list.set(index, element);

    if (oldValue != null && !oldValue.equals(element)) {
      if (oldValue instanceof EntityImpl) {
        ((EntityImpl) oldValue).removeOwner(this);
      }

      addOwner(element);
    }
  }

  @Override
  public T set(int index, T element) {
    checkValue(element);
    final var oldValue = list.set(index, element);

    if (oldValue != null && !oldValue.equals(element)) {
      updateEvent(index, oldValue, element);
    }

    return oldValue;
  }


  @Override
  public T remove(int index) {
    final var oldValue = list.remove(index);
    removeEvent(index, oldValue);
    return oldValue;
  }

  private void addEvent(int index, T added) {
    addOwner(added);

    if (tracker.isEnabled()) {
      tracker.add(index, added);
    } else {
      setDirty();
    }
  }

  private void updateEvent(int index, T oldValue, T newValue) {
    removeOwner(oldValue);
    addOwner(newValue);

    if (tracker.isEnabled()) {
      tracker.updated(index, newValue, oldValue);
    } else {
      setDirty();
    }
  }

  private void removeEvent(int index, T removed) {
    removeOwner(removed);

    if (tracker.isEnabled()) {
      tracker.remove(index, removed);
    } else {
      setDirty();
    }
  }

  @Override
  public boolean remove(Object o) {
    final var index = indexOf(o);
    if (index >= 0) {
      remove(index);
      return true;
    }
    return false;
  }

  @Override
  public boolean removeAll(Collection<?> c) {
    var removed = false;
    for (var o : c) {
      removed = removed | remove(o);
    }

    return removed;
  }

  @Override
  public void clear() {
    for (var i = this.size() - 1; i >= 0; i--) {
      final var origValue = this.get(i);
      removeEvent(i, origValue);
    }
    list.clear();
  }

  public void setDirty() {
    this.dirty = true;
    this.transactionDirty = true;

    var sourceRecord = this.sourceRecord;
    if (sourceRecord != null) {
      if (!(sourceRecord instanceof RecordAbstract)
          || !((RecordAbstract) sourceRecord).isDirty()) {
        sourceRecord.setDirty();
      }
    }
  }

  @Override
  public void setDirtyNoChanged() {
    var sourceRecord = this.sourceRecord;
    if (sourceRecord != null) {
      sourceRecord.setDirtyNoChanged();
    }
  }

  public List<T> returnOriginalState(
      DatabaseSessionInternal session,
      final List<MultiValueChangeEvent<Integer, T>> multiValueChangeEvents) {
    final List<T> reverted = new ArrayList<T>(this);

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

  public Class<?> getGenericClass() {
    return genericClass;
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
  public MultiValueChangeTimeLine<Object, Object> getTimeLine() {
    return tracker.getTimeLine();
  }

  public MultiValueChangeTimeLine<Integer, T> getTransactionTimeLine() {
    return tracker.getTransactionTimeLine();
  }

  @Override
  public int indexOf(Object o) {
    return list.indexOf(o);
  }

  @Override
  public int lastIndexOf(Object o) {
    return list.lastIndexOf(o);
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
    return list.contains(o);
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
  public boolean containsAll(@Nonnull Collection<?> c) {
    return list.containsAll(c);
  }

  @Override
  public String toString() {
    return list.toString();
  }

  @Override
  public void sort(@Nullable Comparator<? super T> c) {
    list.sort(c);
  }

  @Nonnull
  @Override
  public Spliterator<T> spliterator() {
    return list.spliterator();
  }

  @Override
  public void addFirst(T t) {
    checkValue(t);
    list.addFirst(t);
    addEvent(0, t);
  }

  @Override
  public void addLast(T t) {
    checkValue(t);
    list.addLast(t);
    addEvent(list.size() - 1, t);
  }

  @Override
  public T getFirst() {
    return list.getFirst();
  }

  @Override
  public T getLast() {
    return list.getLast();
  }

  @Override
  public T removeFirst() {
    var removed = list.removeFirst();
    removeEvent(0, removed);
    return removed;
  }

  @Override
  public T removeLast() {
    var removed = list.removeLast();
    removeEvent(list.size(), removed);
    return removed;
  }

  @Override
  public List<T> reversed() {
    return list.reversed();
  }

  @Override
  public <T1> T1[] toArray(@Nonnull IntFunction<T1[]> generator) {
    return list.toArray(generator);
  }

  @Nonnull
  @Override
  public Stream<T> stream() {
    return list.stream();
  }

  @Nonnull
  @Override
  public Stream<T> parallelStream() {
    return list.parallelStream();
  }

  @Override
  public void forEach(Consumer<? super T> action) {
    list.forEach(action);
  }
}
