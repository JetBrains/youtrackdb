package com.jetbrains.youtrack.db.internal.common.util;

import it.unimi.dsi.fastutil.HashCommon;

public final class RawPairIntegerObject<V> {

  public final int first;
  public final V second;

  public RawPairIntegerObject(int first, V second) {
    this.first = first;
    this.second = second;
  }

  public int getFirst() {
    return first;
  }

  public V getSecond() {
    return second;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    var oRawPair = (RawPairIntegerObject<?>) o;

    if (first != oRawPair.first) {
      return false;
    }
    return second.equals(oRawPair.second);
  }

  @Override
  public int hashCode() {
    var result = HashCommon.mix(first);
    result = 31 * result + second.hashCode();
    return result;
  }
}
