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
import com.jetbrains.youtrack.db.internal.core.record.impl.SimpleMultiValueTracker;
import java.io.Serializable;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Implementation of Set bound to a source Record object to keep track of changes. This avoid to
 * call the makeDirty() by hand when the set is changed.
 */
public class TrackedSet<T> extends AbstractSet<T>
    implements RecordElement, TrackedMultiValue<T, T>, Serializable {

  protected RecordElement sourceRecord;
  private final boolean embeddedCollection;
  protected Class<?> genericClass;
  private boolean dirty = false;
  private boolean transactionDirty = false;
  @Nonnull
  private final HashSet<T> set;

  private final SimpleMultiValueTracker<T, T> tracker = new SimpleMultiValueTracker<>(this);

  public TrackedSet(final RecordElement iSourceRecord) {
    this.set = new HashSet<>();
    this.sourceRecord = iSourceRecord;
    embeddedCollection = this.getClass().equals(TrackedSet.class);
  }

  public TrackedSet(final RecordElement iSourceRecord, int size) {
    this.set = new HashSet<>(size);
    this.sourceRecord = iSourceRecord;
    embeddedCollection = this.getClass().equals(TrackedSet.class);
  }

  public TrackedSet() {
    this.set = new HashSet<>();
    embeddedCollection = this.getClass().equals(TrackedSet.class);
    tracker.enable();
  }

  public TrackedSet(int size) {
    this.set = new HashSet<>(size);
    embeddedCollection = this.getClass().equals(TrackedSet.class);
    tracker.enable();
  }

  @Override
  public void setOwner(RecordElement newOwner) {
    if (newOwner != null) {
      var owner = sourceRecord;

      if (owner != null && !owner.equals(newOwner)) {
        throw new IllegalStateException(
            "This set is already owned by data container "
                + owner
                + " if you want to use it in other data container create new set instance and copy"
                + " content of current one.");
      }

      sourceRecord = newOwner;
    } else {
      sourceRecord = null;
    }
  }

  @Override
  public RecordElement getOwner() {
    return sourceRecord;
  }

  @Nonnull
  @Override
  public Iterator<T> iterator() {
    return new Iterator<T>() {
      private T current;
      private final Iterator<T> underlying = set.iterator();

      @Override
      public boolean hasNext() {
        return underlying.hasNext();
      }

      @Override
      public T next() {
        current = underlying.next();
        return current;
      }

      @Override
      public void remove() {
        underlying.remove();
        removeEvent(current);
      }
    };
  }

  @Override
  public int size() {
    return set.size();
  }

  @Override
  public boolean isEmbeddedContainer() {
    return embeddedCollection;
  }

  public boolean add(@Nullable final T e) {
    checkValue(e);
    if (set.add(e)) {
      addEvent(e);
      return true;
    }
    return false;
  }

  public boolean addInternal(final T e) {
    checkValue(e);
    if (set.add(e)) {
      addOwner(e);
      return true;
    }
    return false;
  }

  @SuppressWarnings("unchecked")
  @Override
  public boolean remove(final Object o) {
    if (set.remove(o)) {
      removeEvent((T) o);
      return true;
    }
    return false;
  }

  @Override
  public void clear() {
    for (final var item : this) {
      removeEvent(item);
    }
    set.clear();
  }

  protected void addEvent(T added) {
    addOwner(added);

    if (tracker.isEnabled()) {
      tracker.add(added, added);
    } else {
      setDirty();
    }
  }

  private void removeEvent(T removed) {
    removeOwner(removed);

    if (tracker.isEnabled()) {
      tracker.remove(removed, removed);
    } else {
      setDirty();
    }
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

  public Set<T> returnOriginalState(
      DatabaseSessionInternal session,
      final List<MultiValueChangeEvent<T, T>> multiValueChangeEvents) {
    final Set<T> reverted = new HashSet<T>(this);

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

  public Class<?> getGenericClass() {
    return genericClass;
  }

  public void enableTracking(RecordElement parent) {
    if (!tracker.isEnabled()) {
      this.tracker.enable();
      TrackedMultiValue.nestedEnabled(this.iterator(), this);
    }

    if (sourceRecord != parent) {
      this.sourceRecord = parent;
    }
  }

  public void disableTracking(RecordElement parent) {
    if (tracker.isEnabled()) {
      this.tracker.disable();
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

  public MultiValueChangeTimeLine<T, T> getTransactionTimeLine() {
    return tracker.getTransactionTimeLine();
  }

  @Override
  public <T1> T1[] toArray(@Nonnull IntFunction<T1[]> generator) {
    return set.toArray(generator);
  }


  @Nonnull
  @Override
  public Stream<T> stream() {
    return set.stream();
  }

  @Nonnull
  @Override
  public Stream<T> parallelStream() {
    return set.parallelStream();
  }

  @Nonnull
  @Override
  public Spliterator<T> spliterator() {
    return set.spliterator();
  }

  @Override
  public void forEach(Consumer<? super T> action) {
    set.forEach(action);
  }

  @Override
  public boolean equals(Object o) {
    if (o == this) {
      return true;
    }
    if (!(o instanceof Set)) {
      return false;
    }
    return set.equals(o);
  }

  @Override
  public int hashCode() {
    return set.hashCode();
  }

  @Override
  public boolean isEmpty() {
    return set.isEmpty();
  }

  @Override
  public boolean contains(Object o) {
    return set.contains(o);
  }

  @Nonnull
  @Override
  public Object[] toArray() {
    return set.toArray();
  }

  @Nonnull
  @Override
  public <T1> T1[] toArray(@Nonnull T1[] a) {
    return set.toArray(a);
  }

  @Override
  public boolean containsAll(@Nonnull Collection<?> c) {
    return set.containsAll(c);
  }

  @Override
  public String toString() {
    return set.toString();
  }
}
