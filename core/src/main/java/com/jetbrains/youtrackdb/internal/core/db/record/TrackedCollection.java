package com.jetbrains.youtrackdb.internal.core.db.record;

public interface TrackedCollection<K, V> extends TrackedMultiValue<K, V> {
  void addInternal(V value);
}
