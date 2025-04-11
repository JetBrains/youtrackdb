package com.jetbrains.youtrack.db.internal.common.util;

import it.unimi.dsi.fastutil.HashCommon;

public final class RawPairLongLong {

  public final long first;
  public final long second;

  public RawPairLongLong(long first, long second) {
    this.first = first;
    this.second = second;
  }

  public long getFirst() {
    return first;
  }

  public long getSecond() {
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

    var oRawPair = (RawPairLongLong) o;

    if (first != oRawPair.first) {
      return false;
    }
    return second == oRawPair.second;
  }

  @Override
  public int hashCode() {
    var result = Long.hashCode(HashCommon.mix(first));
    result = 31 * result + Long.hashCode(HashCommon.mix(second));
    return result;
  }
}
