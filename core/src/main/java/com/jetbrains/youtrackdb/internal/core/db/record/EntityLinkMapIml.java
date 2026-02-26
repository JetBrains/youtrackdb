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
package com.jetbrains.youtrackdb.internal.core.db.record;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.exception.SchemaException;
import com.jetbrains.youtrackdb.internal.core.query.collection.links.LinkMap;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.record.impl.SimpleMultiValueTracker;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransaction;
import java.io.Serializable;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Lazy implementation of LinkedHashMap. It's bound to a source Record object to keep track of
 * changes. This avoid to call the makeDirty() by hand when the map is changed.
 */
public class EntityLinkMapIml extends AbstractMap<String, Identifiable>
    implements RecordElement, LinkTrackedMultiValue<String>, Serializable, LinkMap {

  public static final int DEFAULT_KEY_SIZE_LIMIT = 64;
  private final int keySizeLimit;

  private final HashMap<String, RID> map;
  private final HashMap<String, byte[]> encodedKeys;
  private final HashMap<RID, ArrayList<String>> reverseMap;

  private final SimpleMultiValueTracker<String, Identifiable> tracker = new SimpleMultiValueTracker<>(
      this);

  protected RecordElement sourceRecord;
  private boolean dirty = false;
  private boolean transactionDirty = false;

  @Nonnull
  private final WeakReference<DatabaseSessionEmbedded> session;

  public EntityLinkMapIml(@Nonnull DatabaseSessionEmbedded session) {
    this.session = new WeakReference<>(session);
    this.keySizeLimit = DEFAULT_KEY_SIZE_LIMIT;
    this.map = new HashMap<>();
    this.encodedKeys = new HashMap<>();
    this.reverseMap = new HashMap<>();
  }

  public EntityLinkMapIml(int size, @Nonnull DatabaseSessionEmbedded session) {
    this.map = new HashMap<>(size);
    this.encodedKeys = new HashMap<>(size);
    this.reverseMap = new HashMap<>(size);
    this.session = new WeakReference<>(session);
    this.keySizeLimit = DEFAULT_KEY_SIZE_LIMIT;
  }

  public EntityLinkMapIml(final RecordElement sourceRecord) {
    this.sourceRecord = sourceRecord;
    this.session = new WeakReference<>(sourceRecord.getSession());
    this.keySizeLimit = DEFAULT_KEY_SIZE_LIMIT;
    this.map = new HashMap<>();
    this.encodedKeys = new HashMap<>();
    this.reverseMap = new HashMap<>();
  }


  public EntityLinkMapIml(final EntityImpl sourceRecord) {
    this.sourceRecord = sourceRecord;
    this.session = new WeakReference<>(sourceRecord.getSession());
    this.keySizeLimit = DEFAULT_KEY_SIZE_LIMIT;
    this.map = new HashMap<>();
    this.encodedKeys = new HashMap<>();
    this.reverseMap = new HashMap<>();
  }

  public EntityLinkMapIml(final EntityImpl sourceRecord, int size) {
    this.map = new HashMap<>(size);
    this.encodedKeys = new HashMap<>(size);
    this.reverseMap = new HashMap<>(size);
    this.sourceRecord = sourceRecord;
    this.session = new WeakReference<>(sourceRecord.getSession());
    this.keySizeLimit = DEFAULT_KEY_SIZE_LIMIT;
  }

  public EntityLinkMapIml(final EntityImpl iSourceRecord, final Map<String, Identifiable> origin) {
    this(iSourceRecord);

    if (origin != null && !origin.isEmpty()) {
      putAll(origin);
    }
  }

  @Override
  @Nullable
  public Identifiable get(final Object key) {
    if (!(key instanceof String stringKey)) {
      return null;
    }

    return map.get(stringKey);
  }

  @Override
  public Identifiable put(final String key, Identifiable value) {
    if (key == null) {
      throw new IllegalArgumentException("null key not supported by embedded map");
    }
    checkValue(value);
    var containsKey = containsKey(key);

    var rid = convertToRid(value);

    var encodedKey = key.getBytes(StandardCharsets.UTF_8);
    if (encodedKey.length > keySizeLimit) {
      throw new IllegalArgumentException(
          "UTF-8 encoded key size limit exceeded: " + key + ":" + encodedKey.length + " > "
              + keySizeLimit);
    }
    encodedKeys.put(key, encodedKey);
    var oldValue = map.put(key, rid);
    reverseMap.compute(rid, (k, v) -> {
      if (v == null) {
        v = new ArrayList<>();
      }
      v.add(key);
      return v;
    });

    if (containsKey && oldValue == value) {
      return oldValue;
    }

    if (containsKey) {
      updateEvent(key, oldValue, rid);
    } else {
      addEvent(key, rid);
    }

    return oldValue;
  }

  @Override
  @Nullable
  public Identifiable remove(Object key) {
    var containsKey = containsKey(key);

    if (containsKey) {
      var stringKey = (String) key;

      final var oldValue = map.remove(stringKey);
      encodedKeys.remove(stringKey);
      reverseMap.computeIfPresent(oldValue, (k, v) -> {
        v.remove(stringKey);
        if (v.isEmpty()) {
          return null;
        }
        return v;
      });

      removeEvent(stringKey, oldValue);
      return oldValue;
    } else {
      return null;
    }
  }

  @Override
  public void clear() {
    for (var entry : map.entrySet()) {
      var value = entry.getValue();
      removeEvent(entry.getKey(), value);
    }

    map.clear();
    encodedKeys.clear();
    reverseMap.clear();
  }

  public void putInternal(String key, Identifiable value) {
    if (key == null) {
      throw new IllegalArgumentException("null key not supported by embedded map");
    }
    checkValue(value);
    var rid = convertToRid(value);

    map.put(key, rid);
    reverseMap.compute(rid, (k, v) -> {
      if (v == null) {
        v = new ArrayList<>();
      }
      v.add(key);
      return v;
    });
  }

  @Nonnull
  @Override
  public Set<Entry<String, Identifiable>> entrySet() {
    return new LinkEntrySet(map.entrySet());
  }

  @Nullable
  @Override
  public DatabaseSessionEmbedded getSession() {
    return session.get();
  }

  @Override
  public void setOwner(RecordElement newOwner) {
    LinkTrackedMultiValue.checkEntityAsOwner(newOwner);
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


  private final class LinkEntrySet extends AbstractSet<Entry<String, Identifiable>> {

    @Nonnull
    private final Set<Entry<String, RID>> entrySet;

    private LinkEntrySet(@Nonnull Set<Entry<String, RID>> entrySet) {
      this.entrySet = entrySet;
    }

    @Nonnull
    @Override
    public Iterator<Entry<String, Identifiable>> iterator() {
      return new LinkEntryIterator(entrySet.iterator());
    }

    @Override
    public int size() {
      return entrySet.size();
    }

    @Override
    public void clear() {
      entrySet.clear();
    }

    @Override
    public boolean remove(Object o) {
      return entrySet.remove(o);
    }
  }

  private final class LinkEntryIterator implements Iterator<Entry<String, Identifiable>> {

    @Nonnull
    private final Iterator<Entry<String, RID>> iterator;
    @Nullable
    private LinkEntry lastEntry;

    private LinkEntryIterator(@Nonnull Iterator<Entry<String, RID>> iterator) {
      this.iterator = iterator;
    }

    @Override
    public boolean hasNext() {
      return iterator.hasNext();
    }

    @Override
    public Entry<String, Identifiable> next() {
      lastEntry = new LinkEntry(iterator.next());
      return lastEntry;
    }

    @Override
    public void remove() {
      if (lastEntry == null) {
        throw new IllegalStateException();
      }

      final var key = lastEntry.getKey();
      final var value = lastEntry.getValue();

      EntityLinkMapIml.this.remove(key, value);
      lastEntry = null;
    }
  }

  private final class LinkEntry implements Entry<String, Identifiable> {

    @Nonnull
    private final Entry<String, RID> entry;

    private LinkEntry(@Nonnull Entry<String, RID> entry) {
      this.entry = entry;
    }

    @Override
    public String getKey() {
      return entry.getKey();
    }

    @Override
    public Identifiable getValue() {
      return entry.getValue();
    }

    @Override
    public Identifiable setValue(Identifiable value) {
      return entry.setValue(convertToRid(value));
    }
  }

  @Override
  public RecordElement getOwner() {
    return sourceRecord;
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
  public Map<String, Identifiable> returnOriginalState(
      FrontendTransaction transaction,
      final List<MultiValueChangeEvent<String, Identifiable>> multiValueChangeEvents) {
    var reverted = new HashMap<>(this);

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

  private static void doRollBackChanges(
      List<MultiValueChangeEvent<String, Identifiable>> multiValueChangeEvents,
      Map<String, Identifiable> reverted) {
    multiValueChangeEvents = List.copyOf(multiValueChangeEvents);
    final var listIterator =
        multiValueChangeEvents.listIterator(multiValueChangeEvents.size());

    while (listIterator.hasPrevious()) {
      final var event = listIterator.previous();
      switch (event.getChangeType()) {
        case ADD -> reverted.remove(event.getKey());
        case REMOVE, UPDATE -> reverted.put(event.getKey(), event.getOldValue());
        default ->
            throw new IllegalArgumentException("Invalid change type : " + event.getChangeType());
      }
    }
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
  public void forEach(BiConsumer<? super String, ? super Identifiable> action) {
    map.forEach(action);
  }

  @Override
  public boolean remove(Object key, Object value) {
    if (!(key instanceof String stringKey)) {
      return false;
    }
    if (!(value instanceof Identifiable identifiable)) {
      return false;
    }
    try {
      checkValue(identifiable);
    } catch (SchemaException | IllegalArgumentException e) {
      return false;
    }

    var rid = convertToRid(identifiable);
    var result = map.remove(stringKey, rid);

    if (result) {
      encodedKeys.remove(stringKey);
      reverseMap.computeIfPresent(rid, (k, v) -> {
        v.remove(stringKey);
        if (v.isEmpty()) {
          return null;
        }
        return v;
      });
      removeEvent(stringKey, rid);
    }
    return result;
  }

  @Override
  public boolean replace(String key, Identifiable oldValue, Identifiable newValue) {
    checkValue(oldValue);
    checkValue(newValue);

    var ridOldValue = convertToRid(oldValue);
    var ridNewValue = convertToRid(newValue);

    var result = map.replace(key, ridOldValue, ridNewValue);
    if (result) {
      reverseMap.computeIfPresent(ridOldValue, (k, v) -> {
        v.remove(key);
        if (v.isEmpty()) {
          return null;
        }
        return v;
      });
      reverseMap.compute(ridNewValue, (k, v) -> {
        if (v == null) {
          v = new ArrayList<>();
        }
        v.add(key);
        return v;
      });

      updateEvent(key, ridOldValue, ridNewValue);
    }

    return result;
  }

  @Nullable
  @Override
  public Identifiable replace(String key, Identifiable value) {
    checkValue(value);
    var rid = convertToRid(value);

    var result = map.replace(key, rid);

    if (result != null) {
      reverseMap.computeIfPresent(result, (k, v) -> {
        v.remove(key);
        if (v.isEmpty()) {
          return null;
        }
        return v;
      });
      reverseMap.compute(rid, (k, v) -> {
        if (v == null) {
          v = new ArrayList<>();
        }
        v.add(key);
        return v;
      });

      updateEvent(key, result, rid);
    }
    return result;
  }

  private void addEvent(String key, RID value) {
    addOwner(value);

    if (tracker.isEnabled()) {
      tracker.add(key, value);
    } else {
      setDirty();
    }
  }

  private void updateEvent(String key, RID oldValue, RID newValue) {
    removeOwner(oldValue);

    addOwner(newValue);

    if (tracker.isEnabled()) {
      tracker.updated(key, newValue, oldValue);
    } else {
      setDirty();
    }
  }

  private void removeEvent(String key, RID removed) {
    removeOwner(removed);

    if (tracker.isEnabled()) {
      tracker.remove(key, removed);
    } else {
      setDirty();
    }
  }

  @Override
  public void enableTracking(RecordElement parent) {
    if (!tracker.isEnabled()) {
      tracker.enable();
      TrackedMultiValue.nestedEnabled(this.values().iterator(), this);
    }

    if (sourceRecord != parent) {
      this.sourceRecord = parent;
    }

  }

  @Override
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

  public boolean addInternal(Identifiable e) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isModified() {
    return dirty || (tracker.isEnabled() && tracker.isChanged());
  }

  @Override
  public boolean isTransactionModified() {
    return transactionDirty || (tracker.isEnabled() && tracker.isTxChanged());
  }

  @Override
  public MultiValueChangeTimeLine<String, Identifiable> getTimeLine() {
    return tracker.getTimeLine();
  }

  @Override
  public MultiValueChangeTimeLine<String, Identifiable> getTransactionTimeLine() {
    return tracker.getTransactionTimeLine();
  }
}
