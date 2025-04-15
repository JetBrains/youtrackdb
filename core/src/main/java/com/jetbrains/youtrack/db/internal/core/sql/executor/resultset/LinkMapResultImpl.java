package com.jetbrains.youtrack.db.internal.core.sql.executor.resultset;

import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.collection.links.LinkMap;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class LinkMapResultImpl implements LinkMap {

  private final HashMap<String, Identifiable> map;

  public LinkMapResultImpl() {
    this.map = new HashMap<>();
  }

  public LinkMapResultImpl(HashMap<String, ? extends Identifiable> map) {
    this.map = new HashMap<>(map);
  }

  public LinkMapResultImpl(int initialCapacity) {
    this.map = new LinkedHashMap<>(initialCapacity);
  }

  @Override
  public Identifiable getOrDefault(Object key, Identifiable defaultValue) {
    if (!(key instanceof String stringKey)) {
      return defaultValue;
    }

    return map.getOrDefault(stringKey, defaultValue);
  }

  @Override
  public void forEach(BiConsumer<? super String, ? super Identifiable> action) {
    map.forEach(action);
  }

  @Override
  public void replaceAll(
      BiFunction<? super String, ? super Identifiable, ? extends Identifiable> function) {
    map.replaceAll(function);
  }

  @Nullable
  @Override
  public Identifiable putIfAbsent(String key, Identifiable value) {
    return map.putIfAbsent(key, value);
  }

  @Override
  public boolean remove(Object key, Object value) {
    return map.remove(key, value);
  }

  @Override
  public boolean replace(String key, Identifiable oldValue, Identifiable newValue) {
    return map.replace(key, oldValue, newValue);
  }

  @Nullable
  @Override
  public Identifiable replace(String key, Identifiable value) {
    return map.replace(key, value);
  }

  @Override
  public Identifiable computeIfAbsent(String key,
      @Nonnull Function<? super String, ? extends Identifiable> mappingFunction) {
    return map.computeIfAbsent(key, mappingFunction);
  }

  @Override
  public Identifiable computeIfPresent(String key,
      @Nonnull BiFunction<? super String, ? super Identifiable, ? extends Identifiable> remappingFunction) {
    return map.computeIfPresent(key, remappingFunction);
  }

  @Override
  public Identifiable compute(String key,
      @Nonnull BiFunction<? super String, ? super Identifiable, ? extends Identifiable> remappingFunction) {
    return map.compute(key, remappingFunction);
  }

  @Override
  public Identifiable merge(String key, @Nonnull Identifiable value,
      @Nonnull BiFunction<? super Identifiable, ? super Identifiable, ? extends Identifiable> remappingFunction) {
    return map.merge(key, value, remappingFunction);
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
  public boolean containsKey(Object key) {
    return key instanceof String && map.containsKey(key);
  }

  @Override
  public boolean containsValue(Object value) {
    return value instanceof Identifiable && map.containsValue(value);
  }

  @Override
  @Nullable
  public Identifiable get(Object key) {
    return key instanceof String ? map.get(key) : null;
  }

  @Nullable
  @Override
  public Identifiable put(String key, Identifiable value) {
    return map.put(key, value);
  }

  @Override
  @Nullable
  public Identifiable remove(Object key) {
    return key instanceof String ? map.remove(key) : null;
  }

  @Override
  public void putAll(@Nonnull Map<? extends String, ? extends Identifiable> m) {
    map.putAll(m);
  }

  @Override
  public void clear() {
    map.clear();
  }

  @Nonnull
  @Override
  public Set<String> keySet() {
    return map.keySet();
  }

  @Nonnull
  @Override
  public Collection<Identifiable> values() {
    return map.values();
  }

  @Nonnull
  @Override
  public Set<Entry<String, Identifiable>> entrySet() {
    return map.entrySet();
  }

  @Override
  public int hashCode() {
    return map.hashCode();
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == this) {
      return true;
    }
    if (!(obj instanceof Map<?, ?>)) {
      return false;
    }

    return super.equals(obj);
  }

  @Override
  public String toString() {
    return map.toString();
  }
}
