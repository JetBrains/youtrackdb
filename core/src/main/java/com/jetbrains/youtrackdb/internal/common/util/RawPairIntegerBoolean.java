package com.jetbrains.youtrackdb.internal.common.util;

import it.unimi.dsi.fastutil.HashCommon;

public record RawPairIntegerBoolean(int first, boolean second) {

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    var oRawPair = (RawPairIntegerBoolean) o;

    if (first != oRawPair.first) {
      return false;
    }
    return second == oRawPair.second;
  }

  @Override
  public int hashCode() {
    var result = HashCommon.mix(first);
    result = 31 * result + (second ? 1 : 0);
    return result;
  }
}
