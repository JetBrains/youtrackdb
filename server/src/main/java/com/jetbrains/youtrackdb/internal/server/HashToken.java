package com.jetbrains.youtrackdb.internal.server;

import java.util.Arrays;

public class HashToken {

  private final byte[] binaryToken;

  public HashToken(byte[] binaryToken) {
    this.binaryToken = binaryToken;
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(binaryToken);
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof HashToken)) {
      return false;
    }
    return Arrays.equals(this.binaryToken, ((HashToken) obj).binaryToken);
  }
}
