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

import com.jetbrains.youtrack.db.api.record.collection.embedded.EmbeddedMap;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.record.impl.SimpleMultiValueTracker;
import java.io.Serializable;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Implementation of LinkedHashMap bound to a source Record object to keep track of changes. This
 * avoid to call the makeDirty() by hand when the map is changed.
 */
public final class EntityEmbeddedMapImpl<T> extends AbstractMap<String, T>
    implements RecordElement, EmbeddedTrackedMultiValue<String, T>, Serializable, EmbeddedMap<T> {

  private RecordElement sourceRecord;
  private boolean dirty = false;
  private boolean transactionDirty = false;

  @Nonnull
  private final HashMap<String, T> map;
  private final SimpleMultiValueTracker<String, T> tracker = new SimpleMultiValueTracker<>(this);

  public EntityEmbeddedMapImpl(
      final RecordElement iRecord, final Map<String, T> iOrigin) {
    this(iRecord);
    if (iOrigin != null && !iOrigin.isEmpty()) {
      putAll(iOrigin);
    }
  }

  public EntityEmbeddedMapImpl(@Nullable final RecordElement iSourceRecord) {
    this.map = new HashMap<>();
    this.sourceRecord = iSourceRecord;
  }

  public EntityEmbeddedMapImpl(final RecordElement iSourceRecord, int size) {
    this.map = new HashMap<>(size);
    this.sourceRecord = iSourceRecord;
  }

  public EntityEmbeddedMapImpl() {
    this.map = new HashMap<>();
    tracker.enable();
  }

  public EntityEmbeddedMapImpl(int size) {
    this.map = new HashMap<>(size);
    tracker.enable();
  }

  @Override
  public void setOwner(RecordElement newOwner) {
    if (newOwner != null) {
      var owner = sourceRecord;
      if (owner != null && !owner.equals(newOwner)) {
        throw new IllegalStateException(
            "This map is already owned by data container "
                + owner
                + " if you want to use it in other data container create new map instance and copy"
                + " content of current one.");
      }

      this.sourceRecord = newOwner;
    } else {
      this.sourceRecord = null;
    }
  }

  @Override
  public boolean isEmbeddedContainer() {
    return true;
  }

  @Override
  public RecordElement getOwner() {
    return sourceRecord;
  }

  @Override
  public boolean addInternal(T e) {
    throw new UnsupportedOperationException();
  }

  public void putInternal(final String key, final T value) {
    if (key == null) {
      throw new IllegalArgumentException("null key not supported by embedded map");
    }
    checkValue(value);

    var containsKey = containsKey(key);
    var oldValue = map.put(key, value);

    if (containsKey && oldValue == value) {
      return;
    }

    if (oldValue instanceof EntityImpl) {
      ((EntityImpl) oldValue).removeOwner(this);
    }

    addOwner(value);
  }

  @Override
  public T put(final String key, final T value) {
    if (key == null) {
      throw new IllegalArgumentException("null key not supported by embedded map");
    }
    checkValue(value);

    var containsKey = containsKey(key);

    var oldValue = map.put(key, value);
    if (containsKey && oldValue == value) {
      return oldValue;
    }

    if (containsKey) {
      updateEvent(key, oldValue, value);
    } else {
      addEvent(key, value);
    }

    return oldValue;
  }

  @Override
  @Nullable
  public T remove(final Object key) {
    var containsKey = containsKey(key);

    if (containsKey) {
      final var oldValue = map.remove(key);
      removeEvent(key.toString(), oldValue);
      return oldValue;
    } else {
      return null;
    }
  }

  @Override
  public void clear() {
    for (var entry : map.entrySet()) {
      var value = entry.getValue();
      if (value instanceof TrackedMultiValue<?, ?> trackedMultiValue) {
        trackedMultiValue.setOwner(null);
      }

      removeEvent(entry.getKey(), value);
    }

    map.clear();
  }

  @Nonnull
  @Override
  public Set<Entry<String, T>> entrySet() {
    return new EntrySet();
  }

  @Override
  public void putAll(Map<? extends String, ? extends T> m) {
    for (var entry : m.entrySet()) {
      put(entry.getKey(), entry.getValue());
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

  public Map<Object, T> returnOriginalState(
      DatabaseSessionInternal session,
      final List<MultiValueChangeEvent<String, T>> multiValueChangeEvents) {
    final Map<Object, T> reverted = new HashMap<Object, T>(this);

    final var listIterator =
        multiValueChangeEvents.listIterator(multiValueChangeEvents.size());

    while (listIterator.hasPrevious()) {
      final var event = listIterator.previous();
      switch (event.getChangeType()) {
        case ADD:
          reverted.remove(event.getKey());
          break;
        case REMOVE:
          reverted.put(event.getKey(), event.getOldValue());
          break;
        case UPDATE:
          reverted.put(event.getKey(), event.getOldValue());
          break;
        default:
          throw new IllegalArgumentException("Invalid change type : " + event.getChangeType());
      }
    }

    return reverted;
  }

  @Override
  public int size() {
    return map.size();
  }

  @Override
  public boolean isEmpty() {
    return map.isEmpty();
  }

  @Override
  public boolean containsValue(Object value) {
    return map.containsValue(value);
  }

  @Override
  public boolean containsKey(Object key) {
    return map.containsKey(key);
  }

  @Override
  @Nullable
  public T get(Object key) {
    return map.get(key);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }

    if (!(o instanceof Map<?, ?>)) {
      return false;
    }

    return map.equals(o);
  }

  @Override
  public int hashCode() {
    return map.hashCode();
  }

  @Override
  public String toString() {
    return map.toString();
  }

  @Override
  public T getOrDefault(Object key, T defaultValue) {
    return map.getOrDefault(key, defaultValue);
  }

  @Override
  public void forEach(BiConsumer<? super String, ? super T> action) {
    map.forEach(action);
  }

  @Override
  public boolean remove(Object key, Object value) {
    var result = map.remove(key, value);
    if (result) {
      //noinspection unchecked
      removeEvent(key.toString(), (T) value);
    }
    return result;
  }

  @Override
  public boolean replace(String key, T oldValue, T newValue) {
    var result = map.replace(key, oldValue, newValue);
    if (result) {
      updateEvent(key, oldValue, newValue);
    }
    return result;
  }

  @Nullable
  @Override
  public T replace(String key, T value) {
    var result = map.replace(key, value);

    if (result != null) {
      updateEvent(key, result, value);
    } else {
      addEvent(key, value);
    }

    return result;
  }

  private void addEvent(String key, T value) {
    addOwner(value);

    if (tracker.isEnabled()) {
      tracker.add(key, value);
    } else {
      setDirty();
    }
  }

  private void updateEvent(String key, T oldValue, T newValue) {
    removeOwner(oldValue);

    addOwner(newValue);

    if (tracker.isEnabled()) {
      tracker.updated(key, newValue, oldValue);
    } else {
      setDirty();
    }
  }

  private void removeEvent(String key, T removed) {
    removeOwner(removed);

    if (tracker.isEnabled()) {
      tracker.remove(key, removed);
    } else {
      setDirty();
    }
  }

  public void enableTracking(RecordElement parent) {
    if (!tracker.isEnabled()) {
      tracker.enable();
      TrackedMultiValue.nestedEnabled(this.values().iterator(), this);
    }

    if (sourceRecord != parent) {
      this.sourceRecord = parent;
    }

  }

  public void disableTracking(RecordElement parent) {
    if (tracker.isEnabled()) {
      this.tracker.disable();
      TrackedMultiValue.nestedDisable(this.values().iterator(), this);
    }
    this.dirty = false;

    if (sourceRecord != parent) {
      this.sourceRecord = parent;
    }

  }

  @Override
  public void transactionClear() {
    tracker.transactionClear();
    TrackedMultiValue.nestedTransactionClear(this.values().iterator());
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
  public MultiValueChangeTimeLine<String, T> getTimeLine() {
    return tracker.getTimeLine();
  }

  public MultiValueChangeTimeLine<String, T> getTransactionTimeLine() {
    return tracker.getTransactionTimeLine();
  }

  private final class EntrySet extends AbstractSet<Entry<String, T>> {

    @Nonnull
    @Override
    public Iterator<Entry<String, T>> iterator() {
      return new EntryIterator(map.entrySet().iterator());
    }

    @Override
    public int size() {
      return map.size();
    }

    @Override
    public void clear() {
      EntityEmbeddedMapImpl.this.clear();
    }

    @Override
    public boolean remove(Object o) {
      if (!(o instanceof Entry)) {
        return false;
      }

      @SuppressWarnings("unchecked") final var entry = (Entry<String, T>) o;
      final var key = entry.getKey();
      final var value = entry.getValue();

      return map.remove(key, value);
    }
  }

  private final class EntryIterator implements Iterator<Entry<String, T>> {

    @Nonnull
    private final Iterator<Entry<String, T>> iterator;
    @Nullable
    private TrackerEntry lastEntry;

    private EntryIterator(@Nonnull Iterator<Entry<String, T>> iterator) {
      this.iterator = iterator;
    }

    @Override
    public boolean hasNext() {
      return iterator.hasNext();
    }

    @Override
    public Entry<String, T> next() {
      lastEntry = new TrackerEntry(iterator.next());
      return lastEntry;
    }

    @Override
    public void remove() {
      if (lastEntry == null) {
        throw new IllegalStateException();
      }

      final var key = lastEntry.getKey();
      final var value = lastEntry.getValue();

      map.remove(key, value);
      lastEntry = null;
    }
  }

  private final class TrackerEntry implements Entry<String, T> {

    @Nonnull
    private final Entry<String, T> entry;

    private TrackerEntry(@Nonnull Entry<String, T> entry) {
      this.entry = entry;
    }

    @Override
    public String getKey() {
      return entry.getKey();
    }

    @Override
    public T getValue() {
      return entry.getValue();
    }

    @Override
    public T setValue(T value) {
      final var key = getKey();
      final var oldValue = getValue();

      entry.setValue(value);
      updateEvent(key, oldValue, value);

      return oldValue;
    }
  }
}
