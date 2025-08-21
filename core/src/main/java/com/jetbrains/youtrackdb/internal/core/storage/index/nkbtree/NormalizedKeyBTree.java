package com.jetbrains.youtrackdb.internal.core.storage.index.nkbtree;

import com.jetbrains.youtrackdb.internal.core.index.CompositeKey;

public interface NormalizedKeyBTree<K> {

  byte[] get(final CompositeKey key);

  void put(final CompositeKey key, final byte[] value);
}
