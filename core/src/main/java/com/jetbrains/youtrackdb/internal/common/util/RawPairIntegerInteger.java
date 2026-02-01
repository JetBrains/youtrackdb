package com.jetbrains.youtrackdb.internal.common.util;

import it.unimi.dsi.fastutil.HashCommon;

public record RawPairIntegerInteger(int first, int second) {

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    var oRawPair = (RawPairIntegerInteger) o;

    if (first != oRawPair.first) {
      return false;
    }
    return second == oRawPair.second;
  }

  @Override
  public int hashCode() {
    var result = HashCommon.mix(first);
    result = 31 * result + HashCommon.mix(second);
    return result;
  }
}
