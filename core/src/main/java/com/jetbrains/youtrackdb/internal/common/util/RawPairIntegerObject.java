package com.jetbrains.youtrackdb.internal.common.util;

import it.unimi.dsi.fastutil.HashCommon;

public record RawPairIntegerObject<V>(int first, V second) {

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
