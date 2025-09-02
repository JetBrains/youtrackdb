package com.jetbrains.youtrackdb.internal.core.db.record;

import com.jetbrains.youtrackdb.api.common.query.collection.embedded.EmbeddedList;
import com.jetbrains.youtrackdb.api.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.record.impl.SimpleMultiValueTracker;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransaction;
import java.io.Serializable;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.RandomAccess;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class EntityEmbeddedListImpl<T> extends AbstractList<T>
    implements RecordElement, EmbeddedTrackedMultiValue<Integer, T>, Serializable, EmbeddedList<T>,
    RandomAccess, TrackedCollection<Integer, T> {

  @Nullable
  protected RecordElement sourceRecord;

  private boolean dirty = false;
  private boolean transactionDirty = false;

  private final SimpleMultiValueTracker<Integer, T> tracker = new SimpleMultiValueTracker<>(this);

  @Nonnull
  private final ArrayList<T> list;


  public EntityEmbeddedListImpl(
      @Nonnull final RecordElement record, final Collection<? extends T> origin) {
    this(record);

    if (origin != null && !origin.isEmpty()) {
      addAll(origin);
    }
  }

  public EntityEmbeddedListImpl(@Nullable final RecordElement iSourceRecord) {
    this.list = new ArrayList<>();
    this.sourceRecord = iSourceRecord;
  }

  public EntityEmbeddedListImpl(@Nonnull final RecordElement iSourceRecord, int size) {
    this.list = new ArrayList<>(size);
    this.sourceRecord = iSourceRecord;
  }

  public EntityEmbeddedListImpl() {
    this.list = new ArrayList<>();
    tracker.enable();
  }

  public EntityEmbeddedListImpl(int size) {
    this.list = new ArrayList<>(size);
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

  @Override
  public void addInternal(T element) {
    checkValue(element);
    final var result = list.add(element);

    if (result) {
      addOwner(element);
    }
  }

  @Override
  public void add(int index, T element) {
    checkValue(element);
    list.add(index, element);
    addEvent(index, element);
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

  @Override
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

  @Override
  public List<T> returnOriginalState(
      FrontendTransaction transaction,
      final List<MultiValueChangeEvent<Integer, T>> multiValueChangeEvents) {
    final List<T> reverted = new ArrayList<>(this);
    doRollBackChanges(multiValueChangeEvents, reverted);

    return reverted;
  }

  private static <T> void doRollBackChanges(
      List<MultiValueChangeEvent<Integer, T>> multiValueChangeEvents, List<T> reverted) {
    multiValueChangeEvents = List.copyOf(multiValueChangeEvents);
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
  }

  @Override
  public void rollbackChanges(FrontendTransaction transaction) {
    if (!tracker.isEnabled()) {
      throw new DatabaseException(transaction.getDatabaseSession(),
          "Changes are not tracked so it is impossible to rollback them");
    }

    var timeLine = tracker.getTimeLine();
    //no changes were performed
    if (timeLine == null) {
      return;
    }
    var changeEvents = timeLine.getMultiValueChangeEvents();
    //no changes were performed
    if (changeEvents == null || changeEvents.isEmpty()) {
      return;
    }

    doRollBackChanges(changeEvents, this);
  }

  @Override
  public void enableTracking(RecordElement parent) {
    if (!tracker.isEnabled()) {
      tracker.enable();
      TrackedMultiValue.nestedEnabled(this.iterator(), this);
    }

    if (sourceRecord != parent) {
      this.sourceRecord = parent;
    }
  }

  @Override
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
  public MultiValueChangeTimeLine<Integer, T> getTimeLine() {
    return tracker.getTimeLine();
  }

  @Override
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

  @Override
  public boolean isEmbeddedContainer() {
    return true;
  }
}
