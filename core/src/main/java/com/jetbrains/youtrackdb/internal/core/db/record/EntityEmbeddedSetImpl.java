package com.jetbrains.youtrackdb.internal.core.db.record;

import com.jetbrains.youtrackdb.api.common.query.collection.embedded.EmbeddedSet;
import com.jetbrains.youtrackdb.api.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.record.impl.SimpleMultiValueTracker;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransaction;
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

public class EntityEmbeddedSetImpl<T> extends AbstractSet<T>
    implements RecordElement, EmbeddedTrackedMultiValue<T, T>, Serializable, EmbeddedSet<T>,
    TrackedCollection<T, T> {

  protected RecordElement sourceRecord;

  private boolean dirty = false;
  private boolean transactionDirty = false;
  @Nonnull
  private final HashSet<T> set;

  private final SimpleMultiValueTracker<T, T> tracker = new SimpleMultiValueTracker<>(this);

  public EntityEmbeddedSetImpl(final RecordElement iSourceRecord) {
    this.set = new HashSet<>();
    this.sourceRecord = iSourceRecord;
  }

  public EntityEmbeddedSetImpl(final RecordElement iSourceRecord, int size) {
    this.set = new HashSet<>(size);
    this.sourceRecord = iSourceRecord;
  }

  public EntityEmbeddedSetImpl() {
    this.set = new HashSet<>();
    tracker.enable();

  }

  public EntityEmbeddedSetImpl(int size) {
    this.set = new HashSet<>(size);
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
  public boolean add(@Nullable final T e) {
    checkValue(e);
    if (set.add(e)) {
      addEvent(e);
      return true;
    }
    return false;
  }

  @Override
  public void addInternal(final T e) {
    checkValue(e);
    if (set.add(e)) {
      addOwner(e);
    }
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
  public Set<T> returnOriginalState(
      FrontendTransaction transaction,
      final List<MultiValueChangeEvent<T, T>> multiValueChangeEvents) {
    var reverted = new HashSet<>(this);

    doRollBackChanges(multiValueChangeEvents, reverted);

    return reverted;
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

  private static <T> void doRollBackChanges(
      List<MultiValueChangeEvent<T, T>> multiValueChangeEvents,
      Set<T> reverted) {
    multiValueChangeEvents = List.copyOf(multiValueChangeEvents);
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
  }

  @Override
  public void enableTracking(RecordElement parent) {
    if (!tracker.isEnabled()) {
      this.tracker.enable();
      TrackedMultiValue.nestedEnabled(this.iterator(), this);
    }

    if (sourceRecord != parent) {
      this.sourceRecord = parent;
    }
  }

  @Override
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
  public MultiValueChangeTimeLine<T, T> getTimeLine() {
    return tracker.getTimeLine();
  }

  @Override
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

  @Override
  public boolean isEmbeddedContainer() {
    return true;
  }
}
