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

import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.common.util.Sizeable;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.lang.ref.WeakReference;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Lazy implementation of LinkedHashMap. It's bound to a source Record object to keep track of
 * changes. This avoid to call the makeDirty() by hand when the map is changed.
 */
public class LinkMap extends TrackedMap<Identifiable> implements Sizeable,
    LinkTrackedMultiValue<String> {

  public static final int DEFAULT_KEY_SIZE_LIMIT = 64;

  private final int keySizeLimit;
  @Nonnull
  private final WeakReference<DatabaseSessionInternal> session;

  public LinkMap(DatabaseSessionInternal session) {
    super();
    this.session = new WeakReference<>(session);
    this.keySizeLimit = DEFAULT_KEY_SIZE_LIMIT;
  }

  public LinkMap(DatabaseSessionInternal session, int keySizeLimit) {
    super();
    this.session = new WeakReference<>(session);
    this.keySizeLimit = keySizeLimit;
  }

  public LinkMap(int size, DatabaseSessionInternal session) {
    super(size);
    this.session = new WeakReference<>(session);
    this.keySizeLimit = DEFAULT_KEY_SIZE_LIMIT;
  }

  public LinkMap(final RecordElement iSourceRecord) {
    super(iSourceRecord);
    this.session = new WeakReference<>(iSourceRecord.getSession());
    this.keySizeLimit = DEFAULT_KEY_SIZE_LIMIT;
  }

  public LinkMap(final EntityImpl iSourceRecord) {
    super(iSourceRecord);
    this.session = new WeakReference<>(iSourceRecord.getSession());
    this.keySizeLimit = DEFAULT_KEY_SIZE_LIMIT;
  }

  public LinkMap(final EntityImpl iSourceRecord, int size) {
    super(iSourceRecord, size);
    this.session = new WeakReference<>(iSourceRecord.getSession());
    this.keySizeLimit = DEFAULT_KEY_SIZE_LIMIT;
  }

  public int getKeySizeLimit() {
    return keySizeLimit;
  }

  public LinkMap(final EntityImpl iSourceRecord, final Map<String, Identifiable> iOrigin) {
    this(iSourceRecord);

    if (iOrigin != null && !iOrigin.isEmpty()) {
      putAll(iOrigin);
    }
  }

  @Override
  public Identifiable get(final Object iKey) {
    if (iKey == null) {
      return null;
    }

    final var key = iKey.toString();
    return super.get(key);
  }

  @Override
  public Identifiable put(final String key, Identifiable value) {
    checkKeySizeLimit(key);
    value = convertToRid(value);
    return super.put(key, value);
  }

  @Nullable
  @Override
  public Identifiable putIfAbsent(String key, Identifiable value) {
    checkKeySizeLimit(key);
    return super.putIfAbsent(key, convertToRid(value));
  }

  @Override
  public Identifiable computeIfAbsent(String key,
      @Nonnull Function<? super String, ? extends Identifiable> mappingFunction) {
    checkKeySizeLimit(key);
    return super.computeIfAbsent(key, k -> convertToRid(mappingFunction.apply(k)));
  }

  @Override
  public Identifiable computeIfPresent(String key,
      @Nonnull BiFunction<? super String, ? super Identifiable, ? extends Identifiable> remappingFunction) {
    return super.computeIfPresent(key, (k, v) -> convertToRid(remappingFunction.apply(k, v)));
  }

  @Override
  public Identifiable compute(String key,
      @Nonnull BiFunction<? super String, ? super Identifiable, ? extends Identifiable> remappingFunction) {
    checkKeySizeLimit(key);
    return super.compute(key, (k, v) -> convertToRid(remappingFunction.apply(k, v)));
  }

  @Override
  public void replaceAll(
      BiFunction<? super String, ? super Identifiable, ? extends Identifiable> function) {
    super.replaceAll((k, v) -> convertToRid(function.apply(k, v)));
  }

  @Override
  public boolean replace(String key, Identifiable oldValue, Identifiable newValue) {
    return super.replace(key, oldValue, convertToRid(newValue));
  }

  @Override
  public Identifiable merge(String key, @Nonnull Identifiable value,
      @Nonnull BiFunction<? super Identifiable, ? super Identifiable, ? extends Identifiable> remappingFunction) {
    checkKeySizeLimit(key);
    return super.merge(key, value, (k, v) -> convertToRid(remappingFunction.apply(k, v)));
  }

  @Nullable
  @Override
  public Identifiable replace(String key, Identifiable value) {
    checkKeySizeLimit(key);
    return super.replace(key, convertToRid(value));
  }

  @Override
  public void putAll(Map<? extends String, ? extends Identifiable> m) {
    for (var entry : m.entrySet()) {
      put(entry.getKey(), convertToRid(entry.getValue()));
    }
  }

  @Override
  public void putInternal(String key, Identifiable value) {
    checkKeySizeLimit(key);
    super.putInternal(key, convertToRid(value));
  }

  @Nonnull
  @Override
  public Set<Entry<String, Identifiable>> entrySet() {
    return new LinkEntrySet(super.entrySet());
  }

  @Nullable
  @Override
  public DatabaseSessionInternal getSession() {
    return session.get();
  }

  private void checkKeySizeLimit(String key) {
    if (key.length() > keySizeLimit) {
      throw new IllegalArgumentException(
          "Key size limit exceeded: " + key + ":" + key.length() + " > " + keySizeLimit);
    }
  }

  private final class LinkEntrySet extends AbstractSet<Entry<String, Identifiable>> {

    @Nonnull
    private final Set<Entry<String, Identifiable>> entrySet;

    private LinkEntrySet(@Nonnull Set<Entry<String, Identifiable>> entrySet) {
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
    private final Iterator<Entry<String, Identifiable>> iterator;
    @Nullable
    private LinkEntry lastEntry;

    private LinkEntryIterator(@Nonnull Iterator<Entry<String, Identifiable>> iterator) {
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

      LinkMap.this.remove(key, value);
      lastEntry = null;
    }
  }

  private final class LinkEntry implements Entry<String, Identifiable> {

    @Nonnull
    private final Entry<String, Identifiable> entry;

    private LinkEntry(@Nonnull Entry<String, Identifiable> entry) {
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
}
