package com.jetbrains.youtrackdb.internal.common.util;

import it.unimi.dsi.fastutil.HashCommon;

public record PairIntegerObject<V>(int key, V value) implements Comparable<PairIntegerObject<V>> {

  @Override
  public int compareTo(PairIntegerObject<V> o) {
    return Integer.compare(key, o.key);
  }

  @Override
  public String toString() {
    return "PairIntegerObject [first=" + key + ", second=" + value + "]";
  }

  @Override
  public int hashCode() {
    return HashCommon.mix(key);
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
    var other = (PairIntegerObject<?>) obj;
    return key == other.key;
  }
}
