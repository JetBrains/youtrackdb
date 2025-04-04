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
import com.jetbrains.youtrack.db.api.record.collection.links.LinkSet;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.record.impl.SimpleMultiValueTracker;
import java.io.Serializable;
import java.lang.ref.WeakReference;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class EntityLinkSetImpl extends AbstractSet<Identifiable> implements
    LinkTrackedMultiValue<Identifiable>,
    LinkSet, RecordElement,
    Serializable, TrackedCollection<Identifiable, Identifiable> {

  @Nonnull
  private final WeakReference<DatabaseSessionInternal> session;
  private final SimpleMultiValueTracker<Identifiable, Identifiable> tracker = new SimpleMultiValueTracker<>(
      this);

  protected RecordElement sourceRecord;
  private boolean dirty = false;
  private boolean transactionDirty = false;
  @Nonnull
  private final HashSet<RID> set;


  public EntityLinkSetImpl(DatabaseSessionInternal session) {
    this.session = new WeakReference<>(session);
    this.set = new HashSet<>();
  }

  public EntityLinkSetImpl(int size, DatabaseSessionInternal session) {
    this.session = new WeakReference<>(session);
    this.set = new HashSet<>(size);
  }

  public EntityLinkSetImpl(final RecordElement sourceRecord) {
    this.sourceRecord = sourceRecord;
    this.set = new HashSet<>();
    this.session = new WeakReference<>(sourceRecord.getSession());
  }

  public EntityLinkSetImpl(final RecordElement iSourceRecord, int size) {
    this.set = new HashSet<>(size);
    this.sourceRecord = iSourceRecord;
    this.session = new WeakReference<>(iSourceRecord.getSession());
  }

  public EntityLinkSetImpl(RecordElement iSourceRecord, Collection<Identifiable> iOrigin) {
    this(iSourceRecord);

    if (iOrigin != null && !iOrigin.isEmpty()) {
      addAll(iOrigin);
    }
  }

  @Override
  public void addInternal(Identifiable e) {
    checkValue(e);
    var rid = convertToRid(e);

    if (set.add(rid)) {
      addOwner(e);
    }
  }

  public boolean remove(Object o) {
    if (o == null) {
      return false;
    }

    if (!(o instanceof Identifiable identifiable)) {
      return false;
    }

    try {
      checkValue(identifiable);
    } catch (IllegalArgumentException | SchemaException e) {
      return false;
    }

    var rid = convertToRid(identifiable);

    if (set.remove(rid)) {
      removeEvent(rid);
      return true;
    }

    return false;
  }

  @Nullable
  @Override
  public DatabaseSessionInternal getSession() {
    return session.get();
  }

  @Override
  public void setOwner(RecordElement newOwner) {
    LinkTrackedMultiValue.checkEntityAsOwner(newOwner);
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
  public Iterator<Identifiable> iterator() {
    return new Iterator<>() {
      private RID current;
      private final Iterator<RID> underlying = set.iterator();

      @Override
      public boolean hasNext() {
        return underlying.hasNext();
      }

      @Override
      public Identifiable next() {
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

  public boolean add(@Nullable final Identifiable e) {
    checkValue(e);
    var rid = convertToRid(e);

    if (set.add(rid)) {
      addEvent(rid);
      return true;
    }

    return false;
  }


  @Override
  public void clear() {
    for (final var item : set) {
      removeEvent(item);
    }

    set.clear();
  }

  protected void addEvent(RID added) {
    addOwner(added);

    if (tracker.isEnabled()) {
      tracker.add(added, added);
    } else {
      setDirty();
    }
  }

  private void removeEvent(RID removed) {
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

  public Set<Identifiable> returnOriginalState(
      DatabaseSessionInternal session,
      final List<MultiValueChangeEvent<Identifiable, Identifiable>> multiValueChangeEvents) {
    var reverted = new HashSet<>(this);

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
  public MultiValueChangeTimeLine<Identifiable, Identifiable> getTimeLine() {
    return tracker.getTimeLine();
  }

  public MultiValueChangeTimeLine<Identifiable, Identifiable> getTransactionTimeLine() {
    return tracker.getTransactionTimeLine();
  }

  @Override
  public <T1> T1[] toArray(@Nonnull IntFunction<T1[]> generator) {
    return set.toArray(generator);
  }

  @Override
  public void forEach(Consumer<? super Identifiable> action) {
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
    if (!(o instanceof Identifiable identifiable)) {
      return false;
    }
    try {
      checkValue(identifiable);
    } catch (IllegalArgumentException | SchemaException e) {
      return false;
    }
    var rid = convertToRid(identifiable);
    return set.contains(rid);
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
  public String toString() {
    return set.toString();
  }
}
