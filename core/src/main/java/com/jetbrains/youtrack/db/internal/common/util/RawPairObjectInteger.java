package com.jetbrains.youtrack.db.internal.common.util;

import it.unimi.dsi.fastutil.HashCommon;

public final class RawPairObjectInteger<V> {

  public final V first;
  public final int second;

  public RawPairObjectInteger(V first, int second) {
    this.first = first;
    this.second = second;
  }

  public V getFirst() {
    return first;
  }

  public int getSecond() {
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

    var oRawPair = (RawPairObjectInteger<?>) o;

    if (!first.equals(oRawPair.first)) {
      return false;
    }
    return second == oRawPair.second;
  }

  @Override
  public int hashCode() {
    var result = first.hashCode();
    result = 31 * result + HashCommon.mix(second);
    return result;
  }
}
