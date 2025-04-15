package com.jetbrains.youtrack.db.internal.common.util;

import it.unimi.dsi.fastutil.HashCommon;

public final class PairLongObject<V> implements Comparable<PairLongObject<V>> {

  public final long key;
  public final V value;

  public PairLongObject(long key, V value) {
    this.key = key;
    this.value = value;
  }

  public long getKey() {
    return key;
  }

  public V getValue() {
    return value;
  }

  @Override
  public int compareTo(PairLongObject<V> o) {
    return Long.compare(key, o.key);
  }

  @Override
  public String toString() {
    return "PairLongObject [first=" + key + ", second=" + value + "]";
  }

  @Override
  public int hashCode() {
    return Long.hashCode(HashCommon.mix(key));
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null) {
      return false;
    }
    if (getClass() != obj.getClass()) {
      return false;
    }
    var other = (PairLongObject<?>) obj;
    return key == other.key;
  }
}
