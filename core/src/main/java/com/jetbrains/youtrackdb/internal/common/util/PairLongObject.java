package com.jetbrains.youtrackdb.internal.common.util;

import it.unimi.dsi.fastutil.HashCommon;

public record PairLongObject<V>(long key, V value) implements Comparable<PairLongObject<V>> {

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
