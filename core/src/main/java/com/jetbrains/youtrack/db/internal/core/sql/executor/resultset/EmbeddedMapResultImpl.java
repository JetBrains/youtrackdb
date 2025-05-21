package com.jetbrains.youtrack.db.internal.core.sql.executor.resultset;

import com.jetbrains.youtrack.db.api.common.query.collection.embedded.EmbeddedMap;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class EmbeddedMapResultImpl<T> implements EmbeddedMap<T> {
  private final HashMap<String, T> map;

  public EmbeddedMapResultImpl() {
    this.map = new HashMap<>();
  }


  public EmbeddedMapResultImpl(int initialCapacity) {
    this.map = new HashMap<>(initialCapacity);
  }

  public EmbeddedMapResultImpl(HashMap<String, ? extends T> map) {
    this.map = new HashMap<>(map);
  }


  @Override
  public int hashCode() {
    return map.hashCode();
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof Map<?, ?>)) {
      return false;
    }

    return map.equals(obj);
  }

  @Override
  public String toString() {
    return map.toString();
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
    return map.containsKey(key);
  }

  @Override
  public boolean containsValue(Object value) {
    return map.containsValue(value);
  }

  @Override
  public T get(Object key) {
    return map.get(key);
  }

  @Nullable
  @Override
  public T put(String key, T value) {
    return map.put(key, value);
  }

  @Override
  public T remove(Object key) {
    return map.remove(key);
  }

  @Override
  public void putAll(@Nonnull Map<? extends String, ? extends T> m) {
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
  public Collection<T> values() {
    return map.values();
  }

  @Nonnull
  @Override
  public Set<Entry<String, T>> entrySet() {
    return map.entrySet();
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
  public void replaceAll(BiFunction<? super String, ? super T, ? extends T> function) {
    map.replaceAll(function);
  }

  @Nullable
  @Override
  public T putIfAbsent(String key, T value) {
    return map.putIfAbsent(key, value);
  }

  @Override
  public boolean remove(Object key, Object value) {
    return map.remove(key, value);
  }

  @Override
  public boolean replace(String key, T oldValue, T newValue) {
    return map.replace(key, oldValue, newValue);
  }

  @Nullable
  @Override
  public T replace(String key, T value) {
    return map.replace(key, value);
  }

  @Override
  public T computeIfAbsent(String key,
      @Nonnull Function<? super String, ? extends T> mappingFunction) {
    return map.computeIfAbsent(key, mappingFunction);
  }

  @Override
  public T computeIfPresent(String key,
      @Nonnull BiFunction<? super String, ? super T, ? extends T> remappingFunction) {
    return map.computeIfPresent(key, remappingFunction);
  }

  @Override
  public T compute(String key,
      @Nonnull BiFunction<? super String, ? super T, ? extends T> remappingFunction) {
    return map.compute(key, remappingFunction);
  }

  @Override
  public T merge(String key, @Nonnull T value,
      @Nonnull BiFunction<? super T, ? super T, ? extends T> remappingFunction) {
    return map.merge(key, value, remappingFunction);
  }
}
