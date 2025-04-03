package com.jetbrains.youtrack.db.internal.core.db.record;

public interface TrackedCollection<K, V> extends TrackedMultiValue<K, V> {
  void addInternal(V value);
}
